package com.reactnative.skia;

import android.graphics.SurfaceTexture;
import android.view.Surface;

public interface SkiaViewAPI {
    /** What a backing view the canvas no longer draws into reports to: nothing. */
    SkiaViewAPI DETACHED = new SkiaViewAPI() {
        @Override
        public void onSurfaceCreated(Surface surface, int width, int height) {}

        @Override
        public void onSurfaceChanged(Surface surface, int width, int height) {}

        @Override
        public void onSurfaceTextureCreated(SurfaceTexture surface, int width, int height) {}

        @Override
        public void onSurfaceTextureChanged(SurfaceTexture surface, int width, int height) {}

        @Override
        public void onSurfaceDestroyed() {}

        @Override
        public void holdFirstFrame() {}

        @Override
        public void releaseFirstFrame() {}
    };

    void onSurfaceCreated(Surface surface, int width, int height);

    void onSurfaceChanged(Surface surface, int width, int height);

    void onSurfaceTextureCreated(SurfaceTexture surface, int width, int height);

    void onSurfaceTextureChanged(SurfaceTexture surface, int width, int height);

    void onSurfaceDestroyed();

    /** Presents nothing until releaseFirstFrame(): the surface's layer shows before the window frame that lays it out. */
    void holdFirstFrame();

    /** The window frame that lays the surface out has committed, or the surface is gone: present again. */
    void releaseFirstFrame();
}
