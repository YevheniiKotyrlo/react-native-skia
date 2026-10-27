package com.reactnative.skia;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class BackingViewWarningsTest {
    @Test
    public void aCanvasEachViewTakesAsItAsksWarnsAboutNothing() {
        assertEquals(Arrays.asList(), BackingViewWarnings.atMount(null, false, false, false, false));
        assertEquals(Arrays.asList(), BackingViewWarnings.atMount("TextureView", false, true, false, false));
        assertEquals(Arrays.asList(), BackingViewWarnings.atMount("SurfaceView", true, true, true, true));
    }

    @Test
    public void aHardwareBufferViewWarnsThatATextureViewStandsIn() {
        List<String> warnings = BackingViewWarnings.atMount("HardwareBufferView", false, false, false, false);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).startsWith("android.backingView: HardwareBufferView is not available"));
    }

    @Test
    public void highBitDepthWarnsOnEveryViewButASurfaceView() {
        for (String backingView : Arrays.asList(null, "TextureView", "HardwareBufferView")) {
            assertTrue(backingView, BackingViewWarnings.atMount(backingView, true, false, false, false).stream()
                    .anyMatch(warning -> warning.startsWith("highBitDepth needs a SurfaceView")));
        }
    }

    @Test
    public void theZOrderWarnsOnEveryViewButASurfaceView() {
        for (String backingView : Arrays.asList(null, "TextureView", "HardwareBufferView")) {
            assertTrue(backingView, BackingViewWarnings.atMount(backingView, false, false, true, false).stream()
                    .anyMatch(warning -> warning.startsWith("android.zOrderOnTop and android.zOrderMediaOverlay")));
            assertTrue(backingView, BackingViewWarnings.atMount(backingView, false, false, false, true).stream()
                    .anyMatch(warning -> warning.startsWith("android.zOrderOnTop and android.zOrderMediaOverlay")));
        }
    }

    @Test
    public void aTranslucentHighBitDepthSurfaceViewWarnsThatItIsOpaque() {
        assertEquals(
                Arrays.asList("Ignoring android.opaque: false, a highBitDepth canvas is opaque on Android"
                        + " (the 10-bit format only has 2 bits of alpha)."),
                BackingViewWarnings.atMount("SurfaceView", true, false, false, false));
    }

    @Test
    public void anIgnoredChangeNamesItsProp() {
        assertTrue(BackingViewWarnings.ignoredChange("android.zOrderMediaOverlay")
                .startsWith("Ignoring a change of android.zOrderMediaOverlay on a mounted canvas"));
    }
}
