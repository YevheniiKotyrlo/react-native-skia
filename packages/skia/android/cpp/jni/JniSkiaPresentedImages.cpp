#include "JniSkiaPresentedImages.h"

#include <algorithm>
#include <utility>

#include <android/bitmap.h>
#include <android/hardware_buffer_jni.h>

#include "JniPresentThread.h"
#include "RNSkAndroidPlatformContext.h"
#include "RNSkPresentedImages.h"

#include "include/core/SkColorSpace.h"

namespace RNSkia {

namespace {

struct JHardwareBuffer : jni::JavaClass<JHardwareBuffer> {
  static constexpr auto kJavaDescriptor = "Landroid/hardware/HardwareBuffer;";
};

// A hardware bitmap of the buffer itself (Bitmap.wrapHardwareBuffer), which
// takes a reference of its own to it.
jni::local_ref<JBitmap::javaobject> wrapBuffer(AHardwareBuffer *buffer) {
  auto *env = jni::Environment::current();
  auto hardwareBuffer =
      jni::adopt_local(static_cast<JHardwareBuffer::javaobject>(
          AHardwareBuffer_toHardwareBuffer(env, buffer)));
  if (!hardwareBuffer) {
    return nullptr;
  }
  jni::local_ref<JBitmap::javaobject> bitmap;
  callJavaFromPresentThread([&]() {
    static const auto method =
        JniSkiaPresentedImages::javaClassStatic()
            ->getStaticMethod<JBitmap::javaobject(
                jni::alias_ref<JHardwareBuffer::javaobject>)>(
                "wrapHardwareBuffer");
    bitmap = method(JniSkiaPresentedImages::javaClassStatic(), hardwareBuffer);
  });
  return bitmap;
}

} // namespace

LockedBitmap JniSkiaPresentedImages::makeBitmap(const SkImageInfo &info) {
  jni::ThreadScope threadScope;
  jni::local_ref<JBitmap::javaobject> bitmap;
  callJavaFromPresentThread([&]() {
    static const auto method =
        javaClassStatic()->getStaticMethod<JBitmap::javaobject(jint, jint)>(
            "makeBitmap");
    bitmap = method(javaClassStatic(), info.width(), info.height());
  });
  if (!bitmap) {
    return {};
  }
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
    jni::alias_ref<JBitmap::javaobject> bitmap) {
  jni::ThreadScope threadScope;
  AndroidBitmap_unlockPixels(jni::Environment::current(), bitmap.get());
}

jni::global_ref<JBitmap::javaobject>
JniSkiaPresentedImages::hardwareBitmapOf(AHardwareBuffer *buffer) {
  jni::ThreadScope threadScope;
  auto bitmap = wrapBuffer(buffer);
  return bitmap ? jni::make_global(bitmap) : nullptr;
}

void JniSkiaPresentedImages::publish(
    uint32_t id, jni::alias_ref<JBitmap::javaobject> bitmap) {
  jni::ThreadScope threadScope;
  callJavaFromPresentThread([&]() {
    static const auto method =
        javaClassStatic()
            ->getStaticMethod<void(jlong, jni::alias_ref<JBitmap::javaobject>)>(
                "publish");
    method(javaClassStatic(), static_cast<jlong>(id), bitmap);
  });
}

void JniSkiaPresentedImages::discard(uint32_t id) {
  jni::ThreadScope threadScope;
  callJavaFromPresentThread([&]() {
    static const auto method =
        javaClassStatic()->getStaticMethod<void(jlong)>("discard");
    method(javaClassStatic(), static_cast<jlong>(id));
  });
}

void JniSkiaPresentedImages::fail(uint32_t id) {
  jni::ThreadScope threadScope;
  callJavaFromPresentThread([&]() {
    static const auto method =
        javaClassStatic()->getStaticMethod<void(jlong)>("fail");
    method(javaClassStatic(), static_cast<jlong>(id));
  });
}

void JniSkiaPresentedImages::registerNatives() {
  javaClassStatic()->registerNatives({
      makeNativeMethod("drawLevel", JniSkiaPresentedImages::drawLevel),
  });
}

jni::local_ref<JBitmap::javaobject>
JniSkiaPresentedImages::drawLevel(jni::alias_ref<jclass>, jlong id, jint width,
                                  jint height) {
  auto image =
      RNSkPresentedImages::getInstance().imageOf(static_cast<uint32_t>(id));
  if (image == nullptr || width <= 0 || height <= 0) {
    return nullptr;
  }
  const auto info =
      SkImageInfo::Make(width, height, kRGBA_8888_SkColorType,
                        kPremul_SkAlphaType, SkColorSpace::MakeSRGB());
  auto target = makeAndroidPresentedTarget(info);
  if (!target.has_value()) {
    return nullptr;
  }
  auto bitmap = RNSkPresentedImages::drawInto(std::move(*target), image, info);
  if (bitmap == nullptr) {
    return nullptr;
  }
  return static_cast<AndroidPresentedBitmap &>(*bitmap).javaBitmap();
}

} // namespace RNSkia
