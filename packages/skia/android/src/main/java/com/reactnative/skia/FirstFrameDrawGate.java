package com.reactnative.skia;

import android.os.Build;

import java.util.ArrayList;

/**
 * Holds a SurfaceView's draw reports, and its hole, until a frame is
 * presented into its surface. The window punches the hole a surface behind it
 * shows through once the draw is reported finished, so a report sent before
 * the first frame would open the hole on an empty surface, which shows black.
 * From Android 14 the platform bounds the window's wait for that report
 * itself, so the report waits for the frame however long it takes. Before it,
 * a first frame that does not come within FIRST_FRAME_WAIT_MILLIS is not
 * waited for: the reports go out, and the view keeps the hole closed until
 * hasFrame(). A SurfaceView counts its draw as reported once, for its later
 * surfaces too, so the view keeps their holes closed the same way. Leaving
 * the window reports what is held, because Android 13 settles a SurfaceView's
 * pending draw only through that report. A first frame is reported for the
 * surface it went into, and one reported for a surface released since opens
 * nothing.
 *
 * A layer below the window shows through every transparent region of the
 * window, another SurfaceView's hole among them, so while its own hole is
 * closed the layer is hidden. It is shown with the window frame that opens
 * the hole where the window can apply a layer change with its frame (from
 * LAYER_SHOWN_WITH_WINDOW_FRAME_FROM), and before that as the frame is drawn,
 * ahead of the window's buffer: there it can show through another hole a
 * frame or two early, and never leaves its own hole empty. Main thread, but
 * for currentSurface().
 */
final class FirstFrameDrawGate {
    /**
     * How long a window waits for the first frame before Android 14, the bound
     * Android 14 puts on the SurfaceSyncGroup a window waits for
     * (TRANSACTION_READY_TIMEOUT). Android 12L reports the draw through
     * ViewRootImpl.drawPending and Android 13 through a SurfaceSyncer, neither
     * of them bounded, so there a present that fails, or a canvas that never
     * presents, would otherwise hold the window's draw report for good.
     */
    static final long FIRST_FRAME_WAIT_MILLIS = 1000;

    /** The first release whose window applies a layer change with its next frame (AttachedSurfaceControl). */
    static final int LAYER_SHOWN_WITH_WINDOW_FRAME_FROM = Build.VERSION_CODES.S;

    /** What the gate asks of the view that owns it. */
    interface Host {
        /** Runs action on the main thread after delayMillis, unless removed. */
        void postDelayed(Runnable action, long delayMillis);

        /** Removes an action posted with postDelayed. */
        void removeCallbacks(Runnable action);

        /** hasFrame() changed: the view redraws, opening or closing its hole. */
        void invalidate();

        /**
         * Hides the surface's layer at once and, from
         * LAYER_SHOWN_WITH_WINDOW_FRAME_FROM, again with the window's next
         * frame; or shows it, with that frame from then and at once before
         * it. Returns whether the layer took the change: not where the
         * platform exposes no layer (before Android 10, or with no surface),
         * and not for a hide of a layer above the window, which has no hole.
         */
        boolean setLayerShown(boolean shown);
    }

    private final Host mHost;
    private final boolean mWindowWaitBoundedByPlatform;
    private final boolean mLayerShownWithWindowFrame;
    private final Runnable mFirstFrameDeadline = this::onFirstFrameDeadline;
    private boolean mFramePresented = false;
    private boolean mDeadlinePosted = false;
    private boolean mLayerHidden = false;
    // The surfaces released so far: written on the main thread, read on the
    // thread that presents (see currentSurface).
    private volatile int mSurfacesReleased = 0;
    private final ArrayList<Runnable> mPendingDrawsFinished = new ArrayList<>();

    FirstFrameDrawGate(Host host, int sdkInt) {
        mHost = host;
        mWindowWaitBoundedByPlatform = sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
        mLayerShownWithWindowFrame = sdkInt >= LAYER_SHOWN_WITH_WINDOW_FRAME_FROM;
    }

    /** Whether a frame has been presented into the current surface: the view opens its hole only then. */
    boolean hasFrame() {
        return mFramePresented;
    }

    /** Whether the layer is hidden until the hole opens. */
    boolean hidesLayer() {
        return mLayerHidden;
    }

    /** A new surface: nothing has been presented into it yet. */
    void onSurfaceCreated() {
        setFramePresented(false);
        mLayerHidden = mHost.setLayerShown(false);
    }

    /** The view draws itself into a window frame, which opens its hole once hasFrame(). */
    void onWindowFrameDrawn() {
        if (mFramePresented) {
            showLayer();
        }
    }

    /**
     * SurfaceView applied a z-order change, which from Android 14 shows a
     * layer below the window: one waiting for its hole is hidden again.
     */
    void onZOrderApplied() {
        if (mLayerHidden) {
            mLayerHidden = mHost.setLayerShown(false);
        }
    }

    /** The window asks to be told once the surface has drawn. */
    void onRedrawNeeded(Runnable drawingFinished) {
        if (mFramePresented) {
            drawingFinished.run();
            return;
        }
        mPendingDrawsFinished.add(drawingFinished);
        if (!mWindowWaitBoundedByPlatform && !mDeadlinePosted) {
            mDeadlinePosted = true;
            mHost.postDelayed(mFirstFrameDeadline, FIRST_FRAME_WAIT_MILLIS);
        }
    }

    /**
     * The surface a frame presented now goes into, to hand to
     * onFirstFramePresented. The thread that presented reads it while the
     * native side holds the window, and onSurfaceReleased counts a surface
     * only once the native side has let go of it, so a frame never names a
     * surface created after the one it went into. Any thread.
     */
    int currentSurface() {
        return mSurfacesReleased;
    }

    /** A first frame was presented into the surface currentSurface() named. */
    void onFirstFramePresented(int surface) {
        if (surface != mSurfacesReleased) {
            return;
        }
        if (mLayerShownWithWindowFrame) {
            showLayer();
        }
        setFramePresented(true);
        cancelDeadline();
        reportPendingDraws();
    }

    /**
     * The surface is gone, so it has nothing left to draw. releaseNative lets
     * the native side go of it, waiting for a present in flight, and the
     * surface is counted released only once it has returned (see
     * currentSurface).
     */
    void onSurfaceReleased(Runnable releaseNative) {
        releaseNative.run();
        mSurfacesReleased++;
        setFramePresented(false);
        cancelDeadline();
        reportPendingDraws();
    }

    /** The view is leaving its window, which must hear about every draw it waits for. */
    void onDetachedFromWindow() {
        cancelDeadline();
        reportPendingDraws();
    }

    private void onFirstFrameDeadline() {
        mDeadlinePosted = false;
        reportPendingDraws();
    }

    private void showLayer() {
        if (mLayerHidden) {
            mLayerHidden = false;
            mHost.setLayerShown(true);
        }
    }

    private void setFramePresented(boolean framePresented) {
        if (mFramePresented != framePresented) {
            mFramePresented = framePresented;
            mHost.invalidate();
        }
    }

    private void cancelDeadline() {
        if (mDeadlinePosted) {
            mDeadlinePosted = false;
            mHost.removeCallbacks(mFirstFrameDeadline);
        }
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
