import { Platform } from "../Platform";
import type { NativeProps } from "../specs/SkiaViewNativeComponent";

import type { AndroidBackingView, AndroidCanvasProps } from "./types";

type AndroidNativePropName =
  | "androidBackingView"
  | "androidOpaque"
  | "androidZOrderOnTop"
  | "androidZOrderMediaOverlay";

/** What the native component is handed for the `android` prop. */
export type AndroidNativeProps = {
  [Name in AndroidNativePropName]-?: NonNullable<NativeProps[Name]>;
};

// A value a JavaScript caller can pass that no view takes would hit the
// generated string-enum parser, which aborts on unknown values: it gets the
// default view, while TypeScript still reports a view the switch misses.
const resolveUnknownBackingView = (_backingView: never): AndroidBackingView =>
  "TextureView";

/** The view the `android` prop names: its `backingView`, a `TextureView` by default. */
export const resolveBackingView = (
  android?: AndroidCanvasProps
): AndroidBackingView => {
  const backingView = android?.backingView;
  switch (backingView) {
    case "SurfaceView":
    case "TextureView":
    case "HardwareBufferView":
      return backingView;
    case undefined:
      return "TextureView";
    default:
      return resolveUnknownBackingView(backingView);
  }
};

/**
 * The native props the `android` prop resolves to, on a canvas that mounted
 * with `mounted`: the native view keeps the view it mounted with, ignoring a
 * later `backingView` with a warning, so the opacity takes that view's
 * default — a `SurfaceView` is opaque unless asked otherwise, a `TextureView`
 * translucent unless asked otherwise, and a `HardwareBufferView` always
 * composites with alpha. The z-order options go to every view: a
 * `SurfaceView` takes them, and any other warns about them. The native view
 * adds the opacity of the 10-bit format it was created with (see
 * BackingViewKind.resolveOpaque()).
 */
export const androidNativeProps = (
  android: AndroidCanvasProps | undefined,
  mounted: AndroidBackingView
): AndroidNativeProps => {
  const requested = {
    androidBackingView: resolveBackingView(android),
    androidZOrderOnTop: !!android?.zOrderOnTop,
    androidZOrderMediaOverlay: !!android?.zOrderMediaOverlay,
  };
  switch (mounted) {
    case "SurfaceView":
      return { ...requested, androidOpaque: android?.opaque !== false };
    case "TextureView":
      return { ...requested, androidOpaque: android?.opaque === true };
    case "HardwareBufferView":
      return { ...requested, androidOpaque: false };
  }
};

/** What a canvas's Android view is created with, when the canvas mounts. */
export interface MountedAndroidView {
  backingView: AndroidBackingView;
  highBitDepth: boolean;
}

/**
 * Whether a canvas has the high bit depth format. On Android the view keeps
 * the format it mounted with, and only a `SurfaceView` has it (see
 * BackingViewKind.resolveHighBitDepth()); elsewhere any canvas that asks
 * does.
 */
export const resolveHighBitDepth = (
  highBitDepth: boolean,
  mounted: MountedAndroidView
) =>
  Platform.OS === "android"
    ? mounted.highBitDepth && mounted.backingView === "SurfaceView"
    : highBitDepth;
