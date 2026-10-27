package com.reactnative.skia;

import android.os.Build;

import androidx.annotation.Nullable;

/**
 * The Android view a SkiaView draws into, see SkiaView.updateView(), and the
 * rules the props resolve by.
 */
enum BackingViewKind {
    SURFACE_VIEW,
    TEXTURE_VIEW;

    /**
     * The kind the androidBackingView prop names. "HardwareBufferView" (a plain
     * view drawing each frame's AHardwareBuffer inline, as React Native WebGPU's
     * does) is not available here yet: it falls back to a TextureView, which
     * composites the same way, with a warning (see BackingViewWarnings). The
     * default is a TextureView.
     */
    static BackingViewKind fromBackingView(@Nullable String backingView) {
        return "SurfaceView".equals(backingView) ? SURFACE_VIEW : TEXTURE_VIEW;
    }

    /**
     * Whether a canvas gets the 10-bit format: only on a SurfaceView, whose
     * layer the compositor shows as is. A TextureView composites through an
     * 8-bit pass anyway.
     */
    static boolean resolveHighBitDepth(BackingViewKind kind, boolean highBitDepth) {
        return highBitDepth && kind == SURFACE_VIEW;
    }

    /**
     * Whether the canvas composites as opaque: what the prop asks for, except
     * that the 10-bit format only has 2 bits of alpha, so a canvas that gets it
     * is opaque.
     */
    static boolean resolveOpaque(boolean opaque, boolean surfaceHighBitDepth) {
        return opaque || surfaceHighBitDepth;
    }

    /**
     * Whether a changed zOrderOnTop moves a mounted SurfaceView: SurfaceView
     * applies it to its live surface from Android 11, for an app targeting it.
     * Before that, the surface reads it when it is created, and so does a media
     * overlay on Android 13, whose SurfaceView moves a surface leaving the top
     * to the default sublayer at once, before setZOrderMediaOverlay() can ask
     * for its own: it would come back among the other SurfaceViews below the
     * window rather than above them.
     */
    static boolean zOrderOnTopChangesInPlace(int sdk, int targetSdk, boolean mediaOverlay) {
        if (sdk < Build.VERSION_CODES.R || targetSdk < Build.VERSION_CODES.R) {
            return false;
        }
        return !(mediaOverlay && sdk == Build.VERSION_CODES.TIRAMISU);
    }

    /**
     * Whether a changed zOrderMediaOverlay moves a mounted SurfaceView: only
     * from Android 14, whose SurfaceView compares the sublayer it is asked for
     * with the one its surface has. Up to Android 13, setZOrderMediaOverlay()
     * only sets the sublayer the next surface is created with.
     */
    static boolean zOrderMediaOverlayChangesInPlace(int sdk) {
        return sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
    }

    /** What a mounted canvas does with a changed z-order prop. */
    enum ZOrderChange {
        /** The SurfaceView moves its live surface. */
        TAKEN,
        /** The view already has the z-order asked for. */
        UNCHANGED,
        /** The SurfaceView keeps the z-order its surface was created with, and says so. */
        IGNORED_ONCE_MOUNTED,
        /** Only a SurfaceView has a z-order of its own: a request for one is ignored, and said so. */
        IGNORED_BY_THE_VIEW,
    }

    /**
     * What a mounted canvas of the given kind does with a z-order prop changed
     * to value from current, the one its view has (a view other than a
     * SurfaceView has none), where changesInPlace says whether this Android
     * release moves a live surface for that prop.
     */
    static ZOrderChange decideZOrderChange(BackingViewKind kind, boolean value, boolean current,
            boolean changesInPlace) {
        if (value == current) {
            return ZOrderChange.UNCHANGED;
        }
        return switch (kind) {
            case SURFACE_VIEW -> changesInPlace ? ZOrderChange.TAKEN : ZOrderChange.IGNORED_ONCE_MOUNTED;
            case TEXTURE_VIEW -> ZOrderChange.IGNORED_BY_THE_VIEW;
        };
    }
}
