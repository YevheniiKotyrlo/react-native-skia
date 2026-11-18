#pragma once

#include <cerrno>
#include <chrono>
#include <exception>
#include <functional>
#include <memory>
#include <optional>
#include <string>
#include <utility>
#include <vector>

#include <android/api-level.h>
#include <android/hardware_buffer.h>
#include <poll.h>

#include "RNDawnContext.h"

#include "JniPlatformContext.h"
#include "JniSkiaPresentedImages.h"
#include "RNSkPlatformContext.h"

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdocumentation"

#include "include/ports/SkFontMgr_android.h"
#include "include/ports/SkFontScanner_FreeType.h"

#pragma clang diagnostic pop

namespace RNSkia {

/** A Java bitmap the image views draw (see RNSkPresentedImages). */
class AndroidPresentedBitmap : public PresentedBitmap {
public:
  explicit AndroidPresentedBitmap(jni::global_ref<JBitmap::javaobject> bitmap)
      : _bitmap(std::move(bitmap)) {}

  // The reference goes on whichever thread drops the last owner, which may
  // not be attached to the JVM.
  ~AndroidPresentedBitmap() override {
    jni::ThreadScope threadScope;
    _bitmap.reset();
  }

  void present(uint32_t id) override {
    JniSkiaPresentedImages::publish(id, _bitmap);
  }

  /** The Java bitmap, for a level a view asked for (see drawLevel). */
  jni::local_ref<JBitmap::javaobject> javaBitmap() const {
    return jni::make_local(_bitmap);
  }

private:
  jni::global_ref<JBitmap::javaobject> _bitmap;
};

/**
 A Java bitmap being written, its pixels locked until it is finished or let go
 of (see JniSkiaPresentedImages::makeBitmap).
 */
class AndroidPresentedPixels : public PresentedPixels {
public:
  explicit AndroidPresentedPixels(LockedBitmap locked)
      : PresentedPixels(locked.pixels), _bitmap(std::move(locked.bitmap)) {}

  // On whichever thread drops the last owner, which may not be attached to
  // the JVM.
  ~AndroidPresentedPixels() override {
    if (!_bitmap) {
      return;
    }
    JniSkiaPresentedImages::unlockBitmap(_bitmap);
    jni::ThreadScope threadScope;
    _bitmap.reset();
  }

  std::shared_ptr<PresentedBitmap> finish() override {
    JniSkiaPresentedImages::unlockBitmap(_bitmap);
    return std::make_shared<AndroidPresentedBitmap>(std::move(_bitmap));
  }

private:
  jni::global_ref<JBitmap::javaobject> _bitmap;
};

/**
 An AHardwareBuffer a presented image is drawn into once (see
 PresentedBuffer), shown as a hardware bitmap of the buffer itself.
 */
class AndroidPresentedBuffer : public PresentedBuffer {
public:
  /** A buffer of `info`'s size; nullptr when it could not be allocated. */
  static std::unique_ptr<AndroidPresentedBuffer> make(const SkImageInfo &info) {
    AHardwareBuffer_Desc description{};
    description.width = static_cast<uint32_t>(info.width());
    description.height = static_cast<uint32_t>(info.height());
    description.layers = 1;
    description.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    description.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                        AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;
    AHardwareBuffer *buffer = nullptr;
    if (AHardwareBuffer_allocate(&description, &buffer) != 0 ||
        buffer == nullptr) {
      return nullptr;
    }
    return std::unique_ptr<AndroidPresentedBuffer>(
        new AndroidPresentedBuffer(buffer));
  }

  ~AndroidPresentedBuffer() override { AHardwareBuffer_release(_buffer); }

  AndroidPresentedBuffer(const AndroidPresentedBuffer &) = delete;
  AndroidPresentedBuffer &operator=(const AndroidPresentedBuffer &) = delete;

  AHardwareBuffer *handle() const { return _buffer; }

  wgpu::SharedTextureMemory import(const wgpu::Device &device) override {
    wgpu::SharedTextureMemoryAHardwareBufferDescriptor hardwareBuffer{};
    hardwareBuffer.handle = _buffer;
    wgpu::SharedTextureMemoryDescriptor descriptor{};
    descriptor.nextInChain = &hardwareBuffer;
    return device.ImportSharedTextureMemory(&descriptor);
  }

  // Each fence is a sync fd its SharedFence owns, readable once it signals.
  bool waitForFences(const std::vector<wgpu::SharedFence> &fences,
                     const std::vector<uint64_t> &,
                     std::chrono::milliseconds timeout) override {
    const auto deadline = std::chrono::steady_clock::now() + timeout;
    for (const auto &fence : fences) {
      wgpu::SharedFenceSyncFDExportInfo syncFd{};
      wgpu::SharedFenceExportInfo info{};
      info.nextInChain = &syncFd;
      fence.ExportInfo(&info);
      if (info.type != wgpu::SharedFenceType::SyncFD) {
        return false;
      }
      if (syncFd.handle >= 0 && !waitForSyncFd(syncFd.handle, deadline)) {
        return false;
      }
    }
    return true;
  }

  std::shared_ptr<PresentedBitmap> finish() override {
    auto bitmap = JniSkiaPresentedImages::hardwareBitmapOf(_buffer);
    if (!bitmap) {
      return nullptr;
    }
    return std::make_shared<AndroidPresentedBitmap>(std::move(bitmap));
  }

private:
  explicit AndroidPresentedBuffer(AHardwareBuffer *buffer) : _buffer(buffer) {}

  static bool waitForSyncFd(int fd,
                            std::chrono::steady_clock::time_point deadline) {
    pollfd descriptor{fd, POLLIN, 0};
    while (true) {
      const auto left = std::chrono::duration_cast<std::chrono::milliseconds>(
                            deadline - std::chrono::steady_clock::now())
                            .count();
      if (left <= 0) {
        return false;
      }
      descriptor.revents = 0;
      const int result = poll(&descriptor, 1, static_cast<int>(left));
      if (result > 0) {
        return (descriptor.revents & POLLIN) != 0;
      }
      if (result == 0 || errno != EINTR) {
        return false;
      }
    }
  }

  AHardwareBuffer *_buffer;
};

/**
 What a presented image of `info` is drawn into on Android (see
 PresentedTarget): from Android 13 a hardware buffer, which Dawn imports as
 React Native WebGPU's view snapshots do; before, CPU pixels a readback is
 copied into, since a driver such as Adreno 630's on Android 10 refuses the
 import and Dawn loses the device for it
 (https://issues.chromium.org/issues/576320192).
 */
inline std::optional<PresentedTarget>
makeAndroidPresentedTarget(const SkImageInfo &info) {
  if (android_get_device_api_level() >= __ANDROID_API_T__) {
    auto buffer = AndroidPresentedBuffer::make(info);
    if (buffer == nullptr) {
      return std::nullopt;
    }
    return PresentedTarget(std::unique_ptr<PresentedBuffer>(std::move(buffer)));
  }
  auto locked = JniSkiaPresentedImages::makeBitmap(info);
  if (!locked.bitmap) {
    return std::nullopt;
  }
  return PresentedTarget(std::unique_ptr<PresentedPixels>(
      std::make_unique<AndroidPresentedPixels>(std::move(locked))));
}

class RNSkAndroidPlatformContext : public RNSkPlatformContext {
public:
  RNSkAndroidPlatformContext(
      JniPlatformContext *jniPlatformContext,
      std::shared_ptr<facebook::react::CallInvoker> jsCallInvoker)
      : RNSkPlatformContext(std::move(jsCallInvoker),
                            jniPlatformContext->getPixelDensity()),
        _jniPlatformContext(jniPlatformContext) {}

  // Follows display density changes (see PlatformContext.java).
  float getPixelDensity() override {
    return _jniPlatformContext->getPixelDensity();
  }

  ~RNSkAndroidPlatformContext() override = default;

  void performStreamOperation(
      const std::string &sourceUri,
      const std::function<void(std::unique_ptr<SkStreamAsset>)> &op) override {
    _jniPlatformContext->performStreamOperation(sourceUri, op);
  }

  void raiseError(const std::exception &err) override {
    _jniPlatformContext->raiseError(err);
  }

  sk_sp<SkSurface> makeOffscreenSurface(int width, int height,
                                        bool useP3ColorSpace = false) override {
    return DawnContext::getInstance().MakeOffscreen(width, height,
                                                    useP3ColorSpace);
  }

  sk_sp<SkFontMgr> createFontMgr() override {
    return SkFontMgr_New_Android(nullptr, SkFontScanner_Make_FreeType());
  }

  void runOnMainThread(std::function<void()> task) override {
    _jniPlatformContext->runTaskOnMainThread(std::move(task));
  }

  std::optional<PresentedTarget>
  makePresentedTarget(const SkImageInfo &info) override {
    return makeAndroidPresentedTarget(info);
  }

  void discardPresentedBitmap(uint32_t id) override {
    JniSkiaPresentedImages::discard(id);
  }

  void presentFailure(uint32_t id) override {
    JniSkiaPresentedImages::fail(id);
  }

  // AOSP's hardware-accelerated canvas throws on a bitmap past 100 MB
  // (RecordingCanvas.MAX_BITMAP_SIZE).
  size_t getMaxPresentedBitmapBytes() override { return 100 * 1024 * 1024; }

private:
  JniPlatformContext *_jniPlatformContext;
};

} // namespace RNSkia
