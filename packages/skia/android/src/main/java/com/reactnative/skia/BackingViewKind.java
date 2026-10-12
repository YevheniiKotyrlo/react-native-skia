package com.reactnative.skia;

import android.os.Build;

import androidx.annotation.Nullable;

/** The Android view a SkiaView draws into, see SkiaView.updateView(). */
enum BackingViewKind {
    SURFACE_VIEW,
    TEXTURE_VIEW;

    /**
     * The kind the androidSurfaceType prop asks for, or null for "auto", which
     * leaves the choice to the opaque prop.
     */
    @Nullable
    static BackingViewKind fromSurfaceType(@Nullable String surfaceType) {
        if ("SurfaceView".equals(surfaceType)) {
            return SURFACE_VIEW;
        }
        if ("TextureView".equals(surfaceType)) {
            return TEXTURE_VIEW;
        }
        return null;
    }

    /**
     * The kind "auto" picks: a SurfaceView for an opaque canvas and a
     * TextureView otherwise. Before Android 12L an opaque canvas gets a
     * TextureView too, unless it asks for zOrderOnTop or highBitDepth, which
     * only a SurfaceView offers: those releases remove a SurfaceView's layer
     * as the view leaves its window, ahead of the window frame that stops
     * showing it, so an opaque canvas leaving the screen leaves a black hole
     * for a frame. Android 8 to 10 remove it in onDetachedFromWindow; Android
     * 11 and 12 hand it to the render thread's positionLost, which runs after
     * the detach has cleared the view's root and so applies it at once too.
     * Android 12L applies the removal with the window's next draw.
     */
    static BackingViewKind forAutoSurfaceType(boolean opaque, boolean zOrderOnTop, boolean highBitDepth, int sdkInt) {
        if (!opaque) {
            return TEXTURE_VIEW;
        }
        if (sdkInt < Build.VERSION_CODES.S_V2 && !zOrderOnTop && !highBitDepth) {
            return TEXTURE_VIEW;
        }
        return SURFACE_VIEW;
    }

    /**
     * Whether a new view of this kind is given its surface, with the last frame
     * presented into it, before its first draw. A TextureView makes its
     * SurfaceTexture in its first draw and shows a frame presented then only in
     * the next one, so it would draw empty once. One that is not shown or not
     * laid out keeps the lazy path: a texture handed to it would hold its
     * buffers with no draw to show them.
     */
    boolean receivesSurfaceBeforeFirstDraw(boolean shown, int width, int height) {
        return switch (this) {
            case SURFACE_VIEW -> false;
            case TEXTURE_VIEW -> shown && width > 0 && height > 0;
        };
    }

    /**
     * Whether a new view of this kind, replacing an outgoing one, keeps that
     * view on screen until the new one presents its first frame. A SurfaceView
     * shows nothing before then, while an outgoing TextureView leaves with the
     * window frame that removes it, so swapping a canvas on screen would show
     * what lies under it in between. An outgoing SurfaceView is not kept: a
     * TextureView draws inside the window, so it reaches the screen with the
     * window frame the SurfaceView leaves with, and keeping the SurfaceView
     * longer would only blend the two.
     */
    boolean keepsOutgoingUntilFirstFrame(BackingViewKind outgoing, boolean shown, int width, int height) {
        return switch (this) {
            case SURFACE_VIEW -> outgoing == TEXTURE_VIEW && shown && width > 0 && height > 0;
            case TEXTURE_VIEW -> false;
        };
    }

    /**
     * Whether a view of this kind, replaced on screen, stays attached and
     * undrawn rather than being removed, so that it leaves the screen with the
     * window frame that covers it. Android 10 to 12 remove a detached
     * SurfaceView's layer ahead of that frame (10 in onDetachedFromWindow,
     * 11 and 12 in a positionLost that no longer has the view's root to defer
     * to) and hide the layer of an attached one the window stops drawing in
     * step with the frame. Android 12L removes a detached one with the window's
     * next draw, and Android 9 and earlier hide nothing in positionLost. A view
     * that is not shown or not laid out has nothing on screen to keep.
     */
    boolean retiresWhenReplaced(int sdkInt, boolean shown, int width, int height) {
        return switch (this) {
            case SURFACE_VIEW -> sdkInt >= Build.VERSION_CODES.Q
                    && sdkInt <= Build.VERSION_CODES.S
                    && shown
                    && width > 0
                    && height > 0;
            case TEXTURE_VIEW -> false;
        };
    }
}
