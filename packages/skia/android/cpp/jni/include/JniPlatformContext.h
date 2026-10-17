#pragma once

#include <fbjni/fbjni.h>
#include <jsi/jsi.h>

#include <exception>
#include <functional>
#include <memory>
#include <mutex>
#include <queue>
#include <string>

#include "RNSkPlatformContext.h"

class SkStreamAsset;
class SkPixmap;
namespace RNSkia {

namespace jsi = facebook::jsi;
namespace jni = facebook::jni;

struct JBitmap : jni::JavaClass<JBitmap> {
  static constexpr auto kJavaDescriptor = "Landroid/graphics/Bitmap;";
};

class JniPlatformContext : public jni::HybridClass<JniPlatformContext> {
public:
  static auto constexpr kJavaDescriptor =
      "Lcom/reactnative/skia/PlatformContext;";

  static jni::local_ref<jhybriddata>
  initHybrid(jni::alias_ref<jhybridobject> jThis, const float);

  static void registerNatives();

  void performStreamOperation(
      const std::string &sourceUri,
      const std::function<void(std::unique_ptr<SkStreamAsset>)> &op);

  void raiseError(const std::exception &err);

  void notifyTaskReadyExternal();

  void runTaskOnMainThread(std::function<void()> task);

  void notifyTaskReadyNative();

  /**
   A Java bitmap holding a copy of `pixels`, premultiplied 8-bit RGBA, for the
   image views (see RNSkPresentedImages). Any thread.
   */
  jni::global_ref<JBitmap::javaobject>
  makePresentedBitmap(const SkPixmap &pixels);

  /** Shows `bitmap` in the image views showing `id`. Any thread. */
  void presentBitmap(uint32_t id, jni::alias_ref<JBitmap::javaobject> bitmap);

  /** Forgets the bitmap of `id`. Any thread. */
  void discardPresentedBitmap(uint32_t id);

  float getPixelDensity() { return _pixelDensity; }

private:
  friend HybridBase;
  jni::global_ref<JniPlatformContext::javaobject> javaPart_;

  float _pixelDensity;
  std::mutex _mainThreadTasksMutex;
  std::queue<std::function<void()>> _mainThreadTasks;
  bool _mainThreadDispatchScheduled = false;

  explicit JniPlatformContext(
      jni::alias_ref<JniPlatformContext::jhybridobject> jThis,
      const float pixelDensity)
      : javaPart_(jni::make_global(jThis)), _pixelDensity(pixelDensity) {}
};
} // namespace RNSkia
