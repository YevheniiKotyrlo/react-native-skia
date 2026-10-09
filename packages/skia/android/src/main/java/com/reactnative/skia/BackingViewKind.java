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
     * Whether a new view of this kind, replacing an outgoing view of the other
     * kind on a shown, laid-out canvas, keeps the outgoing one until the new
     * one shows a frame. A SurfaceView shows nothing before its first frame,
     * while an outgoing TextureView leaves with the window frame that removes
     * it; and on Android 10 an outgoing SurfaceView's layer leaves as the view
     * detaches, ahead of the window frame that closes its hole.
     */
    boolean keepsOutgoingUntilFirstFrame(BackingViewKind outgoing, boolean shown, int width, int height) {
        return outgoing != this && shown && width > 0 && height > 0;
    }

    /**
     * Whether a new view of this kind goes above the outgoing view it replaces.
     * A TextureView paints into the window, so above an outgoing SurfaceView it
     * covers that view's hole; a SurfaceView behind the window shows only
     * through its own hole, so it goes beneath an outgoing TextureView, which
     * covers it until that hole opens.
     */
    boolean coversOutgoing() {
        return switch (this) {
            case SURFACE_VIEW -> false;
            case TEXTURE_VIEW -> true;
        };
    }
}
