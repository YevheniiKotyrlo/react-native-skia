#include "JniPlatformContext.h"

#include <cstring>
#include <exception>
#include <thread>
#include <utility>
#include <vector>

#include <fbjni/ByteBuffer.h>

#include "include/core/SkData.h"
#include "include/core/SkPixmap.h"
#include "include/core/SkRefCnt.h"
#include "include/core/SkStream.h"
#include "include/core/SkTypes.h"

namespace RNSkia {

namespace jsi = facebook::jsi;

using TSelf = jni::local_ref<JniPlatformContext::jhybriddata>;

void JniPlatformContext::registerNatives() {
  registerHybrid({
      makeNativeMethod("initHybrid", JniPlatformContext::initHybrid),
      makeNativeMethod("notifyTaskReadyNative",
                       JniPlatformContext::notifyTaskReadyNative),
  });
}

TSelf JniPlatformContext::initHybrid(jni::alias_ref<jhybridobject> jThis,
                                     float pixelDensity) {
  return makeCxxInstance(jThis, pixelDensity);
}

void JniPlatformContext::performStreamOperation(
    const std::string &sourceUri,
    const std::function<void(std::unique_ptr<SkStreamAsset>)> &op) {
  static auto method = javaPart_->getClass()->getMethod<jbyteArray(jstring)>(
      "getJniStreamFromSource");

  auto loader = [=, this]() -> void {
    jni::ThreadScope ts;
    jstring jstr =
        (*jni::Environment::current()).NewStringUTF(sourceUri.c_str());

    // Get the array with data from input stream from Java
    auto array = method(javaPart_.get(), jstr);

    if (array == nullptr) {
      printf("Calling getJniStreamFromSource failed\n");
      return;
    }

    // Allocate buffer for java byte array
    jsize num_bytes = jni::Environment::current()->GetArrayLength(array.get());
    char *buffer = reinterpret_cast<char *>(malloc(num_bytes + 1));

    if (!buffer) {
      printf("Buff Fail\n");
      return;
    }

    jbyte *elements =
        jni::Environment::current()->GetByteArrayElements(array.get(), nullptr);
    if (!elements) {
      printf("Element Fail\n");
      free(buffer);
      return;
    }

    // Copy data from java array to buffer
    memcpy(buffer, elements, num_bytes);
    buffer[num_bytes] = 0;

    jni::Environment::current()->ReleaseByteArrayElements(array.get(), elements,
                                                          JNI_ABORT);

    // Copy malloced data and give ownership to SkData
    auto data = SkData::MakeFromMalloc(buffer, num_bytes);
    auto skStream = SkMemoryStream::Make(data);

    // Perform operation
    op(std::move(skStream));
  };

  // Fire and forget the thread - will be resolved on completion
  std::thread(loader).detach();
}

void JniPlatformContext::runTaskOnMainThread(std::function<void()> task) {
  bool shouldSchedule = false;
  {
    std::lock_guard<std::mutex> lock(_mainThreadTasksMutex);
    _mainThreadTasks.push(std::move(task));
    if (!_mainThreadDispatchScheduled) {
      _mainThreadDispatchScheduled = true;
      shouldSchedule = true;
    }
  }

  if (shouldSchedule) {
    notifyTaskReadyExternal();
  }
}

void JniPlatformContext::notifyTaskReadyExternal() {
  jni::ThreadScope ts;

  static auto method =
      javaPart_->getClass()->getMethod<void()>("notifyTaskReady");
  method(javaPart_.get());
}

void JniPlatformContext::notifyTaskReadyNative() {
  std::queue<std::function<void()>> tasks;
  {
    std::lock_guard<std::mutex> lock(_mainThreadTasksMutex);
    std::swap(tasks, _mainThreadTasks);
    _mainThreadDispatchScheduled = false;
  }

  while (!tasks.empty()) {
    auto handler = std::move(tasks.front());
    tasks.pop();
    if (handler) {
      handler();
    }
  }
}

void JniPlatformContext::raiseError(const std::exception &err) {
  jni::ThreadScope ts;
  static auto method =
      javaPart_->getClass()->getMethod<void(std::string)>("raise");
  method(javaPart_.get(), std::string(err.what()));
}

jni::global_ref<JBitmap::javaobject>
JniPlatformContext::makePresentedBitmap(const SkPixmap &pixels) {
  jni::ThreadScope ts;
  auto width = pixels.width();
  auto height = pixels.height();
  auto rowBytes = static_cast<size_t>(width) * 4;
  // Bitmap.copyPixelsFromBuffer reads rows of exactly width * 4 bytes, while
  // a readback may pad its rows.
  std::vector<uint8_t> packed;
  auto *bytes = static_cast<uint8_t *>(pixels.writable_addr());
  if (pixels.rowBytes() != rowBytes) {
    packed.resize(rowBytes * static_cast<size_t>(height));
    for (int row = 0; row < height; row++) {
      std::memcpy(packed.data() + rowBytes * static_cast<size_t>(row),
                  pixels.addr(0, row), rowBytes);
    }
    bytes = packed.data();
  }
  auto buffer = jni::JByteBuffer::wrapBytes(
      bytes, rowBytes * static_cast<size_t>(height));
  static auto method =
      javaPart_->getClass()
          ->getMethod<JBitmap::javaobject(jni::alias_ref<jni::JByteBuffer>,
                                          jint, jint)>("makePresentedBitmap");
  return jni::make_global(method(javaPart_.get(), buffer, width, height));
}

void JniPlatformContext::presentBitmap(
    uint32_t id, jni::alias_ref<JBitmap::javaobject> bitmap) {
  jni::ThreadScope ts;
  static auto method =
      javaPart_->getClass()
          ->getMethod<void(jlong, jni::alias_ref<JBitmap::javaobject>)>(
              "presentBitmap");
  method(javaPart_.get(), static_cast<jlong>(id), bitmap);
}

void JniPlatformContext::discardPresentedBitmap(uint32_t id) {
  jni::ThreadScope ts;
  static auto method =
      javaPart_->getClass()->getMethod<void(jlong)>("discardPresentedBitmap");
  method(javaPart_.get(), static_cast<jlong>(id));
}

} // namespace RNSkia
