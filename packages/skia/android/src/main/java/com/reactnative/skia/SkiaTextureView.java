package com.reactnative.skia;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import androidx.annotation.NonNull;

@SuppressLint("ViewConstructor")
public class SkiaTextureView extends TextureView implements TextureView.SurfaceTextureListener {

    private String tag = "SkiaTextureView";

    private static final SkiaViewAPI DETACHED_API = new SkiaViewAPI() {
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
    };

    SkiaViewAPI mApi;

    public SkiaTextureView(Context context, SkiaViewAPI api, boolean opaque) {
        super(context);
        mApi = api;
        // An opaque TextureView lets the UI toolkit skip blending it; it can be
        // toggled on a live view.
        setOpaque(opaque);
        setSurfaceTextureListener(this);
    }

    // Hands the view its texture before its first draw, which then shows the
    // frame presented into it (see BackingViewKind.receivesSurfaceBeforeFirstDraw).
    // TextureView reports no onSurfaceTextureAvailable for a texture it was given.
    void supplySurfaceTexture(int width, int height) {
        SurfaceTexture surfaceTexture = new SurfaceTexture(false);
        surfaceTexture.setDefaultBufferSize(width, height);
        setSurfaceTexture(surfaceTexture);
        mApi.onSurfaceTextureCreated(surfaceTexture, width, height);
    }

    // The renderer moved to another view: this one keeps its last frame on screen
    // until it is removed, and reports nothing.
    void detachFromRenderer() {
        mApi = DETACHED_API;
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
        Log.i(tag, "onSurfaceTextureAvailable:  " + width + "x" + height);
        mApi.onSurfaceTextureCreated(surfaceTexture, width, height);
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
        Log.i(tag, "onSurfaceTextureSizeChanged:  " + width + "x" + height);
        mApi.onSurfaceTextureChanged(surfaceTexture, width, height);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
        mApi.onSurfaceDestroyed();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
    }
}