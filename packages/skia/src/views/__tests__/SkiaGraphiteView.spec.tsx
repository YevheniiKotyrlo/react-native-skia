/**
 * @jest-environment jsdom
 */
import React, { act } from "react";
import type { Ref } from "react";
import { createRoot } from "react-dom/client";

import { SkiaGraphiteView } from "../SkiaGraphiteView";
import type {
  SkiaGraphiteViewProps,
  SkiaGraphiteViewRef,
} from "../SkiaGraphiteView";

// The host instance of the native view, which getContext() measures.
let mockHost: object = {};
// The props the native view was last rendered with.
let mockNativeProps: Record<string, unknown> = {};

jest.mock("../../Platform", () => ({ Platform: { OS: "android" } }));
jest.mock("../../specs/SkiaViewNativeComponent", () => ({
  __esModule: true,
  default: ({ ref, ...props }: { ref: Ref<object> }) => {
    mockNativeProps = props;
    require("react").useImperativeHandle(ref, () => mockHost);
    return null;
  },
}));
jest.mock("../api", () => {
  let nextNativeId = 1000;
  return {
    SkiaViewApi: {
      allocateNativeId: () => nextNativeId++,
      makeGraphiteContext: jest.fn(),
      destroyGraphiteContext: jest.fn(),
    },
  };
});

const { SkiaViewApi } = jest.requireMock<{
  SkiaViewApi: {
    makeGraphiteContext: jest.Mock;
    destroyGraphiteContext: jest.Mock;
  };
}>("../api");

const mountView = ({
  strict = false,
  props = {},
}: { strict?: boolean; props?: SkiaGraphiteViewProps } = {}) => {
  const container = document.createElement("div");
  document.body.appendChild(container);
  const root = createRoot(container);
  const ref = React.createRef<SkiaGraphiteViewRef>();
  const render = (renderedProps: SkiaGraphiteViewProps) => {
    const element = <SkiaGraphiteView {...renderedProps} ref={ref} />;
    act(() => {
      root.render(
        strict ? <React.StrictMode>{element}</React.StrictMode> : element
      );
    });
  };
  render(props);
  const view = ref.current;
  if (view === null) {
    throw new Error("SkiaGraphiteView did not attach its ref");
  }
  return {
    view,
    rerender: render,
    unmount: async () => {
      await act(async () => root.unmount());
      container.remove();
    },
  };
};

beforeAll(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
});

beforeEach(() => {
  SkiaViewApi.makeGraphiteContext.mockReset();
  SkiaViewApi.destroyGraphiteContext.mockReset();
});

// A host laid out at 200 × 100 points and drawn at half that size: measured
// relative to itself, React Native reports its layout box.
const createScaledHost = () => {
  const host = {
    measureLayout: (
      relativeTo: object,
      onSuccess: (
        left: number,
        top: number,
        width: number,
        height: number
      ) => void,
      onFail: () => void
    ) => (relativeTo === host ? onSuccess(0, 0, 200, 100) : onFail()),
    getBoundingClientRect: () => ({ width: 100, height: 50 }),
  };
  return host;
};

describe("SkiaGraphiteView on native", () => {
  it("sizes the context from the view's layout, not its transformed box", async () => {
    mockHost = createScaledHost();
    const { view, unmount } = mountView();
    view.getContext();
    expect(SkiaViewApi.makeGraphiteContext).toHaveBeenCalledWith(
      view.getNativeId(),
      200,
      100,
      false
    );
    await unmount();
  });

  // Until the native view binds, the context records for the format the
  // props call for: 10-bit on a SurfaceView only, as the view will have it.
  it.each<[SkiaGraphiteViewProps["android"], boolean]>([
    [{ opaque: true }, false],
    [{ backingView: "HardwareBufferView" }, false],
    [{ backingView: "SurfaceView" }, true],
    [{ backingView: "SurfaceView", opaque: false }, true],
  ])(
    "records %j with highBitDepth for the 10-bit format: %s",
    async (android, highBitDepth) => {
      mockHost = createScaledHost();
      const { view, unmount } = mountView({
        props: { highBitDepth: true, android },
      });
      view.getContext();
      expect(SkiaViewApi.makeGraphiteContext.mock.calls[0].at(-1)).toBe(
        highBitDepth
      );
      await unmount();
    }
  );

  // The native view keeps the buffer format it was created with.
  it.each([true, false])(
    "records for the bit depth it mounted with, highBitDepth %s",
    async (highBitDepth) => {
      mockHost = createScaledHost();
      const android = { backingView: "SurfaceView" } as const;
      const { view, rerender, unmount } = mountView({
        props: { highBitDepth, android },
      });
      rerender({ highBitDepth: !highBitDepth, android });
      view.getContext();
      expect(SkiaViewApi.makeGraphiteContext.mock.calls[0].at(-1)).toBe(
        highBitDepth
      );
      await unmount();
    }
  );

  // The view makes a canvas with the 10-bit format opaque from the format it
  // was created with, so a translucent SurfaceView asks for a translucent
  // surface whatever highBitDepth says.
  it.each([false, true])(
    "hands a translucent SurfaceView's opacity to the view with highBitDepth %s",
    async (highBitDepth) => {
      mockHost = createScaledHost();
      const { unmount } = mountView({
        props: {
          highBitDepth,
          android: { backingView: "SurfaceView", opaque: false },
        },
      });
      expect(mockNativeProps).toMatchObject({
        androidBackingView: "SurfaceView",
        androidOpaque: false,
      });
      await unmount();
    }
  );

  // The native view ignores a later backingView, so the opacity keeps the
  // default of the view it mounted with.
  it("resolves the opacity for the view it mounted with", async () => {
    mockHost = createScaledHost();
    const { rerender, unmount } = mountView({
      props: { android: { backingView: "SurfaceView" } },
    });
    rerender({ android: { backingView: "TextureView" } });
    expect(mockNativeProps).toMatchObject({
      androidBackingView: "TextureView",
      androidOpaque: true,
    });
    await unmount();
  });

  // The native target made for the id outlives the context JS holds; the
  // view retires it on unmount in case no native view ever bound to it.
  it("retires its context on unmount", async () => {
    mockHost = createScaledHost();
    const { view, unmount } = mountView();
    view.getContext();
    expect(SkiaViewApi.destroyGraphiteContext).not.toHaveBeenCalled();
    await unmount();
    expect(SkiaViewApi.destroyGraphiteContext).toHaveBeenCalledTimes(1);
    expect(SkiaViewApi.destroyGraphiteContext).toHaveBeenCalledWith(
      view.getNativeId()
    );
  });

  // StrictMode runs the unmount cleanup once right after mount and re-runs
  // the effect, with the id unchanged: a context kept from the first effect
  // run was made for the id the cleanup retires, and the native side keeps
  // its target for the view (see RNSkGraphiteTargetRegistry).
  it("retires the same id under StrictMode's simulated unmount", async () => {
    mockHost = createScaledHost();
    const { view, unmount } = mountView({ strict: true });
    const nativeId = view.getNativeId();
    view.getContext();
    expect(SkiaViewApi.makeGraphiteContext).toHaveBeenLastCalledWith(
      nativeId,
      200,
      100,
      false
    );
    expect(SkiaViewApi.destroyGraphiteContext.mock.calls).toEqual([[nativeId]]);
    await unmount();
    expect(SkiaViewApi.destroyGraphiteContext.mock.calls).toEqual([
      [nativeId],
      [nativeId],
    ]);
  });
});
