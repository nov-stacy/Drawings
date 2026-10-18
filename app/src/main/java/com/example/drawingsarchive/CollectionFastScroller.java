package com.example.drawingsarchive;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.AbsListView;
import android.widget.GridView;

/** A tappable track and draggable thumb synchronized with the collection grid. */
final class CollectionFastScroller extends View {
    private final GridView grid;
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float inset;
    private final float minimumThumbHeight;
    private boolean dragging;
    private float dragOffset;
    private int scrollState = AbsListView.OnScrollListener.SCROLL_STATE_IDLE;
    private float lastFraction = Float.NaN;
    private int visibilityGeneration;
    private final Runnable hideAfterIdle = () -> {
        if (dragging || scrollState != AbsListView.OnScrollListener.SCROLL_STATE_IDLE) return;
        final int generation = visibilityGeneration;
        animate().alpha(0f).setDuration(180).withEndAction(() -> {
            if (generation == visibilityGeneration && !dragging
                    && scrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE) {
                setVisibility(INVISIBLE);
            }
        }).start();
    };

    CollectionFastScroller(Context context, GridView grid, int trackColor, int thumbColor) {
        super(context);
        this.grid = grid;
        float density = getResources().getDisplayMetrics().density;
        inset = 8f * density;
        minimumThumbHeight = 44f * density;
        trackPaint.setColor(trackColor);
        trackPaint.setAlpha(100);
        thumbPaint.setColor(thumbColor);
        setContentDescription("Быстрая прокрутка коллекции");
        setFocusable(true);
        setClickable(true);
        setAlpha(0f);
        setVisibility(INVISIBLE);
        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) {
                scrollState = state;
                if (state != SCROLL_STATE_IDLE) reveal();
                else scheduleHide();
            }
            @Override public void onScroll(AbsListView view, int first, int visible, int total) {
                syncVisibility();
            }
        });
        grid.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> syncVisibility());
    }

    private void syncVisibility() {
        Metrics metrics = metrics();
        if (metrics == null) {
            removeCallbacks(hideAfterIdle);
            animate().cancel();
            visibilityGeneration++;
            setAlpha(0f);
            setVisibility(INVISIBLE);
            lastFraction = Float.NaN;
            return;
        }
        boolean moved = !Float.isNaN(lastFraction) && Math.abs(metrics.fraction - lastFraction) > 0.0001f;
        lastFraction = metrics.fraction;
        if (dragging || scrollState != AbsListView.OnScrollListener.SCROLL_STATE_IDLE || moved) {
            reveal();
            if (!dragging && scrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE) scheduleHide();
        }
        invalidate();
    }

    private void reveal() {
        if (metrics() == null) return;
        visibilityGeneration++;
        removeCallbacks(hideAfterIdle);
        animate().cancel();
        setVisibility(VISIBLE);
        setAlpha(1f);
        invalidate();
    }

    private void scheduleHide() {
        removeCallbacks(hideAfterIdle);
        if (!dragging && getVisibility() == VISIBLE) postDelayed(hideAfterIdle, 1300);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(hideAfterIdle);
        animate().cancel();
        visibilityGeneration++;
        dragging = false;
        scrollState = AbsListView.OnScrollListener.SCROLL_STATE_IDLE;
        setAlpha(0f);
        setVisibility(INVISIBLE);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        if (gainFocus) reveal();
        else scheduleHide();
    }

    private Metrics metrics() {
        if (grid.getAdapter() == null || grid.getChildCount() == 0 || grid.getHeight() <= 0) return null;
        int count = grid.getAdapter().getCount();
        int columns = Math.max(1, grid.getNumColumns());
        int stride = grid.getChildAt(0).getHeight() + grid.getVerticalSpacing();
        if (stride <= 0 || count == 0) return null;
        int rows = (count + columns - 1) / columns;
        float contentHeight = (float) rows * stride - grid.getVerticalSpacing()
                + grid.getPaddingTop() + grid.getPaddingBottom();
        float maxOffset = contentHeight - grid.getHeight();
        if (maxOffset <= 0) return null;
        float offset = (float) (grid.getFirstVisiblePosition() / columns) * stride
                + grid.getPaddingTop() - grid.getChildAt(0).getTop();
        return new Metrics(stride, columns, count, contentHeight, maxOffset,
                Math.max(0f, Math.min(1f, offset / maxOffset)));
    }

    private float trackHeight() {
        return Math.max(0f, getHeight() - inset * 2f);
    }

    private float thumbHeight(Metrics metrics) {
        return Math.min(trackHeight(), Math.max(minimumThumbHeight,
                trackHeight() * grid.getHeight() / metrics.contentHeight));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Metrics metrics = metrics();
        if (metrics == null || trackHeight() <= 0f) return;
        float center = getWidth() - getResources().getDisplayMetrics().density * 5f;
        float trackWidth = getResources().getDisplayMetrics().density * 1.5f;
        canvas.drawRoundRect(center - trackWidth / 2f, inset, center + trackWidth / 2f,
                getHeight() - inset, trackWidth, trackWidth, trackPaint);
        float height = thumbHeight(metrics);
        float top = inset + metrics.fraction * (trackHeight() - height);
        float width = getResources().getDisplayMetrics().density * (dragging ? 8f : 4f);
        canvas.drawRoundRect(center - width / 2f, top, center + width / 2f,
                top + height, width / 2f, width / 2f, thumbPaint);
    }

    private void seek(float fraction) {
        Metrics metrics = metrics();
        if (metrics == null) return;
        float offset = Math.max(0f, Math.min(1f, fraction)) * metrics.maxOffset;
        int row = (int) Math.ceil(offset / metrics.stride);
        int position = Math.min(metrics.count - 1, row * metrics.columns);
        // GridView's selection layout also works in touch mode and pins the last row to the bottom.
        grid.setSelection(position);
    }

    private void seekAt(float y, Metrics metrics) {
        float travel = trackHeight() - thumbHeight(metrics);
        if (travel > 0f) seek((y - inset - dragOffset) / travel);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        Metrics metrics = metrics();
        if (metrics == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                float height = thumbHeight(metrics);
                float top = inset + metrics.fraction * (trackHeight() - height);
                dragOffset = event.getY() >= top && event.getY() <= top + height
                        ? event.getY() - top : height / 2f;
                dragging = true;
                reveal();
                getParent().requestDisallowInterceptTouchEvent(true);
                seekAt(event.getY(), metrics);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) seekAt(event.getY(), metrics);
                return true;
            case MotionEvent.ACTION_UP:
                dragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                invalidate();
                performClick();
                scheduleHide();
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                invalidate();
                scheduleHide();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.SeekBar");
        Metrics metrics = metrics();
        if (metrics == null) return;
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0f, 1f, metrics.fraction));
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        Metrics metrics = metrics();
        if (metrics != null) {
            if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()
                    && arguments != null && arguments.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)) {
                seek(arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE));
                reveal();
                scheduleHide();
                return true;
            }
            float page = grid.getHeight() / metrics.maxOffset;
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                seek(metrics.fraction + page);
                reveal();
                scheduleHide();
                return true;
            }
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                seek(metrics.fraction - page);
                reveal();
                scheduleHide();
                return true;
            }
        }
        return super.performAccessibilityAction(action, arguments);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Metrics metrics = metrics();
        if (metrics != null && (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP)) {
            seek(metrics.fraction + (keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1f : -1f)
                    * grid.getHeight() / metrics.maxOffset);
            reveal();
            scheduleHide();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private static final class Metrics {
        final int stride;
        final int columns;
        final int count;
        final float contentHeight;
        final float maxOffset;
        final float fraction;

        Metrics(int stride, int columns, int count, float contentHeight, float maxOffset, float fraction) {
            this.stride = stride;
            this.columns = columns;
            this.count = count;
            this.contentHeight = contentHeight;
            this.maxOffset = maxOffset;
            this.fraction = fraction;
        }
    }
}
