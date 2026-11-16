package com.ncvt.kebiao.ext;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewGroup;

import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationBarView;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.IntConsumer;

import com.ncvt.kebiao.ui.timetable.TimetableView;

/**
 * 零侵入桥接：不改课表任何既有类，通过 ContentProvider 在进程启动时注册
 * Activity 生命周期回调，等 MainActivity 出来后给课表控件挂：
 *   · 长按 → 小窗「添加课程 / 寻找二课活动」
 *   · 横向滑动 → 整屏跟手位移（松手回弹，周切换仍由课表自己完成）
 *   · 底部导航注入「二课」入口
 */
public final class EmptySlotBridge extends ContentProvider {

    private static final Map<View, Boolean> HOOKED = new WeakHashMap<>();
    private static final Map<View, Boolean> NAV_HOOKED = new WeakHashMap<>();
    private static final int SC_TAB_ID = 0x0A5C0001;
    private static boolean AGREEMENT_SHOWN;
    private static boolean SETUP_HINT_SHOWN;

    private static float lastX = -1f;
    private static float lastY = -1f;

    @Override
    public boolean onCreate() {
        Context ctx = getContext();
        Application app = null;
        if (ctx instanceof Application) {
            app = (Application) ctx;
        } else if (ctx != null && ctx.getApplicationContext() instanceof Application) {
            app = (Application) ctx.getApplicationContext();
        }
        if (app == null) return true;

        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(@NonNull Activity a, @Nullable Bundle b) {}
            @Override public void onActivityStarted(@NonNull Activity a) {}
            @Override public void onActivityResumed(@NonNull Activity a) { hookLater(a); }
            @Override public void onActivityPaused(@NonNull Activity a) {}
            @Override public void onActivityStopped(@NonNull Activity a) {}
            @Override public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) {}
            @Override public void onActivityDestroyed(@NonNull Activity a) {}
        });
        return true;
    }

    /** 首次启动弹出用户协议；「以后不再提示」按钮常驻可见。 */
    private void ensureAgreement(Activity activity) {
        if (AGREEMENT_SHOWN) return;
        final android.content.SharedPreferences sp =
                activity.getSharedPreferences("app_meta", Activity.MODE_PRIVATE);
        if (sp.getBoolean("agreed", false)) return;
        AGREEMENT_SHOWN = true;

        float density = activity.getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * density);
        int maxH = Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.55f);

        android.widget.LinearLayout wrap = new android.widget.LinearLayout(activity);
        wrap.setOrientation(android.widget.LinearLayout.VERTICAL);
        wrap.setPadding(pad, pad, pad, 0);

        android.widget.ScrollView sc = new android.widget.ScrollView(activity);
        android.widget.TextView msg = new android.widget.TextView(activity);
        msg.setText(AboutActivity.AGREEMENT);
        msg.setTextSize(12.5f);
        msg.setLineSpacing(pad / 4f, 1f);
        sc.addView(msg);
        wrap.addView(sc, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, maxH));

        android.widget.CheckBox cb = new android.widget.CheckBox(activity);
        cb.setText("以后不再提示");
        cb.setChecked(true);
        cb.setPadding(0, Math.round(6 * density), 0, 0);
        wrap.addView(cb);

        new android.app.AlertDialog.Builder(activity)
                .setTitle("使用前请阅读")
                .setView(wrap)
                .setCancelable(false)
                .setPositiveButton("同意并继续", (d, w) -> {
                    if (cb.isChecked()) sp.edit().putBoolean("agreed", true).apply();
                })
                .setNegativeButton("不同意", (d, w) -> activity.finishAffinity())
                .show();
    }

    /** 课表未配置时：给 + 号加提示，并自动跳到设置页。 */
    private void guideSetup(final Activity activity, View root) {
        final String pkg = activity.getPackageName();
        int navId = activity.getResources().getIdentifier("bottom_nav", "id", pkg);
        int updateId = activity.getResources().getIdentifier("btn_update", "id", pkg);
        int settingsId = activity.getResources().getIdentifier("nav_settings", "id", pkg);
        if (updateId == 0) return;
        final View update = root.findViewById(updateId);
        if (update == null) return;
        final View nav = navId == 0 ? null : root.findViewById(navId);

        String account = activity.getSharedPreferences("kebiao_settings", Activity.MODE_PRIVATE)
                .getString("account", "");
        final boolean needSetup = account == null || account.trim().isEmpty();

        int weekId = activity.getResources().getIdentifier("nav_week_schedule", "id", pkg);
        boolean onTimetableTab = !(nav instanceof BottomNavigationView)
                || ((BottomNavigationView) nav).getSelectedItemId() == weekId;
        if (onTimetableTab && !UPDATE_HINT_SHOWN) {
            update.postDelayed(() -> {
                if (!activity.isFinishing()) showUpdateHint(activity, update);
            }, 1200);
        }

        update.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() != MotionEvent.ACTION_DOWN) return false;
            if (!needSetupNow(activity)) return false;
            android.widget.Toast.makeText(activity,
                    "请先在「设置」里填写学号密码并点「保存设置」",
                    android.widget.Toast.LENGTH_SHORT).show();
            if (nav instanceof BottomNavigationView && settingsId != 0) {
                ((BottomNavigationView) nav).setSelectedItemId(settingsId);
            }
            return true;
        });

        if (!needSetup) return;

        if (nav instanceof BottomNavigationView && settingsId != 0) {
            nav.postDelayed(() -> {
                if (activity.isFinishing()) return;
                BottomNavigationView bnv = (BottomNavigationView) nav;
                if (bnv.getSelectedItemId() != settingsId) {
                    bnv.setSelectedItemId(settingsId);
                    android.widget.Toast.makeText(activity,
                            "先填学号密码，保存后回来点右上角 + 获取课表",
                            android.widget.Toast.LENGTH_LONG).show();
                }
            }, 900);
        }
    }

    private static boolean UPDATE_HINT_SHOWN;

    /** 未配置账号时，把课表上方的提示行改成「点右上角 + 获取课程表」。 */
    private void showUpdateHint(Activity activity, View update) {
        if (UPDATE_HINT_SHOWN || update == null || !update.isShown()) return;
        if (!needSetupNow(activity)) return;
        UPDATE_HINT_SHOWN = true;

        View hint = findById(activity, update.getRootView(), "tv_week_hint");
        if (hint instanceof TextView) {
            TextView tv = (TextView) hint;
            tv.setText("点右上角 + 获取课程表");
            tv.setTextColor(0xFF1F4F96);
            tv.setSingleLine(true);
        }
    }

    /** 在 + 号上方弹一个「点这里获取课表」的小气泡。 */
    private void showTapHint(Activity activity, View anchor, boolean needSetup) {
        TextView tv = new TextView(activity);
        tv.setText(needSetup ? "先点这里填账号" : "点这里获取课表");
        tv.setTextSize(12);
        tv.setTextColor(android.graphics.Color.WHITE);
        tv.setPadding(dp(anchor, 12), dp(anchor, 7), dp(anchor, 12), dp(anchor, 7));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(anchor, 10));
        bg.setColor(0xEE1F4F96);
        tv.setBackground(bg);

        final android.widget.PopupWindow pw = new android.widget.PopupWindow(tv,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, false);
        pw.setOutsideTouchable(true);
        pw.setBackgroundDrawable(new GradientDrawable());

        tv.measure(android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED));
        int h = tv.getMeasuredHeight();
        try {
            pw.showAsDropDown(anchor, -dp(anchor, 70), -(anchor.getHeight() + h + dp(anchor, 8)));
            anchor.postDelayed(pw::dismiss, 4000);
        } catch (Exception ignored) {
        }
    }

    private void hookLater(Activity activity) {
        if (!"com.ncvt.kebiao.MainActivity".equals(activity.getClass().getName())) return;
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (decor == null) return;
        decor.post(() -> attach(activity, decor));
    }

    private void attach(Activity activity, View root) {
        ensureAgreement(activity);
        guideSetup(activity, root);
        UpdateChecker.checkQuietly(activity);
        root.postDelayed(() -> moveSaveButton(activity, root), 400);
        root.postDelayed(() -> installVersionBlock(activity, root), 700);
        root.postDelayed(() -> refreshOverlay(activity), 900);
        root.postDelayed(() -> syncRegistered(activity), 1200);

        installNavEntry(activity, root);

        View timetable = findTimetable(activity, root);
        if (timetable == null || HOOKED.containsKey(timetable)) return;
        HOOKED.put(timetable, Boolean.TRUE);
        if (!(timetable instanceof TimetableView)) return;
        final TimetableView real = (TimetableView) timetable;

        ViewParent parent = real.getParent();
        if (!(parent instanceof ViewGroup)) return;
        final ViewGroup host = (ViewGroup) parent;
        ViewGroup.LayoutParams realLp = real.getLayoutParams();

        final FrameLayout strip = new FrameLayout(activity);
        strip.setClipChildren(true);
        strip.setClipToPadding(true);
        final TimetableView prev = new TimetableView(activity);
        final TimetableView next = new TimetableView(activity);
        prev.setClickable(false);
        next.setClickable(false);

        int index = host.indexOfChild(real);
        host.removeView(real);
        strip.addView(real, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        strip.addView(prev, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        strip.addView(next, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        host.addView(strip, index, realLp);

        final android.widget.FrameLayout overlay = RegisteredOverlay.build(activity,
                (id, name, start) -> showActivityPopup(activity, real, id, name, start));
        strip.addView(overlay, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sOverlay = overlay;
        sReal = real;
        strip.post(() -> {
            RegisteredOverlay.layoutBlocks(activity, overlay, strip.getWidth());
            syncOverlayY(real, overlay);
        });

        final float[] down = new float[2];
        final long[] downAt = new long[1];
        final boolean[] dragging = new boolean[1];
        final boolean[] fired = new boolean[1];
        final Runnable[] longPress = new Runnable[1];

        strip.post(() -> {
            prev.setTranslationX(-strip.getWidth());
            next.setTranslationX(strip.getWidth());
        });

        real.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getX();
                    down[1] = e.getY();
                    downAt[0] = System.currentTimeMillis();
                    dragging[0] = false;
                    fired[0] = false;
                    lastX = down[0];
                    lastY = down[1];
                    longPress[0] = () -> {
                        if (dragging[0] || fired[0]) return;
                        if (lastX < 0 || lastY < 0) return;
                        if (hitsCourse(real, lastX, lastY)) return;
                        int[] cell = cellAt(real, lastX, lastY);
                        if (cell == null) return;
                        int[] block = freeBlock(real, cell[0], cell[1]);
                        fired[0] = true;
                        real.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                        showSlotPopup((Activity) real.getContext(), real, cell[0], cell[1],
                                block[0], block[1], (int) lastX, (int) lastY,
                                columnDate(real, cell[0]));
                    };
                    real.postDelayed(longPress[0], 420);
                    break;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getX() - down[0];
                    float dy = e.getY() - down[1];
                    if (!dragging[0] && Math.abs(dx) > dp(v, 10) && Math.abs(dx) > Math.abs(dy)) {
                        dragging[0] = true;
                        if (longPress[0] != null) real.removeCallbacks(longPress[0]);
                        real.animate().cancel();
                        prev.animate().cancel();
                        next.animate().cancel();
                        refreshNeighbours(real, prev, next);   // 只在本次拖动开始时刷新一次
                    }
                    if (dragging[0]) {
                        drag(strip, real, prev, next, dx);
                        return true;      // 接管横向拖动，避免课表再触发一次切周
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (longPress[0] != null) real.removeCallbacks(longPress[0]);
                    boolean wasDragging = dragging[0];
                    dragging[0] = false;
                    if (wasDragging) {
                        float dx = e.getX() - down[0];
                        long dt = Math.max(1, System.currentTimeMillis() - downAt[0]);
                        settle(strip, real, prev, next, dx,
                                e.getActionMasked() == MotionEvent.ACTION_CANCEL, dt);
                        return true;
                    }
                    if (fired[0]) {
                        fired[0] = false;
                        return true;
                    }
                    break;
                }
                default:
                    break;
            }
            return false;
        });
    }

    /** 读课表头部的日期标签，得到被点那一列的真实日期。 */
    private String columnDate(View timetable, int day) {
        try {
            Context ctx = timetable.getContext();
            int id = ctx.getResources().getIdentifier("layout_week_dates", "id", ctx.getPackageName());
            if (id == 0) return null;
            ViewGroup box = timetable.getRootView().findViewById(id);
            if (box == null || box.getChildCount() < day + 1) return null;

            String monthText = textOf(box.getChildAt(0), true);
            String dayText = textOf(box.getChildAt(day), false);
            if (monthText == null || dayText == null) return null;

            int month = Integer.parseInt(monthText.replaceAll("[^0-9]", ""));
            int dayOfMonth = Integer.parseInt(dayText.replaceAll("[^0-9]", ""));
            java.time.LocalDate today = java.time.LocalDate.now();
            int year = today.getYear();
            if (month == 12 && today.getMonthValue() == 1) year -= 1;
            if (month == 1 && today.getMonthValue() == 12) year += 1;
            return String.format(java.util.Locale.CHINA, "%04d-%02d-%02d", year, month, dayOfMonth);
        } catch (Exception e) {
            return null;
        }
    }

    /** 深度找第一个符合要求的文本（日期格是容器，不是单个 TextView）。 */
    private String textOf(View v, boolean monthLike) {
        if (v instanceof TextView) {
            String t = ((TextView) v).getText().toString().trim();
            boolean ok = monthLike ? t.matches("\\d{1,2}月") : t.matches("\\d{1,2}");
            if (ok) return t;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                String t = textOf(g.getChildAt(i), monthLike);
                if (t != null) return t;
            }
        }
        return null;
    }

    /** 让覆盖层跟着课表的垂直滚动一起动。 */
    static void syncOverlayY(final TimetableView real, final android.view.View overlay) {
        try {
            final java.lang.reflect.Field f = TimetableView.class.getDeclaredField("scrollYOffset");
            f.setAccessible(true);
            final Runnable[] loop = new Runnable[1];
            loop[0] = () -> {
                try {
                    Object v = f.get(real);
                    if (v instanceof Integer) {
                        overlay.setTranslationY(-((Integer) v));
                    }
                } catch (Exception ignored) {
                }
                overlay.postOnAnimation(loop[0]);
            };
            overlay.postOnAnimation(loop[0]);
        } catch (Exception ignored) {
        }
    }

    /** 回前台时按「我的活动」校正：不在里面的就删掉（取消/过期都会消失）。 */
    void syncRegistered(final Activity activity) {
        final String ck = activity.getSharedPreferences("second_class", Activity.MODE_PRIVATE)
                .getString("cookie_school", "");
        if (ck == null || ck.isEmpty()) return;

        new Thread(() -> {
            try {
                org.json.JSONArray arr = new org.json.JSONArray(
                        activity.getSharedPreferences("app_meta", Activity.MODE_PRIVATE)
                                .getString("registered_acts", "[]"));
                if (arr.length() == 0) return;

                okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
                okhttp3.Request req = new okhttp3.Request.Builder()
                        .url("https://class.bigdata.ncvt.net/Student/My/myActivity.html")
                        .header("Cookie", ck).build();
                String html;
                try (okhttp3.Response resp = client.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) return;
                    html = resp.body().string();
                }

                org.json.JSONArray keep = new org.json.JSONArray();
                boolean changed = false;
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String id = o.optString("id", "");
                    if (!id.isEmpty() && html.contains(id)) {
                        String oldPlace = o.optString("place", "");
                        if (oldPlace.isEmpty() || "是".equals(oldPlace) || "否".equals(oldPlace)
                                || oldPlace.contains("<")) {
                            String fresh = refreshPlace(ck, id);
                            if (!fresh.isEmpty()) {
                                o.put("place", fresh);
                                changed = true;
                            }
                        }
                        keep.put(o);
                    } else {
                        changed = true;
                    }
                }
                if (changed) {
                    activity.getSharedPreferences("app_meta", Activity.MODE_PRIVATE)
                            .edit().putString("registered_acts", keep.toString()).apply();
                    activity.runOnUiThread(() -> refreshOverlay(activity));
                }
            } catch (Exception ignored) {
            }
        }).start();
    }


    /** 从详情页重新取地点，修正历史记录。 */
    static String refreshPlace(String cookie, String id) {
        try {
            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
            okhttp3.Request req = new okhttp3.Request.Builder()
                    .url("https://class.bigdata.ncvt.net/Student/Activity/apply.html?activityID=" + id)
                    .header("Cookie", cookie).build();
            try (okhttp3.Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return "";
                String html = resp.body().string();
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("活动地点\\s*[:：]\\s*([^<\\s，,。；;]{1,20})").matcher(html);
                if (m.find()) return m.group(1).trim();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    static java.util.List<float[]> courseRects(TimetableView real) {
        java.util.List<float[]> out = new java.util.ArrayList<>();
        try {
            java.lang.reflect.Field f = TimetableView.class.getDeclaredField("hitAreas");
            f.setAccessible(true);
            Object list = f.get(real);
            if (list instanceof java.util.List) {
                for (Object item : (java.util.List<?>) list) {
                    if (item == null) continue;
                    android.graphics.RectF r = null;
                    for (String n : new String[]{"rect", "bounds", "area"}) {
                        try {
                            java.lang.reflect.Field rf = item.getClass().getDeclaredField(n);
                            rf.setAccessible(true);
                            Object v = rf.get(item);
                            if (v instanceof android.graphics.RectF) {
                                r = (android.graphics.RectF) v;
                                break;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    if (r != null) out.add(new float[]{r.left, r.top, r.right, r.bottom});
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static android.widget.FrameLayout sOverlay;
    private static TimetableView sReal;

    /** 二课数据变了（报名成功回来）就重画一次。 */
    private void refreshOverlay(Activity activity) {
        try {
            if (sOverlay == null || sReal == null) return;
            sOverlay.removeAllViews();
            RegisteredOverlay.fill(activity, sOverlay,
                    (id, name, start) -> showActivityPopup(activity, sReal, id, name, start));
            RegisteredOverlay.RECTS = courseRects(sReal);
            RegisteredOverlay.CELL_H = 82 * activity.getResources().getDisplayMetrics().density;
            RegisteredOverlay.HEADER_H = 4 * activity.getResources().getDisplayMetrics().density;
            RegisteredOverlay.layoutBlocks(activity, sOverlay, sOverlay.getWidth());
        } catch (Exception ignored) {
        }
    }

    private FrameLayout prepareStrip(FrameLayout strip, TimetableView real,
                                     TimetableView prev, TimetableView next) {
        int w = strip.getWidth();
        real.setTranslationX(0f);
        prev.setTranslationX(-w);
        next.setTranslationX(w);
        return strip;
    }

    /** 拖动中：当前页跟手，相邻周从两侧露出。 */
    private void drag(FrameLayout strip, TimetableView real, TimetableView prev,
                      TimetableView next, float dx) {
        int w = strip.getWidth();
        if (w <= 0) w = real.getWidth();
        if (w <= 0) return;
        real.setTranslationX(dx);
        if (dx < 0) {
            next.setTranslationX(w + dx);
            prev.setTranslationX(-w);
        } else {
            prev.setTranslationX(-w + dx);
            next.setTranslationX(w);
        }
    }

    /** 松手：过阈值就整屏移入相邻周并切周，否则回弹。 */
    private void settle(FrameLayout strip, TimetableView real, TimetableView prev,
                        TimetableView next, float dx, boolean cancel, long dtMs) {
        int w = strip.getWidth();
        if (w <= 0) w = real.getWidth();
        if (w <= 0) return;
        float threshold = w * 0.22f;
        float flick = Math.abs(dx) / Math.max(1, dtMs);     // px/ms
        boolean fast = flick > 0.9f && Math.abs(dx) > w * 0.10f;
        boolean goNext = !cancel && (dx <= -threshold || (fast && dx < 0));
        boolean goPrev = !cancel && (dx >= threshold || (fast && dx > 0));
        int duration = 190;

        if (goNext) {
            real.animate().translationX(-w).setDuration(duration).setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
            next.animate().translationX(0f).setDuration(duration).setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
            real.postDelayed(() -> {
                swipeWeek(real, 1);
                real.post(() -> resetStrip(strip, real, prev, next));
            }, duration);
        } else if (goPrev) {
            real.animate().translationX(w).setDuration(duration).setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
            prev.animate().translationX(0f).setDuration(duration).setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
            real.postDelayed(() -> {
                swipeWeek(real, -1);
                real.post(() -> resetStrip(strip, real, prev, next));
            }, duration);
        } else {
            real.animate().translationX(0f).setDuration(160).setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
            prev.animate().translationX(-w).setDuration(160).setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
            next.animate().translationX(w).setDuration(160).setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        }
    }

    private void resetStrip(FrameLayout strip, TimetableView real,
                            TimetableView prev, TimetableView next) {
        int w = strip.getWidth();
        real.animate().cancel();
        prev.animate().cancel();
        next.animate().cancel();
        real.setTranslationX(0f);
        prev.setTranslationX(-w);
        next.setTranslationX(w);
    }

    /** 让预览页显示正确的上一周 / 下一周。 */
    private void refreshNeighbours(TimetableView real, TimetableView prev, TimetableView next) {
        try {
            Object coursesObj = field(real, "courses");
            Object weekObj = field(real, "currentWeekNumber");
            if (!(coursesObj instanceof List)) return;
            int week = weekObj instanceof Integer ? (Integer) weekObj : 1;
            @SuppressWarnings("unchecked")
            List<com.ncvt.kebiao.model.Course> list =
                    (List<com.ncvt.kebiao.model.Course>) coursesObj;
            prev.setCourses(list, Math.max(1, week - 1));
            next.setCourses(list, week + 1);
        } catch (Exception ignored) {
        }
    }

    /** 调课表自己的切周回调。 */
    private void swipeWeek(TimetableView real, int delta) {
        try {
            Object c = field(real, "onWeekSwipe");
            if (c instanceof IntConsumer) ((IntConsumer) c).accept(delta);
        } catch (Exception ignored) {
        }
    }

    /** 长按小窗：添加课程 / 寻找二课活动。 */
    private void showSlotPopup(Activity activity, View anchor, int day, int period,
                               int pStart, int pEnd, int x, int y, String date) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(anchor, 6), dp(anchor, 6), dp(anchor, 6), dp(anchor, 6));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(anchor, 14));
        bg.setColor(Color.WHITE);
        bg.setStroke(dp(anchor, 1), 0xFFE5EAF2);
        box.setBackground(bg);
        box.setElevation(dp(anchor, 8));

        PopupWindow popup = new PopupWindow(box,
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new GradientDrawable());
        popup.setElevation(dp(anchor, 8));

        String slot = pStart == pEnd
                ? "第" + (pStart + 1) + "节"
                : "第" + (pStart + 1) + "-" + (pEnd + 1) + "节";
        TextView hint = new TextView(activity);
        hint.setText("周" + "一二三四五六日".charAt(day - 1) + " " + slot + " 空着");
        hint.setTextSize(11);
        hint.setTextColor(0xFF94A0B0);
        hint.setPadding(dp(anchor, 10), dp(anchor, 4), dp(anchor, 14), dp(anchor, 0));
        box.addView(hint);

        if (date != null && date.length() >= 10) {
            TextView dateView = new TextView(activity);
            dateView.setText(date.substring(5).replace("-", "/") + " 的空档");
            dateView.setTextSize(10.5f);
            dateView.setTextColor(0xFFB6C0CC);
            dateView.setPadding(dp(anchor, 10), dp(anchor, 1), dp(anchor, 14), dp(anchor, 6));
            box.addView(dateView);
        }

        box.addView(popupItem(activity, "＋  添加课程", "用课表右上角的 + 添加", false,
                () -> {
                    popup.dismiss();
                    android.widget.Toast.makeText(activity, "用右上角 + 添加这门课", android.widget.Toast.LENGTH_SHORT).show();
                }));

        box.addView(popupItem(activity, "◎  寻找二课活动", "看这格时间能报什么", true,
                () -> {
                    popup.dismiss();
                    Intent it = new Intent(activity, SecondClassActivity.class);
                    it.putExtra(SecondClassActivity.EXTRA_DAY, day);
                    it.putExtra(SecondClassActivity.EXTRA_PERIOD, period);
                    it.putExtra(SecondClassActivity.EXTRA_PERIOD_START, pStart);
                    it.putExtra(SecondClassActivity.EXTRA_PERIOD_END, pEnd);
                    it.putExtra(SecondClassActivity.EXTRA_DATE, date);
                    activity.startActivity(it);
                }));

        popup.showAtLocation(anchor, Gravity.TOP | Gravity.START,
                Math.max(8, x - dp(anchor, 60)), Math.max(8, y - dp(anchor, 40)));
    }

    private static final String[][] SLOTS = {
            {"08:00", "08:40"}, {"08:50", "09:30"}, {"09:40", "10:20"},
            {"10:30", "11:10"}, {"11:20", "12:00"}, {"14:40", "15:20"},
            {"15:30", "16:10"}, {"16:20", "17:00"}, {"19:00", "19:40"},
            {"19:50", "20:30"}, {"20:40", "21:20"}, {"21:30", "22:10"},
    };

    /** 找这一格时间上重叠的已报名活动。 */
    private org.json.JSONObject findRegistered(Activity activity, String date, int pStart, int pEnd) {
        if (date == null) return null;
        try {
            org.json.JSONArray arr = new org.json.JSONArray(
                    activity.getSharedPreferences("app_meta", Activity.MODE_PRIVATE)
                            .getString("registered_acts", "[]"));
            int s1 = toMinutes(SLOTS[Math.max(0, Math.min(11, pStart))][0]);
            int e1 = toMinutes(SLOTS[Math.max(0, Math.min(11, pEnd))][1]);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                long start = o.optLong("start", 0);
                long end = o.optLong("end", 0);
                if (start <= 0) continue;
                java.util.Calendar c = java.util.Calendar.getInstance();
                c.setTimeInMillis(start * 1000);
                String d = String.format(java.util.Locale.CHINA, "%04d-%02d-%02d",
                        c.get(java.util.Calendar.YEAR),
                        c.get(java.util.Calendar.MONTH) + 1,
                        c.get(java.util.Calendar.DAY_OF_MONTH));
                if (!d.equals(date)) continue;
                int as = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
                java.util.Calendar ce = java.util.Calendar.getInstance();
                ce.setTimeInMillis(end * 1000);
                int ae = ce.get(java.util.Calendar.HOUR_OF_DAY) * 60 + ce.get(java.util.Calendar.MINUTE);
                if (as <= e1 && ae >= s1) return o;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static int toMinutes(String hhmm) {
        try {
            String[] p = hhmm.split(":");
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 点二课块：和「添加课程」一样的小窗，只放三个操作。 */
    void showActivityPopup(Activity activity, android.view.View anchor, final String id,
                           final String name, final long start) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(anchor, 12), dp(anchor, 12), dp(anchor, 12), dp(anchor, 6));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(anchor, 16));
        bg.setColor(0xFFFFFFFF);
        bg.setStroke(dp(anchor, 1), 0x1A000000);
        box.setBackground(bg);

        android.widget.PopupWindow popup = new android.widget.PopupWindow(box,
                Math.min(Math.round(activity.getResources().getDisplayMetrics().widthPixels * 0.62f),
                        dp(anchor, 260)),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        popup.setElevation(dp(anchor, 12));

        box.addView(popupItem(activity, "◎  签到签退", "打开官方活动页签到/签退", true, () -> {
            popup.dismiss();
            Intent it = new Intent(activity, ApplyWebViewActivity.class);
            it.putExtra(ApplyWebViewActivity.EXTRA_ID, id);
            activity.startActivity(it);
        }));

        box.addView(popupItem(activity, "🔔  时间提醒", "活动开始前 30 分钟提醒我", false, () -> {
            popup.dismiss();
            scheduleReminder(activity, id, name, start);
        }));

        box.addView(redItem(activity, "✕  取消报名", "跳转官方页面确认取消", () -> {
            popup.dismiss();
            Intent it = new Intent(activity, ApplyWebViewActivity.class);
            it.putExtra(ApplyWebViewActivity.EXTRA_URL,
                    "https://class.bigdata.ncvt.net/Student/Activity/applyCancel.html?activityID=" + id);
            activity.startActivity(it);
        }));

        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int sw = activity.getResources().getDisplayMetrics().widthPixels;
        int sh = activity.getResources().getDisplayMetrics().heightPixels;
        box.measure(android.view.View.MeasureSpec.makeMeasureSpec(sw, android.view.View.MeasureSpec.AT_MOST),
                android.view.View.MeasureSpec.makeMeasureSpec(sh, android.view.View.MeasureSpec.AT_MOST));
        int ph = box.getMeasuredHeight();
        int pw = box.getMeasuredWidth();
        int x = Math.min(loc[0] + dp(anchor, 8), sw - pw - dp(anchor, 8));
        int y = loc[1] + dp(anchor, 40);
        if (y + ph > sh - dp(anchor, 120)) y = Math.max(dp(anchor, 80), sh - ph - dp(anchor, 160));
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, Math.max(dp(anchor, 8), x), y);
    }

    /** 点课表里的二课块：签到签退 / 时间提醒 / 取消报名。 */
    void showActivityActions(Activity activity, String id, String name, long start) {
        final String[] items = {"签到签退", "时间提醒（活动前 30 分钟）", "取消报名"};
        new android.app.AlertDialog.Builder(activity)
                .setTitle(name)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        Intent it = new Intent(activity, ApplyWebViewActivity.class);
                        it.putExtra(ApplyWebViewActivity.EXTRA_ID, id);
                        activity.startActivity(it);
                    } else if (which == 1) {
                        scheduleReminder(activity, id, name, start);
                    } else {
                        Intent it = new Intent(activity, ApplyWebViewActivity.class);
                        it.putExtra(ApplyWebViewActivity.EXTRA_URL,
                                "https://class.bigdata.ncvt.net/Student/Activity/applyCancel.html?activityID=" + id);
                        activity.startActivity(it);
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void scheduleReminder(Activity activity, String id, String name, long startSec) {
        try {
            long at = startSec * 1000 - 30 * 60 * 1000L;
            if (at < System.currentTimeMillis() + 3000) at = System.currentTimeMillis() + 5000;
            Intent i = new Intent(activity, ActivityReminderReceiver.class);
            i.putExtra("id", id);
            i.putExtra("name", name);
            int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            if (android.os.Build.VERSION.SDK_INT >= 23) flags |= android.app.PendingIntent.FLAG_IMMUTABLE;
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                    activity, id == null ? 0 : id.hashCode(), i, flags);
            android.app.AlarmManager am =
                    (android.app.AlarmManager) activity.getSystemService(Activity.ALARM_SERVICE);
            if (am != null) {
                am.set(android.app.AlarmManager.RTC_WAKEUP, at, pi);
                android.widget.Toast.makeText(activity,
                        "已设置提醒：" + (name == null ? "活动" : name) + " 开始前 30 分钟",
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            android.widget.Toast.makeText(activity, "提醒设置失败", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** 红色文字的菜单项（取消报名）。 */
    private View redItem(Activity activity, String title, String subtitle, Runnable click) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(row, 14), dp(row, 10), dp(row, 18), dp(row, 10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(row, 10));
        bg.setColor(0x00FFFFFF);
        bg.setStroke(dp(row, 1), 0xFFDC2626);
        row.setBackground(bg);

        TextView t = new TextView(activity);
        t.setText(title);
        t.setTextSize(13.5f);
        t.setTextColor(0xFFDC2626);
        t.getPaint().setFakeBoldText(true);
        row.addView(t);

        TextView s = new TextView(activity);
        s.setText(subtitle);
        s.setTextSize(10.5f);
        s.setTextColor(0xFFB91C1C);
        row.addView(s);

        row.setOnClickListener(v -> click.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(row, 4);
        row.setLayoutParams(lp);
        return row;
    }

    private View popupItem(Activity activity, String title, String subtitle, boolean primary, Runnable click) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(row, 14), dp(row, 10), dp(row, 18), dp(row, 10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(row, 10));
        bg.setColor(primary ? 0xFF2563EB : 0x00000000);
        row.setBackground(bg);

        TextView t = new TextView(activity);
        t.setText(title);
        t.setTextSize(13.5f);
        t.setTextColor(primary ? Color.WHITE : 0xFF16202C);
        t.getPaint().setFakeBoldText(true);
        row.addView(t);

        TextView s = new TextView(activity);
        s.setText(subtitle);
        s.setTextSize(10.5f);
        s.setTextColor(primary ? 0xCCFFFFFF : 0xFF94A0B0);
        row.addView(s);

        row.setOnClickListener(v -> click.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(row, 2);
        row.setLayoutParams(lp);
        return row;
    }

    /** 在底部导航加一个「二课」入口（运行时注入，不动 menu 资源与 MainActivity）。 */
    private void installNavEntry(Activity activity, View root) {
        int navId = activity.getResources().getIdentifier("bottom_nav", "id", activity.getPackageName());
        if (navId == 0) return;
        View v = root.findViewById(navId);
        if (!(v instanceof BottomNavigationView)) return;
        final BottomNavigationView nav = (BottomNavigationView) v;
        if (NAV_HOOKED.containsKey(nav)) return;
        NAV_HOOKED.put(nav, Boolean.TRUE);

        android.view.Menu menu = nav.getMenu();
        if (menu.findItem(SC_TAB_ID) == null) {
            int settingsId = activity.getResources()
                    .getIdentifier("nav_settings", "id", activity.getPackageName());
            android.view.MenuItem settings = menu.findItem(settingsId);

            if (settings != null) {
                CharSequence title = settings.getTitle();
                android.graphics.drawable.Drawable icon = settings.getIcon();
                menu.removeItem(settingsId);

                android.view.MenuItem sc = menu.add(android.view.Menu.NONE, SC_TAB_ID, 0, "二课");
                sc.setIcon(com.ncvt.kebiao.R.drawable.ic_sc);

                android.view.MenuItem re = menu.add(android.view.Menu.NONE, settingsId, 0, title);
                if (icon != null) re.setIcon(icon);
            } else {
                android.view.MenuItem sc = menu.add(android.view.Menu.NONE, SC_TAB_ID, 0, "二课");
                sc.setIcon(com.ncvt.kebiao.R.drawable.ic_sc);
            }
        }

        final Object orig = field(nav, "selectedListener");
        nav.setOnItemSelectedListener(item -> {
            if (item.getItemId() == SC_TAB_ID) {
                activity.startActivity(new Intent(activity, SecondClassActivity.class));
                return false;   // 不占用选中态
            }
            if (orig instanceof NavigationBarView.OnItemSelectedListener) {
                boolean handled = ((NavigationBarView.OnItemSelectedListener) orig)
                        .onNavigationItemSelected(item);
                int weekId = activity.getResources().getIdentifier(
                        "nav_week_schedule", "id", activity.getPackageName());
                if (item.getItemId() == weekId) {
                    View update = findById(activity, root, "btn_update");
                    nav.postDelayed(() -> showUpdateHint(activity, update), 700);
                } else {
                    nav.postDelayed(() -> moveSaveButton(activity, root), 400);
                }
                return handled;
            }
            return false;
        });
    }

    /** 每次点击时实时判断是否还没配置账号。 */
    private boolean needSetupNow(Activity activity) {
        String account = activity.getSharedPreferences("kebiao_settings", Activity.MODE_PRIVATE)
                .getString("account", "");
        return account == null || account.trim().isEmpty();
    }

    private static final java.util.WeakHashMap<TextView, CharSequence> ORIG_DATE = new java.util.WeakHashMap<>();

    /** 本周有已报名二课活动的日期，在日期头下面标一个橙色「● 二课」。 */
    private void markRegisteredDays(Activity activity, View root) {
        try {
            View dates = findById(activity, root, "layout_week_dates");
            if (!(dates instanceof ViewGroup)) return;

            java.util.List<String> cols = new java.util.ArrayList<>();
            collectDateTexts((ViewGroup) dates, cols);
            if (cols.isEmpty()) return;

            java.util.Set<String> hit = new java.util.HashSet<>();
            org.json.JSONArray arr = new org.json.JSONArray(
                    activity.getSharedPreferences("app_meta", Activity.MODE_PRIVATE)
                            .getString("registered_acts", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                long start = o.optLong("start", 0);
                if (start <= 0) continue;
                java.util.Calendar c = java.util.Calendar.getInstance();
                c.setTimeInMillis(start * 1000);
                hit.add(String.format(java.util.Locale.CHINA, "%d", c.get(java.util.Calendar.DAY_OF_MONTH)));
            }
            if (hit.isEmpty()) return;

            java.util.List<TextView> cells = new java.util.ArrayList<>();
            collectTextViews((ViewGroup) dates, cells);
            for (TextView tv : cells) {
                Object tag = ORIG_DATE.get(tv);
                CharSequence base = tag instanceof CharSequence ? (CharSequence) tag : tv.getText();
                if (tag == null) ORIG_DATE.put(tv, base);

                String plain = base.toString();
                boolean marked = false;
                for (String d : hit) {
                    if (plain.contains(d)) { marked = true; break; }
                }
                if (!marked) {
                    tv.setText(base);
                    continue;
                }
                android.text.SpannableString sp = new android.text.SpannableString(plain + "\n● 二课");
                int startIdx = plain.length() + 1;
                sp.setSpan(new android.text.style.ForegroundColorSpan(0xFFF59E0B),
                        startIdx, sp.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sp.setSpan(new android.text.style.RelativeSizeSpan(0.75f),
                        startIdx, sp.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                tv.setText(sp);
            }
        } catch (Exception ignored) {
        }
    }

    private void collectTextViews(ViewGroup g, java.util.List<TextView> out) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c instanceof TextView) out.add((TextView) c);
            else if (c instanceof ViewGroup) collectTextViews((ViewGroup) c, out);
        }
    }

    private void collectDateTexts(ViewGroup g, java.util.List<String> out) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c instanceof TextView) out.add(((TextView) c).getText().toString());
            else if (c instanceof ViewGroup) collectDateTexts((ViewGroup) c, out);
        }
    }

    /** 设置页底部加「当前版本 + 检查更新」。 */
    private void installVersionBlock(Activity activity, View root) {
        if (root.findViewWithTag("versionBlock") != null) return;
        View save = findById(activity, root, "btn_save");
        if (save == null || !(save.getParent() instanceof LinearLayout)) return;
        LinearLayout parent = (LinearLayout) save.getParent();

        float d = activity.getResources().getDisplayMetrics().density;
        int pad = Math.round(8 * d);

        LinearLayout box = new LinearLayout(activity);
        box.setTag("versionBlock");
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, Math.round(18 * d), 0, 0);

        TextView ver = new TextView(activity);
        ver.setText("当前版本 " + ExtUi.VERSION);
        ver.setTextSize(12);
        ver.setTextColor(0xFF94A0B0);
        box.addView(ver);

        TextView btn = new TextView(activity);
        btn.setText("检查更新");
        btn.setTextSize(13);
        btn.setGravity(Gravity.CENTER);
        btn.setTextColor(0xFF2563EB);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Math.round(10 * d));
        bg.setColor(0xFFEAF1FF);
        bg.setStroke(Math.round(1 * d), 0xFF2563EB);
        btn.setBackground(bg);
        btn.setPadding(0, pad + Math.round(4 * d), 0, pad + Math.round(4 * d));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Math.round(8 * d);
        btn.setLayoutParams(blp);
        View.OnClickListener check = v -> UpdateChecker.checkManually(activity);
        btn.setOnClickListener(check);
        box.addView(btn);
        box.setOnClickListener(check);
        View.OnLongClickListener diag = v -> {
            new android.app.AlertDialog.Builder(activity)
                    .setTitle("更新源诊断")
                    .setMessage(UpdateChecker.diagText())
                    .setPositiveButton("好", null)
                    .show();
            return true;
        };
        btn.setOnLongClickListener(diag);
        box.setOnLongClickListener(diag);

        int saveIndex = parent.indexOfChild(save);
        if (saveIndex >= 0) {
            parent.addView(box, saveIndex + 1);
        } else {
            parent.addView(box);
        }
    }

    /** 把「保存设置」移到密码框正下方，提醒用户保存。 */
    private void moveSaveButton(Activity activity, View root) {
        String pkg = activity.getPackageName();
        View save = findById(activity, root, "btn_save");
        View pwd = findById(activity, root, "til_password");
        if (save == null || pwd == null) return;
        android.view.ViewParent sp = save.getParent();
        android.view.ViewParent pp = pwd.getParent();
        if (sp == null || sp != pp) return;
        android.view.ViewGroup parent = (android.view.ViewGroup) sp;
        int pwdIndex = parent.indexOfChild(pwd);
        if (pwdIndex < 0) return;
        int saveIndex = parent.indexOfChild(save);
        if (saveIndex == pwdIndex + 1) return;   // 已经就位
        android.view.ViewGroup.LayoutParams lp = save.getLayoutParams();
        parent.removeView(save);
        parent.addView(save, pwdIndex + 1, lp);
    }

    private View findById(Activity activity, View root, String name) {
        int id = activity.getResources().getIdentifier(name, "id", activity.getPackageName());
        return id == 0 ? null : root.findViewById(id);
    }

    private View findTimetable(Context ctx, View root) {
        int id = ctx.getResources().getIdentifier("timetableView", "id", ctx.getPackageName());
        if (id != 0) {
            View v = root.findViewById(id);
            if (v != null) return v;
        }
        return findByClass(root, "com.ncvt.kebiao.ui.timetable.TimetableView");
    }

    private View findByClass(View v, String className) {
        if (className.equals(v.getClass().getName())) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View hit = findByClass(g.getChildAt(i), className);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    /** 复刻 TimetableView 的命中判断：点到已排课程的卡片时返回 true。 */
    private boolean hitsCourse(View view, float x, float y) {
        Object areas = field(view, "hitAreas");
        if (!(areas instanceof List)) return false;
        Object weekObj = field(view, "currentWeekNumber");
        int week = weekObj instanceof Integer ? (Integer) weekObj : 1;
        for (Object area : (List<?>) areas) {
            if (area == null) continue;
            Object rect = field(area, "rect");
            if (!(rect instanceof RectF) || !((RectF) rect).contains(x, y)) continue;
            Object c = field(area, "course");
            if (c instanceof com.ncvt.kebiao.model.Course) {
                com.ncvt.kebiao.model.Course course = (com.ncvt.kebiao.model.Course) c;
                if (!weekMatches(course.weekPattern, week)) return false;
            }
            return true;
        }
        return false;
    }

    /** 由坐标算 (周几, 第几节)。 */
    private int[] cellAt(View view, float x, float y) {
        int labelW = dimen(view, "time_label_width", 48);
        int headerH = dimen(view, "header_height", 4);
        int cellH = dimen(view, "cell_height", 82);
        int cellW = (view.getWidth() - labelW) / 7;
        if (cellW <= 0) return null;

        Object scrollObj = field(view, "scrollYOffset");
        int scroll = scrollObj instanceof Integer ? (Integer) scrollObj : 0;

        int col = (int) ((x - labelW) / cellW);
        int row = (int) ((y + scroll - headerH) / cellH);
        if (col < 0 || col > 6 || row < 0 || row > 11) return null;
        return new int[]{col + 1, row};
    }

    /**
     * 找出被点这一节所在的「连续空课块」。
     * 例如 6-8 节都空，点第 8 节也应把 14:40~17:00 整段作为时间窗。
     */
    private int[] freeBlock(View view, int day, int period) {
        boolean[] busy = new boolean[12];
        Object coursesObj = field(view, "courses");
        Object weekObj = field(view, "currentWeekNumber");
        int week = weekObj instanceof Integer ? (Integer) weekObj : 1;
        if (coursesObj instanceof List) {
            for (Object o : (List<?>) coursesObj) {
                if (!(o instanceof com.ncvt.kebiao.model.Course)) continue;
                com.ncvt.kebiao.model.Course c = (com.ncvt.kebiao.model.Course) o;
                if (c.dayOfWeek != day) continue;
                if (!weekMatches(c.weekPattern, week)) continue;
                int from = Math.max(0, c.startPeriod - 1);
                int to = Math.min(11, c.startPeriod - 1 + Math.max(1, c.duration) - 1);
                for (int p = from; p <= to; p++) busy[p] = true;
            }
        }
        int s = Math.max(0, Math.min(11, period));
        int e = s;
        while (s - 1 >= 0 && !busy[s - 1]) s--;
        while (e + 1 < 12 && !busy[e + 1]) e++;
        return new int[]{s, e};
    }

    /** "1-16周" / "1-4周,6-7周,9-16周" / "3周" 这类周次模式。 */
    private static boolean weekMatches(String pattern, int week) {
        if (pattern == null || pattern.trim().isEmpty()) return true;
        String p = pattern.replace("周", "").replace(" ", "");
        for (String part : p.split("[,，、]")) {
            if (part.isEmpty()) continue;
            int dash = part.indexOf('-');
            try {
                if (dash > 0) {
                    int a = Integer.parseInt(part.substring(0, dash).replaceAll("[^0-9]", ""));
                    int b = Integer.parseInt(part.substring(dash + 1).replaceAll("[^0-9]", ""));
                    if (week >= Math.min(a, b) && week <= Math.max(a, b)) return true;
                } else {
                    int a = Integer.parseInt(part.replaceAll("[^0-9]", ""));
                    if (week == a) return true;
                }
            } catch (Exception ignored) {
                return true;   // 解析不了就当本周有课，宁可保守
            }
        }
        return false;
    }

    private int dp(View v, float dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                v.getResources().getDisplayMetrics()));
    }

    private int dimen(View view, String name, int fallbackDp) {
        int id = view.getResources().getIdentifier(name, "dimen", view.getContext().getPackageName());
        if (id != 0) return view.getResources().getDimensionPixelSize(id);
        return dp(view, fallbackDp);
    }

    private Object field(Object target, String name) {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }


    @Nullable @Override
    public Cursor query(@NonNull Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) { return null; }

    @Nullable @Override public String getType(@NonNull Uri uri) { return null; }

    @Nullable @Override
    public Uri insert(@NonNull Uri uri, ContentValues values) { return null; }

    @Override
    public int delete(@NonNull Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override
    public int update(@NonNull Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) { return 0; }
}
