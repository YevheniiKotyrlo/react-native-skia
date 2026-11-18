#pragma once

#include <cstdint>

#include <android/hardware_buffer.h>
#include <fbjni/fbjni.h>

#include "include/core/SkImageInfo.h"
#include "include/core/SkPixmap.h"

namespace RNSkia {

namespace jni = facebook::jni;

struct JBitmap : jni::JavaClass<JBitmap> {
  static constexpr auto kJavaDescriptor = "Landroid/graphics/Bitmap;";
};

/** A Java bitmap with its pixels locked, at `pixels`, for writing. */
struct LockedBitmap {
  jni::global_ref<JBitmap::javaobject> bitmap;
  SkPixmap pixels;
};

/**
 * The image views' bitmaps on the Java side (SkiaPresentedImages), a static
 * store the presented images make, publish and discard them through (see
 * RNSkPresentedImages): the worker makes and publishes them, the JS thread,
 * or the one tearing a runtime down, discards them. Its class is resolved
 * while the library loads (see JNI_OnLoad), since the worker is a thread the
 * JVM did not create. A Java exception a call throws is reported as uncaught,
 * as on the present thread (see callJavaFromPresentThread): none can unwind
 * from the worker, nor from the destructor that discards a torn-down
 * runtime's bitmaps (see ~RNSkJsiViewApi), which makes the discard the JS
 * thread's release makes the same call.
 */
struct JniSkiaPresentedImages : jni::JavaClass<JniSkiaPresentedImages> {
  static constexpr auto kJavaDescriptor =
      "Lcom/reactnative/skia/SkiaPresentedImages;";

  /**
   A bitmap of `info` with its pixels locked for writing until
   unlockBitmap(); no bitmap when there was no memory for it or its pixels
   could not be locked.
   */
  static LockedBitmap makeBitmap(const SkImageInfo &info);

  /** Unlocks the pixels of a bitmap makeBitmap() made. */
  static void unlockBitmap(jni::alias_ref<JBitmap::javaobject> bitmap);

  /**
   A hardware bitmap of `buffer` itself, whose drawing has finished
   (Bitmap.wrapHardwareBuffer); no bitmap when Android could not wrap it.
   */
  static jni::global_ref<JBitmap::javaobject>
  hardwareBitmapOf(AHardwareBuffer *buffer);

  /** Shows `bitmap` in the views of the image `id`. */
  static void publish(uint32_t id, jni::alias_ref<JBitmap::javaobject> bitmap);

  /** Forgets the bitmap of the image `id`. */
  static void discard(uint32_t id);

  /** No bitmap could be made for the image `id`: its views show nothing. */
  static void fail(uint32_t id);

  /** Registers the store's native methods (see drawLevel). */
  static void registerNatives();

private:
  /**
   SkiaPresentedImages.drawLevel: the image `id` drawn at `width` by `height`
   (see RNSkPresentedImages::drawInto); null while the image is not retained,
   and when it could not be drawn.
   */
  static jni::local_ref<JBitmap::javaobject>
  drawLevel(jni::alias_ref<jclass>, jlong id, jint width, jint height);
};

} // namespace RNSkia
