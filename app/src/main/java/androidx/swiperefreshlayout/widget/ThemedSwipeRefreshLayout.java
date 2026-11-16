/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package androidx.swiperefreshlayout.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.ShapeDrawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import me.zhanghai.android.files.R;
import me.zhanghai.android.files.compat.ContextCompatKt;
import me.zhanghai.android.files.home.PullPictogram;
import me.zhanghai.android.files.util.ContextExtensionsKt;

public class ThemedSwipeRefreshLayout extends SwipeRefreshLayout {
    public ThemedSwipeRefreshLayout(@NonNull Context context) {
        super(context);

        init();
    }

    public ThemedSwipeRefreshLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        init();
    }

    private void init() {
        Context context = getContext();
        boolean isMaterial3Theme = ContextExtensionsKt.isMaterial3Theme(context);
        int backgroundColor;
        if (isMaterial3Theme) {
            int surfaceColor = ContextExtensionsKt.getColorByAttr(context,
                    com.google.android.material.R.attr.colorSurface);
            @SuppressLint("PrivateResource")
            int overlayColor = ContextCompatKt.getColorCompat(context,
                    com.google.android.material.R.color.m3_popupmenu_overlay_color);
            backgroundColor = ColorUtils.compositeColors(overlayColor, surfaceColor);
        } else {
            backgroundColor = ContextExtensionsKt.getColorByAttr(context,
                    androidx.appcompat.R.attr.colorBackgroundFloating);
        }
        ((ShapeDrawable) mCircleView.getBackground()).getPaint().setColor(backgroundColor);
        setColorSchemeColors(ContextExtensionsKt.getColorByAttr(context,
                androidx.appcompat.R.attr.colorAccent));
    }

    private boolean mDeliberate;
    private boolean mGestureFromTop = true;

    /**
     * Ordo's pictogram instead of the spinner, and a pull that only counts when it is meant: the gesture has to start
     * with the content already at the top (scrolling up into the top never refreshes), and it has to go
     * [triggerDistancePx] past it.
     */
    public void useDeliberatePull(int triggerDistancePx) {
        mDeliberate = true;
        setSize(LARGE);
        mProgress = new PullPictogram(getContext());
        mCircleView.setImageDrawable(mProgress);
        mCircleView.setContentDescription(getContext().getString(R.string.refresh));
        setDistanceToTriggerSync(triggerDistancePx);
    }

    /** Ends a refresh: the pictogram shows its check first, the plain spinner just goes. */
    public void finishRefreshing() {
        if (mRefreshing && mProgress instanceof PullPictogram) {
            ((PullPictogram) mProgress).finish(() -> setRefreshing(false));
        } else {
            setRefreshing(false);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mGestureFromTop = !canChildScrollUp();
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return (!mDeliberate || mGestureFromTop) && super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onStartNestedScroll(@NonNull View child, @NonNull View target, int nestedScrollAxes) {
        return (!mDeliberate || mGestureFromTop) && super.onStartNestedScroll(child, target, nestedScrollAxes);
    }

    @Override
    public boolean onStartNestedScroll(@NonNull View child, @NonNull View target, int axes, int type) {
        return (!mDeliberate || mGestureFromTop) && super.onStartNestedScroll(child, target, axes, type);
    }

    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        View child = getChildView();
        if (child != null) {
            measureChild(child, widthMeasureSpec, heightMeasureSpec);
            setMeasuredDimension(child.getMeasuredWidth() + getPaddingLeft() + getPaddingRight(),
                    child.getMeasuredHeight() + getPaddingTop() + getPaddingBottom());
        }
    }

    @Nullable
    private View getChildView() {
        for (int i = 0; i < getChildCount(); ++i) {
            View child = getChildAt(i);
            if (!child.equals(mCircleView)) {
                return child;
            }
        }
        return null;
    }
}
