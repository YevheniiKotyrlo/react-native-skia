#pragma once

#include <algorithm>
#include <limits>
#include <memory>
#include <mutex>
#include <utility>
#include <vector>

#include <jsi/jsi.h>

#include "JsiSkConverters.h"
#include "JsiSkDispatcher.h"
#include "JsiSkNativeObjects.h"

#include "JsiSkCanvas.h"
#include "JsiSkImage.h"

#include "rnskia/RNDawnContext.h"

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdocumentation"

#include "include/core/SkSurface.h"
#include "include/gpu/graphite/Surface.h"

#pragma clang diagnostic pop

namespace RNSkia {

namespace jsi = facebook::jsi;

class JsiSkSurface
    : public JsiSkWrappingSkPtrNativeObject<JsiSkSurface, SkSurface> {
public:
  static constexpr const char *CLASS_NAME = "Surface";

  JsiSkSurface(std::shared_ptr<RNSkPlatformContext> context,
               sk_sp<SkSurface> surface)
      : JsiSkWrappingSkPtrNativeObject<JsiSkSurface, SkSurface>(
            std::move(context), std::move(surface)),
        _dispatcher(Dispatcher::getDispatcher()) {
    // The surface belongs to the recorder of this thread, which is also the
    // thread that destroys the surfaces finalized elsewhere (see
    // releaseResources).
    _dispatcher->processQueue();
  }

  ~JsiSkSurface() override {
    if (!isDisposed()) {
      releaseResources();
    }
  }

  // TODO-API: Properties?
  double width() { return static_cast<double>(getObject()->width()); }
  double height() { return static_cast<double>(getObject()->height()); }

  std::shared_ptr<JsiSkCanvas> getCanvas() {
    auto surface = getObject();
    auto canvas =
        std::make_shared<JsiSkCanvas>(getContext(), surface->getCanvas());
    // Keep a reference to the owning surface so the canvas can read pixels back
    // through a snapshot on Graphite (which lacks synchronous canvas readback).
    canvas->setSurface(surface, _dispatcher);
    return canvas;
  }

  void flush(JsiOptional<bool> syncParam) {
    _dispatcher->processQueue();
    auto surface = getObject();
    // When `sync` is true, block until the GPU has finished executing the
    // submitted work. Required before a consumer on a different command queue
    // (a secondary Dawn device, for instance) reads the texture this surface
    // draws into (see Surface.MakeFromGPUTexture). #3916
    bool sync = syncParam.has_value() && *syncParam;
    // A raster surface (e.g. Skia.Surface.Make) has no Graphite recorder;
    // only Graphite-backed surfaces need to snap and submit a recording.
    if (auto *recorder = surface->recorder()) {
      auto recording = recorder->snap();
      DawnContext::getInstance().submitRecording(
          recording.get(), sync ? skgpu::graphite::SyncToCpu::kYes
                                : skgpu::graphite::SyncToCpu::kNo);
    }
  }

  // An image sharing the texture of the surface, without a copy: a canvas
  // drawing it shows what the surface holds by then (flush the surface
  // first). A raster surface has no texture to share: its snapshot instead.
  std::shared_ptr<JsiSkImage> asImage() {
    _dispatcher->processQueue();
    auto surface = getObject();
    auto sharesTexture = surface->recorder() != nullptr;
    auto image = sharesTexture ? SkSurfaces::AsImage(surface)
                               : surface->makeImageSnapshot();
    if (image == nullptr) {
      throw std::runtime_error(
          "asImage: the texture of the surface cannot be sampled");
    }
    if (!sharesTexture) {
      return std::make_shared<JsiSkImage>(getContext(), std::move(image));
    }
    // The surface reports the texture as its memory until it is disposed (see
    // dispose).
    std::lock_guard<std::mutex> lock(_textureChargeMutex);
    auto jsiImage = std::make_shared<JsiSkImage>(getContext(), std::move(image),
                                                 _textureCharge);
    _sharedImages.push_back(jsiImage);
    if (_sharedImages.size() >= _pruneSharedImagesAt) {
      _sharedImages.erase(
          std::remove_if(_sharedImages.begin(), _sharedImages.end(),
                         [](const std::weak_ptr<JsiSkImage> &sharedImage) {
                           return sharedImage.expired();
                         }),
          _sharedImages.end());
      _pruneSharedImagesAt =
          std::max(kMinPruneSharedImagesAt, _sharedImages.size() * 2);
    }
    return jsiImage;
  }

  // The texture outlives the disposed surface in the images that still hold
  // it. One object charges it, kept reachable by those images' wrappers on
  // this runtime, until the last of them is disposed or collected.
  JSI_HOST_FUNCTION(dispose) {
    std::shared_ptr<SharedTextureCharge> textureCharge;
    std::vector<std::weak_ptr<JsiSkImage>> sharedImages;
    {
      std::lock_guard<std::mutex> lock(_textureChargeMutex);
      textureCharge = std::move(_textureCharge);
      sharedImages.swap(_sharedImages);
    }
    auto result =
        JsiSkWrappingSkPtrNativeObject<JsiSkSurface, SkSurface>::dispose(
            runtime, thisValue, arguments, count);
    if (textureCharge != nullptr && textureCharge->hasHolders()) {
      chargeSharedTexture(runtime, textureCharge, sharedImages);
    }
    return result;
  }

  JSI_HOST_FUNCTION(makeImageSnapshot) {
    _dispatcher->processQueue();
    auto surface = getObject();
    sk_sp<SkImage> image;
    if (count > 0 && arguments[0].isObject()) {
      auto rect = JsiSkRect::fromValue(runtime, arguments[0]);
      image = surface->makeImageSnapshot(SkIRect::MakeXYWH(
          rect->x(), rect->y(), rect->width(), rect->height()));
    } else {
      image = surface->makeImageSnapshot();
    }
    // A raster surface (e.g. Skia.Surface.Make) has no Graphite recorder; its
    // snapshot is already a valid CPU image, so skip the recording submit.
    if (auto *recorder = surface->recorder()) {
      auto recording = recorder->snap();
      DawnContext::getInstance().submitRecording(recording.get());
    }
    if (count > 1 && arguments[1].isObject()) {
      auto jsiImage = getJsiObject<JsiSkImage>(runtime, arguments[1]);
      jsiImage->replaceImage(runtime, arguments[1].asObject(runtime),
                             std::move(image));
      return jsi::Value(runtime, arguments[1]);
    }
    return makeJsiObject(
        runtime, std::make_shared<JsiSkImage>(getContext(), std::move(image)));
  }

  size_t getMemoryPressure() override {
    if (isDisposed()) {
      return 0;
    }
    auto surface = getObjectUnchecked();
    if (!surface) {
      return 0;
    }

    const auto safeAdd = [](size_t a, size_t b) {
      if (std::numeric_limits<size_t>::max() - a < b) {
        return std::numeric_limits<size_t>::max();
      }
      return a + b;
    };

    SkImageInfo info = surface->imageInfo();
    size_t pixelBytes = info.computeMinByteSize();
    if (pixelBytes == 0) {
      auto width = std::max(info.width(), surface->width());
      auto height = std::max(info.height(), surface->height());
      int bytesPerPixel = info.bytesPerPixel();
      if (bytesPerPixel <= 0) {
        bytesPerPixel = 4;
      }
      if (width > 0 && height > 0) {
        pixelBytes = static_cast<size_t>(width) * static_cast<size_t>(height) *
                     static_cast<size_t>(bytesPerPixel);
      }
    }

    if (pixelBytes == 0) {
      return 0;
    }

    size_t estimated = pixelBytes;

    auto canvas = surface->getCanvas();
    const bool isGpuBacked = surface->recorder() != nullptr ||
                             (canvas && canvas->recorder() != nullptr);

    if (isGpuBacked) {
      // Account for a resolved texture and depth/stencil attachments.
      estimated = safeAdd(estimated, pixelBytes);     // resolve/texture copy
      estimated = safeAdd(estimated, pixelBytes / 2); // depth-stencil buffers
    }

    // Add a small overhead buffer for bookkeeping allocations.
    estimated = safeAdd(estimated, 128 * 1024);

    return estimated;
  }

  static void definePrototype(jsi::Runtime &runtime, jsi::Object &prototype) {
    installCommon(runtime, prototype);
    installMethod(runtime, prototype, "width", &JsiSkSurface::width);
    installMethod(runtime, prototype, "height", &JsiSkSurface::height);
    installMethod(runtime, prototype, "getCanvas", &JsiSkSurface::getCanvas);
    installHostMethod(runtime, prototype, "makeImageSnapshot",
                      &JsiSkSurface::makeImageSnapshot);
    installMethod(runtime, prototype, "asImage", &JsiSkSurface::asImage);
    installMethod(runtime, prototype, "flush", &JsiSkSurface::flush);
  }

protected:
  void releaseResources() override {
    {
      std::lock_guard<std::mutex> lock(_textureChargeMutex);
      _textureCharge.reset();
    }
    // Destroying a Graphite surface flushes it into its recorder and
    // deregisters it from it, and a recorder is single-threaded. The GC may
    // finalize this object on any thread, and dispose() may be called from
    // another runtime: let the thread that created the surface destroy it.
    auto surface = getObjectUnchecked();
    setObject(nullptr);
    _dispatcher->release(std::move(surface));
  }

private:
  // Images whose wrapper lives only on another runtime are left out: once the
  // charge is gone they report the texture at their next conversion.
  static void chargeSharedTexture(
      jsi::Runtime &runtime,
      const std::shared_ptr<SharedTextureCharge> &textureCharge,
      const std::vector<std::weak_ptr<JsiSkImage>> &sharedImages) {
    auto &cache = RNJsi::JSICache::get(runtime);
    std::vector<jsi::Object> holders;
    for (const auto &sharedImage : sharedImages) {
      auto image = sharedImage.lock();
      if (image == nullptr || !image->holdsTexture()) {
        continue;
      }
      auto wrapper = cache.lockWrapper(runtime, image.get());
      if (wrapper.isObject()) {
        holders.push_back(wrapper.getObject(runtime));
      }
    }
    if (holders.empty()) {
      return;
    }
    jsi::Object charge(runtime);
    charge.setNativeState(runtime, textureCharge);
    charge.setExternalMemoryPressure(runtime, textureCharge->textureBytes());
    cache.setWrapper(runtime, textureCharge.get(), charge);
    auto key = jsi::PropNameID::forSymbol(
        runtime, runtime.global()
                     .getPropertyAsFunction(runtime, "Symbol")
                     .call(runtime, "textureCharge")
                     .getSymbol(runtime));
    for (const auto &holder : holders) {
      holder.setProperty(runtime, key, charge);
    }
  }

  std::shared_ptr<Dispatcher> _dispatcher;
  std::mutex _textureChargeMutex;
  // Alive while the surface or a disposed surface's charge object reports the
  // texture as memory; the images sharing the texture watch it.
  std::shared_ptr<SharedTextureCharge> _textureCharge =
      std::make_shared<SharedTextureCharge>();
  // The images sharing the texture. Entries of collected images are pruned in
  // asImage once the list has doubled since the last pruning, so it stays
  // proportional to the number of live images.
  std::vector<std::weak_ptr<JsiSkImage>> _sharedImages;
  size_t _pruneSharedImagesAt = kMinPruneSharedImagesAt;
  static constexpr size_t kMinPruneSharedImagesAt = 64;
};

} // namespace RNSkia
