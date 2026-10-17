/**
 * @jest-environment jsdom
 */
import React, { act } from "react";
import type { ReactElement } from "react";
import { createRoot } from "react-dom/client";

import type { SkImage } from "../../skia/types";
import { SkiaImageView } from "../SkiaImageView";

// Every retain, release and id the native view was given, in order.
const mockLog: string[] = [];

jest.mock("../../specs/SkiaImageViewNativeComponent", () => ({
  __esModule: true,
  default: ({ presentedImageId }: { presentedImageId: number }) => {
    mockLog.push(`shows ${presentedImageId}`);
    return null;
  },
}));
jest.mock("../api", () => ({
  SkiaViewApi: {
    presentedImageId: (image: { id: number }) => image.id,
    retainPresentedImage: (image: { id: number }) =>
      mockLog.push(`retain ${image.id}`),
    releasePresentedImage: (id: number) => mockLog.push(`release ${id}`),
  },
}));

const makeImage = (id: number) => ({ id }) as unknown as SkImage;

const mountView = (element: ReactElement) => {
  const container = document.createElement("div");
  document.body.appendChild(container);
  const root = createRoot(container);
  act(() => root.render(element));
  return {
    rerender: (next: ReactElement) => act(() => root.render(next)),
    unmount: () => {
      act(() => root.unmount());
      container.remove();
    },
  };
};

beforeAll(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
});

beforeEach(() => {
  mockLog.length = 0;
});

describe("SkiaImageView on native", () => {
  it("retains the image it shows and releases it by id on unmount", () => {
    const view = mountView(<SkiaImageView image={makeImage(7)} />);
    view.unmount();
    expect(mockLog).toEqual(["shows 7", "retain 7", "release 7"]);
  });

  it("releases the image it showed before retaining the next one", () => {
    const view = mountView(<SkiaImageView image={makeImage(7)} />);
    view.rerender(<SkiaImageView image={makeImage(8)} />);
    view.unmount();
    expect(mockLog).toEqual([
      "shows 7",
      "retain 7",
      "shows 8",
      "release 7",
      "retain 8",
      "release 8",
    ]);
  });

  it("shows nothing for a null image and retains nothing", () => {
    const view = mountView(<SkiaImageView image={null} />);
    view.unmount();
    expect(mockLog).toEqual(["shows 0"]);
  });

  // StrictMode runs the unmount cleanup once right after mount and the
  // effect again: every retain still meets its release.
  it("balances its retains under StrictMode's simulated unmount", () => {
    const view = mountView(
      <React.StrictMode>
        <SkiaImageView image={makeImage(7)} />
      </React.StrictMode>
    );
    view.unmount();
    const retains = mockLog.filter((entry) => entry === "retain 7");
    const releases = mockLog.filter((entry) => entry === "release 7");
    expect(retains.length).toBe(2);
    expect(releases.length).toBe(2);
    expect(mockLog[mockLog.length - 1]).toBe("release 7");
  });
});
