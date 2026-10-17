package com.reactnative.skia;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.uimanager.PointerEvents;
import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.ViewProps;
import com.facebook.react.uimanager.annotations.ReactProp;
import com.facebook.react.viewmanagers.SkiaImageViewManagerDelegate;
import com.facebook.react.viewmanagers.SkiaImageViewManagerInterface;

public class SkiaImageViewManager extends SimpleViewManager<SkiaImageView>
        implements SkiaImageViewManagerInterface<SkiaImageView> {

    protected SkiaImageViewManagerDelegate mDelegate;

    SkiaImageViewManager() {
        mDelegate = new SkiaImageViewManagerDelegate(this);
    }

    protected SkiaImageViewManagerDelegate getDelegate() {
        return mDelegate;
    }

    @NonNull
    @Override
    public String getName() {
        return "SkiaImageView";
    }

    @NonNull
    @Override
    public SkiaImageView createViewInstance(@NonNull ThemedReactContext reactContext) {
        return new SkiaImageView(reactContext);
    }

    @Override
    @ReactProp(name = "presentedImageId")
    public void setPresentedImageId(SkiaImageView view, double value) {
        view.setPresentedImageId((long) value);
    }

    @Override
    @ReactProp(name = ViewProps.POINTER_EVENTS)
    public void setPointerEvents(SkiaImageView view, @Nullable String value) {
        view.setPointerEvents(PointerEvents.parsePointerEvents(value));
    }

    // React drops the view for good: it stops observing its image, whose
    // bitmap the store keeps until JS releases the image.
    @Override
    public void onDropViewInstance(@NonNull SkiaImageView view) {
        super.onDropViewInstance(view);
        view.setPresentedImageId(0);
    }
}
