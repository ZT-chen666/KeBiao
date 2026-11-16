package com.ncvt.kebiao.ext;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.text.SimpleDateFormat;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 空课 → 二课活动。
 *
 * 由 {@link EmptySlotBridge} 在课表点到空白格时拉起：按被点的那一格的时间窗，
 * 直接请求学校「第二课堂」官方接口，筛出时间冲突、还能报名的活动。
 *
 * 登录：首次使用会跳 {@link LoginWebViewActivity}，学生用学校账号登录一次，
 * 会话 cookie 存在本地，之后复用。
 *
 * 本类只新增，不参与课表原有逻辑。
 */
public class SecondClassActivity extends Activity {

    public static final String EXTRA_DAY = "day";        // 1=周一 .. 7=周日
    public static final String EXTRA_PERIOD = "period";  // 0..11
    public static final String EXTRA_CHOOSER = "chooser";// 空课时先让用户选：加课 / 找二课
    public static final String EXTRA_PERIOD_START = "pStart";  // 连续空课块起始节（0-based）
    public static final String EXTRA_PERIOD_END = "pEnd";      // 连续空课块结束节（0-based）
    public static final String EXTRA_DATE = "date";   // yyyy-MM-dd，所看周对应日期
    public static final String EXTRA_KEYWORDS = "kw";

    private static final String BASE = "https://class.bigdata.ncvt.net";
    private static final String LIST_API = BASE + "/Student/Activity/getActivityCanApply.html";
    private static final String DETAIL_API = BASE + "/Student/Activity/apply.html?activityID=";
    private static final String PREFS = LoginWebViewActivity.PREFS;
    private static final String KEY_COOKIE = LoginWebViewActivity.KEY_COOKIE;

    /** 与课表一致的节次时间（第 12 节无固定时间）。 */
    static final String[][] SLOTS = {
            {"1", "08:00", "08:40"}, {"2", "08:50", "09:30"}, {"3", "09:40", "10:20"},
            {"4", "10:30", "11:10"}, {"5", "11:20", "12:00"}, {"6", "14:40", "15:20"},
            {"7", "15:30", "16:10"}, {"8", "16:20", "17:00"}, {"9", "19:00", "19:40"},
            {"10", "19:50", "20:30"}, {"11", "20:40", "21:20"}, {"12", "", ""},
    };

    private final OkHttpClient http = new OkHttpClient();
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout listBox;
    private TextView tvSubtitle;
    private TextView tvStatus;

    private int day;
    private int period;
    private int periodStart;
    private int periodEnd;
    private String filter = "time";
    private String keywords = "";
    private JSONArray activities = new JSONArray();
    private long slotStart;
    private long slotEnd;

    private final List<TextView> chips = new ArrayList<>();
    private String currentDetailId = "";
    private boolean standalone;
    private String lastPlace = "";
    private boolean tintedCard;
    private boolean loading;   // 当前卡片是否着了底色
    private String dateText;          // 所看周次这一列的真实日期 yyyy-MM-dd

    private final java.util.Map<String, int[]> quotaMap = new java.util.HashMap<>();
    private final java.util.Set<String> rosterSet = new java.util.HashSet<>();
    private final java.util.Map<String, Integer> moduleGap = new java.util.HashMap<>();
    private boolean extrasLoaded;
    private String filterQuota = "";    // "" | has | none
    private String filterRoster = "";   // "" | has | none
    private String filterModule = "";   // "" = 全部
    private String filterStatus = "";   // "" = 全部
    private boolean sortByScore;        // false=按截止时间   // 从底部「二课」进入 → 无具体空课时段

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        day = getIntent().getIntExtra(EXTRA_DAY, 1);
        period = getIntent().getIntExtra(EXTRA_PERIOD, 0);
        periodStart = getIntent().getIntExtra(EXTRA_PERIOD_START, period);
        periodEnd = getIntent().getIntExtra(EXTRA_PERIOD_END, period);
        if (periodEnd < periodStart) periodEnd = periodStart;
        dateText = getIntent().getStringExtra(EXTRA_DATE);
        standalone = !getIntent().hasExtra(EXTRA_DAY);
        if (standalone) filter = "open";
        keywords = getIntent().getStringExtra(EXTRA_KEYWORDS);
        if (keywords == null) keywords = "";
        setContentView(buildRoot());
        ExtUi.applyInsets(this);
        computeWindow();
        if (getIntent().getBooleanExtra(EXTRA_CHOOSER, false)) {
            showChooser();
        } else if (cookie().isEmpty()) {
            showLoginCard();
        } else {
            load();
        }
    }

    /** 空课时的两个选择：加自己的课 / 找这个时段能报的二课。 */
    private void showChooser() {
        listBox.removeAllViews();
        tvStatus.setText("这格是空的，你想做什么？");
        listBox.addView(optionCard("＋  添加课程",
                "把这节课加进课表（用课表右上角的 + 也可以）", false, v -> {
                    finish();
                }));
        listBox.addView(optionCard("◎  寻找二课活动",
                "看看这个时段有哪些能报的二课活动", true, v -> {
                    tvStatus.setText("");
                    if (cookie().isEmpty()) showLoginCard(); else load();
                }));
    }

    private View optionCard(String title, String subtitle, boolean primary, View.OnClickListener click) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(primary ? color(com.ncvt.kebiao.R.color.sc_primary)
                : color(com.ncvt.kebiao.R.color.sc_surface));
        bg.setStroke(dp(1), primary ? color(com.ncvt.kebiao.R.color.sc_primary)
                : color(com.ncvt.kebiao.R.color.sc_line));
        card.setBackground(bg);
        card.setElevation(dp(2));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16);
        t.setTextColor(primary ? Color.WHITE : color(com.ncvt.kebiao.R.color.sc_text));
        t.getPaint().setFakeBoldText(true);
        card.addView(t);

        TextView s = new TextView(this);
        s.setText(subtitle);
        s.setTextSize(12.5f);
        s.setTextColor(primary ? 0xCCFFFFFF : color(com.ncvt.kebiao.R.color.sc_text_dim));
        s.setPadding(0, dp(6), 0, 0);
        card.addView(s);

        card.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        card.setLayoutParams(lp);
        return card;
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }

    private String cookie() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String ck = sp.getString(KEY_COOKIE, "");
        return ck == null ? "" : ck;
    }


    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private int color(int id) {
        return getResources().getColor(id, getTheme());
    }

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(color(com.ncvt.kebiao.R.color.sc_bg));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(color(com.ncvt.kebiao.R.color.sc_surface));
        bar.setPadding(dp(12), dp(14), dp(12), dp(14));
        bar.setElevation(dp(2));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(26);
        back.setTextColor(color(com.ncvt.kebiao.R.color.sc_primary));
        back.setPadding(dp(4), 0, dp(12), 0);
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("空课 · 找二课");
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setTextSize(16.5f);
        title.setTextColor(color(com.ncvt.kebiao.R.color.sc_text));
        title.getPaint().setFakeBoldText(true);
        titles.addView(title);
        tvSubtitle = new TextView(this);
        tvSubtitle.setSingleLine(true);
        tvSubtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvSubtitle.setTextSize(11.5f);
        tvSubtitle.setTextColor(color(com.ncvt.kebiao.R.color.sc_text_dim));
        titles.addView(tvSubtitle);
        bar.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView navProj = textBtn("项目", v -> startActivity(new Intent(this, ProjectListActivity.class)));
        bar.addView(navProj);
        TextView navStudy = textBtn("学习", v -> startActivity(new Intent(this, StudyActivity.class)));
        bar.addView(navStudy);
        TextView navMine = textBtn("我的", v -> startActivity(new Intent(this, MyScoreActivity.class)));
        bar.addView(navMine);

        TextView account = new TextView(this);
        account.setText("账号");
        account.setTextSize(12.5f);
        account.setTextColor(color(com.ncvt.kebiao.R.color.sc_primary));
        GradientDrawable ab = new GradientDrawable();
        ab.setCornerRadius(dp(16));
        ab.setColor(color(com.ncvt.kebiao.R.color.sc_primary_soft));
        account.setBackground(ab);
        account.setPadding(dp(12), dp(6), dp(12), dp(6));
        account.setOnClickListener(v -> showLoginPrompt());
        bar.addView(account);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout chipsRow = new LinearLayout(this);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        chipsRow.setPadding(dp(16), dp(10), dp(16), dp(6));
        addChip(chipsRow, "本时段", "time");
        addChip(chipsRow, "当天全部", "day");
        addChip(chipsRow, "报名中", "open");
        paintChips();
        root.addView(chipsRow);

        android.widget.HorizontalScrollView ddScroll = new android.widget.HorizontalScrollView(this);
        ddScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout dd = new LinearLayout(this);
        dd.setOrientation(LinearLayout.HORIZONTAL);
        dd.setPadding(dp(16), 0, dp(16), dp(6));
        addPicker(dd, "模块", () -> filterModule.isEmpty() ? "全部模块" : filterModule, () -> {
            java.util.List<String> opts = new java.util.ArrayList<>();
            java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
            for (int i = 0; i < activities.length(); i++) {
                JSONObject o = activities.optJSONObject(i);
                if (o != null) set.add(o.optString("moduleName"));
            }
            opts.add("全部模块");
            opts.addAll(set);
            return opts;
        }, v -> filterModule = "全部模块".equals(v) ? "" : v);
        addPicker(dd, "状态", () -> filterStatus.isEmpty() ? "全部状态" : statusLabel(filterStatus), () -> {
            java.util.List<String> opts = new java.util.ArrayList<>();
            opts.add("全部状态");
            for (String code : statusCodes()) {
                String label = statusLabel(code);
                if (!opts.contains(label)) opts.add(label);
            }
            return opts;
        }, v -> filterStatus = "全部状态".equals(v) ? "" : codeOfStatus(v));
        addPicker(dd, "名单", () -> filterRoster.isEmpty() ? "名单：全部"
                        : ("has".equals(filterRoster) ? "有名单" : "无名单"),
                () -> java.util.Arrays.asList("名单：全部", "有名单", "无名单"),
                v -> filterRoster = "有名单".equals(v) ? "has" : ("无名单".equals(v) ? "none" : ""));
        addPicker(dd, "名额", () -> filterQuota.isEmpty() ? "名额：全部"
                        : ("has".equals(filterQuota) ? "有名额" : "无名额"),
                () -> java.util.Arrays.asList("名额：全部", "有名额", "无名额"),
                v -> filterQuota = "有名额".equals(v) ? "has" : ("无名额".equals(v) ? "none" : ""));
        ddScroll.addView(dd);
        root.addView(ddScroll);

        android.widget.HorizontalScrollView sortScroll = new android.widget.HorizontalScrollView(this);
        sortScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout sortRow = new LinearLayout(this);
        sortRow.setOrientation(LinearLayout.HORIZONTAL);
        sortRow.setPadding(dp(16), 0, dp(16), dp(6));
        addSortChip(sortRow, "按截止时间", false);
        addSortChip(sortRow, "按积分", true);
        sortScroll.addView(sortRow);
        root.addView(sortScroll);

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(dp(16), dp(2), dp(16), dp(8));
        android.widget.EditText searchBox = new android.widget.EditText(this);
        searchBox.setHint("搜二课活动 / 主办方");
        searchBox.setTextSize(13);
        searchBox.setSingleLine(true);
        searchBox.setText(keywords);
        searchBox.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        GradientDrawable sb = new GradientDrawable();
        sb.setCornerRadius(dp(10));
        sb.setColor(color(com.ncvt.kebiao.R.color.sc_surface));
        sb.setStroke(dp(1), color(com.ncvt.kebiao.R.color.sc_line));
        searchBox.setBackground(sb);
        searchBox.setPadding(dp(12), dp(8), dp(12), dp(8));
        searchRow.addView(searchBox, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView go = new TextView(this);
        go.setText("搜索");
        go.setTextSize(13);
        go.setTextColor(color(com.ncvt.kebiao.R.color.sc_primary));
        go.setPadding(dp(12), dp(8), dp(4), dp(8));
        go.getPaint().setFakeBoldText(true);
        searchRow.addView(go);
        View.OnClickListener doSearch = v -> {
            keywords = searchBox.getText().toString().trim();
            if (cookie().isEmpty()) showLoginCard(); else load();
        };
        go.setOnClickListener(doSearch);
        searchBox.setOnEditorActionListener((v, actionId, ev) -> {
            doSearch.onClick(v);
            return true;
        });
        root.addView(searchRow);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(12);
        tvStatus.setTextColor(color(com.ncvt.kebiao.R.color.sc_text_hint));
        tvStatus.setPadding(dp(16), dp(2), dp(16), dp(6));
        root.addView(tvStatus);

        ScrollView scroll = new ScrollView(this);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(dp(12), dp(2), dp(12), dp(24));
        scroll.addView(listBox, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView footer = new TextView(this);
        footer.setText("仅供学习参考 · 完全免费，任何售卖都是骗子 · 关于");
        footer.setTextSize(10.5f);
        footer.setGravity(Gravity.CENTER);
        footer.setTextColor(color(com.ncvt.kebiao.R.color.sc_text_hint));
        footer.setPadding(dp(12), dp(8), dp(12), dp(10));
        footer.setOnClickListener(v -> startActivity(new Intent(this, AboutActivity.class)));
        root.addView(footer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    private void addChip(LinearLayout parent, String text, String key) {
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextSize(12.5f);
        chip.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        chip.setLayoutParams(lp);
        chips.add(chip);
        chip.setOnClickListener(v -> {
            filter = key;
            paintChips();
            render();
        });
        parent.addView(chip);
    }

    private void paintChips() {
        String[] keys = {"time", "day", "open"};
        for (int i = 0; i < chips.size() && i < keys.length; i++) {
            TextView c = chips.get(i);
            boolean on = keys[i].equals(filter);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(16));
            bg.setColor(on ? color(com.ncvt.kebiao.R.color.sc_primary)
                    : color(com.ncvt.kebiao.R.color.sc_primary_soft));
            c.setBackground(bg);
            c.setTextColor(on ? color(com.ncvt.kebiao.R.color.sc_surface)
                    : color(com.ncvt.kebiao.R.color.sc_primary));
        }
    }


    private void computeWindow() {
        if (standalone) {
            tvSubtitle.setText("全部二课活动 · 报名中");
            slotStart = 0;
            slotEnd = 0;
            return;
        }
        String[] first = SLOTS[Math.max(0, Math.min(SLOTS.length - 1, periodStart))];
        String[] last = SLOTS[Math.max(0, Math.min(SLOTS.length - 1, periodEnd))];
        Calendar c = Calendar.getInstance();
        if (dateText != null && dateText.length() >= 10) {
            try {
                String[] ym = dateText.split("-");
                c.set(Integer.parseInt(ym[0]), Integer.parseInt(ym[1]) - 1,
                        Integer.parseInt(ym[2]), 0, 0, 0);
                c.set(Calendar.MILLISECOND, 0);
            } catch (Exception ignored) {
            }
        } else {
            int todayMon = c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY ? 7
                    : c.get(Calendar.DAY_OF_WEEK) - 1;
            c.add(Calendar.DAY_OF_MONTH, day - todayMon);
        }

        int startMin = parseMinutes(first[1], 8 * 60);
        int endMin = parseMinutes(last[2], 9 * 60);
        Calendar s = (Calendar) c.clone();
        s.set(Calendar.HOUR_OF_DAY, startMin / 60);
        s.set(Calendar.MINUTE, startMin % 60);
        s.set(Calendar.SECOND, 0);
        Calendar e = (Calendar) c.clone();
        e.set(Calendar.HOUR_OF_DAY, endMin / 60);
        e.set(Calendar.MINUTE, endMin % 60);
        e.set(Calendar.SECOND, 59);
        slotStart = s.getTimeInMillis() / 1000;
        slotEnd = e.getTimeInMillis() / 1000;

        String week = "周" + "一二三四五六日".charAt(day - 1);
        String periods = periodStart == periodEnd
                ? "第" + (periodStart + 1) + "节"
                : "第" + (periodStart + 1) + "-" + (periodEnd + 1) + "节";
        String range = first[1].isEmpty() ? "时间未定" : first[1] + "–" + last[2];
        tvSubtitle.setText(week + " " + periods + " · " + range);
    }

    private static int parseMinutes(String hhmm, int fallback) {
        try {
            String[] p = hhmm.split(":");
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (Exception e) {
            return fallback;
        }
    }


    private void showLoginPrompt() {
        startActivityForResult(new Intent(this, LoginWebViewActivity.class), 1001);
    }

    /** 未登录时在列表区显示登录卡片（不弹窗，更稳）。 */
    private void showLoginCard() {
        listBox.removeAllViews();
        tvStatus.setText("需要登录后才能查二课活动");

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(color(com.ncvt.kebiao.R.color.sc_surface));
        card.setBackground(bg);
        card.setElevation(dp(2));

        TextView t1 = new TextView(this);
        t1.setText("登录二课");
        t1.setTextSize(16);
        t1.setTextColor(color(com.ncvt.kebiao.R.color.sc_text));
        t1.getPaint().setFakeBoldText(true);
        card.addView(t1);

        TextView t2 = new TextView(this);
        t2.setText("用学校账号登录一次即可，之后点空课就能直接看这个时段能报的活动。");
        t2.setTextSize(12.5f);
        t2.setTextColor(color(com.ncvt.kebiao.R.color.sc_text_dim));
        t2.setPadding(0, dp(6), 0, dp(14));
        card.addView(t2);

        TextView btn = new TextView(this);
        btn.setText("去登录");
        btn.setTextSize(14);
        btn.setTextColor(color(com.ncvt.kebiao.R.color.sc_surface));
        btn.setGravity(Gravity.CENTER);
        GradientDrawable bb = new GradientDrawable();
        bb.setCornerRadius(dp(10));
        bb.setColor(color(com.ncvt.kebiao.R.color.sc_primary));
        btn.setBackground(bb);
        btn.setPadding(0, dp(10), 0, dp(10));
        btn.setOnClickListener(v -> showLoginPrompt());
        card.addView(btn);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        listBox.addView(card, lp);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == 1001) {
            if (!cookie().isEmpty()) load();
            else tvStatus.setText("还没登录成功");
        }
    }



    private long lastPauseAt;

    @Override
    protected void onPause() {
        super.onPause();
        lastPauseAt = System.currentTimeMillis();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (lastPauseAt > 0 && System.currentTimeMillis() - lastPauseAt > 1500) {
            lastPauseAt = 0;
            load();
        }
    }
    private void load() {
        loading = true;
        tvStatus.setText("正在获取二课活动…");
        listBox.removeAllViews();
        showLoading();
        ui.postDelayed(() -> {
            if (loading) failLoad("加载超时，请检查网络后点「搜索」重试");
        }, 12000);
        final String ck = cookie();
        pool.execute(() -> {
            String body = null;
            String err = null;
            try {
                Request req = new Request.Builder()
                        .url(LIST_API)
                        .header("Cookie", ck)
                        .header("Referer", BASE + "/Student/Activity/index.html")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .post(new FormBody.Builder()
                                .add("moduleID", "").add("typeID", "").add("keywords", keywords)
                                .add("sortByTime", "").add("sortByScore", "").build())
                        .build();
                try (Response resp = http.newCall(req).execute()) {
                    if (resp.body() != null) body = resp.body().string();
                }
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String b = body, e = err;
            ui.post(() -> {
                if (b == null) {
                    failLoad("网络错误：" + e);
                    return;
                }
                String t = b.trim();
                if (!t.startsWith("[")) {
                    loading = false;
                    tvStatus.setText("会话已过期，请重新登录");
                    listBox.removeAllViews();
                    showLoginCard();
                    return;
                }
                try {
                    activities = new JSONArray(t);
                    if (!extrasLoaded) {
                        extrasLoaded = true;
                        loadScore();
                    }
                    loadDetails();
                } catch (Exception ex) {
                    tvStatus.setText("解析失败：" + ex.getMessage());
                }
            });
        });
    }

    private boolean overlaps(long aStart, long aEnd) {
        if (aStart <= 0) return false;
        if (aEnd <= 0) aEnd = aStart;
        return aStart <= slotEnd && aEnd >= slotStart;
    }

    private List<JSONObject> filtered() {
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < activities.length(); i++) {
            JSONObject o = activities.optJSONObject(i);
            if (o == null) continue;
            long s = o.optLong("startDate", 0), e = o.optLong("endDate", 0);
            String st = o.optString("status2", "");
            boolean ok;
            if ("open".equals(filter)) ok = "2".equals(st);
            else if ("day".equals(filter)) ok = sameDay(s, slotStart) || overlaps(s, e);
            else ok = overlaps(s, e);
            if (!ok) continue;
            String aid = o.optString("activityID");
            if (!filterModule.isEmpty() && !filterModule.equals(o.optString("moduleName"))) continue;
            if (!filterStatus.isEmpty() && !filterStatus.equals(o.optString("status2"))) continue;
            if (!filterQuota.isEmpty()) {
                int[] q = quotaMap.get(aid);
                if (q == null || q[0] <= 0) continue;
                boolean none = q[1] >= q[0];
                if ("none".equals(filterQuota) != none) continue;
            }
            if ("has".equals(filterRoster) && !rosterSet.contains(aid)) continue;
            out.add(o);
        }
        if (sortByScore) {
            out.sort((a, b) -> b.optInt("score") - a.optInt("score"));
        } else {
            out.sort((a, b) -> Long.compare(a.optLong("applyEndDate", 0), b.optLong("applyEndDate", 0)));
        }
        return out;
    }

    private static boolean sameDay(long ts, long ref) {
        if (ts <= 0) return false;
        Calendar a = Calendar.getInstance();
        a.setTimeInMillis(ts * 1000);
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(ref * 1000);
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    /** 失败也要退出加载态，别一直转圈。 */
    private void failLoad(String msg) {
        loading = false;
        tvStatus.setText(msg);
        listBox.removeAllViews();
        TextView tv = new TextView(this);
        tv.setText(msg + "\n\n点上方「搜索」重新加载");
        tv.setTextSize(12.5f);
        tv.setTextColor(color(com.ncvt.kebiao.R.color.sc_text_hint));
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(24), dp(36), dp(24), 0);
        listBox.addView(tv);
    }

    /** 数据没回来之前只显示加载动画，不摆空卡片。 */
    private void showLoading() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(0, dp(48), 0, 0);

        android.widget.ProgressBar pb = new android.widget.ProgressBar(this);
        pb.setIndeterminate(true);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(34), dp(34));
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(pb, plp);

        TextView tv = new TextView(this);
        tv.setText("正在加载二课活动…");
        tv.setTextSize(12.5f);
        tv.setTextColor(color(com.ncvt.kebiao.R.color.sc_text_hint));
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(10), 0, 0);
        box.addView(tv);

        listBox.addView(box);
    }

    private void render() {
        loading = false;
        listBox.removeAllViews();
        List<JSONObject> items = filtered();
        if (standalone || "open".equals(filter)) {
            tvStatus.setText(items.size() + " 个活动正在报名");
        } else if ("day".equals(filter)) {
            tvStatus.setText(items.size() + " 个活动在当天");
        } else {
            tvStatus.setText("本时段有 " + items.size() + " 个活动");
        }
        if (items.isEmpty() && !loading) {
            TextView empty = new TextView(this);
            empty.setText("time".equals(filter)
                    ? "这个空档没有活动，试试「当天全部」或「报名中」"
                    : "没有符合条件的活动");
            empty.setTextColor(color(com.ncvt.kebiao.R.color.sc_text_dim));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(40), 0, 0);
            listBox.addView(empty);
            return;
        }
        for (JSONObject o : items) listBox.addView(buildCard(o));
    }

    private static String hhmm(long ts) {
        if (ts <= 0) return "-";
        return new SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(new Date(ts * 1000));
    }

    private long parseEpoch(String v) {
        if (v == null) return 0L;
        String t = v.trim();
        if (t.isEmpty()) return 0L;
        try {
            return (long) Double.parseDouble(t);
        } catch (Exception e) {
            return 0L;
        }
    }

    private View buildCard(JSONObject o) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        String cardId = o.optString("activityID");
        int[] q0 = quotaMap.get(cardId);
        boolean noQuota = q0 != null && q0[0] > 0 && q0[1] >= q0[0];
        boolean hasRoster = rosterSet.contains(cardId)
                || o.optString("activityName").contains("已有名单");

        long nowSec = System.currentTimeMillis() / 1000L;
        long applyEnd = parseEpoch(o.optString("applyEndDate"));
        boolean applyClosed = applyEnd > 0 && nowSec > applyEnd;

        int cardBg;
        int cardStroke;
        if (applyClosed) {
            cardBg = color(com.ncvt.kebiao.R.color.sc_amber_bg);
            cardStroke = color(com.ncvt.kebiao.R.color.sc_amber_line);
        } else if (noQuota) {
            cardBg = color(com.ncvt.kebiao.R.color.sc_red_bg);
            cardStroke = 0x66DC2626;
        } else if (hasRoster) {
            cardBg = color(com.ncvt.kebiao.R.color.sc_purple_bg);
            cardStroke = 0x667C3AED;
        } else {
            cardBg = color(com.ncvt.kebiao.R.color.sc_surface);
            cardStroke = color(com.ncvt.kebiao.R.color.sc_line);
        }
        tintedCard = noQuota || hasRoster;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(cardBg);
        bg.setStroke(dp(1), cardStroke);
        card.setBackground(bg);
        card.setElevation(dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        card.setLayoutParams(lp);

        String module = o.optString("moduleName");

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(pill(module.isEmpty() ? "二课" : module, moduleColor(module), moduleBg(module)));
        TextView score = new TextView(this);
        score.setText(o.optInt("score") + " 分");
        score.setTextSize(14);
        score.setTextColor(color(com.ncvt.kebiao.R.color.sc_primary));
        score.getPaint().setFakeBoldText(true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(8);
        top.addView(score, slp);
        card.addView(top);

        TextView name = new TextView(this);
        name.setText(o.optString("activityName"));
        name.setTextSize(15.5f);
        name.setTextColor(color(com.ncvt.kebiao.R.color.sc_text));
        name.getPaint().setFakeBoldText(true);
        name.setPadding(0, dp(8), 0, dp(2));
        card.addView(name);

        TextView org = new TextView(this);
        org.setText("主办 · " + o.optString("organizerName"));
        org.setTextSize(12);
        org.setTextColor(color(com.ncvt.kebiao.R.color.sc_text_dim));
        card.addView(org);

        LinearLayout times = new LinearLayout(this);
        times.setOrientation(LinearLayout.VERTICAL);
        times.setPadding(0, dp(8), 0, 0);
        times.addView(timeLine("报名", o.optLong("applyStartDate", 0), o.optLong("applyEndDate", 0)));
        times.addView(timeLine("活动", o.optLong("startDate", 0), o.optLong("endDate", 0)));
        card.addView(times);

        FlowLayout tags = new FlowLayout(this);
        tags.setPadding(0, dp(8), 0, 0);
        tags.addView(pill(o.optString("status2Name"), color(com.ncvt.kebiao.R.color.sc_ok),
                color(com.ncvt.kebiao.R.color.sc_ok_bg)));
        if ("1".equals(o.optString("checkAfterApply")))
            tags.addView(pill("审核制", color(com.ncvt.kebiao.R.color.sc_warn),
                    color(com.ncvt.kebiao.R.color.sc_warn_bg)));
        if (hasRoster)
            tags.addView(pill("有名单", color(com.ncvt.kebiao.R.color.sc_purple),
                    color(com.ncvt.kebiao.R.color.sc_purple_bg)));
        Integer gap = moduleGap.get(module);
        if (gap != null) {
            if (gap <= 0) tags.addView(pill("模块已达标", color(com.ncvt.kebiao.R.color.sc_ok),
                    color(com.ncvt.kebiao.R.color.sc_ok_bg)));
            else tags.addView(pill("模块差 " + gap + " 分", color(com.ncvt.kebiao.R.color.sc_warn),
                    color(com.ncvt.kebiao.R.color.sc_warn_bg)));
        }
        String aid = o.optString("activityID");
        int[] qq = quotaMap.get(aid);
        if (qq != null && qq[0] > 0) {
            int left = Math.max(0, qq[0] - qq[1]);
            if (left <= 0) tags.addView(pill("已无名额", color(com.ncvt.kebiao.R.color.sc_red),
                    color(com.ncvt.kebiao.R.color.sc_red_bg)));
            else tags.addView(pill("剩 " + left + " 名额", color(com.ncvt.kebiao.R.color.sc_ok),
                    color(com.ncvt.kebiao.R.color.sc_ok_bg)));
        }

        long end = o.optLong("applyEndDate", 0);
        if (end > 0) {
            long left = end - System.currentTimeMillis() / 1000;
            if (left > 0 && left <= 86400) {
                String t = left >= 3600 ? "距截止 " + (left / 3600) + " 小时" : "距截止 " + (left / 60) + " 分";
                tags.addView(pill(t, color(com.ncvt.kebiao.R.color.sc_red),
                        color(com.ncvt.kebiao.R.color.sc_red_bg)));
            }
        }
        card.addView(tags);

        card.setOnClickListener(v -> openDetail(o.optString("activityID"), o.optString("activityName")));

        if (applyClosed) {
            FrameLayout wrap = new FrameLayout(this);
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            wlp.bottomMargin = dp(10);
            wrap.setLayoutParams(wlp);
            card.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            wrap.addView(card);

            TextView badge = new TextView(this);
            badge.setText("已超出报名时间");
            badge.setTextSize(10f);
            badge.setTextColor(0xFFFFFFFF);
            badge.getPaint().setFakeBoldText(true);
            GradientDrawable bbg = new GradientDrawable();
            bbg.setCornerRadii(new float[]{0, 0, dp(14), dp(14), 0, 0, dp(12), dp(12)});
            bbg.setColor(0xFFF59E0B);
            badge.setBackground(bbg);
            badge.setPadding(dp(9), dp(3), dp(9), dp(3));
            badge.setElevation(dp(6));
            wrap.addView(badge, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END));

            tintedCard = false;
            return wrap;
        }

        tintedCard = false;
        return card;
    }

    private void addPicker(LinearLayout parent, String name,
                           java.util.function.Supplier<String> current,
                           java.util.function.Supplier<java.util.List<String>> options,
                           java.util.function.Consumer<String> onPick) {
        TextView tv = new TextView(this);
        tv.setText(current.get() + " ▾");
        tv.setTextSize(12);
        tv.setTextColor(color(com.ncvt.kebiao.R.color.sc_text_dim));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(color(com.ncvt.kebiao.R.color.sc_surface));
        bg.setStroke(dp(1), color(com.ncvt.kebiao.R.color.sc_line));
        tv.setBackground(bg);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setPadding(dp(10), dp(5), dp(10), dp(5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        tv.setLayoutParams(lp);
        tv.setOnClickListener(v -> {
            java.util.List<String> opts = options.get();
            new AlertDialog.Builder(this)
                    .setItems(opts.toArray(new String[0]), (d, which) -> {
                        onPick.accept(opts.get(which));
                        tv.setText(current.get() + " ▾");
                        render();
                    })
                    .show();
        });
        parent.addView(tv);
    }

    private void addSortChip(LinearLayout parent, String text, boolean scoreMode) {
        TextView tv = new TextView(this);
        boolean on = sortByScore == scoreMode;
        tv.setText(text);
        tv.setTextSize(12);
        tv.setPadding(dp(10), dp(5), dp(10), dp(5));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(on ? color(com.ncvt.kebiao.R.color.sc_primary)
                : color(com.ncvt.kebiao.R.color.sc_surface));
        bg.setStroke(dp(1), on ? color(com.ncvt.kebiao.R.color.sc_primary)
                : color(com.ncvt.kebiao.R.color.sc_line));
        tv.setBackground(bg);
        tv.setTextColor(on ? Color.WHITE : color(com.ncvt.kebiao.R.color.sc_text_dim));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        tv.setLayoutParams(lp);
        tv.setOnClickListener(v -> {
            if (sortByScore == scoreMode) return;
            sortByScore = scoreMode;
            rebuildFilters();
            render();
        });
        parent.addView(tv);
    }

    /** 简单起见：排序切换后整页重建筛选区。 */
    private void rebuildFilters() {
        View rootView = findViewById(android.R.id.content);
        if (rootView instanceof ViewGroup && ((ViewGroup) rootView).getChildCount() > 0) {
            ViewGroup root = (ViewGroup) ((ViewGroup) rootView).getChildAt(0);
            for (int i = 0; i < root.getChildCount(); i++) {
                View child = root.getChildAt(i);
                if (child instanceof LinearLayout && child.getTag() != null
                        && "filters".equals(child.getTag())) {
                    root.removeViewAt(i);
                    break;
                }
            }
        }
        render();
    }

    private java.util.List<String> statusCodes() {
        java.util.List<String> codes = new java.util.ArrayList<>();
        for (int i = 0; i < activities.length(); i++) {
            JSONObject o = activities.optJSONObject(i);
            if (o == null) continue;
            String c = o.optString("status2");
            if (!c.isEmpty() && !codes.contains(c)) codes.add(c);
        }
        return codes;
    }

    private String statusLabel(String code) {
        for (int i = 0; i < activities.length(); i++) {
            JSONObject o = activities.optJSONObject(i);
            if (o != null && code.equals(o.optString("status2"))) return o.optString("status2Name");
        }
        return code;
    }

    private String codeOfStatus(String label) {
        for (int i = 0; i < activities.length(); i++) {
            JSONObject o = activities.optJSONObject(i);
            if (o != null && label.equals(o.optString("status2Name"))) return o.optString("status2");
        }
        return "";
    }


    private TextView textBtn(String text, View.OnClickListener click) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12.5f);
        tv.setTextColor(color(com.ncvt.kebiao.R.color.sc_primary));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(color(com.ncvt.kebiao.R.color.sc_primary_soft));
        tv.setBackground(bg);
        tv.setPadding(dp(9), dp(5), dp(9), dp(5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        tv.setLayoutParams(lp);
        tv.setOnClickListener(click);
        return tv;
    }

    private TextView timeLine(String label, long a, long b) {
        TextView tv = new TextView(this);
        tv.setText(label + "  " + hhmm(a) + "  →  " + hhmm(b));
        tv.setTextSize(12);
        tv.setTextColor(color(com.ncvt.kebiao.R.color.sc_text_dim));
        tv.setPadding(0, dp(2), 0, 0);
        return tv;
    }

    private int moduleColor(String m) {
        if (m == null) return color(com.ncvt.kebiao.R.color.sc_primary);
        if (m.startsWith("公益")) return color(com.ncvt.kebiao.R.color.sc_ok);
        if (m.startsWith("健康")) return 0xFFD97706;
        if (m.startsWith("文化")) return 0xFFDB2777;
        if (m.startsWith("双创")) return color(com.ncvt.kebiao.R.color.sc_purple);
        if (m.startsWith("实践")) return 0xFF0891B2;
        if (m.startsWith("网络")) return 0xFF2563EB;
        if (m.startsWith("职业")) return 0xFF4F46E5;
        return color(com.ncvt.kebiao.R.color.sc_primary);
    }

    private int moduleBg(String m) {
        int c = moduleColor(m);
        return Color.argb(24, Color.red(c), Color.green(c), Color.blue(c));
    }

    private TextView pill(String text, int fg, int bgColor) {
        TextView tv = new TextView(this);
        tv.setText(text == null ? "" : text);
        tv.setTextSize(11);
        tv.setSingleLine(true);
        tv.setEllipsize(null);
        tv.setTextColor(fg);
        tv.setPadding(dp(8), dp(3), dp(8), dp(3));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(9));
        bg.setColor(tintedCard ? Color.WHITE : bgColor);
        bg.setStroke(dp(1), Color.argb(70, Color.red(fg), Color.green(fg), Color.blue(fg)));
        tv.setBackground(bg);
        return tv;
    }

    private TextView meta(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(color(com.ncvt.kebiao.R.color.sc_text_dim));
        tv.setPadding(0, dp(4), 0, 0);
        return tv;
    }

    private void addTag(LinearLayout parent, String text, int colorRes) {
        if (TextUtils.isEmpty(text)) return;
        int c = color(colorRes);
        parent.addView(pill(text, c, Color.argb(24, Color.red(c), Color.green(c), Color.blue(c))));
    }


    private static final String SCORE_API = BASE + "/Student/My/getScoreDataJson.html";

    /** 拉成绩单，算出每个模块离毕业下限还差多少分。 */
    private void loadScore() {
        final String ck = cookie();
        pool.execute(() -> {
            String body = null;
            try {
                Request req = new Request.Builder().url(SCORE_API)
                        .header("Cookie", ck)
                        .header("Referer", BASE + "/Student/My/myScore.html")
                        .build();
                try (Response resp = http.newCall(req).execute()) {
                    if (resp.body() != null) body = resp.body().string();
                }
            } catch (Exception ignored) {
            }
            if (body == null || !body.trim().startsWith("{")) return;
            try {
                JSONObject d = new JSONObject(body);
                JSONObject stu = d.optJSONObject("dataStudent");
                String type = stu == null ? "专科" : stu.optString("typeName", "专科");
                java.util.Map<String, Integer> floors = floorsOf(type);
                JSONArray mods = d.optJSONArray("modules");
                java.util.Map<String, Integer> gaps = new java.util.HashMap<>();
                for (int i = 0; mods != null && i < mods.length(); i++) {
                    JSONObject m = mods.optJSONObject(i);
                    if (m == null) continue;
                    String name = m.optString("name");
                    Integer floor = floors.get(name);
                    if (floor == null) continue;
                    int mine = m.optInt("zf", 0);
                    gaps.put(name, Math.max(0, floor - mine));
                }
                ui.post(() -> {
                    moduleGap.clear();
                    moduleGap.putAll(gaps);
                    render();
                });
            } catch (Exception ignored) {
            }
        });
    }

    /** 毕业下限（学校说明原文，按专科/本科）。 */
    private static java.util.Map<String, Integer> floorsOf(String type) {
        java.util.Map<String, Integer> m = new java.util.HashMap<>();
        if ("本科".equals(type)) {
            m.put("政治素养", 150); m.put("健康素养", 150);
            m.put("文化素养", 150); m.put("专业素养", 150);
            m.put("实践素养", 150); m.put("职业素养", 150);
            m.put("公益素养", 100); m.put("双创素养", 100); m.put("网络素养", 100);
        } else {
            m.put("政治素养", 150); m.put("健康素养", 150);
            m.put("文化素养", 100); m.put("专业素养", 100);
            m.put("实践素养", 100); m.put("公益素养", 100);
            m.put("职业素养", 100); m.put("网络素养", 100);
            m.put("双创素养", 50);
        }
        return m;
    }

    /** 给列表补名额与名单；全部处理完再一次性渲染，避免卡片先白后变色的闪动。 */
    private JSONObject findById2(String id) {
        for (int i = 0; i < activities.length(); i++) {
            JSONObject o = activities.optJSONObject(i);
            if (o != null && id.equals(o.optString("activityID"))) return o;
        }
        return null;
    }

    /** 把已报名的活动存下来，供课表叠加显示。 */
    private void saveRegistered(JSONObject o) {
        try {
            String id = o.optString("activityID");
            org.json.JSONArray arr = new org.json.JSONArray(
                    getSharedPreferences("app_meta", MODE_PRIVATE)
                            .getString("registered_acts", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.optJSONObject(i);
                if (e != null && id.equals(e.optString("id"))) return;
            }
            JSONObject e = new JSONObject();
            e.put("id", id);
            e.put("name", o.optString("activityName"));
            e.put("start", o.optLong("startDate", 0));
            e.put("end", o.optLong("endDate", 0));
            arr.put(e);
            getSharedPreferences("app_meta", MODE_PRIVATE).edit()
                    .putString("registered_acts", arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private void loadDetails() {
        final String ck = cookie();
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < activities.length() && ids.size() < 120; i++) {
            JSONObject o = activities.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("activityID");
            if (id.isEmpty() || ids.contains(id)) continue;
            if (quotaMap.containsKey(id) && rosterSet.contains(id)) continue;
            ids.add(id);
        }

        ui.postDelayed(() -> {
            if (loading) render();
        }, 9000);

        if (ids.isEmpty()) {
            ui.post(this::render);
            return;
        }

        pool.execute(() -> {
            for (String id : ids) {
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        Request req = new Request.Builder().url(DETAIL_API + id)
                                .header("Cookie", ck)
                                .header("Referer", BASE + "/Student/Activity/index.html")
                                .build();
                        String html;
                        try (Response resp = http.newCall(req).execute()) {
                            html = resp.body() == null ? null : resp.body().string();
                        }
                        if (html == null) continue;
                        Detail d = parseDetail(html, "");
                        if (d.quotaTotal > 0) {
                            quotaMap.put(id, new int[]{d.quotaTotal, Math.max(0, d.quotaApplied)});
                        }
                        if (html.contains("活动名单") || html.contains("已有名单")) {
                            rosterSet.add(id);
                        }
                        if ("已报名".equals(d.applyState)) {
                            JSONObject raw = findById2(id);
                            if (raw != null) saveRegistered(raw);
                        }
                        break;
                    } catch (Exception ignored) {
                        try {
                            Thread.sleep(300);
                        } catch (InterruptedException ignored2) {
                        }
                    }
                }
            }
            ui.post(this::render);
        });
    }


    private void openDetail(String id, String fallbackName) {
        if (TextUtils.isEmpty(id)) return;
        currentDetailId = id;
        tvStatus.setText("加载详情…");
        final String ck = cookie();
        pool.execute(() -> {
            String html = null;
            try {
                Request req = new Request.Builder().url(DETAIL_API + id)
                        .header("Cookie", ck)
                        .header("Referer", BASE + "/Student/Activity/index.html")
                        .build();
                try (Response resp = http.newCall(req).execute()) {
                    if (resp.body() != null) html = resp.body().string();
                }
            } catch (Exception ignored) {
            }
            final String h = html;
            ui.post(() -> {
                tvStatus.setText("");
                if (h == null) return;
                showDetailDialog(parseDetail(h, fallbackName));
            });
        });
    }

    static class Detail {
        String name = "", module = "", category = "", scoreText = "", applyTime = "",
                actTime = "", location = "", organizer = "", detailText = "", applyState = "";
        int quotaTotal = -1, quotaApplied = -1;
        final List<String> reds = new ArrayList<>();
    }

    private Detail parseDetail(String html, String fallbackName) {
        Detail d = new Detail();
        d.name = fallbackName;
        try {
            Document doc = Jsoup.parse(html);
            Element n = doc.selectFirst("b.mui-pull-left");
            if (n != null && !n.text().isEmpty()) d.name = n.text();

            Map<String, String> cells = new LinkedHashMap<>();
            for (Element li : doc.select("li.mui-table-view-cell")) {
                Element v = li.selectFirst("span.mui-pull-right");
                if (v == null) continue;
                String val = v.text().trim();
                String full = li.text().trim();
                String label = val.isEmpty() ? full : full.replace(val, "").trim();
                if (!label.isEmpty()) cells.put(label, val);
            }
            d.module = cells.getOrDefault("模块名称", "");
            d.category = cells.getOrDefault("类别名称", "");
            d.scoreText = cells.getOrDefault("活动积分", "");
            d.applyTime = cells.getOrDefault("报名时间", "");
            d.actTime = cells.getOrDefault("活动时间", "");
            d.location = cells.getOrDefault("活动地点", "");
            try {
                java.util.regex.Matcher lm = java.util.regex.Pattern
                        .compile("活动地点\\s*[:：]\\s*([^<\\s，,。；;]{1,20})").matcher(html);
                if (lm.find()) {
                    String real = lm.group(1).trim();
                    if (!real.isEmpty()) d.location = real;
                } else if (d.location.isEmpty() || "是".equals(d.location) || "否".equals(d.location)) {
                    java.util.regex.Matcher lm2 = java.util.regex.Pattern
                            .compile("活动地点\\s*[:：]?\\s*([^<\\s，,。；;]{1,20})").matcher(
                                    org.jsoup.Jsoup.parse(html).text());
                    if (lm2.find()) d.location = lm2.group(1).trim();
                }
            } catch (Exception ignored) {
            }
            d.quotaTotal = digits(cells.getOrDefault("限制人数", ""));
            d.quotaApplied = firstDigit(cells.getOrDefault("报名人数", ""));

            for (Element sp : doc.select("span[style*=255, 0, 0], span[style*=255,0,0], font[color=red]")) {
                String t = sp.text().trim();
                if (!t.isEmpty() && !d.reds.contains(t)) d.reds.add(t);
            }
            Element org = doc.selectFirst("div:containsOwn(主办方：)");
            if (org != null) {
                String t = org.ownText();
                int i = t.indexOf("主办方：");
                if (i >= 0) d.organizer = t.substring(i + 4).trim();
            }
            Element jg = doc.selectFirst("input#jieguo");
            String jieguo = jg == null ? "" : jg.attr("value");
            if ("1".equals(jieguo) || "2".equals(jieguo)) d.applyState = "已报名";
            else if ("-2".equals(jieguo)) d.applyState = "名额已满";
            else d.applyState = "未报名";

            Element content = doc.selectFirst("div:containsOwn(活动详情)");
            if (content != null && content.nextElementSibling() != null) {
                d.detailText = content.nextElementSibling().text();
            }
        } catch (Exception ignored) {
        }
        return d;
    }

    private static int digits(String s) {
        String d = s.replaceAll("[^0-9]", "");
        return d.isEmpty() ? -1 : Integer.parseInt(d);
    }

    private static int firstDigit(String s) {
        String d = s.replaceAll("[^0-9].*$", "").replaceAll("[^0-9]", "");
        if (d.isEmpty()) return -1;
        return Integer.parseInt(d);
    }

    private void showDetailDialog(Detail d) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(16), dp(18), dp(16));
        box.setBackgroundColor(color(com.ncvt.kebiao.R.color.sc_bg));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(pill(d.module.isEmpty() ? "二课" : d.module, moduleColor(d.module), moduleBg(d.module)));
        if (!d.scoreText.isEmpty()) {
            TextView sc = new TextView(this);
            sc.setText(d.scoreText);
            sc.setTextSize(12);
            sc.setTextColor(color(com.ncvt.kebiao.R.color.sc_primary));
            sc.getPaint().setFakeBoldText(true);
            head.addView(sc);
        }
        box.addView(head);

        TextView title = new TextView(this);
        title.setText(d.name);
        title.setTextSize(17);
        title.setTextColor(color(com.ncvt.kebiao.R.color.sc_text));
        title.getPaint().setFakeBoldText(true);
        title.setPadding(0, dp(8), 0, dp(10));
        box.addView(title);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable ibg = new GradientDrawable();
        ibg.setCornerRadius(dp(14));
        ibg.setColor(color(com.ncvt.kebiao.R.color.sc_surface));
        ibg.setStroke(dp(1), color(com.ncvt.kebiao.R.color.sc_line));
        info.setBackground(ibg);
        info.setPadding(dp(14), dp(12), dp(14), dp(12));
        addRow(info, "报名时间", d.applyTime);
        addRow(info, "活动时间", d.actTime);
        addRow(info, "活动地点", d.location);
        addRow(info, "主办方", d.organizer);
        addRow(info, "我的状态", d.applyState);
        if (d.quotaTotal > 0) {
            int applied = Math.max(0, d.quotaApplied);
            int left = Math.max(0, d.quotaTotal - applied);
            addRow(info, "名额", "剩 " + left + " / " + d.quotaTotal);
            info.addView(quotaBar(applied, d.quotaTotal));
        }
        box.addView(info);

        if (!d.reds.isEmpty()) {
            LinearLayout red = new LinearLayout(this);
            red.setOrientation(LinearLayout.VERTICAL);
            red.setPadding(dp(14), dp(12), dp(14), dp(12));
            GradientDrawable rbg = new GradientDrawable();
            rbg.setCornerRadius(dp(14));
            rbg.setColor(color(com.ncvt.kebiao.R.color.sc_red_bg));
            rbg.setStroke(dp(1), 0x55DC2626);
            red.setBackground(rbg);
            TextView rt = new TextView(this);
            rt.setText("⚠ 重要须知");
            rt.setTextSize(12);
            rt.setTextColor(color(com.ncvt.kebiao.R.color.sc_red));
            rt.getPaint().setFakeBoldText(true);
            red.addView(rt);
            for (String r : d.reds) {
                TextView l = new TextView(this);
                l.setText("· " + r);
                l.setTextSize(13);
                l.setTextColor(0xFF8F1D1D);
                l.setPadding(0, dp(4), 0, 0);
                red.addView(l);
            }
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(10);
            box.addView(red, rlp);
        }

        if (!d.detailText.isEmpty()) {
            TextView body = new TextView(this);
            body.setText(d.detailText);
            body.setTextSize(12.5f);
            body.setTextColor(color(com.ncvt.kebiao.R.color.sc_text_dim));
            body.setPadding(0, dp(12), 0, 0);
            box.addView(body);
        }

        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.addView(box);

        new AlertDialog.Builder(this)
                .setView(sc)
                .setPositiveButton("去报名", (dlg, w) -> {
                    Intent it = new Intent(this, ApplyWebViewActivity.class);
                    it.putExtra(ApplyWebViewActivity.EXTRA_ID, currentDetailId);
                    it.putExtra(ApplyWebViewActivity.EXTRA_PLACE, lastPlace);
                    org.json.JSONObject src = findById2(currentDetailId);
                    if (src != null) {
                        it.putExtra(ApplyWebViewActivity.EXTRA_NAME, src.optString("activityName"));
                        try {
                            it.putExtra(ApplyWebViewActivity.EXTRA_START,
                                    (long) Double.parseDouble(src.optString("startDate", "0")));
                            it.putExtra(ApplyWebViewActivity.EXTRA_END,
                                    (long) Double.parseDouble(src.optString("endDate", "0")));
                        } catch (Exception ignored) {
                        }
                    }
                    startActivity(it);
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void addRow(LinearLayout parent, String k, String v) {
        if (v == null || v.isEmpty()) return;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(3), 0, dp(3));
        TextView kt = new TextView(this);
        kt.setText(k);
        kt.setTextSize(12);
        kt.setTextColor(color(com.ncvt.kebiao.R.color.sc_text_hint));
        kt.setMinWidth(dp(64));
        row.addView(kt);
        TextView vt = new TextView(this);
        vt.setText(v);
        vt.setTextSize(12.5f);
        vt.setTextColor(color(com.ncvt.kebiao.R.color.sc_text));
        row.addView(vt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row);
    }

    private View quotaBar(int applied, int total) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setPadding(0, dp(6), 0, 0);
        android.widget.ProgressBar pb = new android.widget.ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        pb.setMax(Math.max(1, total));
        pb.setProgress(Math.min(applied, total));
        pb.setProgressTintList(android.content.res.ColorStateList.valueOf(
                color(com.ncvt.kebiao.R.color.sc_primary)));
        pb.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(
                color(com.ncvt.kebiao.R.color.sc_line_soft)));
        wrap.addView(pb, new LinearLayout.LayoutParams(0, dp(8), 1f));
        return wrap;
    }
}
