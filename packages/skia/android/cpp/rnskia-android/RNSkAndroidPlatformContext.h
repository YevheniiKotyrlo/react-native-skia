#pragma once

#include <exception>
#include <functional>
#include <memory>
#include <string>

#include "RNDawnContext.h"

#include "JniPlatformContext.h"
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
  AndroidPresentedBitmap(JniPlatformContext *jniPlatformContext,
                         jni::global_ref<JBitmap::javaobject> bitmap)
      : _jniPlatformContext(jniPlatformContext), _bitmap(std::move(bitmap)) {}

  // The reference goes on whichever thread drops the last owner, which may
  // not be attached to the JVM.
  ~AndroidPresentedBitmap() override {
    jni::ThreadScope ts;
    _bitmap.reset();
  }

  void present(uint32_t id) override {
    _jniPlatformContext->presentBitmap(id, _bitmap);
  }

private:
  JniPlatformContext *_jniPlatformContext;
  jni::global_ref<JBitmap::javaobject> _bitmap;
};

class RNSkAndroidPlatformContext : public RNSkPlatformContext {
public:
  RNSkAndroidPlatformContext(
      JniPlatformContext *jniPlatformContext,
      std::shared_ptr<facebook::react::CallInvoker> jsCallInvoker)
      : RNSkPlatformContext(std::move(jsCallInvoker),
                            jniPlatformContext->getPixelDensity()),
        _jniPlatformContext(jniPlatformContext) {}

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

  std::shared_ptr<PresentedBitmap>
  makePresentedBitmap(const SkPixmap &pixels) override {
    auto bitmap = _jniPlatformContext->makePresentedBitmap(pixels);
    if (!bitmap) {
      return nullptr;
    }
    return std::make_shared<AndroidPresentedBitmap>(_jniPlatformContext,
                                                    std::move(bitmap));
  }

  void discardPresentedBitmap(uint32_t id) override {
    _jniPlatformContext->discardPresentedBitmap(id);
  }

private:
  JniPlatformContext *_jniPlatformContext;
};

} // namespace RNSkia
