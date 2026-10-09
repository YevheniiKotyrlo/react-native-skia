package com.reactnative.skia;

import androidx.annotation.Nullable;

/**
 * Keeps the view a SkiaView replaced on screen until the new one shows the
 * canvas: the outgoing view stays until the new view has shown a frame and a
 * window frame drawn after that has committed, so the new view is on screen
 * before the outgoing one leaves. A frame that does not come within
 * FirstFrameDrawGate's wait is not waited for, as the window does not wait for
 * it either. Another swap, or the SkiaView leaving its window, removes the view
 * at once. Main thread.
 */
final class OutgoingViewHold<V> {
    /** What the hold asks of the view that owns it. */
    interface Host<V> {
        /** Removes a held view from the hierarchy. */
        void removeView(V view);

        /** Runs action once a window frame drawn after this call has committed. */
        void runAfterWindowFrameCommits(Runnable action);

        /** Runs action on the main thread after delayMillis, unless removed. */
        void postDelayed(Runnable action, long delayMillis);

        /** Removes an action posted with postDelayed. */
        void removeCallbacks(Runnable action);
    }

    private final Host<V> mHost;
    private final Runnable mFirstFrameDeadline = this::release;
    @Nullable
    private V mHeld;
    private boolean mReleaseScheduled = false;

    OutgoingViewHold(Host<V> host) {
        mHost = host;
    }

    /** The held view, laid out with the canvas while it stays; null when none is held. */
    @Nullable
    V held() {
        return mHeld;
    }

    /** Holds view, removing the one held before it. */
    void hold(V view) {
        release();
        mHeld = view;
        mHost.postDelayed(mFirstFrameDeadline, FirstFrameDrawGate.FIRST_FRAME_WAIT_MILLIS);
    }

    /** Removes the held view now. */
    void release() {
        if (mHeld == null) {
            return;
        }
        V view = mHeld;
        mHeld = null;
        mReleaseScheduled = false;
        mHost.removeCallbacks(mFirstFrameDeadline);
        mHost.removeView(view);
    }

    /** The new view showed a frame. Reported again with every later frame, which changes nothing. */
    void onFirstFramePresented() {
        if (mHeld == null || mReleaseScheduled) {
            return;
        }
        mReleaseScheduled = true;
        mHost.removeCallbacks(mFirstFrameDeadline);
        V view = mHeld;
        mHost.runAfterWindowFrameCommits(() -> {
            if (mHeld == view) {
                release();
            }
        });
    }
}
