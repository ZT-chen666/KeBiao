package com.ncvt.kebiao.ext;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * 简单的流式布局：一行放不下就换到下一行。
 * 用于二课卡片的标签行，避免标签被挤出屏幕或压成竖排。
 */
public class FlowLayout extends ViewGroup {

    private int gapH;
    private int gapV;

    public FlowLayout(Context c) {
        super(c);
        init(c);
    }

    public FlowLayout(Context c, AttributeSet a) {
        super(c, a);
        init(c);
    }

    private void init(Context c) {
        float d = c.getResources().getDisplayMetrics().density;
        gapH = Math.round(6 * d);
        gapV = Math.round(6 * d);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
        int lineWidth = 0;
        int lineHeight = 0;
        int totalHeight = 0;
        int maxLineWidth = 0;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            measureChild(child, MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                    heightMeasureSpec);
            int cw = child.getMeasuredWidth();
            int ch = child.getMeasuredHeight();
            if (lineWidth > 0 && lineWidth + gapH + cw > width) {
                totalHeight += lineHeight + gapV;
                maxLineWidth = Math.max(maxLineWidth, lineWidth);
                lineWidth = cw;
                lineHeight = ch;
            } else {
                lineWidth += (lineWidth == 0 ? 0 : gapH) + cw;
                lineHeight = Math.max(lineHeight, ch);
            }
        }
        totalHeight += lineHeight;
        maxLineWidth = Math.max(maxLineWidth, lineWidth);

        setMeasuredDimension(
                Math.min(width + getPaddingLeft() + getPaddingRight(),
                        maxLineWidth + getPaddingLeft() + getPaddingRight()
                                + MeasureSpec.getSize(widthMeasureSpec)),
                totalHeight + getPaddingTop() + getPaddingBottom());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l - getPaddingLeft() - getPaddingRight();
        int x = getPaddingLeft();
        int y = getPaddingTop();
        int lineHeight = 0;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            int cw = child.getMeasuredWidth();
            int ch = child.getMeasuredHeight();
            if (x > getPaddingLeft() && x + cw > getPaddingLeft() + width) {
                x = getPaddingLeft();
                y += lineHeight + gapV;
                lineHeight = 0;
            }
            child.layout(x, y, x + cw, y + ch);
            x += cw + gapH;
            lineHeight = Math.max(lineHeight, ch);
        }
    }
}
