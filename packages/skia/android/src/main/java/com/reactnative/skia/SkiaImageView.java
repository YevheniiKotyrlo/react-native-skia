package com.reactnative.skia;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.uimanager.PointerEvents;
import com.facebook.react.uimanager.ReactPointerEventsView;

/**
 * The Android view behind <SkiaImageView>: a plain view that draws its
 * image's bitmap, shared with every view showing the same image, inside the
 * window's own drawing. It has no surface, swapchain or native Skia view, so
 * it mounts and unmounts at the cost of any view, and it composites, clips,
 * fades and transforms like one.
 */
@SuppressLint("ViewConstructor")
public class SkiaImageView extends View
        implements SkiaPresentedImages.Observer, ReactPointerEventsView {

    private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect mSource = new Rect();
    private final Rect mDestination = new Rect();

    // 0 shows nothing; see RNSkPresentedImages::idOf for the others.
    private long mImageId = 0;
    @Nullable
    private Bitmap mBitmap;
    private PointerEvents mPointerEvents = PointerEvents.AUTO;

    public SkiaImageView(Context context) {
        super(context);
    }

    void setPresentedImageId(long id) {
        if (id == mImageId) {
            return;
        }
        if (mImageId != 0) {
            SkiaPresentedImages.unobserve(mImageId, this);
        }
        mImageId = id;
        if (id == 0) {
            mBitmap = null;
            invalidate();
            return;
        }
        // Until the new image's bitmap is published, the view keeps the one it
        // shows: a change of image never shows nothing in between.
        Bitmap published = SkiaPresentedImages.observe(id, this);
        if (published != null) {
            mBitmap = published;
            invalidate();
        }
    }

    void setPointerEvents(@NonNull PointerEvents pointerEvents) {
        mPointerEvents = pointerEvents;
    }

    @NonNull
    @Override
    public PointerEvents getPointerEvents() {
        return mPointerEvents;
    }

    @Override
    public void onBitmapPublished(@NonNull Bitmap bitmap) {
        mBitmap = bitmap;
        invalidate();
    }

    // The bitmap is the view's only drawing unless it has a background, so an
    // alpha below 1 applies to that one draw rather than to an offscreen layer.
    @Override
    public boolean hasOverlappingRendering() {
        return getBackground() != null;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        Bitmap bitmap = mBitmap;
        if (bitmap == null) {
            return;
        }
        // The image fills the view: a view laid out at the image's size in
        // pixels draws it unscaled.
        mSource.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
        mDestination.set(0, 0, getWidth(), getHeight());
        canvas.drawBitmap(bitmap, mSource, mDestination, mPaint);
    }
}
