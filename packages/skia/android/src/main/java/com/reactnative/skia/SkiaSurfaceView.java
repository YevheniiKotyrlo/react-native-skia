package com.reactnative.skia;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Region;
import android.os.Build;
import android.view.AttachedSurfaceControl;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewTreeObserver;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

@SuppressLint("ViewConstructor")
public class SkiaSurfaceView extends SurfaceView implements SurfaceHolder.Callback2 {

    SkiaViewAPI mApi;
    private boolean mOnTop = false;
    private final FirstFrameDrawGate mDrawGate = new FirstFrameDrawGate(new FirstFrameDrawGate.Host() {
        @Override
        public void postDelayed(Runnable action, long delayMillis) {
            SkiaSurfaceView.this.postDelayed(action, delayMillis);
        }

        @Override
        public void removeCallbacks(Runnable action) {
            SkiaSurfaceView.this.removeCallbacks(action);
        }

        @Override
        public void invalidate() {
            SkiaSurfaceView.this.invalidate();
        }

        @Override
        public boolean setLayerShown(boolean shown) {
            return SkiaSurfaceView.this.setLayerShown(shown);
        }
    }, Build.VERSION.SDK_INT);
    // Registered after SurfaceView's own pre-draw listener, which applies a
    // changed z-order, so it runs after it in the same pre-draw pass.
    private final ViewTreeObserver.OnPreDrawListener mZOrderApplied = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            getViewTreeObserver().removeOnPreDrawListener(this);
            mDrawGate.onZOrderApplied();
            return true;
        }
    };

    public SkiaSurfaceView(Context context, SkiaViewAPI api, boolean zOrderOnTop,
            boolean zOrderMediaOverlay, boolean opaque) {
        super(context);
        mApi = api;
        // Both are read when the surface is created.
        setZOrder(zOrderOnTop, zOrderMediaOverlay);
        setOpaque(opaque);
        getHolder().addCallback(this);
    }

    // The format drives the compositor's opaque flag for this layer. It can
    // change on a live surface: SurfaceView reports it through surfaceChanged.
    public void setOpaque(boolean opaque) {
        getHolder().setFormat(opaque ? PixelFormat.OPAQUE : PixelFormat.TRANSLUCENT);
    }

    // The sublayer the surface is composited at, relative to the window: above
    // it (on top), or below it, above the other SurfaceViews there (media
    // overlay) or among them (the default). setZOrderOnTop(false) resets the
    // request to the default, so it goes first. Before Android 11 the request
    // is read when the surface is created; from Android 11 on, SurfaceView
    // applies a changed request with the window's next frame (its pre-draw
    // listener runs updateSurface()), which invalidate() schedules.
    public void setZOrder(boolean onTop, boolean mediaOverlay) {
        mOnTop = onTop;
        setZOrderOnTop(onTop);
        if (!onTop) {
            setZOrderMediaOverlay(mediaOverlay);
        }
        invalidate();
        if (mDrawGate.hidesLayer()) {
            ViewTreeObserver observer = getViewTreeObserver();
            observer.removeOnPreDrawListener(mZOrderApplied);
            observer.addOnPreDrawListener(mZOrderApplied);
        }
    }

    // SurfaceView skips draw() and punches its hole in dispatchDraw.
    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        mDrawGate.onWindowFrameDrawn();
        if (mDrawGate.hasFrame()) {
            super.dispatchDraw(canvas);
        }
    }

    // The layer is SurfaceView's own. Before Android 12L SurfaceView shows a
    // new layer in a transaction it applies at creation, and Android 14 sets a
    // layer's alpha in the transaction it merges into the window's next frame
    // on a z-order change, so a hide goes at once and, where the window
    // applies changes with its frame, again with that frame, after it.
    private boolean setLayerShown(boolean shown) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || (!shown && mOnTop)) {
            return false;
        }
        SurfaceControl layer = getSurfaceControl();
        if (layer == null) {
            return false;
        }
        float alpha = shown ? 1f : 0f;
        AttachedSurfaceControl window =
                Build.VERSION.SDK_INT >= FirstFrameDrawGate.LAYER_SHOWN_WITH_WINDOW_FRAME_FROM
                        ? getRootSurfaceControl() : null;
        if (!shown || window == null) {
            new SurfaceControl.Transaction().setAlpha(layer, alpha).apply();
        }
        if (window != null) {
            window.applyTransactionOnDraw(new SurfaceControl.Transaction().setAlpha(layer, alpha));
        }
        return true;
    }

    // SurfaceView adds its bounds to the window's transparent region once its
    // draw is reported, and the compositor skips the window there.
    @Override
    public boolean gatherTransparentRegion(@Nullable Region region) {
        return !mDrawGate.hasFrame() || super.gatherTransparentRegion(region);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(mZOrderApplied);
        mDrawGate.onDetachedFromWindow();
        super.onDetachedFromWindow();
        releaseSurface();
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        mDrawGate.onSurfaceCreated();
        mApi.onSurfaceCreated(holder.getSurface(), getWidth(), getHeight());
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        mApi.onSurfaceChanged(holder.getSurface(), getWidth(), getHeight());
    }

    @Override
    public void surfaceRedrawNeeded(@NonNull SurfaceHolder holder) {
        // Answered in surfaceRedrawNeededAsync, which SurfaceView calls instead.
    }

    // The draw is reported finished once a frame is in the surface: see
    // FirstFrameDrawGate. GLSurfaceView holds this runnable the same way.
    @Override
    public void surfaceRedrawNeededAsync(@NonNull SurfaceHolder holder, @NonNull Runnable drawingFinished) {
        mDrawGate.onRedrawNeeded(drawingFinished);
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        releaseSurface();
    }

    /** See FirstFrameDrawGate.currentSurface. Any thread. */
    int currentSurface() {
        return mDrawGate.currentSurface();
    }

    /** A first frame was presented into the surface currentSurface() named. Main thread. */
    void onFirstFramePresented(int surface) {
        mDrawGate.onFirstFramePresented(surface);
    }

    private void releaseSurface() {
        mDrawGate.onSurfaceReleased(mApi::onSurfaceDestroyed);
    }
}
