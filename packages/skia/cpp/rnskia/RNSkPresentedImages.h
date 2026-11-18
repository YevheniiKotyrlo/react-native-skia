#pragma once

#include <algorithm>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <future>
#include <limits>
#include <memory>
#include <mutex>
#include <optional>
#include <type_traits>
#include <unordered_map>
#include <utility>
#include <variant>

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdocumentation"

#include "include/core/SkBitmap.h"
#include "include/core/SkBlendMode.h"
#include "include/core/SkCanvas.h"
#include "include/core/SkColorSpace.h"
#include "include/core/SkImage.h"
#include "include/core/SkImageInfo.h"
#include "include/core/SkPaint.h"
#include "include/core/SkPixmap.h"
#include "include/core/SkRect.h"
#include "include/core/SkSamplingOptions.h"
#include "include/gpu/graphite/Image.h"

#pragma clang diagnostic pop

#include "RNDawnContext.h"
#include "RNSkPlatformContext.h"
#include "RNSkPresentThread.h"
#include "RNSkPresentedBitmapSize.h"
#include "RNSkPresentedImageLedger.h"
#include "RNSkWorker.h"
#include "utils/RNSkLog.h"

namespace RNSkia {

/**
 The images the <SkiaImageView>s show. An image is drawn once on the GPU,
 however many views show it, into a buffer of the platform's own (see
 PresentedBuffer) that every view showing it draws inside the window's own
 drawing: no view has a surface or a swapchain of its own, and nothing reads
 the GPU device's memory back.

 An image is retained while a view shows it and released after (see
 SkiaImageView.tsx); the platform forgets the bitmap once the last retain is
 released. A view shows an image by its id, the image's own unique id, so
 every view of one image shares one bitmap. Retains are counted per owner, the
 view API of the runtime that made them, so a runtime torn down after its
 successor started (a reload) gives back its own retains and no others.

 Threading: retain and release run on the thread of the JS runtime that calls
 them; an image is drawn on the images' worker. The registry hands the
 platform its present, failure and discard requests under its own lock, so
 they reach the main thread in the order the retains and releases happened: a
 release always overtakes a drawing still in flight for the same retain.
 */
class RNSkPresentedImages {
public:
  static RNSkPresentedImages &getInstance() {
    // Never destroyed, like RNSkWorker: the images' worker and the poller
    // may still use it while the process exits.
    static auto *instance = new RNSkPresentedImages();
    return *instance;
  }

  RNSkPresentedImages(const RNSkPresentedImages &) = delete;
  RNSkPresentedImages &operator=(const RNSkPresentedImages &) = delete;

  /**
   The thread the bitmaps are made on, an encoded image decoded first: one of
   their own, so that the shared worker's jobs, the encodes and decodes JS
   asks for, hold up none of them.
   */
  static RNSkWorker &worker() {
    static auto &instance = []() -> RNSkWorker & {
      auto &made = RNSkWorker::makeDedicated("RNSkia Bitmaps");
#if defined(__ANDROID__)
      // It calls into Java for every image and lets go of Java references.
      made.post(attachPresentThread);
#endif
      return made;
    }();
    return instance;
  }

  /** The id a view shows `image` by. */
  static uint32_t idOf(const sk_sp<SkImage> &image) {
    return image->uniqueID();
  }

  /**
   Keeps `image` presentable until the matching release. The first retain of
   an image, and the first after its drawing failed, draws it and presents
   it; later ones only count. A retain whose drawing cannot be requested is
   given back before it throws, and no release follows it.
   */
  void retain(const void *owner, const sk_sp<SkImage> &image,
              const std::shared_ptr<RNSkPlatformContext> &context) {
    auto id = idOf(image);
    // Started before the retain counts: a thread that cannot start throws
    // with nothing counted.
    auto &images = worker();
    std::optional<uint64_t> drawing;
    {
      std::lock_guard<std::mutex> lock(_mutex);
      drawing = _ledger.retain(owner, id);
      if (drawing.has_value()) {
        _images[id] = image;
      }
    }
    if (!drawing.has_value()) {
      return;
    }
    const uint64_t generation = *drawing;
    // The drawing is requested from the images' worker, so that the caller
    // only counts.
    std::weak_ptr<RNSkPlatformContext> weakContext = context;
    try {
      images.post([this, id, generation, image, weakContext]() {
        produce(id, generation, image, weakContext);
      });
    } catch (...) {
      abandon(owner, id, generation, *context);
      throw;
    }
  }

  /** Gives back a retain. The last one discards the image's bitmap. */
  void release(const void *owner, uint32_t id,
               const std::shared_ptr<RNSkPlatformContext> &context) {
    std::lock_guard<std::mutex> lock(_mutex);
    if (_ledger.release(owner, id)) {
      _images.erase(id);
      context->discardPresentedBitmap(id);
    }
  }

  /**
   Gives back every retain `owner` holds: its runtime is gone, and none of
   them will be released.
   */
  void releaseAll(const void *owner,
                  const std::shared_ptr<RNSkPlatformContext> &context) {
    std::lock_guard<std::mutex> lock(_mutex);
    for (auto id : _ledger.releaseAll(owner)) {
      _images.erase(id);
      context->discardPresentedBitmap(id);
    }
  }

  /** The image `id` while a view retains it; nullptr otherwise. */
  sk_sp<SkImage> imageOf(uint32_t id) {
    std::lock_guard<std::mutex> lock(_mutex);
    auto found = _images.find(id);
    return found == _images.end() ? nullptr : found->second;
  }

  /**
   Draws `image` over `info`'s size into `target` and makes the bitmap the
   views draw of it; nullptr when it could not be drawn. Into a buffer, on the
   GPU, recorded on the calling thread and submitted under the GPU context's
   lock, then waited for holding no lock: once the GPU is done the buffer is
   the platform's alone, and a device lost or destroyed after that changes
   nothing it holds. Into pixels, where the platform cannot import a buffer,
   by a readback copied inside its callback, under the GPU context's lock,
   which a device's destruction takes too. Never on the main thread or a JS
   thread.
   */
  static std::shared_ptr<PresentedBitmap> drawInto(PresentedTarget target,
                                                   const sk_sp<SkImage> &image,
                                                   const SkImageInfo &info) {
    return std::visit(
        [&](auto &&made) -> std::shared_ptr<PresentedBitmap> {
          using Made = std::decay_t<decltype(made)>;
          if constexpr (std::is_same_v<Made,
                                       std::unique_ptr<PresentedBuffer>>) {
            return drawOnGpu(*made, image, info) ? made->finish() : nullptr;
          } else {
            static_assert(
                std::is_same_v<Made, std::unique_ptr<PresentedPixels>>);
            return readBackInto(
                std::shared_ptr<PresentedPixels>(std::move(made)), image, info);
          }
        },
        target);
  }

private:
  RNSkPresentedImages() = default;

  // Premultiplied 8-bit color, in Display P3 where the platform's views can
  // show it (see RNSkPlatformContext::canPresentP3Bitmaps) and the image has
  // colors outside sRGB to keep, whatever the main screen; in sRGB otherwise.
  // At most as large as the platform draws and the GPU makes a texture (see
  // presentedBitmapSize).
  static SkImageInfo
  presentedInfo(const sk_sp<SkImage> &image,
                const std::shared_ptr<RNSkPlatformContext> &context,
                DawnContext &dawnContext) {
    auto colorSpace = SkColorSpace::MakeSRGB();
    auto *imageColorSpace = image->colorSpace();
    if (context->canPresentP3Bitmaps() && imageColorSpace != nullptr &&
        !imageColorSpace->isSRGB()) {
      colorSpace = DawnUtils::viewColorSpace(/* useP3ColorSpace= */ true);
    }
    return SkImageInfo::Make(
        presentedBitmapSize(image->dimensions(),
                            context->getMaxPresentedBitmapBytes(),
                            maxTextureSide(dawnContext)),
        kRGBA_8888_SkColorType, kPremul_SkAlphaType, std::move(colorSpace));
  }

  // The longest side a texture of the device in use can have.
  static int maxTextureSide(DawnContext &dawnContext) {
    const auto side = dawnContext.getMaxTextureDimension2D();
    return side == 0 ||
                   side > static_cast<uint32_t>(std::numeric_limits<int>::max())
               ? std::numeric_limits<int>::max()
               : static_cast<int>(side);
  }

  // `image` as the GPU can draw it: a CPU image with a side longer than the
  // largest texture cannot be uploaded whole, so it is scaled to `info` on
  // the CPU first, from its mipmaps; nullptr when there is no memory for that.
  static sk_sp<SkImage> textureSized(const sk_sp<SkImage> &image,
                                     const SkImageInfo &info, int maxSide) {
    if (image->isTextureBacked() ||
        (image->width() <= maxSide && image->height() <= maxSide)) {
      return image;
    }
    SkBitmap bitmap;
    if (!bitmap.tryAllocPixels(info) ||
        !image->scalePixels(
            bitmap.pixmap(),
            SkSamplingOptions(SkFilterMode::kLinear, SkMipmapMode::kLinear))) {
      return nullptr;
    }
    bitmap.setImmutable();
    return bitmap.asImage();
  }

  // How long the images' worker waits for the GPU to finish a drawing.
  static constexpr std::chrono::seconds kDrawingTimeout{1};

  // Runs on the images' worker (see drawInto).
  void produce(uint32_t id, uint64_t generation, const sk_sp<SkImage> &image,
               const std::weak_ptr<RNSkPlatformContext> &weakContext) {
    auto context = weakContext.lock();
    if (context == nullptr || !isCurrent(id, generation)) {
      return;
    }
    auto *dawnContext = DawnContext::tryGetInstance();
    if (dawnContext == nullptr) {
      RNSkLogger::logToConsole(
          "SkiaImageView: no GPU device to draw a %dx%d image on",
          image->width(), image->height());
      presentFailure(id, generation, *context);
      return;
    }
    const auto info = presentedInfo(image, context, *dawnContext);
    auto target = context->makePresentedTarget(info);
    auto bitmap = target.has_value() ? drawInto(std::move(*target), image, info)
                                     : nullptr;
    if (bitmap == nullptr) {
      RNSkLogger::logToConsole(
          "SkiaImageView: could not draw a %dx%d image for the platform",
          info.width(), info.height());
      presentFailure(id, generation, *context);
      return;
    }
    // An image released while it was drawn gets no bitmap.
    std::lock_guard<std::mutex> lock(_mutex);
    if (_ledger.isCurrent(id, generation)) {
      bitmap->present(id);
    }
  }

  // `image` drawn over `size`: as it is at its own size, and from its
  // mipmaps where it is drawn at half its size or less on a side, as a
  // texture's mipmaps would smooth it.
  static void drawScaled(SkCanvas *canvas, const sk_sp<SkImage> &image,
                         SkISize size, skgpu::graphite::Recorder *recorder) {
    SkPaint paint;
    paint.setBlendMode(SkBlendMode::kSrc);
    if (image->dimensions() == size) {
      canvas->drawImage(image, 0, 0, SkSamplingOptions(), &paint);
      return;
    }
    const bool minified = int64_t{size.width()} * 2 <= image->width() ||
                          int64_t{size.height()} * 2 <= image->height();
    sk_sp<SkImage> source;
    if (minified) {
      SkImage::RequiredProperties mipmapped;
      mipmapped.fMipmapped = true;
      source = SkImages::TextureFromImage(recorder, image, mipmapped);
    }
    canvas->drawImageRect(
        source != nullptr ? source : image, SkRect::Make(size),
        SkSamplingOptions(SkFilterMode::kLinear, source != nullptr
                                                     ? SkMipmapMode::kLinear
                                                     : SkMipmapMode::kNone),
        &paint);
  }

  static bool drawOnGpu(PresentedBuffer &buffer, const sk_sp<SkImage> &image,
                        const SkImageInfo &info) {
    auto *dawnContext = DawnContext::tryGetInstance();
    if (dawnContext == nullptr || DawnContext::isDeviceLost()) {
      return false;
    }
    auto source = textureSized(image, info, maxTextureSide(*dawnContext));
    if (source == nullptr) {
      return false;
    }
    auto fences = dawnContext->drawIntoSharedTexture(
        buffer.import(dawnContext->getWGPUDevice()), info.dimensions(),
        info.refColorSpace(), [&](SkCanvas *canvas) {
          drawScaled(canvas, source, info.dimensions(),
                     dawnContext->getRecorder());
        });
    return fences.has_value() &&
           buffer.waitForFences(fences->fences, fences->signaledValues,
                                kDrawingTimeout);
  }

  static std::shared_ptr<PresentedBitmap>
  readBackInto(std::shared_ptr<PresentedPixels> pixels,
               const sk_sp<SkImage> &image, const SkImageInfo &info) {
    if (!image->isTextureBacked()) {
      // Mipmapped, as the GPU's repeated halving is for a texture.
      if (!image->scalePixels(pixels->getPixels(),
                              SkSamplingOptions(SkFilterMode::kLinear,
                                                SkMipmapMode::kLinear))) {
        return nullptr;
      }
      return pixels->finish();
    }
    auto *dawnContext = DawnContext::tryGetInstance();
    if (dawnContext == nullptr) {
      return nullptr;
    }
    auto copied = std::make_shared<std::promise<bool>>();
    auto done = copied->get_future();
    dawnContext->readPixels(
        image, image->bounds(), info, [pixels, copied](ReadResult rows) {
          copied->set_value(rows != nullptr &&
                            SkPixmap(pixels->getPixels().info(), rows->data(0),
                                     rows->rowBytes(0))
                                .readPixels(pixels->getPixels()));
        });
    if (done.wait_for(kDrawingTimeout) != std::future_status::ready ||
        !done.get()) {
      return nullptr;
    }
    return pixels->finish();
  }

  // The views of an image no bitmap could be made for show nothing, unless it
  // was released meanwhile, and the next retain draws it again.
  void presentFailure(uint32_t id, uint64_t generation,
                      RNSkPlatformContext &context) {
    std::lock_guard<std::mutex> lock(_mutex);
    if (_ledger.fail(id, generation)) {
      context.presentFailure(id);
    }
  }

  void abandon(const void *owner, uint32_t id, uint64_t generation,
               RNSkPlatformContext &context) {
    std::lock_guard<std::mutex> lock(_mutex);
    switch (_ledger.abandon(owner, id, generation)) {
    case RNSkPresentedImageLedger::Abandon::kDiscard:
      _images.erase(id);
      context.discardPresentedBitmap(id);
      return;
    case RNSkPresentedImageLedger::Abandon::kFail:
      context.presentFailure(id);
      return;
    case RNSkPresentedImageLedger::Abandon::kNothing:
      return;
    }
  }

  bool isCurrent(uint32_t id, uint64_t generation) {
    std::lock_guard<std::mutex> lock(_mutex);
    return _ledger.isCurrent(id, generation);
  }

  std::mutex _mutex;
  RNSkPresentedImageLedger _ledger;
  // Each retained image, for the levels a platform draws of it on request.
  std::unordered_map<uint32_t, sk_sp<SkImage>> _images;
};

} // namespace RNSkia
