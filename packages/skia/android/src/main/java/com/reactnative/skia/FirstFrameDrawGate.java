package com.reactnative.skia;

import java.util.ArrayList;

/**
 * Holds a SurfaceView's draw reports until a frame is presented into its
 * surface. The window punches the hole a surface behind it shows through once
 * the draw is reported finished, so a report sent before the first frame
 * opens the hole on an empty surface, which shows black. Main thread.
 */
final class FirstFrameDrawGate {
    private boolean mFramePresented = false;
    private final ArrayList<Runnable> mPendingDrawsFinished = new ArrayList<>();

    /** A new surface: nothing has been presented into it yet. */
    void onSurfaceCreated() {
        mFramePresented = false;
    }

    /** The window asks to be told once the surface has drawn. */
    void onRedrawNeeded(Runnable drawingFinished) {
        if (mFramePresented) {
            drawingFinished.run();
        } else {
            mPendingDrawsFinished.add(drawingFinished);
        }
    }

    /** A frame was presented into the surface. */
    void onFirstFramePresented() {
        mFramePresented = true;
        reportPendingDraws();
    }

    /** The surface is gone, so it has nothing left to draw. */
    void onSurfaceReleased() {
        mFramePresented = false;
        reportPendingDraws();
    }

    /** The view left its window, which settles the draws it still waits for. */
    void onDetachedFromWindow() {
        mPendingDrawsFinished.clear();
    }

    private void reportPendingDraws() {
        if (mPendingDrawsFinished.isEmpty()) {
            return;
        }
        ArrayList<Runnable> pending = new ArrayList<>(mPendingDrawsFinished);
        mPendingDrawsFinished.clear();
        for (Runnable drawingFinished : pending) {
            drawingFinished.run();
        }
    }
}
