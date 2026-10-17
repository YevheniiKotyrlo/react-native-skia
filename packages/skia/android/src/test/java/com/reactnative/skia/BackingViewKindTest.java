package com.reactnative.skia;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Build;

import org.junit.Test;

public class BackingViewKindTest {
    private static final int[] BEFORE_ANDROID_12L = {
        Build.VERSION_CODES.O,
        Build.VERSION_CODES.O_MR1,
        Build.VERSION_CODES.P,
        Build.VERSION_CODES.Q,
        Build.VERSION_CODES.R,
        Build.VERSION_CODES.S,
    };

    private static final int[] FROM_ANDROID_12L = {
        Build.VERSION_CODES.S_V2, Build.VERSION_CODES.TIRAMISU, Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
    };

    @Test
    public void translucentCanvasIsATextureView() {
        for (int sdkInt : BEFORE_ANDROID_12L) {
            assertEquals(BackingViewKind.TEXTURE_VIEW, BackingViewKind.forAutoSurfaceType(false, false, false, sdkInt));
        }
        for (int sdkInt : FROM_ANDROID_12L) {
            assertEquals(BackingViewKind.TEXTURE_VIEW, BackingViewKind.forAutoSurfaceType(false, true, true, sdkInt));
        }
    }

    @Test
    public void opaqueCanvasIsATextureViewBeforeAndroid12L() {
        for (int sdkInt : BEFORE_ANDROID_12L) {
            assertEquals(BackingViewKind.TEXTURE_VIEW, BackingViewKind.forAutoSurfaceType(true, false, false, sdkInt));
        }
    }

    @Test
    public void opaqueCanvasIsASurfaceViewFromAndroid12L() {
        for (int sdkInt : FROM_ANDROID_12L) {
            assertEquals(BackingViewKind.SURFACE_VIEW, BackingViewKind.forAutoSurfaceType(true, false, false, sdkInt));
        }
    }

    @Test
    public void opaqueCanvasAskingForZOrderOnTopIsASurfaceViewBeforeAndroid12L() {
        for (int sdkInt : BEFORE_ANDROID_12L) {
            assertEquals(BackingViewKind.SURFACE_VIEW, BackingViewKind.forAutoSurfaceType(true, true, false, sdkInt));
        }
    }

    @Test
    public void opaqueCanvasAskingForHighBitDepthIsASurfaceViewBeforeAndroid12L() {
        for (int sdkInt : BEFORE_ANDROID_12L) {
            assertEquals(BackingViewKind.SURFACE_VIEW, BackingViewKind.forAutoSurfaceType(true, false, true, sdkInt));
        }
    }

    @Test
    public void surfaceViewShownAndLaidOutIsRetiredWhenReplacedOnAndroid10To12() {
        assertTrue(BackingViewKind.SURFACE_VIEW.retiresWhenReplaced(Build.VERSION_CODES.Q, true, 260, 200));
        assertTrue(BackingViewKind.SURFACE_VIEW.retiresWhenReplaced(Build.VERSION_CODES.R, true, 260, 200));
        assertTrue(BackingViewKind.SURFACE_VIEW.retiresWhenReplaced(Build.VERSION_CODES.S, true, 260, 200));
    }

    @Test
    public void surfaceViewIsRemovedWhenReplacedFromAndroid12L() {
        assertFalse(BackingViewKind.SURFACE_VIEW.retiresWhenReplaced(Build.VERSION_CODES.S_V2, true, 260, 200));
        assertFalse(BackingViewKind.SURFACE_VIEW.retiresWhenReplaced(Build.VERSION_CODES.UPSIDE_DOWN_CAKE, true, 260, 200));
    }

    @Test
    public void surfaceViewIsRemovedWhenReplacedBeforeAndroid10() {
        assertFalse(BackingViewKind.SURFACE_VIEW.retiresWhenReplaced(Build.VERSION_CODES.O, true, 260, 200));
        assertFalse(BackingViewKind.SURFACE_VIEW.retiresWhenReplaced(Build.VERSION_CODES.P, true, 260, 200));
    }

    @Test
    public void surfaceViewThatIsNotShownIsRemovedWhenReplaced() {
        assertFalse(BackingViewKind.SURFACE_VIEW.retiresWhenReplaced(Build.VERSION_CODES.Q, false, 260, 200));
    }

    @Test
    public void surfaceViewWithoutASizeIsRemovedWhenReplaced() {
        assertFalse(BackingViewKind.SURFACE_VIEW.retiresWhenReplaced(Build.VERSION_CODES.Q, true, 0, 200));
        assertFalse(BackingViewKind.SURFACE_VIEW.retiresWhenReplaced(Build.VERSION_CODES.Q, true, 260, 0));
    }

    @Test
    public void textureViewIsRemovedWhenReplaced() {
        assertFalse(BackingViewKind.TEXTURE_VIEW.retiresWhenReplaced(Build.VERSION_CODES.P, true, 260, 200));
        assertFalse(BackingViewKind.TEXTURE_VIEW.retiresWhenReplaced(Build.VERSION_CODES.Q, true, 260, 200));
        assertFalse(BackingViewKind.TEXTURE_VIEW.retiresWhenReplaced(Build.VERSION_CODES.R, true, 260, 200));
    }

    @Test
    public void textureViewShownAndLaidOutReceivesItsSurfaceBeforeItsFirstDraw() {
        assertTrue(BackingViewKind.TEXTURE_VIEW.receivesSurfaceBeforeFirstDraw(true, 260, 200));
    }

    @Test
    public void textureViewThatIsNotShownKeepsTheLazyPath() {
        assertFalse(BackingViewKind.TEXTURE_VIEW.receivesSurfaceBeforeFirstDraw(false, 260, 200));
    }

    @Test
    public void textureViewWithoutASizeKeepsTheLazyPath() {
        assertFalse(BackingViewKind.TEXTURE_VIEW.receivesSurfaceBeforeFirstDraw(true, 0, 200));
        assertFalse(BackingViewKind.TEXTURE_VIEW.receivesSurfaceBeforeFirstDraw(true, 260, 0));
    }

    @Test
    public void surfaceViewNeverReceivesItsSurfaceBeforeItsFirstDraw() {
        assertFalse(BackingViewKind.SURFACE_VIEW.receivesSurfaceBeforeFirstDraw(true, 260, 200));
        assertFalse(BackingViewKind.SURFACE_VIEW.receivesSurfaceBeforeFirstDraw(false, 260, 200));
    }

    @Test
    public void surfaceViewKeepsAnOutgoingTextureViewOnScreen() {
        assertTrue(BackingViewKind.SURFACE_VIEW.keepsOutgoingOnScreen(BackingViewKind.TEXTURE_VIEW, true, 260, 200));
    }

    @Test
    public void nothingIsKeptForACanvasThatIsNotShown() {
        assertFalse(BackingViewKind.SURFACE_VIEW.keepsOutgoingOnScreen(BackingViewKind.TEXTURE_VIEW, false, 260, 200));
    }

    @Test
    public void nothingIsKeptForACanvasWithoutASize() {
        assertFalse(BackingViewKind.SURFACE_VIEW.keepsOutgoingOnScreen(BackingViewKind.TEXTURE_VIEW, true, 0, 200));
        assertFalse(BackingViewKind.SURFACE_VIEW.keepsOutgoingOnScreen(BackingViewKind.TEXTURE_VIEW, true, 260, 0));
    }

    @Test
    public void anOutgoingSurfaceViewIsNotKept() {
        assertFalse(BackingViewKind.SURFACE_VIEW.keepsOutgoingOnScreen(BackingViewKind.SURFACE_VIEW, true, 260, 200));
    }

    @Test
    public void textureViewKeepsNothing() {
        assertFalse(BackingViewKind.TEXTURE_VIEW.keepsOutgoingOnScreen(BackingViewKind.SURFACE_VIEW, true, 260, 200));
        assertFalse(BackingViewKind.TEXTURE_VIEW.keepsOutgoingOnScreen(BackingViewKind.TEXTURE_VIEW, true, 260, 200));
    }
}
