package com.reactnative.skia;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The bitmaps SkiaImageViews show, by image id: one per image however many
 * views show it (see cpp/rnskia/RNSkPresentedImages.h). Native publishes a
 * bitmap once the image is read back and discards it once JS releases the
 * image; both are posted to the main thread, where the views read the store.
 */
final class SkiaPresentedImages {

    /** A view showing an image, told when its bitmap is published. Main thread. */
    interface Observer {
        void onBitmapPublished(@NonNull Bitmap bitmap);
    }

    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
    private static final Map<Long, Bitmap> sBitmaps = new HashMap<>();
    private static final Map<Long, Set<Observer>> sObservers = new HashMap<>();

    private SkiaPresentedImages() {}

    /**
     * A bitmap of premultiplied 8-bit RGBA pixels, rows of exactly width * 4
     * bytes. The GPU texture is uploaded ahead of the first draw. Any thread.
     */
    static Bitmap makeBitmap(ByteBuffer pixels, int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(pixels);
        bitmap.prepareToDraw();
        return bitmap;
    }

    /** Any thread. */
    static void publish(long id, Bitmap bitmap) {
        sMainHandler.post(() -> {
            sBitmaps.put(id, bitmap);
            Set<Observer> observers = sObservers.get(id);
            if (observers == null) {
                return;
            }
            for (Observer observer : new ArrayList<>(observers)) {
                observer.onBitmapPublished(bitmap);
            }
        });
    }

    /** Any thread. A view showing the bitmap keeps it until it shows another. */
    static void discard(long id) {
        sMainHandler.post(() -> sBitmaps.remove(id));
    }

    /** The bitmap of `id` if it is published yet; `observer` is told when it is. Main thread. */
    @Nullable
    static Bitmap observe(long id, Observer observer) {
        Set<Observer> observers = sObservers.get(id);
        if (observers == null) {
            observers = new HashSet<>();
            sObservers.put(id, observers);
        }
        observers.add(observer);
        return sBitmaps.get(id);
    }

    /** Main thread. */
    static void unobserve(long id, Observer observer) {
        Set<Observer> observers = sObservers.get(id);
        if (observers == null) {
            return;
        }
        observers.remove(observer);
        if (observers.isEmpty()) {
            sObservers.remove(id);
        }
    }
}
