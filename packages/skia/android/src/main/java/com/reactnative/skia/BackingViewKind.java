package com.reactnative.skia;

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
     * Whether a new view of this kind, replacing an outgoing one, keeps that
     * view on screen until the new one presents its first frame. A SurfaceView
     * shows nothing before then, while an outgoing TextureView leaves with the
     * window frame that removes it, so swapping a canvas on screen would show
     * what lies under it in between. An outgoing SurfaceView is not kept: on
     * Android 10 its layer leaves as it detaches, whatever the window draws.
     */
    boolean keepsOutgoingUntilFirstFrame(BackingViewKind outgoing, boolean shown, int width, int height) {
        return switch (this) {
            case SURFACE_VIEW -> outgoing == TEXTURE_VIEW && shown && width > 0 && height > 0;
            case TEXTURE_VIEW -> false;
        };
    }
}
