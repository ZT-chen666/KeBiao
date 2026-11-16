package com.ncvt.kebiao.ext;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

/**
 * 把已报名的二课活动画在课表网格上，外观模仿核心课程块：
 * 顶部一条深色小色条 + 浅色主体 + 深色文字，圆角与内边距一致。
 */
final class RegisteredOverlay {

    private static final float TIME_LABEL_DP = 48f;
    private static final float HEADER_DP = 4f;
    private static final float CELL_DP = 82f;

    /** 已报名活动统一用琥珀色系（和课程块的紫/绿/橙区分开，但同一套版式）。 */
    private static final int BAR_COLOR = 0xFFF59E0B;
    private static final int BODY_COLOR = 0xFFFFF3E0;
    private static final int TEXT_COLOR = 0xFF171A1F;

    private RegisteredOverlay() {
    }

    interface OnBlockClick {
        void onClick(String id, String name, long start);
    }

    static FrameLayout build(Activity activity, OnBlockClick listener) {
        FrameLayout layer = new FrameLayout(activity);
        layer.setClipChildren(false);
        layer.setClickable(false);
        fill(activity, layer, listener);
        return layer;
    }

    /** 重新填充（报名后回课表时刷新用）。 */
    static java.util.List<float[]> RECTS = new java.util.ArrayList<>();

    static void fill(Activity activity, FrameLayout layer, OnBlockClick listener) {

        SharedPreferences sp = activity.getSharedPreferences("app_meta", Activity.MODE_PRIVATE);
        try {
            JSONArray arr = new JSONArray(sp.getString("registered_acts", "[]"));
            float d = activity.getResources().getDisplayMetrics().density;

            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                long start = o.optLong("start", 0);
                long end = o.optLong("end", 0);
                if (start <= 0 || end <= 0) continue;

                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(start * 1000);
                int col = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
                int startMin = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);

                Calendar ce = Calendar.getInstance();
                ce.setTimeInMillis(end * 1000);
                int endMin = ce.get(Calendar.HOUR_OF_DAY) * 60 + ce.get(Calendar.MINUTE);

                int p0 = slotForStart(startMin);
                if (p0 < 0) p0 = 0;
                int p1 = p0;   // 只看开始时间，占一格

                LinearLayout block = new LinearLayout(activity);
                block.setOrientation(LinearLayout.VERTICAL);
                block.setClipToOutline(true);

                GradientDrawable body = new GradientDrawable();
                body.setCornerRadius(8 * d);
                body.setColor(BODY_COLOR);
                block.setBackground(body);

                View bar = new View(activity);
                GradientDrawable barBg = new GradientDrawable();
                barBg.setCornerRadii(new float[]{8 * d, 8 * d, 8 * d, 8 * d, 0, 0, 0, 0});
                barBg.setColor(BAR_COLOR);
                bar.setBackground(barBg);
                block.addView(bar, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, (int) (6 * d)));

                TextView title = new TextView(activity);
                String place = o.optString("place", "");
                if ("是".equals(place) || "否".equals(place) || "null".equals(place)) place = "";
                title.setText(shortName(o.optString("name", "二课活动")) + "\n" + timeRange(start, end)
                        + (place.isEmpty() ? "" : "\n" + place));
                title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 9f);
                title.setTextColor(TEXT_COLOR);
                title.getPaint().setFakeBoldText(true);
                title.setMaxLines(6);
                title.setLineSpacing(0f, 1.08f);
                title.setPadding((int) (6 * d), (int) (5 * d), (int) (5 * d), (int) (5 * d));
                block.addView(title, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                block.setTag("col:" + col + ";p0:" + p0 + ";p1:" + p1 + ";n:" + (overlapsCourse(col, p0, p1) ? 1 : 0));
                block.setClickable(true);
                final String aid = o.optString("id", o.optString("activityID", ""));
                final String aname = o.optString("name", "二课活动");
                block.setOnClickListener(v -> {
                    if (listener != null) listener.onClick(aid, aname, start);
                });

                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(0, 0);
                lp.gravity = Gravity.TOP | Gravity.START;
                layer.addView(block, lp);
            }
        } catch (Exception ignored) {
        }
        return;
    }

    static void layoutBlocks(Activity activity, FrameLayout layer, int width) {
        if (width <= 0) return;
        float d = activity.getResources().getDisplayMetrics().density;
        int label = (int) (TIME_LABEL_DP * d);
        float colW = (width - label) / 7f;
        int header = (int) (HEADER_DP * d);
        float cellH = CELL_DP * d;

        for (int i = 0; i < layer.getChildCount(); i++) {
            View b = layer.getChildAt(i);
            Object tag = b.getTag();
            if (!(tag instanceof String)) continue;
            String t = (String) tag;
            try {
                int col = Integer.parseInt(t.replaceAll(".*col:(\\d+).*", "$1"));
                int p0 = Integer.parseInt(t.replaceAll(".*p0:(\\d+).*", "$1"));
                int p1 = Integer.parseInt(t.replaceAll(".*p1:(\\d+).*", "$1"));

                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) b.getLayoutParams();
                lp.leftMargin = (int) (label + col * colW + 2 * d);
                lp.width = (int) (colW - 4 * d);
                lp.height = (int) (cellH * 1.30f);
                lp.topMargin = (int) (header + (p0 + 1) * cellH - cellH * 0.40f + cellH * 0.10f);
                b.setLayoutParams(lp);
            } catch (Exception ignored) {
            }
        }
    }

    /** 该节次是否被真实课程占用（用于收窄，避免盖住课程）。 */
    private static boolean overlapsCourse(int col, int p0, int p1) {
        try {
            java.lang.reflect.Field f = com.ncvt.kebiao.ui.timetable.TimetableView.class
                    .getDeclaredField("hitAreas");
            f.setAccessible(true);
            // 无法判断时不收窄，避免正常情况也被压窄
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** 该时间段是否与真实课程重叠；重叠则返回那门课的矩形。 */
    private static float[] hitRect(int col, int p0, int p1) {
        java.util.List<float[]> rects = RECTS;
        if (rects == null || rects.isEmpty()) return null;
        float cellH = CELL_H;
        float top = HEADER_H + p0 * cellH;
        float bottom = HEADER_H + (p1 + 1) * cellH;
        for (float[] r : rects) {
            if (r == null || r.length < 4) continue;
            if (r[3] <= top || r[1] >= bottom) continue;      // 纵向不相交
            return r;
        }
        return null;
    }

    static float CELL_H;
    static float HEADER_H;

    /** 08:00-18:00 */
    private static String timeRange(long startSec, long endSec) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA);
            String a = f.format(new java.util.Date(startSec * 1000));
            String b = f.format(new java.util.Date(endSec * 1000));
            return a + "-" + b;
        } catch (Exception e) {
            return "";
        }
    }

    /** 去掉「（补发）」这类前缀，尽量显示完整活动名。 */
    private static String shortName(String n) {
        if (n == null) return "二课活动";
        return n.replace("（补发）", "").replace("(补发)", "").trim();
    }

    /** 开始时间所在格子；正好等于某格结束时间时，归这一格。 */
    private static int slotForStart(int minutes) {
        int[] starts = {480, 530, 580, 630, 680, 880, 930, 980, 1140, 1190, 1240, 1290};
        int[] ends   = {520, 570, 620, 670, 720, 920, 970, 1020, 1180, 1230, 1280, 1330};
        for (int i = 0; i < starts.length; i++) {
            if (minutes >= starts[i] && minutes <= ends[i]) return i;
        }
        int best = -1;
        for (int i = 0; i < starts.length; i++) {
            if (minutes >= starts[i]) best = i;
        }
        return Math.max(0, best);
    }

    private static int slotOf(int minutes) {
        int[] starts = {480, 530, 580, 630, 680, 880, 930, 980, 1140, 1190, 1240, 1290};
        int best = -1;
        for (int i = 0; i < starts.length; i++) {
            if (minutes >= starts[i]) best = i;
        }
        return best;
    }
}
