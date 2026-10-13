package com.reactnative.skia;

import android.graphics.Region;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

final class SwapTrace {
    private static final String TAG = "SkiaSwapTrace";

    private SwapTrace() {}

    static void log(String message) {
        Log.i(TAG, message + " t=" + SystemClock.uptimeMillis());
    }

    static String describeChildren(ViewGroup parent) {
        StringBuilder description = new StringBuilder();
        for (int index = 0; index < parent.getChildCount(); index++) {
            View child = parent.getChildAt(index);
            description.append(index == 0 ? "" : ",").append(child.getClass().getSimpleName()).append('@')
                    .append(Integer.toHexString(System.identityHashCode(child)));
            if (child instanceof SkiaSurfaceView) {
                SkiaSurfaceView surfaceView = (SkiaSurfaceView) child;
                Region region = new Region();
                surfaceView.probeHole(region);
                description.append(surfaceView.isRetired() ? "(retired" : "(").append(region.isEmpty() ? " no-hole)" : " hole)");
            }
        }
        return description.toString();
    }
}
