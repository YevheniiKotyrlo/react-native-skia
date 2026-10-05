#include "RNDawnWindowContext.h"

#include <chrono>

#include "RNDawnContext.h"
#include "RNSkLog.h"

namespace RNSkia {

namespace {
struct PresentProbe {
  std::chrono::steady_clock::time_point windowStart =
      std::chrono::steady_clock::now();
  int calls = 0;
  int noTexture = 0;
  int lastStatus = -1;
  int presented = 0;
  double acquireMilliseconds = 0;
  double presentMilliseconds = 0;
};
PresentProbe presentProbe;

double millisecondsSince(std::chrono::steady_clock::time_point start) {
  return std::chrono::duration<double, std::milli>(
             std::chrono::steady_clock::now() - start)
      .count();
}

void reportPresentProbe(int width, int height) {
  if (millisecondsSince(presentProbe.windowStart) < 1000) {
    return;
  }
  RNSkLogger::logToConsole(
      "PRESENT_PROBE %dx%d calls=%d noTexture=%d lastStatus=%d presented=%d "
      "acquireMs=%.1f presentMs=%.1f",
      width, height, presentProbe.calls, presentProbe.noTexture,
      presentProbe.lastStatus, presentProbe.presented,
      presentProbe.acquireMilliseconds, presentProbe.presentMilliseconds);
  presentProbe = PresentProbe{};
}
} // namespace

void DawnWindowContext::present() {
  auto recording = _recorder->snap();
  if (!recording) {
    throw std::runtime_error("Failed to create graphite recording");
  }
  DawnContext::getInstance().submitRecording(recording.get());
#ifdef __APPLE__
  dawn::native::metal::WaitForCommandsToBeScheduled(_device.Get());
#endif
  _surface.Present();
}

bool DawnWindowContext::presentRecordings(
    const std::vector<skgpu::graphite::Recording *> &recordings) {
  presentProbe.calls++;
  auto acquireStart = std::chrono::steady_clock::now();
  wgpu::SurfaceTexture surfaceTexture;
  _surface.GetCurrentTexture(&surfaceTexture);
  presentProbe.acquireMilliseconds += millisecondsSince(acquireStart);
  auto texture = surfaceTexture.texture;
  if (!texture) {
    presentProbe.noTexture++;
    presentProbe.lastStatus = static_cast<int>(surfaceTexture.status);
    reportPresentProbe(_width, _height);
    return false;
  }
  // The surface only names the replay target; wrapping the swapchain texture
  // records nothing on the window's recorder.
  auto backendTex = skgpu::graphite::BackendTextures::MakeDawn(texture.Get());
  SkSurfaceProps surfaceProps;
  auto surface = SkSurfaces::WrapBackendTexture(
      _recorder, backendTex, DawnUtils::viewColorSpace(_useP3ColorSpace),
      &surfaceProps);
  if (!surface) {
    return false;
  }
  bool success =
      DawnContext::getInstance().insertRecordings(recordings, surface.get());
#ifdef __APPLE__
  dawn::native::metal::WaitForCommandsToBeScheduled(_device.Get());
#endif
  auto presentStart = std::chrono::steady_clock::now();
  _surface.Present();
  presentProbe.presentMilliseconds += millisecondsSince(presentStart);
  presentProbe.presented++;
  reportPresentProbe(_width, _height);
  return success;
}

bool DawnWindowContext::presentImage(const sk_sp<SkImage> &image) {
  auto surface = getSurface();
  if (!surface) {
    return false;
  }
  surface->getCanvas()->drawImage(image, 0, 0);
  present();
  return true;
}

} // namespace RNSkia
