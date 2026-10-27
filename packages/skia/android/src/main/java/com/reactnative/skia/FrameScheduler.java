package com.reactnative.skia;

import androidx.annotation.Nullable;

/**
 * Decides when a SkiaView presents the recordings queued for it. A SurfaceView
 * presents on the next vsync, when the compositor takes its buffer. A
 * TextureView shows a frame when its window draws, which takes the latest
 * frame presented and lets the others go: it presents one frame at a time,
 * the next once the window drew the previous one (see onFrameConsumed), so
 * that every draw gets a new frame and no frame is made for nothing; a frame
 * for a window resized since the one it shows goes on the spot in either case
 * (see requestFrame). While a replay is in flight it asks for every vsync,
 * and it tells the view how many vsyncs asked for each present, each a tick
 * that paces the replays (see RNSkView::presentFrame). Keeps at most one frame
 * callback outstanding. Lives on the present thread, whose Choreographer gives
 * the vsync.
 */
final class FrameScheduler {
    /** Host.presentFrame: a frame went to the window. */
    static final int PRESENTED = 1;
    /** Host.presentFrame: a frame is left, more queued, held for the GPU or declined (no drawable). */
    static final int MORE_QUEUED = 2;
    /** Host.presentFrame and Host.tick: a replay is in flight, its frame coming. */
    static final int REPLAY_COMING = 4;

    /** What the scheduler asks of the view. */
    interface Host {
        /** Calls onFrame() on the next Choreographer frame. */
        void postFrameCallback();

        /**
         * Presents what is waiting, ticks being how many vsyncs asked for this
         * present: none for the one a submitted recording or the window's draw
         * asked for. PRESENTED, MORE_QUEUED and REPLAY_COMING, as they apply.
         */
        int presentFrame(int ticks);

        /**
         * A vsync passed while the window has yet to draw the frame it was
         * given: counts the tick and presents nothing. REPLAY_COMING while a
         * replay is in flight.
         */
        int tick();
    }

    private final Host mHost;
    private boolean mFrameCallbackPosted = false;
    // Whether the frame callback outstanding retries a frame that is left,
    // rather than only ticking for a replay that is coming.
    private boolean mRetryPosted = false;
    // TextureView: the window has a frame it has not drawn yet, and a request
    // arrived while it had.
    private boolean mFrameInWindow = false;
    private boolean mRequested = false;

    FrameScheduler(Host host) {
        mHost = host;
    }

    /** A recording was submitted. Without a backing view there is no surface to present on yet. */
    void requestFrame(@Nullable BackingViewKind kind) {
        requestFrame(kind, false);
    }

    /**
     * A recording was submitted. With forResizedWindow, the window was resized since
     * the frame it shows was made, so it shows that frame stretched to its size, and
     * the recording is for its size: it is presented on the spot, ahead of the vsync
     * or the draw it would otherwise wait for (a TextureView's window takes it in
     * place of the frame it has not drawn yet), so that a window resized on every
     * frame is drawn at the right size on every frame.
     */
    void requestFrame(@Nullable BackingViewKind kind, boolean forResizedWindow) {
        if (kind == null) {
            return;
        }
        if (forResizedWindow) {
            present(kind, 0);
            return;
        }
        // A retry still outstanding presents the new recording along with the
        // leftovers on that vsync.
        if (mRetryPosted) {
            return;
        }
        switch (kind) {
            case SURFACE_VIEW -> postFrameCallback();
            case TEXTURE_VIEW -> {
                if (mFrameInWindow) {
                    mRequested = true;
                } else {
                    present(kind, 0);
                }
            }
            // A static canvas has no frame scheduler: its frames are presented
            // on the worker as they are queued (see RNSkView::presentAsImages).
            case IMAGE -> {
            }
        }
    }

    /** The Choreographer frame asked for with Host.postFrameCallback(). */
    void onFrame(@Nullable BackingViewKind kind) {
        mFrameCallbackPosted = false;
        mRetryPosted = false;
        if (kind == null) {
            return;
        }
        if (kind == BackingViewKind.TEXTURE_VIEW && mFrameInWindow) {
            if ((mHost.tick() & REPLAY_COMING) != 0) {
                postFrameCallback();
            }
            return;
        }
        present(kind, 1);
    }

    /**
     * The TextureView drew the frame it was given (or a later one, presented for a
     * resized window): the recording waiting for that, if any, goes to it now.
     */
    void onFrameConsumed(@Nullable BackingViewKind kind) {
        mFrameInWindow = false;
        if (kind == null || !mRequested) {
            return;
        }
        present(kind, 0);
    }

    /** The view left its window, or the window lost its surface: the host removed the callbacks. */
    void cancel() {
        mFrameCallbackPosted = false;
        mRetryPosted = false;
        mFrameInWindow = false;
        mRequested = false;
    }

    // A frame left over is never presented straight away: a TextureView's
    // waits for the window to draw the frame it has; any other (a
    // SurfaceView's, or one the window could not take or holds for the GPU)
    // is tried again on the next vsync. A replay coming keeps the vsync asked
    // for, its tick.
    private void present(BackingViewKind kind, int ticks) {
        mRequested = false;
        final int result = mHost.presentFrame(ticks);
        if ((result & PRESENTED) != 0 && kind == BackingViewKind.TEXTURE_VIEW) {
            mFrameInWindow = true;
        }
        if ((result & MORE_QUEUED) != 0) {
            if (kind == BackingViewKind.TEXTURE_VIEW && mFrameInWindow) {
                mRequested = true;
            } else {
                mRetryPosted = true;
                postFrameCallback();
            }
        }
        if ((result & REPLAY_COMING) != 0) {
            postFrameCallback();
        }
    }

    private void postFrameCallback() {
        if (!mFrameCallbackPosted) {
            mFrameCallbackPosted = true;
            mHost.postFrameCallback();
        }
    }
}
