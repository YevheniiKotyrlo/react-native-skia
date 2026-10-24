#include "JniSkiaPresentedImages.h"

#include <algorithm>
#include <thread>

#include <android/bitmap.h>

#include "rnskia/RNSkPixelHalving.h"

namespace RNSkia {

LockedBitmap JniSkiaPresentedImages::makeBitmap(const SkImageInfo &info) {
  jni::ThreadScope threadScope;
  static const auto method =
      javaClassStatic()->getStaticMethod<JBitmap::javaobject(jint, jint)>(
          "makeBitmap");
  auto bitmap = method(javaClassStatic(), info.width(), info.height());
  // The pixels are written straight into the bitmap's memory, in rows of its
  // own stride.
  auto *env = jni::Environment::current();
  AndroidBitmapInfo bitmapInfo;
  void *address = nullptr;
  if (AndroidBitmap_getInfo(env, bitmap.get(), &bitmapInfo) !=
          ANDROID_BITMAP_RESULT_SUCCESS ||
      AndroidBitmap_lockPixels(env, bitmap.get(), &address) !=
          ANDROID_BITMAP_RESULT_SUCCESS) {
    return {};
  }
  return {jni::make_global(bitmap), SkPixmap(info, address, bitmapInfo.stride)};
}

void JniSkiaPresentedImages::unlockBitmap(
    jni::alias_ref<JBitmap::javaobject> bitmap, bool written) {
  jni::ThreadScope threadScope;
  AndroidBitmap_unlockPixels(jni::Environment::current(), bitmap.get());
  if (written) {
    // Uploads the texture ahead of the first draw: the views pick their levels
    // only once the bitmap's size is published (see PresentedImage).
    static const auto prepareToDraw =
        JBitmap::javaClassStatic()->getMethod<void()>("prepareToDraw");
    prepareToDraw(bitmap);
  }
}

void JniSkiaPresentedImages::publish(
    uint32_t id, jni::alias_ref<JBitmap::javaobject> bitmap) {
  jni::ThreadScope threadScope;
  static const auto method =
      javaClassStatic()
          ->getStaticMethod<void(jlong, jni::alias_ref<JBitmap::javaobject>)>(
              "publish");
  method(javaClassStatic(), static_cast<jlong>(id), bitmap);
}

void JniSkiaPresentedImages::discard(uint32_t id) {
  jni::ThreadScope threadScope;
  static const auto method =
      javaClassStatic()->getStaticMethod<void(jlong)>("discard");
  method(javaClassStatic(), static_cast<jlong>(id));
}

void JniSkiaPresentedImages::registerNatives() {
  javaClassStatic()->registerNatives({
      makeNativeMethod("halve", JniSkiaPresentedImages::halve),
  });
}

void JniSkiaPresentedImages::halve(jni::alias_ref<jclass>,
                                   jni::alias_ref<JBitmap::javaobject> bitmap,
                                   jni::alias_ref<JBitmap::javaobject> half) {
  auto *env = jni::Environment::current();
  AndroidBitmapInfo info;
  AndroidBitmapInfo halfInfo;
  void *pixels = nullptr;
  void *halfPixels = nullptr;
  if (AndroidBitmap_getInfo(env, bitmap.get(), &info) !=
          ANDROID_BITMAP_RESULT_SUCCESS ||
      AndroidBitmap_getInfo(env, half.get(), &halfInfo) !=
          ANDROID_BITMAP_RESULT_SUCCESS ||
      AndroidBitmap_lockPixels(env, bitmap.get(), &pixels) !=
          ANDROID_BITMAP_RESULT_SUCCESS) {
    jni::throwNewJavaException("java/lang/OutOfMemoryError",
                               "the pixels of a level could not be locked");
  }
  if (AndroidBitmap_lockPixels(env, half.get(), &halfPixels) !=
      ANDROID_BITMAP_RESULT_SUCCESS) {
    AndroidBitmap_unlockPixels(env, bitmap.get());
    jni::throwNewJavaException("java/lang/OutOfMemoryError",
                               "the pixels of a level could not be locked");
  }
  // A thread costs more than halving a small level takes.
  constexpr uint32_t kPixelsPerThread = 256 * 1024;
  const auto halfPixelCount =
      static_cast<uint64_t>(halfInfo.width) * halfInfo.height;
  const int threads = static_cast<int>(std::min<uint64_t>(
      {std::max(1u, std::thread::hardware_concurrency()), 4,
       std::max<uint64_t>(1, halfPixelCount / kPixelsPerThread)}));
  halvePixels(static_cast<const uint8_t *>(pixels),
              static_cast<int>(info.width), static_cast<int>(info.height),
              info.stride, static_cast<uint8_t *>(halfPixels), halfInfo.stride,
              threads);
  AndroidBitmap_unlockPixels(env, half.get());
  AndroidBitmap_unlockPixels(env, bitmap.get());
}

} // namespace RNSkia
