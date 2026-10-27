package com.reactnative.skia;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The backing-view props of a SkiaView: as React last sent them, and what the
 * backing view has. The first prop transaction creates the backing view (see
 * mount()); after it, the view takes a change of opaque, and of the z-order
 * where Android moves a live surface (see BackingViewKind), and ignores any
 * other request it does not already honour, with a warning.
 *
 * A setter called with the value it last got changes nothing, since Fabric
 * may hand a view its props again. The warnings go out once the view has
 * been in a window: Fabric creates a view, props and all, ahead of the
 * commit, for a render React may discard.
 *
 * Main thread, but kind(), which any thread reads.
 */
final class BackingViewProps {
    /** Where the warnings go. */
    interface Reporter {
        void warn(String message);
    }

    // The spec's default (SkiaViewNativeComponent), for which Fabric hands null or nothing.
    private static final String DEFAULT_BACKING_VIEW = "TextureView";

    private final int mSdk;
    private final int mTargetSdk;
    private final Reporter mReporter;

    // As React last sent them.
    private String mBackingView = DEFAULT_BACKING_VIEW;
    private boolean mOpaque = false;
    private boolean mZOrderOnTop = false;
    private boolean mZOrderMediaOverlay = false;
    private boolean mHighBitDepth = false;

    // What the backing view has, from mount() on: the kind is null until then.
    // A view other than a SurfaceView has no z-order.
    @Nullable
    private String mMountedBackingView = null;
    @Nullable
    private volatile BackingViewKind mKind = null;
    private boolean mSurfaceHighBitDepth = false;
    private boolean mSurfaceZOrderOnTop = false;
    private boolean mSurfaceZOrderMediaOverlay = false;

    // What the view said before it was first in a window; null from then on.
    @Nullable
    private List<String> mUnreported = new ArrayList<>();

    BackingViewProps(int sdk, int targetSdk, Reporter reporter) {
        mSdk = sdk;
        mTargetSdk = targetSdk;
        mReporter = reporter;
    }

    void setBackingView(@Nullable String value) {
        String backingView = value != null ? value : DEFAULT_BACKING_VIEW;
        if (backingView.equals(mBackingView)) {
            return;
        }
        mBackingView = backingView;
        if (mKind != null && !backingView.equals(mMountedBackingView)) {
            warn(BackingViewWarnings.ignoredChange("android.backingView"));
        }
    }

    void setOpaque(boolean value) {
        if (value == mOpaque) {
            return;
        }
        mOpaque = value;
        if (mKind != null && BackingViewKind.resolveOpaque(value, mSurfaceHighBitDepth) != value) {
            warn(BackingViewWarnings.translucentHighBitDepth());
        }
    }

    void setHighBitDepth(boolean value) {
        if (value == mHighBitDepth) {
            return;
        }
        mHighBitDepth = value;
        BackingViewKind kind = mKind;
        if (kind == null || value == mSurfaceHighBitDepth) {
            return;
        }
        if (BackingViewKind.resolveHighBitDepth(kind, value) != value) {
            warn(BackingViewWarnings.highBitDepthOnlyOnASurfaceView());
        } else {
            warn(BackingViewWarnings.ignoredChange("highBitDepth"));
        }
    }

    void setZOrderOnTop(boolean value) {
        if (value == mZOrderOnTop) {
            return;
        }
        mZOrderOnTop = value;
        boolean changesInPlace =
                BackingViewKind.zOrderOnTopChangesInPlace(mSdk, mTargetSdk, mSurfaceZOrderMediaOverlay);
        if (takesZOrder("android.zOrderOnTop", value, mSurfaceZOrderOnTop, changesInPlace)) {
            mSurfaceZOrderOnTop = value;
        }
    }

    void setZOrderMediaOverlay(boolean value) {
        if (value == mZOrderMediaOverlay) {
            return;
        }
        mZOrderMediaOverlay = value;
        boolean changesInPlace = BackingViewKind.zOrderMediaOverlayChangesInPlace(mSdk);
        if (takesZOrder("android.zOrderMediaOverlay", value, mSurfaceZOrderMediaOverlay, changesInPlace)) {
            mSurfaceZOrderMediaOverlay = value;
        }
    }

    // Whether a mounted view moves to the z-order prop changed to value, from
    // the one it has (see BackingViewKind.decideZOrderChange). Before mount
    // the prop is read when the view is created.
    private boolean takesZOrder(String prop, boolean value, boolean current, boolean changesInPlace) {
        BackingViewKind kind = mKind;
        if (kind == null) {
            return false;
        }
        return switch (BackingViewKind.decideZOrderChange(kind, value, current, changesInPlace)) {
            case TAKEN -> true;
            case UNCHANGED -> false;
            case IGNORED_ONCE_MOUNTED -> {
                warn(BackingViewWarnings.ignoredChange(prop));
                yield false;
            }
            case IGNORED_BY_THE_VIEW -> {
                warn(BackingViewWarnings.zOrderOnlyOnASurfaceView());
                yield false;
            }
        };
    }

    /**
     * Fixes what the backing view is created with, from the props of the first
     * transaction, and says which of them it does not take.
     */
    BackingViewKind mount() {
        BackingViewKind kind = BackingViewKind.fromBackingView(mBackingView);
        boolean surfaceView = kind == BackingViewKind.SURFACE_VIEW;
        mMountedBackingView = mBackingView;
        mSurfaceHighBitDepth = BackingViewKind.resolveHighBitDepth(kind, mHighBitDepth);
        mSurfaceZOrderOnTop = surfaceView && mZOrderOnTop;
        mSurfaceZOrderMediaOverlay = surfaceView && mZOrderMediaOverlay;
        mKind = kind;
        for (String warning : BackingViewWarnings.atMount(mBackingView, mHighBitDepth, mOpaque, mZOrderOnTop,
                mZOrderMediaOverlay)) {
            warn(warning);
        }
        return kind;
    }

    /** The view is in a window: what it said goes out, and from now on at once. */
    void onAttachedToWindow() {
        List<String> unreported = mUnreported;
        if (unreported == null) {
            return;
        }
        mUnreported = null;
        for (String message : unreported) {
            mReporter.warn(message);
        }
    }

    /** The kind of the backing view, null until it is created. Any thread. */
    @Nullable
    BackingViewKind kind() {
        return mKind;
    }

    /** Whether the surface has the 10-bit format. */
    boolean surfaceHighBitDepth() {
        return mSurfaceHighBitDepth;
    }

    /** Whether the canvas composites as opaque (see BackingViewKind.resolveOpaque). */
    boolean isOpaque() {
        return BackingViewKind.resolveOpaque(mOpaque, mSurfaceHighBitDepth);
    }

    /** Whether the SurfaceView's surface is on top of the window. */
    boolean zOrderOnTop() {
        return mSurfaceZOrderOnTop;
    }

    /** Whether the SurfaceView's surface is a media overlay. */
    boolean zOrderMediaOverlay() {
        return mSurfaceZOrderMediaOverlay;
    }

    private void warn(String message) {
        List<String> unreported = mUnreported;
        if (unreported != null) {
            unreported.add(message);
        } else {
            mReporter.warn(message);
        }
    }
}
