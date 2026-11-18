#pragma once

#include <chrono>
#include <cstdint>
#include <exception>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <unordered_map>
#include <utility>
#include <variant>
#include <vector>

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdocumentation"

#include "include/core/SkData.h"
#include "include/core/SkFontMgr.h"
#include "include/core/SkImage.h"
#include "include/core/SkPixmap.h"
#include "include/core/SkStream.h"
#include "include/core/SkSurface.h"

#pragma clang diagnostic pop

#include <ReactCommon/CallInvoker.h>

#include "webgpu/webgpu_cpp.h"

namespace RNSkia {

namespace react = facebook::react;

/**
 A bitmap of the platform's own, which a <SkiaImageView> draws inside the
 window's drawing (see RNSkPresentedImages).
 */
class PresentedBitmap {
public:
  virtual ~PresentedBitmap() = default;

  /**
   Shows this bitmap in every <SkiaImageView> showing the image `id`, and in
   any that starts to. Any thread: the views change on the main thread, in the
   order presents and discards were made.
   */
  virtual void present(uint32_t id) = 0;
};

/**
 A buffer of the platform's own that the GPU draws a presented image into
 once, an AHardwareBuffer on Android and an IOSurface on Apple platforms: the
 views draw it as it is, and it outlives the GPU device that drew it. A buffer
 let go of unfinished is freed.
 */
class PresentedBuffer {
public:
  virtual ~PresentedBuffer() = default;

  /** The buffer imported into `device`, for one drawing into it. */
  virtual wgpu::SharedTextureMemory import(const wgpu::Device &device) = 0;

  /**
   Waits until the fences the drawing's end of access exported have signalled,
   each `fences[i]` reaching `signaledValues[i]`, for at most `timeout`:
   whether they all did.
   */
  virtual bool waitForFences(const std::vector<wgpu::SharedFence> &fences,
                             const std::vector<uint64_t> &signaledValues,
                             std::chrono::milliseconds timeout) = 0;

  /**
   The bitmap of the buffer as drawn, once; nullptr when the platform could
   not make it. Never called on the main thread or a JS thread.
   */
  virtual std::shared_ptr<PresentedBitmap> finish() = 0;
};

/**
 A presented bitmap before it is drawn, on a platform whose GPU cannot import
 a PresentedBuffer: its pixels are the platform's own memory, which no GPU
 device owns, in the rows the platform lays out. A readback is copied into
 them inside its callback, under the GPU context's lock, then finish() makes
 the bitmap the views draw. Let go of unfinished, the bitmap is freed.
 */
class PresentedPixels {
public:
  virtual ~PresentedPixels() = default;

  /** The pixels to write. */
  const SkPixmap &getPixels() const { return _pixels; }

  /**
   The bitmap of the pixels as written, once; nullptr when the platform could
   not make it. Never called on the main thread or a JS thread.
   */
  virtual std::shared_ptr<PresentedBitmap> finish() = 0;

protected:
  explicit PresentedPixels(const SkPixmap &pixels) : _pixels(pixels) {}

private:
  SkPixmap _pixels;
};

/** What a presented image is drawn into: a GPU buffer or CPU pixels. */
using PresentedTarget = std::variant<std::unique_ptr<PresentedBuffer>,
                                     std::unique_ptr<PresentedPixels>>;

class RNSkPlatformContext {
public:
  /**
   * Constructor
   */
  RNSkPlatformContext(std::shared_ptr<react::CallInvoker> callInvoker,
                      float pixelDensity)
      : _pixelDensity(pixelDensity), _callInvoker(callInvoker) {}

  virtual ~RNSkPlatformContext() = default;

  /**
   * Schedules the function to be run on the javascript thread async
   * @param func Function to run
   */
  void runOnJavascriptThread(std::function<void()> func) {
    _callInvoker->invokeAsync(std::move(func));
  }

  /**
   * Same, with the runtime the function runs on. Asynchronous work uses this
   * one rather than a runtime captured when it started: a reload may have
   * destroyed that runtime by then, and the function is dropped instead.
   * @param func Function to run
   */
  void
  runOnJavascriptThread(std::function<void(facebook::jsi::Runtime &)> func) {
    _callInvoker->invokeAsync(react::CallFunc(std::move(func)));
  }

  /**
   * Runs the passed function on the main thread
   * @param func Function to run.
   */
  virtual void runOnMainThread(std::function<void()> func) = 0;

  /**
   * Loads the resource at the uri on a thread of the platform's and hands
   * `op` a stream over its bytes, or nullptr when it could not be loaded.
   * `op` is called exactly once, on that thread.
   * @param sourceUri Uri for the resource to load as a string
   * @param op Operation to execute with the stream.
   */
  virtual void performStreamOperation(
      const std::string &sourceUri,
      const std::function<void(std::unique_ptr<SkStreamAsset>)> &op) = 0;

  /**
   * Raises an exception on the platform. This function does not necessarily
   * throw an exception and stop execution, so it is important to stop execution
   * by returning after calling the function
   * @param err Error to raise
   */
  virtual void raiseError(const std::exception &err) = 0;

  /**
   * Creates an offscreen surface
   * @param width Width of the offscreen surface
   * @param height Height of the offscreen surface
   * @param useP3ColorSpace If true, surface will use Display P3 color space
   * @return sk_sp<SkSurface>
   */
  virtual sk_sp<SkSurface>
  makeOffscreenSurface(int width, int height, bool useP3ColorSpace = false) = 0;

  /**
   * Return the Platform specific font manager
   */
  virtual sk_sp<SkFontMgr> createFontMgr() = 0;

  /**
   * Return platform-specific system font family names that aren't
   * enumerated by the standard font manager (e.g., .AppleSystemUIFont on iOS)
   */
  virtual std::vector<std::string> getSystemFontFamilies() { return {}; }

  /**
   * Resolve font family aliases to actual font family names.
   * For example, "System" on iOS resolves to ".AppleSystemUIFont".
   * Returns the input unchanged if no mapping exists.
   */
  virtual std::string resolveFontFamily(const std::string &familyName) {
    return familyName;
  }

  /**
   * Raises an exception on the platform. This function does not necessarily
   * throw an exception and stop execution, so it is important to stop execution
   * by returning after calling the function
   * @param message Message to show
   */
  void raiseError(const std::string &message) {
    return raiseError(std::runtime_error(message));
  }

  /**
   * @return Current scale factor for pixels. A view records its frames with
   * the density of its own layout when the platform gives one (see
   * RNSkWindowSurface::setLayoutSize); this one is for everything else.
   */
  virtual float getPixelDensity() { return _pixelDensity; }

  /**
   * Whether views render in Display P3 rather than sRGB: true on Apple
   * platforms when the main screen has a wide color gamut. The answer does
   * not change over the lifetime of the context, so a frame can be recorded
   * for a view before its surface exists.
   */
  virtual bool prefersP3ColorSpace() { return false; }

  /**
   What a presented image of `info` is drawn into (see PresentedTarget): 8-bit
   premultiplied, in sRGB, or in Display P3 where the platform's views can
   show it (see canPresentP3Bitmaps). nullopt when it could not be made, and
   on a platform without <SkiaImageView>. Never called on the main thread or a
   JS thread.
   */
  virtual std::optional<PresentedTarget>
  makePresentedTarget(const SkImageInfo &info) {
    return std::nullopt;
  }

  /**
   Whether a presented bitmap can be in Display P3: on Apple platforms,
   whose CGImage carries its color space on any screen. An Android bitmap
   is in sRGB, as Android's views render.
   */
  virtual bool canPresentP3Bitmaps() { return false; }

  /**
   The most bytes one presented bitmap may hold: the platform draws none
   larger, so an image past it is presented scaled down to fit. No limit by
   default.
   */
  virtual size_t getMaxPresentedBitmapBytes() { return SIZE_MAX; }

  /**
   Forgets the bitmap of the image `id`. A view still showing it keeps it until
   it shows something else. Any thread, ordered with PresentedBitmap::present.
   */
  virtual void discardPresentedBitmap(uint32_t id) {}

  /**
   No bitmap could be made for the image `id`: every view showing it shows
   nothing, as React Native's Image shows nothing when its source fails. Any
   thread, ordered with PresentedBitmap::present and discardPresentedBitmap.
   */
  virtual void presentFailure(uint32_t id) {}

private:
  float _pixelDensity;
  std::shared_ptr<react::CallInvoker> _callInvoker;
};
} // namespace RNSkia
