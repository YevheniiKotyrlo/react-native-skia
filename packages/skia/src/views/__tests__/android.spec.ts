import type { NativeProps } from "../../specs/SkiaViewNativeComponent";
import type { AndroidBackingView, AndroidCanvasProps } from "../types";
import {
  androidNativeProps,
  resolveBackingView,
  resolveHighBitDepth,
} from "../android";

jest.mock("../../Platform", () => ({
  Platform: { OS: "android" },
}));

const { Platform } = jest.requireMock<{
  Platform: { OS: string };
}>("../../Platform");

beforeEach(() => {
  Platform.OS = "android";
});

// What a JavaScript caller can pass that TypeScript refuses.
const untyped = (android: object) => android as AndroidCanvasProps;

describe("AndroidCanvasProps", () => {
  // Without `backingView` the union cannot tell the views apart, so asking
  // another view for an option only a SurfaceView takes must not compile.
  it("takes each option on the views that honour it only", () => {
    const textureViewTakesZOrder: {
      opaque: false;
      zOrderOnTop: true;
    } extends AndroidCanvasProps
      ? true
      : false = false;
    const hardwareBufferViewTakesOpaque: {
      backingView: "HardwareBufferView";
      opaque: true;
    } extends AndroidCanvasProps
      ? true
      : false = false;
    const surfaceViewTakesZOrder: {
      backingView: "SurfaceView";
      opaque: false;
      zOrderOnTop: true;
    } extends AndroidCanvasProps
      ? true
      : false = true;
    expect([
      textureViewTakesZOrder,
      hardwareBufferViewTakesOpaque,
      surfaceViewTakesZOrder,
    ]).toEqual([false, false, true]);
  });

  // An option's default is what every view does without it.
  it("takes an option's default on every view", () => {
    const textureViewTakesDefaults: {
      opaque: true;
      zOrderOnTop: false;
      zOrderMediaOverlay: false;
    } extends AndroidCanvasProps
      ? true
      : false = true;
    const hardwareBufferViewTakesDefaults: {
      backingView: "HardwareBufferView";
      opaque: false;
      zOrderOnTop: false;
    } extends AndroidCanvasProps
      ? true
      : false = true;
    expect([textureViewTakesDefaults, hardwareBufferViewTakesDefaults]).toEqual(
      [true, true]
    );
  });

  // The published types restate the views rather than import the spec.
  it("names the views the native component's spec takes", () => {
    type SpecBackingView = NonNullable<NativeProps["androidBackingView"]>;
    const sameViews: [AndroidBackingView] extends [SpecBackingView]
      ? [SpecBackingView] extends [AndroidBackingView]
        ? true
        : false
      : false = true;
    expect(sameViews).toBe(true);
  });
});

describe("resolveBackingView", () => {
  it("names a TextureView by default", () => {
    expect(resolveBackingView()).toBe("TextureView");
    expect(resolveBackingView({ opaque: true })).toBe("TextureView");
  });

  it.each<AndroidBackingView>([
    "TextureView",
    "SurfaceView",
    "HardwareBufferView",
  ])("names %s", (backingView) => {
    expect(resolveBackingView(untyped({ backingView }))).toBe(backingView);
  });

  // The generated string-enum parser aborts on a value it does not know.
  it("gives a value no view takes the default view", () => {
    expect(resolveBackingView(untyped({ backingView: "SurfaceControl" }))).toBe(
      "TextureView"
    );
  });
});

describe("androidNativeProps", () => {
  it("backs a canvas with a translucent TextureView by default", () => {
    expect(androidNativeProps(undefined, "TextureView")).toEqual({
      androidBackingView: "TextureView",
      androidOpaque: false,
      androidZOrderOnTop: false,
      androidZOrderMediaOverlay: false,
    });
  });

  it.each<[AndroidCanvasProps, boolean]>([
    [{ opaque: true }, true],
    [{ backingView: "TextureView" }, false],
    [{ backingView: "SurfaceView" }, true],
    [{ backingView: "SurfaceView", opaque: false }, false],
    [{ backingView: "HardwareBufferView" }, false],
  ])("resolves the opacity of %j to %s", (android, opaque) => {
    expect(
      androidNativeProps(android, resolveBackingView(android)).androidOpaque
    ).toBe(opaque);
  });

  // The native view ignores a later backingView, with a warning, and keeps
  // the view it mounted with: the opacity takes that view's default.
  it("resolves the opacity for the view the canvas mounted with", () => {
    expect(
      androidNativeProps({ backingView: "SurfaceView" }, "TextureView")
    ).toEqual({
      androidBackingView: "SurfaceView",
      androidOpaque: false,
      androidZOrderOnTop: false,
      androidZOrderMediaOverlay: false,
    });
    expect(androidNativeProps({}, "SurfaceView")).toMatchObject({
      androidBackingView: "TextureView",
      androidOpaque: true,
    });
  });

  // A SurfaceView takes them, and any other view warns about them.
  it("hands the z-order options to every view", () => {
    const zOrder = { zOrderOnTop: true, zOrderMediaOverlay: true };
    expect(
      androidNativeProps(
        { backingView: "SurfaceView", ...zOrder },
        "SurfaceView"
      )
    ).toMatchObject({
      androidZOrderOnTop: true,
      androidZOrderMediaOverlay: true,
    });
    expect(androidNativeProps(untyped(zOrder), "TextureView")).toMatchObject({
      androidBackingView: "TextureView",
      androidZOrderOnTop: true,
      androidZOrderMediaOverlay: true,
    });
  });

  it("keeps the opacity asked of a value no view takes", () => {
    const android = untyped({ backingView: "SurfaceControl", opaque: true });
    expect(androidNativeProps(android, resolveBackingView(android))).toEqual({
      androidBackingView: "TextureView",
      androidOpaque: true,
      androidZOrderOnTop: false,
      androidZOrderMediaOverlay: false,
    });
  });

  it("is a pure function of its arguments", () => {
    const android: AndroidCanvasProps = Object.freeze({
      backingView: "SurfaceView",
      opaque: false,
      zOrderMediaOverlay: true,
    });
    expect(androidNativeProps(android, "SurfaceView")).toEqual(
      androidNativeProps(android, "SurfaceView")
    );
    expect(android).toEqual({
      backingView: "SurfaceView",
      opaque: false,
      zOrderMediaOverlay: true,
    });
  });
});

describe("resolveHighBitDepth", () => {
  it("gives the 10-bit format to a SurfaceView only, on Android", () => {
    expect(
      resolveHighBitDepth(true, {
        backingView: "SurfaceView",
        highBitDepth: true,
      })
    ).toBe(true);
    expect(
      resolveHighBitDepth(true, {
        backingView: "TextureView",
        highBitDepth: true,
      })
    ).toBe(false);
    expect(
      resolveHighBitDepth(true, {
        backingView: "HardwareBufferView",
        highBitDepth: true,
      })
    ).toBe(false);
  });

  // The native view keeps the buffer format it was created with.
  it("keeps the bit depth the canvas mounted with, on Android", () => {
    expect(
      resolveHighBitDepth(true, {
        backingView: "SurfaceView",
        highBitDepth: false,
      })
    ).toBe(false);
    expect(
      resolveHighBitDepth(false, {
        backingView: "SurfaceView",
        highBitDepth: true,
      })
    ).toBe(true);
  });

  it("leaves the bit depth to the prop on the other platforms", () => {
    Platform.OS = "ios";
    const mounted = {
      backingView: "TextureView",
      highBitDepth: false,
    } as const;
    expect(resolveHighBitDepth(true, mounted)).toBe(true);
    expect(resolveHighBitDepth(false, mounted)).toBe(false);
  });
});
