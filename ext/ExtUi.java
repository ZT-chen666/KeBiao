package com.ncvt.kebiao.ext;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Android 15（targetSdk 35）强制 edge-to-edge，内容会顶到状态栏下面。
 * 这里统一把系统栏高度补回给根布局，避免标题压到时间、按钮点不到。
 */
final class ExtUi {

    /** 构建标识，用来确认手机上装的是哪一版。 */
    static final String VERSION = "v1.18 · 2026-11-16";


    private ExtUi() {}

    static void applyInsets(Activity activity) {
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) content;
        if (group.getChildCount() == 0) return;
        final View root = group.getChildAt(0);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), bars.top, v.getPaddingRight(), bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }
}
