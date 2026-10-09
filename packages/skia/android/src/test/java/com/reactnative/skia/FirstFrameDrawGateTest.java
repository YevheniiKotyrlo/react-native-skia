package com.reactnative.skia;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class FirstFrameDrawGateTest {
    private final List<String> mReported = new ArrayList<>();
    private final FirstFrameDrawGate mGate = new FirstFrameDrawGate();

    private Runnable draw(String name) {
        return () -> mReported.add(name);
    }

    @Test
    public void drawRequestedBeforeTheFirstFrameIsReportedWithIt() {
        mGate.onSurfaceCreated();
        mGate.onRedrawNeeded(draw("first"));
        assertEquals(Arrays.asList(), mReported);

        mGate.onFirstFramePresented();
        assertEquals(Arrays.asList("first"), mReported);
    }

    @Test
    public void drawRequestedAfterTheFirstFrameIsReportedAtOnce() {
        mGate.onSurfaceCreated();
        mGate.onFirstFramePresented();
        mGate.onRedrawNeeded(draw("resize"));
        assertEquals(Arrays.asList("resize"), mReported);
    }

    @Test
    public void newSurfaceWaitsForItsOwnFirstFrame() {
        mGate.onSurfaceCreated();
        mGate.onFirstFramePresented();
        mGate.onSurfaceReleased();
        mGate.onSurfaceCreated();
        mGate.onRedrawNeeded(draw("second surface"));
        assertEquals(Arrays.asList(), mReported);

        mGate.onFirstFramePresented();
        assertEquals(Arrays.asList("second surface"), mReported);
    }

    @Test
    public void surfaceReleasedBeforeItsFirstFrameReportsItsPendingDraws() {
        mGate.onSurfaceCreated();
        mGate.onRedrawNeeded(draw("a"));
        mGate.onRedrawNeeded(draw("b"));
        mGate.onSurfaceReleased();
        assertEquals(Arrays.asList("a", "b"), mReported);
    }

    @Test
    public void detachingDropsPendingDrawsWithoutReportingThem() {
        mGate.onSurfaceCreated();
        mGate.onRedrawNeeded(draw("dropped"));
        mGate.onDetachedFromWindow();
        mGate.onSurfaceReleased();
        mGate.onFirstFramePresented();
        assertEquals(Arrays.asList(), mReported);
    }

    @Test
    public void eachDrawIsReportedOnce() {
        mGate.onSurfaceCreated();
        mGate.onRedrawNeeded(draw("once"));
        mGate.onFirstFramePresented();
        mGate.onFirstFramePresented();
        mGate.onSurfaceReleased();
        assertEquals(Arrays.asList("once"), mReported);
    }
}
