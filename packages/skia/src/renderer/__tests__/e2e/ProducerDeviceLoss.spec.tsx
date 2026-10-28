import { itRunsE2eOnly } from "../../../__tests__/setup";
import { AlphaType, ColorType } from "../../../skia/types";
import type { SkImage } from "../../../skia/types";
import { surface, wait } from "../setup";

// Whether two recordings are in flight when the device goes is a matter of
// timing, so the device is lost several times while the canvas animates.
const LOSSES = 10;

const readAllPixels = (image: SkImage) => {
  const pixels = image.readPixels(0, 0, {
    width: image.width(),
    height: image.height(),
    colorType: ColorType.RGBA_8888,
    alphaType: AlphaType.Unpremul,
  });
  if (pixels === null) {
    throw new Error("The snapshot has no readable pixels");
  }
  return pixels;
};

const differ = (a: SkImage, b: SkImage) => {
  const first = readAllPixels(a);
  const second = readAllPixels(b);
  return (
    first.length !== second.length ||
    first.some((channel, index) => channel !== second[index])
  );
};

describe("A canvas animating through GPU device losses", () => {
  itRunsE2eOnly(
    "keeps drawing on each device that replaced a lost one",
    async () => {
      await surface.screen("AnimatedCanvas");
      for (let loss = 1; loss <= LOSSES; loss++) {
        const replaced = await surface.eval(
          (Skia, ctx) => {
            const { lost } = Skia.gpu;
            ctx.forceGpuDeviceLoss();
            return lost.then(() => Skia.gpu.isAvailable);
          },
          {} as { forceGpuDeviceLoss: () => void }
        );
        expect(replaced).toBe(true);
        // The screen is requested again without being mounted again.
        const before = await surface.screen("AnimatedCanvas");
        await wait(300);
        const after = await surface.screen("AnimatedCanvas");
        expect({ loss, moved: differ(before, after) }).toEqual({
          loss,
          moved: true,
        });
      }
    }
  );
});
