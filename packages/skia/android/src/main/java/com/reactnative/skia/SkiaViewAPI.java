package com.reactnative.skia;

import android.graphics.SurfaceTexture;
import android.view.Surface;

public interface SkiaViewAPI {
    /** What a backing view reports to once the renderer has moved to another view: nothing. */
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
        public void onSurfaceTextureUpdated() {}

        @Override
        public void onSurfaceDestroyed() {}
    };

    void onSurfaceCreated(Surface surface, int width, int height);

    void onSurfaceChanged(Surface surface, int width, int height);

    void onSurfaceTextureCreated(SurfaceTexture surface, int width, int height);

    void onSurfaceTextureChanged(SurfaceTexture surface, int width, int height);

    /** The TextureView drew a frame from its SurfaceTexture, in the window frame being recorded. */
    void onSurfaceTextureUpdated();

    void onSurfaceDestroyed();
}
