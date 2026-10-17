/* eslint-disable @typescript-eslint/no-explicit-any */
import { AlphaType, ColorType } from "../../../skia/types";
import { surface } from "../setup";

// HermesInternal.getInstrumentedStats().js_externalBytes is the memory the
// runtime has been told about through setExternalMemoryPressure. It only
// exists on Hermes, so these tests run on device and are skipped on CanvasKit.
const ctx = {
  width: 256,
  height: 256,
  colorType: ColorType.RGBA_8888,
  alphaType: AlphaType.Opaque,
};

describe("Native object memory pressure", () => {
  it("charges the pixels of a raster image to the Hermes GC", async () => {
    if (surface.OS === "node") {
      return;
    }
    const result = await surface.eval((Skia, c) => {
      const hermes = (globalThis as any).HermesInternal;
      if (!hermes?.getInstrumentedStats) {
        return null;
      }
      const externalBytes = (): number =>
        hermes.getInstrumentedStats().js_externalBytes;
      const { width, height, colorType, alphaType } = c;
      const size = width * height * 4;
      const data = Skia.Data.fromBytes(new Uint8Array(size));
      const info = { width, height, colorType, alphaType };
      // A GC between the two readings frees the memory of earlier tests and
      // skews the delta, so retry a few times and keep the exact match.
      let delta = 0;
      for (let attempt = 0; attempt < 5; attempt++) {
        const before = externalBytes();
        const image = Skia.Image.MakeRasterImage(info, data, width * 4);
        delta = externalBytes() - before;
        if (image === null || delta === size) {
          break;
        }
      }
      return { delta, size };
    }, ctx);
    if (result === null) {
      return;
    }
    expect(result.delta).toBe(result.size);
  });

  it("charges an asImage() texture after its surface is disposed", async () => {
    if (surface.OS === "node") {
      return;
    }
    const result = await surface.eval((Skia, c) => {
      const hermes = (globalThis as any).HermesInternal;
      if (!hermes?.getInstrumentedStats) {
        return null;
      }
      const externalBytes = (): number =>
        hermes.getInstrumentedStats().js_externalBytes;
      const { width, height } = c;
      const size = width * height * 4;
      // The image keeps the texture alive once the surface is gone, so it
      // charges the texture. A GC between the two readings can only lower the
      // delta, so retry a few times.
      let delta = 0;
      for (let attempt = 0; attempt < 5; attempt++) {
        const before = externalBytes();
        const offscreen = Skia.Surface.MakeOffscreen(width, height)!;
        offscreen.flush();
        const image = offscreen.asImage();
        offscreen.dispose();
        delta = externalBytes() - before;
        image.dispose();
        if (delta >= size) {
          break;
        }
      }
      return { delta, size };
    }, ctx);
    if (result === null) {
      return;
    }
    expect(result.delta).toBeGreaterThanOrEqual(result.size);
  });

  it("charges nothing for an asImage() texture its surface still charges", async () => {
    if (surface.OS === "node") {
      return;
    }
    const result = await surface.eval((Skia, c) => {
      const hermes = (globalThis as any).HermesInternal;
      if (!hermes?.getInstrumentedStats) {
        return null;
      }
      const externalBytes = (): number =>
        hermes.getInstrumentedStats().js_externalBytes;
      const { width, height } = c;
      const offscreen = Skia.Surface.MakeOffscreen(width, height)!;
      offscreen.flush();
      const before = externalBytes();
      const image = offscreen.asImage();
      const delta = externalBytes() - before;
      image.dispose();
      offscreen.dispose();
      return { delta, size: width * height * 4 };
    }, ctx);
    if (result === null) {
      return;
    }
    // The surface charges the texture already: a second charge counts it twice.
    expect(result.delta).toBeLessThan(result.size);
  });

  it("charges a texture several asImage() images share only once", async () => {
    if (surface.OS === "node") {
      return;
    }
    const result = await surface.eval((Skia, c) => {
      const hermes = (globalThis as any).HermesInternal;
      if (!hermes?.getInstrumentedStats) {
        return null;
      }
      const externalBytes = (): number =>
        hermes.getInstrumentedStats().js_externalBytes;
      const { width, height } = c;
      const size = width * height * 4;
      // A GC between the two readings can only lower the delta, so retry a few
      // times.
      let delta = 0;
      for (let attempt = 0; attempt < 5; attempt++) {
        const before = externalBytes();
        const offscreen = Skia.Surface.MakeOffscreen(width, height)!;
        offscreen.flush();
        const first = offscreen.asImage();
        const second = offscreen.asImage();
        offscreen.dispose();
        delta = externalBytes() - before;
        first.dispose();
        second.dispose();
        if (delta >= size) {
          break;
        }
      }
      return { delta, size };
    }, ctx);
    if (result === null) {
      return;
    }
    // Both images keep the one texture alive: charging each counts it twice.
    expect(result.delta).toBeGreaterThanOrEqual(result.size);
    expect(result.delta).toBeLessThan(2 * result.size);
  });

  it("keeps an asImage() texture charged while an image holds it", async () => {
    if (surface.OS === "node") {
      return;
    }
    const result = await surface.eval((Skia, c) => {
      const hermes = (globalThis as any).HermesInternal;
      const { gc } = globalThis as any;
      if (!hermes?.getInstrumentedStats || typeof gc !== "function") {
        return Promise.resolve(null);
      }
      const externalBytes = (): number =>
        hermes.getInstrumentedStats().js_externalBytes;
      const { width, height } = c;
      // In a function of its own, so nothing but the image refers to what the
      // surface leaves behind.
      const bake = () => {
        const offscreen = Skia.Surface.MakeOffscreen(width, height)!;
        offscreen.flush();
        const image = offscreen.asImage();
        offscreen.dispose();
        return image;
      };
      gc();
      const before = externalBytes();
      const image = bake();
      return new Promise((resolve) => {
        setTimeout(() => {
          gc();
          resolve({
            held: externalBytes() - before,
            size: width * height * 4,
            width: image.width(),
          });
        }, 100);
      });
    }, ctx);
    if (result === null) {
      return;
    }
    const { held, size } = result as { held: number; size: number };
    expect(held).toBeGreaterThanOrEqual(size);
  });

  it("releases an asImage() texture's charge when its last image is disposed", async () => {
    if (surface.OS === "node") {
      return;
    }
    const result = await surface.eval((Skia, c) => {
      const hermes = (globalThis as any).HermesInternal;
      if (!hermes?.getInstrumentedStats) {
        return null;
      }
      const externalBytes = (): number =>
        hermes.getInstrumentedStats().js_externalBytes;
      const { width, height } = c;
      const size = width * height * 4;
      // A GC between the readings can only lower them, so retry a few times.
      let afterFirst = 0;
      let afterSecond = 0;
      for (let attempt = 0; attempt < 5; attempt++) {
        const before = externalBytes();
        const offscreen = Skia.Surface.MakeOffscreen(width, height)!;
        offscreen.flush();
        const first = offscreen.asImage();
        const second = offscreen.asImage();
        offscreen.dispose();
        first.dispose();
        afterFirst = externalBytes() - before;
        second.dispose();
        afterSecond = externalBytes() - before;
        if (afterFirst >= size) {
          break;
        }
      }
      return { afterFirst, afterSecond, size };
    }, ctx);
    if (result === null) {
      return;
    }
    // The second image still holds the texture after the first is disposed.
    expect(result.afterFirst).toBeGreaterThanOrEqual(result.size);
    expect(result.afterSecond).toBeLessThan(result.size);
  });

  it("charges nothing for an asImage() texture whose images were disposed first", async () => {
    if (surface.OS === "node") {
      return;
    }
    const result = await surface.eval((Skia, c) => {
      const hermes = (globalThis as any).HermesInternal;
      if (!hermes?.getInstrumentedStats) {
        return null;
      }
      const externalBytes = (): number =>
        hermes.getInstrumentedStats().js_externalBytes;
      const { width, height } = c;
      const before = externalBytes();
      const offscreen = Skia.Surface.MakeOffscreen(width, height)!;
      offscreen.flush();
      const image = offscreen.asImage();
      image.dispose();
      offscreen.dispose();
      return { delta: externalBytes() - before, size: width * height * 4 };
    }, ctx);
    if (result === null) {
      return;
    }
    // Disposing the surface frees the texture: nothing holds it any more.
    expect(result.delta).toBeLessThan(result.size);
  });

  it("releases an asImage() texture's charge once its images are collected", async () => {
    if (surface.OS === "node") {
      return;
    }
    const result = await surface.eval((Skia, c) => {
      const hermes = (globalThis as any).HermesInternal;
      const { gc } = globalThis as any;
      if (!hermes?.getInstrumentedStats || typeof gc !== "function") {
        return Promise.resolve(null);
      }
      const externalBytes = (): number =>
        hermes.getInstrumentedStats().js_externalBytes;
      const { width, height } = c;
      // Nothing outlives this function, so a collection can reclaim it all.
      const bakeAndDrop = () => {
        const offscreen = Skia.Surface.MakeOffscreen(width, height)!;
        offscreen.flush();
        offscreen.asImage();
        offscreen.asImage();
        offscreen.dispose();
      };
      gc();
      const before = externalBytes();
      bakeAndDrop();
      return new Promise((resolve) => {
        setTimeout(() => {
          gc();
          resolve({ held: externalBytes() - before, size: width * height * 4 });
        }, 100);
      });
    }, ctx);
    if (result === null) {
      return;
    }
    const { held, size } = result as { held: number; size: number };
    expect(held).toBeLessThan(size);
  });

  it("keeps no texture charge reachable from an image disposed before its surface", async () => {
    if (surface.OS === "node") {
      return;
    }
    const result = await surface.eval((Skia, c) => {
      const hermes = (globalThis as any).HermesInternal;
      const { gc } = globalThis as any;
      if (!hermes?.getInstrumentedStats || typeof gc !== "function") {
        return Promise.resolve(null);
      }
      const externalBytes = (): number =>
        hermes.getInstrumentedStats().js_externalBytes;
      const { width, height } = c;
      // Only the disposed image outlives this function.
      const bakeAndKeepDisposed = () => {
        const offscreen = Skia.Surface.MakeOffscreen(width, height)!;
        offscreen.flush();
        const disposed = offscreen.asImage();
        offscreen.asImage();
        disposed.dispose();
        offscreen.dispose();
        return disposed;
      };
      gc();
      const before = externalBytes();
      const disposed = bakeAndKeepDisposed();
      return new Promise((resolve) => {
        setTimeout(() => {
          gc();
          resolve({
            held: externalBytes() - before,
            size: width * height * 4,
            kept: typeof disposed,
          });
        }, 100);
      });
    }, ctx);
    if (result === null) {
      return;
    }
    const { held, size } = result as { held: number; size: number };
    expect(held).toBeLessThan(size);
  });

  it("charges the snapshot makeImageSnapshot() writes into an asImage() image, not the texture it shared", async () => {
    if (surface.OS === "node") {
      return;
    }
    const result = await surface.eval((Skia, c) => {
      const hermes = (globalThis as any).HermesInternal;
      if (!hermes?.getInstrumentedStats) {
        return null;
      }
      const externalBytes = (): number =>
        hermes.getInstrumentedStats().js_externalBytes;
      const { width, height } = c;
      const size = width * height * 4;
      const target = Skia.Surface.MakeOffscreen(width, height)!;
      target.flush();
      // A GC between the readings can only lower them, so retry a few times.
      let written = 0;
      let released = 0;
      let surfaceCharge = 0;
      for (let attempt = 0; attempt < 5; attempt++) {
        const beforeSource = externalBytes();
        const source = Skia.Surface.MakeOffscreen(width, height)!;
        surfaceCharge = externalBytes() - beforeSource;
        source.flush();
        const image = source.asImage();
        const beforeWrite = externalBytes();
        target.makeImageSnapshot(undefined, image);
        written = externalBytes() - beforeWrite;
        const beforeDispose = externalBytes();
        source.dispose();
        released = beforeDispose - externalBytes();
        image.dispose();
        if (written >= size) {
          break;
        }
      }
      target.dispose();
      return { written, released, surfaceCharge, size };
    }, ctx);
    if (result === null) {
      return;
    }
    expect(result.written).toBeGreaterThanOrEqual(result.size);
    // The image holds the snapshot now: nothing takes over the source's
    // texture when the source is disposed.
    expect(result.released).toBeGreaterThan(
      result.surfaceCharge - result.size / 2
    );
  });

  it("hands back the same wrapper when an object is unboxed again", async () => {
    if (surface.OS === "node") {
      return;
    }
    const result = await surface.eval((Skia, c) => {
      const hermes = (globalThis as any).HermesInternal;
      const externalBytes = (): number =>
        hermes?.getInstrumentedStats
          ? hermes.getInstrumentedStats().js_externalBytes
          : 0;
      const { width, height, colorType, alphaType } = c;
      const data = Skia.Data.fromBytes(new Uint8Array(width * height * 4));
      const image = Skia.Image.MakeRasterImage(
        { width, height, colorType, alphaType },
        data,
        width * 4
      )!;
      // box()/unbox() is how worklets move an object to another runtime.
      // Unboxing on the runtime that already has a wrapper must resolve to
      // that wrapper, and charge nothing more.
      const boxed = (globalThis as any).__skiaWorkletHelpers.box(image);
      const before = externalBytes();
      let sameObject = true;
      for (let i = 0; i < 100; i++) {
        if (boxed.unbox() !== image) {
          sameObject = false;
        }
      }
      return { sameObject, delta: externalBytes() - before };
    }, ctx);
    expect(result.sameObject).toBe(true);
    // A GC in between can only lower the reading.
    expect(result.delta).toBeLessThanOrEqual(0);
  });
});
