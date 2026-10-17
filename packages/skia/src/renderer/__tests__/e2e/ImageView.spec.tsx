import { itRunsE2eOnly } from "../../../__tests__/setup";
import { AlphaType, ColorType } from "../../../skia/types";
import type { SkImage } from "../../../skia/types";
import { surface } from "../setup";

// A snapshot's colour pipeline can move a channel by a few units, so the
// screen's colours are read as colours rather than exact bytes.
const readScreenColor = (image: SkImage, fraction: number) => {
  const pixel = image.readPixels(
    Math.round(image.width() * fraction),
    Math.round(image.height() * fraction),
    {
      width: 1,
      height: 1,
      colorType: ColorType.RGBA_8888,
      alphaType: AlphaType.Unpremul,
    }
  );
  if (pixel === null) {
    return "unreadable";
  }
  const [red, green, blue] = Array.from(pixel);
  if (red < 60 && green > 200 && blue > 200) {
    return "cyan";
  }
  if (red > 200 && green < 60 && blue < 60) {
    return "red";
  }
  return `rgb(${red}, ${green}, ${blue})`;
};

describe("SkiaImageView", () => {
  itRunsE2eOnly(
    "fills its box with a GPU image and with a CPU image",
    async () => {
      const image = await surface.screen("ImageView");
      expect({
        top: readScreenColor(image, 1 / 4),
        bottom: readScreenColor(image, 3 / 4),
      }).toEqual({ top: "cyan", bottom: "red" });
    }
  );
});
