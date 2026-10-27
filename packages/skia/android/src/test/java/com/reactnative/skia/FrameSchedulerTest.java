package com.reactnative.skia;

import static org.junit.Assert.assertEquals;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import org.junit.Test;

public class FrameSchedulerTest {
    private enum Call {
        POST_FRAME_CALLBACK,
        PRESENT_ON_REQUEST,
        PRESENT_ON_VSYNC,
        TICK
    }

    private static final class RecordingHost implements FrameScheduler.Host {
        final List<Call> calls = new ArrayList<>();
        // What the next presents report; PRESENTED once these run out.
        final Deque<Integer> results = new ArrayDeque<>();
        // What the next ticks report; nothing once these run out.
        final Deque<Integer> tickResults = new ArrayDeque<>();

        @Override
        public void postFrameCallback() {
            calls.add(Call.POST_FRAME_CALLBACK);
        }

        @Override
        public int presentFrame(int ticks) {
            calls.add(switch (ticks) {
                case 0 -> Call.PRESENT_ON_REQUEST;
                case 1 -> Call.PRESENT_ON_VSYNC;
                default -> throw new AssertionError("presented for " + ticks + " ticks");
            });
            return results.isEmpty() ? FrameScheduler.PRESENTED : results.removeFirst();
        }

        @Override
        public int tick() {
            calls.add(Call.TICK);
            return tickResults.isEmpty() ? 0 : tickResults.removeFirst();
        }

        List<Call> takeCalls() {
            List<Call> taken = new ArrayList<>(calls);
            calls.clear();
            return taken;
        }
    }

    private static final int PRESENTED = FrameScheduler.PRESENTED;
    private static final int MORE_QUEUED = FrameScheduler.MORE_QUEUED;
    private static final int REPLAY_COMING = FrameScheduler.REPLAY_COMING;

    private final RecordingHost mHost = new RecordingHost();
    private final FrameScheduler mScheduler = new FrameScheduler(mHost);

    @Test
    public void surfaceViewFrameIsPresentedOnTheNextVsync() {
        mScheduler.requestFrame(BackingViewKind.SURFACE_VIEW);
        assertEquals(Arrays.asList(Call.POST_FRAME_CALLBACK), mHost.takeCalls());

        mScheduler.onFrame(BackingViewKind.SURFACE_VIEW);
        assertEquals(Arrays.asList(Call.PRESENT_ON_VSYNC), mHost.takeCalls());
    }

    @Test
    public void textureViewFrameIsPresentedOnTheSpotWhileTheWindowHasNone() {
        mScheduler.requestFrame(BackingViewKind.TEXTURE_VIEW);
        assertEquals(Arrays.asList(Call.PRESENT_ON_REQUEST), mHost.takeCalls());
    }

    @Test
    public void textureViewNextFrameWaitsForTheWindowToDrawThePrevious() {
        mScheduler.requestFrame(BackingViewKind.TEXTURE_VIEW);
        mScheduler.requestFrame(BackingViewKind.TEXTURE_VIEW);
        assertEquals(Arrays.asList(Call.PRESENT_ON_REQUEST), mHost.takeCalls());

        mScheduler.onFrameConsumed(BackingViewKind.TEXTURE_VIEW);
        assertEquals(Arrays.asList(Call.PRESENT_ON_REQUEST), mHost.takeCalls());

        // Nothing was requested since: the window's next draw presents nothing.
        mScheduler.onFrameConsumed(BackingViewKind.TEXTURE_VIEW);
        assertEquals(Arrays.asList(), mHost.takeCalls());
    }

    @Test
    public void textureViewLeftoverWaitsForTheWindowToDraw() {
        mHost.results.add(PRESENTED | MORE_QUEUED);
        mScheduler.requestFrame(BackingViewKind.TEXTURE_VIEW);
        assertEquals(Arrays.asList(Call.PRESENT_ON_REQUEST), mHost.takeCalls());

        mScheduler.onFrameConsumed(BackingViewKind.TEXTURE_VIEW);
        assertEquals(Arrays.asList(Call.PRESENT_ON_REQUEST), mHost.takeCalls());
    }

    @Test
    public void textureViewFrameTheWindowCouldNotTakeIsTriedOnTheNextVsync() {
        mHost.results.add(MORE_QUEUED);
        mScheduler.requestFrame(BackingViewKind.TEXTURE_VIEW);
        assertEquals(Arrays.asList(Call.PRESENT_ON_REQUEST, Call.POST_FRAME_CALLBACK), mHost.takeCalls());

        // Requests meanwhile wait for that vsync.
        mScheduler.requestFrame(BackingViewKind.TEXTURE_VIEW);
        assertEquals(Arrays.asList(), mHost.takeCalls());

        mScheduler.onFrame(BackingViewKind.TEXTURE_VIEW);
        assertEquals(Arrays.asList(Call.PRESENT_ON_VSYNC), mHost.takeCalls());
    }

    @Test
    public void textureViewFrameHeldForTheGpuIsNotInTheWindow() {
        mHost.results.add(MORE_QUEUED);
        mScheduler.requestFrame(BackingViewKind.TEXTURE_VIEW);
        mScheduler.onFrame(BackingViewKind.TEXTURE_VIEW);
        assertEquals(
                Arrays.asList(Call.PRESENT_ON_REQUEST, Call.POST_FRAME_CALLBACK, Call.PRESENT_ON_VSYNC),
                mHost.takeCalls());

        // The held frame went to the window at that vsync: the next waits for its draw.
        mScheduler.requestFrame(BackingViewKind.TEXTURE_VIEW);
        assertEquals(Arrays.asList(), mHost.takeCalls());
        mScheduler.onFrameConsumed(BackingViewKind.TEXTURE_VIEW);
        assertEquals(Arrays.asList(Call.PRESENT_ON_REQUEST), mHost.takeCalls());
    }

    @Test
    public void surfaceViewLeftoverIsPresentedOnTheNextVsync() {
        mHost.results.add(PRESENTED | MORE_QUEUED);
        mScheduler.requestFrame(BackingViewKind.SURFACE_VIEW);
        mScheduler.onFrame(BackingViewKind.SURFACE_VIEW);
        assertEquals(
                Arrays.asList(Call.POST_FRAME_CALLBACK, Call.PRESENT_ON_VSYNC, Call.POST_FRAME_CALLBACK),
                mHost.takeCalls());

        mScheduler.onFrame(BackingViewKind.SURFACE_VIEW);
        assertEquals(Arrays.asList(Call.PRESENT_ON_VSYNC), mHost.takeCalls());
    }

    @Test
    public void aReplayComingKeepsTheVsyncTicking() {
        mHost.results.add(REPLAY_COMING);
        mHost.results.add(REPLAY_COMING);
        mScheduler.requestFrame(BackingViewKind.SURFACE_VIEW);
        mScheduler.onFrame(BackingViewKind.SURFACE_VIEW);
        mScheduler.onFrame(BackingViewKind.SURFACE_VIEW);
        mScheduler.onFrame(BackingViewKind.SURFACE_VIEW);
        assertEquals(
                Arrays.asList(
                        Call.POST_FRAME_CALLBACK,
                        Call.PRESENT_ON_VSYNC,
                        Call.POST_FRAME_CALLBACK,
                        Call.PRESENT_ON_VSYNC,
                        Call.POST_FRAME_CALLBACK,
                        Call.PRESENT_ON_VSYNC),
                mHost.takeCalls());
    }

    @Test
    public void textureViewVsyncOnlyTicksWhileItsWindowHasAFrameToDraw() {
        mHost.results.add(PRESENTED | REPLAY_COMING);
        mHost.tickResults.add(REPLAY_COMING);
        mScheduler.requestFrame(BackingViewKind.TEXTURE_VIEW);
        mScheduler.onFrame(BackingViewKind.TEXTURE_VIEW);
        assertEquals(
                Arrays.asList(Call.PRESENT_ON_REQUEST, Call.POST_FRAME_CALLBACK, Call.TICK, Call.POST_FRAME_CALLBACK),
                mHost.takeCalls());

        mScheduler.onFrameConsumed(BackingViewKind.TEXTURE_VIEW);
        assertEquals(Arrays.asList(), mHost.takeCalls());

        mScheduler.onFrame(BackingViewKind.TEXTURE_VIEW);
        assertEquals(Arrays.asList(Call.PRESENT_ON_VSYNC), mHost.takeCalls());
    }

    @Test
    public void textureViewTickWithoutAReplayComingAsksForNoMoreVsyncs() {
        mHost.results.add(PRESENTED | REPLAY_COMING);
        mScheduler.requestFrame(BackingViewKind.TEXTURE_VIEW);
        mScheduler.onFrame(BackingViewKind.TEXTURE_VIEW);
        assertEquals(
                Arrays.asList(Call.PRESENT_ON_REQUEST, Call.POST_FRAME_CALLBACK, Call.TICK),
                mHost.takeCalls());
    }

    @Test
    public void textureViewRequestWhileTheVsyncOnlyTicksIsPresentedOnTheSpot() {
        mHost.results.add(REPLAY_COMING);
        mScheduler.requestFrame(BackingViewKind.TEXTURE_VIEW);
        assertEquals(Arrays.asList(Call.PRESENT_ON_REQUEST, Call.POST_FRAME_CALLBACK), mHost.takeCalls());

        mScheduler.requestFrame(BackingViewKind.TEXTURE_VIEW);
        assertEquals(Arrays.asList(Call.PRESENT_ON_REQUEST), mHost.takeCalls());

        // The window has that frame now: the vsync only ticks.
        mScheduler.onFrame(BackingViewKind.TEXTURE_VIEW);
        assertEquals(Arrays.asList(Call.TICK), mHost.takeCalls());
    }

    @Test
    public void textureViewLeftoverWithAReplayComingTicksUntilTheWindowDraws() {
        mHost.results.add(PRESENTED | MORE_QUEUED | REPLAY_COMING);
        mHost.tickResults.add(REPLAY_COMING);
        mScheduler.requestFrame(BackingViewKind.TEXTURE_VIEW);
        mScheduler.onFrame(BackingViewKind.TEXTURE_VIEW);
        assertEquals(
                Arrays.asList(Call.PRESENT_ON_REQUEST, Call.POST_FRAME_CALLBACK, Call.TICK, Call.POST_FRAME_CALLBACK),
                mHost.takeCalls());

        mScheduler.onFrameConsumed(BackingViewKind.TEXTURE_VIEW);
        assertEquals(Arrays.asList(Call.PRESENT_ON_REQUEST), mHost.takeCalls());
    }

    @Test
    public void frameForAResizedWindowIsPresentedOnTheSpot() {
        mScheduler.requestFrame(BackingViewKind.SURFACE_VIEW, true);
        assertEquals(Arrays.asList(Call.PRESENT_ON_REQUEST), mHost.takeCalls());

        // Also while the TextureView's window has a frame it has not drawn.
        mScheduler.requestFrame(BackingViewKind.TEXTURE_VIEW);
        mScheduler.requestFrame(BackingViewKind.TEXTURE_VIEW, true);
        assertEquals(Arrays.asList(Call.PRESENT_ON_REQUEST, Call.PRESENT_ON_REQUEST), mHost.takeCalls());
    }

    @Test
    public void leftoverOfAFrameForAResizedWindowWaitsLikeAnyOther() {
        mHost.results.add(PRESENTED | MORE_QUEUED);
        mScheduler.requestFrame(BackingViewKind.SURFACE_VIEW, true);
        assertEquals(Arrays.asList(Call.PRESENT_ON_REQUEST, Call.POST_FRAME_CALLBACK), mHost.takeCalls());

        mHost.results.add(PRESENTED | MORE_QUEUED);
        mScheduler.requestFrame(BackingViewKind.TEXTURE_VIEW, true);
        assertEquals(Arrays.asList(Call.PRESENT_ON_REQUEST), mHost.takeCalls());
        mScheduler.onFrameConsumed(BackingViewKind.TEXTURE_VIEW);
        assertEquals(Arrays.asList(Call.PRESENT_ON_REQUEST), mHost.takeCalls());
    }

    @Test
    public void requestsWaitingForTheSameFrameArePostedOnce() {
        mScheduler.requestFrame(BackingViewKind.SURFACE_VIEW);
        mScheduler.requestFrame(BackingViewKind.SURFACE_VIEW);
        mScheduler.onFrame(BackingViewKind.SURFACE_VIEW);
        assertEquals(Arrays.asList(Call.POST_FRAME_CALLBACK, Call.PRESENT_ON_VSYNC), mHost.takeCalls());
    }

    @Test
    public void requestAfterTheFrameRanIsPostedAgain() {
        mScheduler.requestFrame(BackingViewKind.SURFACE_VIEW);
        mScheduler.onFrame(BackingViewKind.SURFACE_VIEW);
        mScheduler.requestFrame(BackingViewKind.SURFACE_VIEW);
        mScheduler.onFrame(BackingViewKind.SURFACE_VIEW);
        assertEquals(
                Arrays.asList(
                        Call.POST_FRAME_CALLBACK,
                        Call.PRESENT_ON_VSYNC,
                        Call.POST_FRAME_CALLBACK,
                        Call.PRESENT_ON_VSYNC),
                mHost.takeCalls());
    }

    @Test
    public void withoutABackingViewNothingIsPostedOrPresented() {
        mScheduler.requestFrame(null);
        mScheduler.requestFrame(null, true);
        mScheduler.onFrame(null);
        mScheduler.onFrameConsumed(null);
        assertEquals(Arrays.asList(), mHost.takeCalls());
    }

    @Test
    public void aRetryEndsWithItsVsyncEvenWithoutABackingView() {
        mHost.results.add(MORE_QUEUED);
        mScheduler.requestFrame(BackingViewKind.TEXTURE_VIEW);
        mScheduler.onFrame(null);
        mHost.takeCalls();

        mScheduler.requestFrame(BackingViewKind.TEXTURE_VIEW);
        assertEquals(Arrays.asList(Call.PRESENT_ON_REQUEST), mHost.takeCalls());
    }

    @Test
    public void cancelDropsOutstandingRequestsAndTheFrameInTheWindow() {
        mScheduler.requestFrame(BackingViewKind.SURFACE_VIEW);
        mScheduler.cancel();
        mScheduler.requestFrame(BackingViewKind.SURFACE_VIEW);
        assertEquals(
                Arrays.asList(Call.POST_FRAME_CALLBACK, Call.POST_FRAME_CALLBACK),
                mHost.takeCalls());

        mScheduler.cancel();
        mScheduler.requestFrame(BackingViewKind.TEXTURE_VIEW);
        mScheduler.requestFrame(BackingViewKind.TEXTURE_VIEW);
        mScheduler.cancel();
        mScheduler.requestFrame(BackingViewKind.TEXTURE_VIEW);
        assertEquals(Arrays.asList(Call.PRESENT_ON_REQUEST, Call.PRESENT_ON_REQUEST), mHost.takeCalls());
    }

    @Test
    public void cancelDropsAnOutstandingRetry() {
        mHost.results.add(MORE_QUEUED);
        mScheduler.requestFrame(BackingViewKind.TEXTURE_VIEW);
        mScheduler.cancel();
        mHost.takeCalls();

        mScheduler.requestFrame(BackingViewKind.TEXTURE_VIEW);
        assertEquals(Arrays.asList(Call.PRESENT_ON_REQUEST), mHost.takeCalls());
    }
}
