package com.reactnative.skia;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class BackingViewPropsTest {
    private final List<String> mWarnings = new ArrayList<>();

    private BackingViewProps propsOn(int sdk) {
        return new BackingViewProps(sdk, sdk, mWarnings::add);
    }

    // Mounts the canvas with the props sent so far, in a window, and forgets
    // what it said at mount.
    private void mountInAWindow(BackingViewProps props) {
        props.mount();
        props.onAttachedToWindow();
        mWarnings.clear();
    }

    @Test
    public void propsHandedAgainChangeNothingAndSayNothing() {
        BackingViewProps props = propsOn(33);
        props.setBackingView("SurfaceView");
        props.setHighBitDepth(true);
        props.setOpaque(false);
        props.setZOrderMediaOverlay(true);
        mountInAWindow(props);
        props.setBackingView("SurfaceView");
        props.setHighBitDepth(true);
        props.setOpaque(false);
        props.setZOrderMediaOverlay(true);
        assertEquals(Arrays.asList(), mWarnings);
        assertTrue(props.zOrderMediaOverlay());
    }

    @Test
    public void anIgnoredRequestHandedAgainSaysNothingMore() {
        BackingViewProps props = propsOn(34);
        props.setBackingView("TextureView");
        props.setZOrderOnTop(true);
        props.setZOrderMediaOverlay(true);
        props.setHighBitDepth(true);
        mountInAWindow(props);
        props.setZOrderOnTop(true);
        props.setZOrderMediaOverlay(true);
        props.setHighBitDepth(true);
        props.setBackingView("SurfaceView");
        props.setBackingView("SurfaceView");
        assertEquals(Arrays.asList(BackingViewWarnings.ignoredChange("android.backingView")), mWarnings);
    }

    @Test
    public void aChangeBackToWhatTheViewHasSaysNothing() {
        BackingViewProps props = propsOn(33);
        props.setBackingView("SurfaceView");
        props.setZOrderMediaOverlay(true);
        mountInAWindow(props);
        props.setBackingView("TextureView");
        props.setZOrderMediaOverlay(false);
        assertEquals(Arrays.asList(BackingViewWarnings.ignoredChange("android.backingView"),
                BackingViewWarnings.ignoredChange("android.zOrderMediaOverlay")), mWarnings);
        mWarnings.clear();
        props.setBackingView("SurfaceView");
        props.setZOrderMediaOverlay(true);
        assertEquals(Arrays.asList(), mWarnings);
        assertTrue(props.zOrderMediaOverlay());
    }

    @Test
    public void aTranslucentRequestOnAHighBitDepthCanvasSaysTheFormatIsOpaque() {
        BackingViewProps props = propsOn(34);
        props.setBackingView("SurfaceView");
        props.setHighBitDepth(true);
        props.setOpaque(true);
        mountInAWindow(props);
        props.setOpaque(false);
        assertEquals(Arrays.asList(BackingViewWarnings.translucentHighBitDepth()), mWarnings);
        assertTrue(props.isOpaque());
    }

    @Test
    public void aLaterHighBitDepthOnAnotherViewSaysItNeedsASurfaceView() {
        BackingViewProps props = propsOn(34);
        props.setBackingView("TextureView");
        mountInAWindow(props);
        props.setHighBitDepth(true);
        assertEquals(Arrays.asList(BackingViewWarnings.highBitDepthOnlyOnASurfaceView()), mWarnings);
        assertFalse(props.surfaceHighBitDepth());
    }

    @Test
    public void aLaterHighBitDepthOnAMountedSurfaceViewIsIgnoredUntilItComesBack() {
        BackingViewProps props = propsOn(34);
        props.setBackingView("SurfaceView");
        mountInAWindow(props);
        props.setHighBitDepth(true);
        props.setHighBitDepth(false);
        assertEquals(Arrays.asList(BackingViewWarnings.ignoredChange("highBitDepth")), mWarnings);
        assertFalse(props.surfaceHighBitDepth());
    }

    @Test
    public void anotherViewMountedWithHighBitDepthSaysNothingWhenItIsTurnedOff() {
        BackingViewProps props = propsOn(34);
        props.setBackingView("TextureView");
        props.setHighBitDepth(true);
        mountInAWindow(props);
        props.setHighBitDepth(false);
        assertEquals(Arrays.asList(), mWarnings);
    }

    @Test
    public void anotherViewMountedWithAZOrderSaysNothingWhenItIsTurnedOff() {
        BackingViewProps props = propsOn(34);
        props.setBackingView("TextureView");
        props.setZOrderOnTop(true);
        props.setZOrderMediaOverlay(true);
        mountInAWindow(props);
        props.setZOrderOnTop(false);
        props.setZOrderMediaOverlay(false);
        assertEquals(Arrays.asList(), mWarnings);
        assertFalse(props.zOrderOnTop());
        assertFalse(props.zOrderMediaOverlay());
    }

    @Test
    public void onAndroid13ZOrderOnTopFollowsTheMediaOverlayTheSurfaceHas() {
        BackingViewProps props = propsOn(33);
        props.setBackingView("SurfaceView");
        mountInAWindow(props);
        props.setZOrderMediaOverlay(true);
        props.setZOrderOnTop(true);
        assertEquals(Arrays.asList(BackingViewWarnings.ignoredChange("android.zOrderMediaOverlay")), mWarnings);
        assertFalse(props.zOrderMediaOverlay());
        assertTrue(props.zOrderOnTop());
    }

    @Test
    public void aCanvasMountedWithoutTheBackingViewPropHasTheDefaultView() {
        BackingViewProps props = propsOn(34);
        mountInAWindow(props);
        assertEquals(BackingViewKind.TEXTURE_VIEW, props.kind());
        props.setBackingView("SurfaceView");
        props.setBackingView("TextureView");
        props.setBackingView(null);
        assertEquals(Arrays.asList(BackingViewWarnings.ignoredChange("android.backingView")), mWarnings);
    }

    @Test
    public void aViewNeverInAWindowSaysNothing() {
        BackingViewProps props = propsOn(34);
        props.setBackingView("HardwareBufferView");
        props.setHighBitDepth(true);
        props.mount();
        props.setBackingView("SurfaceView");
        assertEquals(Arrays.asList(), mWarnings);
    }

    @Test
    public void whatTheViewSaysGoesOutOnceItIsInAWindow() {
        BackingViewProps props = propsOn(34);
        props.setBackingView("HardwareBufferView");
        props.mount();
        props.setOpaque(true);
        props.setBackingView("SurfaceView");
        assertEquals(Arrays.asList(), mWarnings);
        props.onAttachedToWindow();
        List<String> said = new ArrayList<>(BackingViewWarnings.atMount("HardwareBufferView", false, false, false,
                false));
        said.add(BackingViewWarnings.ignoredChange("android.backingView"));
        assertEquals(said, mWarnings);
        props.onAttachedToWindow();
        props.setBackingView("TextureView");
        said.add(BackingViewWarnings.ignoredChange("android.backingView"));
        assertEquals(said, mWarnings);
    }

    @Test
    public void theViewIsCreatedWithTheFirstTransactionsProps() {
        BackingViewProps props = propsOn(34);
        props.setBackingView("SurfaceView");
        props.setHighBitDepth(true);
        props.setZOrderOnTop(true);
        assertEquals(BackingViewKind.SURFACE_VIEW, props.mount());
        assertEquals(BackingViewKind.SURFACE_VIEW, props.kind());
        assertTrue(props.surfaceHighBitDepth());
        assertTrue(props.isOpaque());
        assertTrue(props.zOrderOnTop());
        assertFalse(props.zOrderMediaOverlay());
    }

    @Test
    public void aMountedSurfaceViewTakesTheChangesItsReleaseMovesInPlace() {
        BackingViewProps props = propsOn(34);
        props.setBackingView("SurfaceView");
        mountInAWindow(props);
        props.setZOrderMediaOverlay(true);
        props.setOpaque(true);
        assertTrue(props.zOrderMediaOverlay());
        assertTrue(props.isOpaque());
        props.setZOrderOnTop(true);
        assertTrue(props.zOrderOnTop());
        assertEquals(Arrays.asList(), mWarnings);
    }

    @Test
    public void aMountedSurfaceViewKeepsTheZOrderItsReleaseCannotMove() {
        BackingViewProps props = propsOn(33);
        props.setBackingView("SurfaceView");
        props.setZOrderMediaOverlay(true);
        mountInAWindow(props);
        props.setZOrderOnTop(true);
        assertFalse(props.zOrderOnTop());
        assertEquals(Arrays.asList(BackingViewWarnings.ignoredChange("android.zOrderOnTop")), mWarnings);
    }

    @Test
    public void anotherViewAskedForAZOrderSaysItHasNone() {
        BackingViewProps props = propsOn(34);
        props.setBackingView("TextureView");
        mountInAWindow(props);
        props.setZOrderOnTop(true);
        props.setZOrderOnTop(false);
        props.setZOrderMediaOverlay(true);
        assertEquals(Arrays.asList(BackingViewWarnings.zOrderOnlyOnASurfaceView(),
                BackingViewWarnings.zOrderOnlyOnASurfaceView()), mWarnings);
        assertFalse(props.zOrderOnTop());
        assertFalse(props.zOrderMediaOverlay());
    }
}
