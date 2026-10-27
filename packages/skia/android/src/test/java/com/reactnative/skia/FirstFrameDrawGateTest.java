package com.reactnative.skia;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Build;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class FirstFrameDrawGateTest {
    private static final class RecordingHost implements FirstFrameDrawGate.Host {
        final List<Runnable> posted = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        int invalidations = 0;
        boolean layerAboveWindow = false;

        @Override
        public void postDelayed(Runnable action, long delayMillis) {
            posted.add(action);
            delays.add(delayMillis);
        }

        @Override
        public void removeCallbacks(Runnable action) {
            posted.remove(action);
        }

        @Override
        public void invalidate() {
            invalidations++;
            events.add("invalidate");
        }

        @Override
        public boolean setLayerShown(boolean shown) {
            events.add(shown ? "show layer" : "hide layer");
            return !layerAboveWindow;
        }

        List<String> layerChanges() {
            List<String> changes = new ArrayList<>(events);
            changes.removeIf("invalidate"::equals);
            return changes;
        }

        void elapse() {
            List<Runnable> due = new ArrayList<>(posted);
            posted.clear();
            for (Runnable action : due) {
                action.run();
            }
        }
    }

    private static final Runnable NO_NATIVE_WORK = () -> {};

    private final List<String> mReported = new ArrayList<>();
    private final RecordingHost mHost = new RecordingHost();
    private final FirstFrameDrawGate mGate = new FirstFrameDrawGate(mHost, Build.VERSION_CODES.TIRAMISU);

    private Runnable draw(String name) {
        return () -> mReported.add(name);
    }

    @Test
    public void drawRequestedBeforeTheFirstFrameIsReportedWithIt() {
        mGate.onSurfaceCreated();
        mGate.onRedrawNeeded(draw("first"));
        assertEquals(Arrays.asList(), mReported);

        mGate.onFirstFramePresented(mGate.currentSurface());
        assertEquals(Arrays.asList("first"), mReported);
    }

    @Test
    public void drawRequestedAfterTheFirstFrameIsReportedAtOnce() {
        mGate.onSurfaceCreated();
        mGate.onFirstFramePresented(mGate.currentSurface());
        mGate.onRedrawNeeded(draw("resize"));
        assertEquals(Arrays.asList("resize"), mReported);
        assertEquals(0, mHost.posted.size());
    }

    @Test
    public void newSurfaceWaitsForItsOwnFirstFrame() {
        mGate.onSurfaceCreated();
        mGate.onFirstFramePresented(mGate.currentSurface());
        mGate.onSurfaceReleased(NO_NATIVE_WORK);
        mGate.onSurfaceCreated();
        mGate.onRedrawNeeded(draw("second surface"));
        assertEquals(Arrays.asList(), mReported);

        mGate.onFirstFramePresented(mGate.currentSurface());
        assertEquals(Arrays.asList("second surface"), mReported);
    }

    @Test
    public void aFirstFrameOfAReleasedSurfaceLeavesTheNextSurfaceWaiting() {
        mGate.onSurfaceCreated();
        int released = mGate.currentSurface();
        mGate.onSurfaceReleased(NO_NATIVE_WORK);
        mGate.onSurfaceCreated();
        mGate.onRedrawNeeded(draw("second surface"));

        mGate.onFirstFramePresented(released);
        assertEquals(Arrays.asList(), mReported);
        assertFalse(mGate.hasFrame());
    }

    @Test
    public void surfaceReleasedBeforeItsFirstFrameReportsItsPendingDraws() {
        mGate.onSurfaceCreated();
        mGate.onRedrawNeeded(draw("a"));
        mGate.onRedrawNeeded(draw("b"));
        mGate.onSurfaceReleased(NO_NATIVE_WORK);
        assertEquals(Arrays.asList("a", "b"), mReported);
        assertEquals(0, mHost.posted.size());
    }

    @Test
    public void detachingReportsPendingDrawsOnce() {
        mGate.onSurfaceCreated();
        mGate.onRedrawNeeded(draw("held"));
        mGate.onDetachedFromWindow();
        assertEquals(Arrays.asList("held"), mReported);

        mGate.onSurfaceReleased(NO_NATIVE_WORK);
        mGate.onFirstFramePresented(mGate.currentSurface());
        mHost.elapse();
        assertEquals(Arrays.asList("held"), mReported);
    }

    @Test
    public void eachDrawIsReportedOnce() {
        mGate.onSurfaceCreated();
        mGate.onRedrawNeeded(draw("once"));
        mGate.onFirstFramePresented(mGate.currentSurface());
        mGate.onFirstFramePresented(mGate.currentSurface());
        mGate.onSurfaceReleased(NO_NATIVE_WORK);
        mHost.elapse();
        assertEquals(Arrays.asList("once"), mReported);
    }

    @Test
    public void aFirstFrameThatNeverComesIsWaitedForOnlyUntilTheDeadline() {
        mGate.onSurfaceCreated();
        mGate.onRedrawNeeded(draw("held"));
        assertEquals(Arrays.asList(Long.valueOf(FirstFrameDrawGate.FIRST_FRAME_WAIT_MILLIS)), mHost.delays);

        mHost.elapse();
        assertEquals(Arrays.asList("held"), mReported);
    }

    @Test
    public void oneDeadlineCoversEveryDrawWaitingForTheSameFrame() {
        mGate.onSurfaceCreated();
        mGate.onRedrawNeeded(draw("a"));
        mGate.onRedrawNeeded(draw("b"));
        assertEquals(1, mHost.posted.size());

        mHost.elapse();
        assertEquals(Arrays.asList("a", "b"), mReported);
    }

    @Test
    public void theFirstFrameCancelsTheDeadline() {
        mGate.onSurfaceCreated();
        mGate.onRedrawNeeded(draw("framed"));
        mGate.onFirstFramePresented(mGate.currentSurface());
        assertEquals(0, mHost.posted.size());
        assertEquals(Arrays.asList("framed"), mReported);
    }

    @Test
    public void detachingCancelsTheDeadline() {
        mGate.onSurfaceCreated();
        mGate.onRedrawNeeded(draw("held"));
        mGate.onDetachedFromWindow();
        assertEquals(0, mHost.posted.size());
    }

    @Test
    public void fromAndroid14ADrawWaitsForTheFirstFrameWithNoDeadline() {
        FirstFrameDrawGate gate = new FirstFrameDrawGate(mHost, Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
        gate.onSurfaceCreated();
        gate.onRedrawNeeded(draw("held"));
        assertEquals(0, mHost.posted.size());

        mHost.elapse();
        assertEquals(Arrays.asList(), mReported);

        gate.onFirstFramePresented(gate.currentSurface());
        assertEquals(Arrays.asList("held"), mReported);
    }

    @Test
    public void aDrawAfterTheDeadlineWaitsForTheFirstFrameAgain() {
        mGate.onSurfaceCreated();
        mGate.onRedrawNeeded(draw("first"));
        mHost.elapse();
        mGate.onRedrawNeeded(draw("resize"));
        assertEquals(Arrays.asList("first"), mReported);
        assertEquals(1, mHost.posted.size());

        mGate.onFirstFramePresented(mGate.currentSurface());
        assertEquals(Arrays.asList("first", "resize"), mReported);
    }

    @Test
    public void theHoleOpensWithTheFirstFrame() {
        mGate.onSurfaceCreated();
        assertFalse(mGate.hasFrame());

        mGate.onFirstFramePresented(mGate.currentSurface());
        mGate.onFirstFramePresented(mGate.currentSurface());
        assertTrue(mGate.hasFrame());
        assertEquals(1, mHost.invalidations);
    }

    @Test
    public void aFirstFrameLaterThanTheDeadlineKeepsTheHoleClosedUntilItLands() {
        mGate.onSurfaceCreated();
        mGate.onRedrawNeeded(draw("held"));
        mHost.elapse();
        assertEquals(Arrays.asList("held"), mReported);
        assertFalse(mGate.hasFrame());
        assertEquals(0, mHost.invalidations);

        mGate.onFirstFramePresented(mGate.currentSurface());
        assertTrue(mGate.hasFrame());
        assertEquals(1, mHost.invalidations);
    }

    @Test
    public void aNewSurfaceKeepsTheHoleClosedUntilItsOwnFirstFrame() {
        mGate.onSurfaceCreated();
        mGate.onFirstFramePresented(mGate.currentSurface());
        mGate.onSurfaceReleased(NO_NATIVE_WORK);
        assertFalse(mGate.hasFrame());

        mGate.onSurfaceCreated();
        assertFalse(mGate.hasFrame());

        mGate.onFirstFramePresented(mGate.currentSurface());
        assertTrue(mGate.hasFrame());
        assertEquals(3, mHost.invalidations);
    }

    @Test
    public void fromAndroid12TheLayerShowsWithTheWindowFrameThatOpensTheHole() {
        FirstFrameDrawGate gate = new FirstFrameDrawGate(mHost, Build.VERSION_CODES.S);
        gate.onSurfaceCreated();
        assertEquals(Arrays.asList("hide layer"), mHost.events);

        gate.onFirstFramePresented(gate.currentSurface());
        assertEquals(Arrays.asList("hide layer", "show layer", "invalidate"), mHost.events);

        gate.onWindowFrameDrawn();
        assertEquals(Arrays.asList("hide layer", "show layer"), mHost.layerChanges());
    }

    @Test
    public void beforeAndroid12TheLayerShowsAsTheWindowFrameThatOpensTheHoleIsDrawn() {
        FirstFrameDrawGate gate = new FirstFrameDrawGate(mHost, Build.VERSION_CODES.R);
        gate.onSurfaceCreated();
        gate.onWindowFrameDrawn();
        gate.onFirstFramePresented(gate.currentSurface());
        assertEquals(Arrays.asList("hide layer", "invalidate"), mHost.events);

        gate.onWindowFrameDrawn();
        assertEquals(Arrays.asList("hide layer", "invalidate", "show layer"), mHost.events);
    }

    @Test
    public void aLayerThatStaysHiddenThroughTheDeadlineShowsWithTheFirstFrame() {
        mGate.onSurfaceCreated();
        mGate.onRedrawNeeded(draw("held"));
        mHost.elapse();
        assertEquals(Arrays.asList("hide layer"), mHost.layerChanges());

        mGate.onFirstFramePresented(mGate.currentSurface());
        assertEquals(Arrays.asList("hide layer", "show layer"), mHost.layerChanges());
    }

    @Test
    public void aFirstFrameOfAReleasedSurfaceLeavesTheNextLayerHidden() {
        mGate.onSurfaceCreated();
        int released = mGate.currentSurface();
        mGate.onSurfaceReleased(NO_NATIVE_WORK);
        mGate.onSurfaceCreated();

        mGate.onFirstFramePresented(released);
        mGate.onWindowFrameDrawn();
        assertEquals(Arrays.asList("hide layer", "hide layer"), mHost.layerChanges());
    }

    @Test
    public void eachSurfaceHidesItsLayerUntilItsOwnFirstFrame() {
        mGate.onSurfaceCreated();
        mGate.onFirstFramePresented(mGate.currentSurface());
        mGate.onSurfaceReleased(NO_NATIVE_WORK);
        mGate.onSurfaceCreated();
        assertEquals(Arrays.asList("hide layer", "show layer", "hide layer"), mHost.layerChanges());

        mGate.onFirstFramePresented(mGate.currentSurface());
        assertEquals(Arrays.asList("hide layer", "show layer", "hide layer", "show layer"),
                mHost.layerChanges());
    }

    @Test
    public void theLayerIsShownOnce() {
        mGate.onSurfaceCreated();
        mGate.onFirstFramePresented(mGate.currentSurface());
        mGate.onWindowFrameDrawn();
        mGate.onFirstFramePresented(mGate.currentSurface());
        mGate.onWindowFrameDrawn();
        assertEquals(Arrays.asList("hide layer", "show layer"), mHost.layerChanges());
    }

    @Test
    public void aLayerTheViewDidNotHideIsNeverShown() {
        mHost.layerAboveWindow = true;
        mGate.onSurfaceCreated();
        mGate.onFirstFramePresented(mGate.currentSurface());
        mGate.onWindowFrameDrawn();
        assertEquals(Arrays.asList("hide layer"), mHost.layerChanges());
    }

    @Test
    public void aSurfaceCountsAsReleasedOnlyOnceTheNativeSideLetGoOfIt() {
        mGate.onSurfaceCreated();
        int created = mGate.currentSurface();
        int[] seenWhileReleasing = new int[1];

        mGate.onSurfaceReleased(() -> seenWhileReleasing[0] = mGate.currentSurface());

        assertEquals(created, seenWhileReleasing[0]);
        assertEquals(created + 1, mGate.currentSurface());
    }

    @Test
    public void aZOrderChangeBeforeTheFirstFrameHidesTheLayerAgain() {
        mGate.onSurfaceCreated();
        mGate.onZOrderApplied();
        assertEquals(Arrays.asList("hide layer", "hide layer"), mHost.layerChanges());

        mGate.onFirstFramePresented(mGate.currentSurface());
        assertEquals(Arrays.asList("hide layer", "hide layer", "show layer"), mHost.layerChanges());
    }

    @Test
    public void aZOrderChangeOnceTheHoleOpenedLeavesTheLayerShown() {
        mGate.onSurfaceCreated();
        mGate.onFirstFramePresented(mGate.currentSurface());
        mGate.onZOrderApplied();
        assertEquals(Arrays.asList("hide layer", "show layer"), mHost.layerChanges());
    }

    @Test
    public void aLayerMovedAboveTheWindowBeforeItsFirstFrameIsLeftToTheWindow() {
        mGate.onSurfaceCreated();
        mHost.layerAboveWindow = true;
        mGate.onZOrderApplied();
        mGate.onFirstFramePresented(mGate.currentSurface());
        mGate.onWindowFrameDrawn();
        assertEquals(Arrays.asList("hide layer", "hide layer"), mHost.layerChanges());
    }
}
