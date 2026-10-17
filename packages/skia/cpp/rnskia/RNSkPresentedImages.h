#pragma once

#include <cstdint>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <utility>

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdocumentation"

#include "include/core/SkBitmap.h"
#include "include/core/SkColorSpace.h"
#include "include/core/SkImage.h"
#include "include/core/SkImageInfo.h"
#include "include/core/SkPixmap.h"

#pragma clang diagnostic pop

#include "RNDawnContext.h"
#include "RNSkPlatformContext.h"
#include "RNSkWorker.h"

namespace RNSkia {

/**
 The images <SkiaImageView> shows. An image is read back to the CPU once,
 however many views show it, and handed to the platform as one bitmap that
 every view showing it draws inside the window's own drawing: no view has a
 surface, a swapchain or a native Skia view of its own.

 JS retains an image while a view shows it and releases it after (see
 SkiaImageView.tsx); the platform forgets the bitmap once the last retain is
 released. A view shows an image by its id, the image's own unique id, so
 every view of one image shares one bitmap. Retains are counted per owner, the
 view API of the runtime that made them, so a runtime torn down after its
 successor started (a reload) gives back its own retains and no others.

 Threading: retain and release run on the thread of the JS runtime that called
 them; a readback completes on whichever thread finishes the GPU work. The
 registry hands the platform its present and discard requests under its own
 lock, so they reach the main thread in the order the retains and releases
 happened: a release always overtakes a readback still in flight for the same
 retain.
 */
class RNSkPresentedImages {
public:
  static RNSkPresentedImages &getInstance() {
    static RNSkPresentedImages instance;
    return instance;
  }

  RNSkPresentedImages(const RNSkPresentedImages &) = delete;
  RNSkPresentedImages &operator=(const RNSkPresentedImages &) = delete;

  /** The id a view shows `image` by. */
  static uint32_t idOf(const sk_sp<SkImage> &image) {
    return image->uniqueID();
  }

  /**
   Keeps `image` presentable until the matching release. The first retain of
   an image reads it back and presents it; later ones only count.
   */
  void retain(const void *owner, const sk_sp<SkImage> &image,
              const std::shared_ptr<RNSkPlatformContext> &context) {
    auto id = idOf(image);
    uint64_t generation;
    {
      std::lock_guard<std::mutex> lock(_mutex);
      auto &entry = _entries[id];
      bool first = entry.retains.empty();
      entry.retains[owner]++;
      if (!first) {
        return;
      }
      generation = ++_generation;
      entry.generation = generation;
    }
    produce(id, generation, image, context);
  }

  /** Gives back a retain. The last one discards the image's bitmap. */
  void release(const void *owner, uint32_t id,
               const std::shared_ptr<RNSkPlatformContext> &context) {
    std::lock_guard<std::mutex> lock(_mutex);
    auto it = _entries.find(id);
    if (it == _entries.end()) {
      return;
    }
    auto &retains = it->second.retains;
    auto own = retains.find(owner);
    if (own == retains.end()) {
      return;
    }
    if (--own->second == 0) {
      retains.erase(own);
    }
    if (retains.empty()) {
      _entries.erase(it);
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
    for (auto it = _entries.begin(); it != _entries.end();) {
      it->second.retains.erase(owner);
      if (!it->second.retains.empty()) {
        ++it;
        continue;
      }
      context->discardPresentedBitmap(it->first);
      it = _entries.erase(it);
    }
  }

private:
  RNSkPresentedImages() = default;

  struct Entry {
    std::unordered_map<const void *, size_t> retains;
    // Which retain-from-zero produced the bitmap being read back: a readback
    // that completes after its image was released, and retained again,
    // presents nothing.
    uint64_t generation = 0;
  };

  // Premultiplied 8-bit RGBA in sRGB: what an Android ARGB_8888 bitmap and a
  // CGImage both take as they are.
  static SkImageInfo presentedInfo(const sk_sp<SkImage> &image) {
    return SkImageInfo::Make(image->width(), image->height(),
                             kRGBA_8888_SkColorType, kPremul_SkAlphaType,
                             SkColorSpace::MakeSRGB());
  }

  // The copy into a platform bitmap runs on the worker: a readback completes
  // inside the GPU context's lock, where it would hold up every view's frame.
  void produce(uint32_t id, uint64_t generation, const sk_sp<SkImage> &image,
               const std::shared_ptr<RNSkPlatformContext> &context) {
    auto info = presentedInfo(image);
    std::weak_ptr<RNSkPlatformContext> weakContext = context;
    if (!image->isTextureBacked()) {
      RNSkWorker::getInstance().post(
          [this, id, generation, image, info, weakContext]() {
            SkBitmap pixels;
            if (!pixels.tryAllocPixels(info) ||
                !image->readPixels(nullptr, pixels.pixmap(), 0, 0)) {
              return;
            }
            makeAndPresent(id, generation, pixels.pixmap(), weakContext);
          });
      return;
    }
    auto *dawnContext = DawnContext::tryGetInstance();
    if (dawnContext == nullptr) {
      return;
    }
    dawnContext->readPixels(
        image, image->bounds(), info,
        [this, id, generation, info, weakContext](ReadResult result) {
          if (result == nullptr) {
            return;
          }
          std::shared_ptr<const SkImage::AsyncReadResult> pixels(
              std::move(result));
          RNSkWorker::getInstance().post([this, id, generation, info,
                                          weakContext, pixels]() {
            makeAndPresent(id, generation,
                           SkPixmap(info, pixels->data(0), pixels->rowBytes(0)),
                           weakContext);
          });
        });
  }

  void makeAndPresent(uint32_t id, uint64_t generation, const SkPixmap &pixels,
                      const std::weak_ptr<RNSkPlatformContext> &weakContext) {
    auto context = weakContext.lock();
    if (context == nullptr) {
      return;
    }
    auto bitmap = context->makePresentedBitmap(pixels);
    if (bitmap == nullptr) {
      return;
    }
    std::lock_guard<std::mutex> lock(_mutex);
    auto it = _entries.find(id);
    if (it == _entries.end() || it->second.generation != generation) {
      return;
    }
    bitmap->present(id);
  }

  std::mutex _mutex;
  std::unordered_map<uint32_t, Entry> _entries;
  uint64_t _generation = 0;
};

} // namespace RNSkia
