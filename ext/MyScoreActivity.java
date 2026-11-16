package com.ncvt.kebiao.ext;

import android.app.Activity;

import com.ncvt.kebiao.R;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** 我的 · 毕业视角（对应网页版的「我的 → 毕业」）。只读。 */
public class MyScoreActivity extends Activity {

    private static final String BASE = "https://class.bigdata.ncvt.net";
    private static final String API = BASE + "/Student/My/getScoreDataJson.html";

    private final OkHttpClient http = new OkHttpClient();
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout listBox;
    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(color(R.color.sc_bg));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(color(R.color.sc_surface));
        bar.setPadding(dp(12), dp(14), dp(16), dp(14));
        bar.setElevation(dp(2));
        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(26);
        back.setTextColor(color(R.color.sc_primary));
        back.setPadding(dp(4), 0, dp(12), 0);
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        TextView title = new TextView(this);
        title.setText("我的 · 毕业");
        title.setTextSize(17);
        title.setTextColor(color(R.color.sc_text));
        title.getPaint().setFakeBoldText(true);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView segScore = new TextView(this);
        segScore.setText("积分");
        segScore.setTextSize(12.5f);
        segScore.setTextColor(0xFFFFFFFF);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setCornerRadius(dp(16));
        sbg.setColor(getResources().getColor(R.color.sc_primary, getTheme()));
        segScore.setBackground(sbg);
        segScore.setPadding(dp(14), dp(6), dp(14), dp(6));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.rightMargin = dp(8);
        segScore.setLayoutParams(slp);
        bar.addView(segScore);

        TextView mine = new TextView(this);
        mine.setText("我的活动");
        mine.setTextSize(12.5f);
        mine.setTextColor(getResources().getColor(R.color.sc_primary, getTheme()));
        GradientDrawable mbg = new GradientDrawable();
        mbg.setCornerRadius(dp(16));
        mbg.setColor(getResources().getColor(R.color.sc_primary_soft, getTheme()));
        mbg.setStroke(dp(1), getResources().getColor(R.color.sc_primary, getTheme()));
        mine.setBackground(mbg);
        mine.setPadding(dp(12), dp(6), dp(12), dp(6));
        mine.setOnClickListener(v -> startActivity(
                new android.content.Intent(this, MyActivityActivity.class)));
        bar.addView(mine);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setTextSize(12);
        status.setTextColor(color(R.color.sc_text_hint));
        status.setPadding(dp(16), dp(10), dp(16), dp(6));
        root.addView(status);

        ScrollView sc = new ScrollView(this);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(dp(12), dp(2), dp(12), dp(24));
        sc.addView(listBox);
        root.addView(sc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        ExtUi.applyInsets(this);

        load();
    }

    private void load() {
        status.setText("加载中…");
        String ck = getSharedPreferences(LoginWebViewActivity.PREFS, MODE_PRIVATE)
                .getString(LoginWebViewActivity.KEY_COOKIE, "");
        pool.execute(() -> {
            String body = null;
            try {
                Request req = new Request.Builder().url(API)
                        .header("Cookie", ck)
                        .header("Referer", BASE + "/Student/My/myScore.html")
                        .build();
                try (Response resp = http.newCall(req).execute()) {
                    if (resp.body() != null) body = resp.body().string();
                }
            } catch (Exception ignored) {
            }
            final String b = body;
            ui.post(() -> {
                if (b == null) {
                    status.setText("网络错误");
                    return;
                }
                try {
                    render(new JSONObject(b.trim()));
                } catch (Exception e) {
                    status.setText("会话已过期，请到「二课」页重新登录");
                }
            });
        });
    }

    private void render(JSONObject d) {
        JSONObject stu = d.optJSONObject("dataStudent");
        String type = stu == null ? "专科" : stu.optString("typeName", "专科");
        Map<String, Integer> floors = floorsOf(type);
        int need = "本科".equals(type) ? 1800 : 1200;

        JSONArray mods = d.optJSONArray("modules");
        int total = 0;
        for (int i = 0; mods != null && i < mods.length(); i++) {
            JSONObject m = mods.optJSONObject(i);
            if (m != null) total += m.optInt("zf", 0);
        }
        status.setText(stu == null ? "" :
                stu.optString("studentName") + " · " + stu.optString("collegeName")
                        + " · " + stu.optString("className"));

        LinearLayout totalCard = card();
        LinearLayout tTop = new LinearLayout(this);
        tTop.setGravity(Gravity.CENTER_VERTICAL);
        TextView tTitle = new TextView(this);
        tTitle.setText("毕业总积分（" + type + " " + need + " 分）");
        tTitle.setTextSize(14);
        tTitle.setTextColor(color(R.color.sc_text));
        tTitle.getPaint().setFakeBoldText(true);
        tTop.addView(tTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView tState = new TextView(this);
        tState.setText(total >= need ? "已达成" : "还差 " + (need - total) + " 分");
        tState.setTextSize(12);
        tState.setTextColor(total >= need ? color(R.color.sc_ok) : color(R.color.sc_warn));
        tTop.addView(tState);
        totalCard.addView(tTop);

        ProgressBar pb = bar(total, need);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(10));
        plp.topMargin = dp(10);
        totalCard.addView(pb, plp);

        TextView tNum = new TextView(this);
        tNum.setText(total + " / " + need + "　" + (need > 0 ? total * 100 / need : 0) + "%");
        tNum.setTextSize(13);
        tNum.setTextColor(color(R.color.sc_text_dim));
        tNum.setPadding(0, dp(8), 0, 0);
        totalCard.addView(tNum);
        listBox.addView(totalCard);

        for (int i = 0; mods != null && i < mods.length(); i++) {
            JSONObject m = mods.optJSONObject(i);
            if (m == null) continue;
            String name = m.optString("name");
            int mine = m.optInt("zf", 0);
            double avg = m.optDouble("avg", 0);
            Integer floorObj = floors.get(name);
            int floor = floorObj == null ? 0 : floorObj;

            LinearLayout row = card();
            LinearLayout rTop = new LinearLayout(this);
            rTop.setGravity(Gravity.CENTER_VERTICAL);
            TextView nm = new TextView(this);
            nm.setText(name);
            nm.setTextSize(13.5f);
            nm.setTextColor(color(R.color.sc_text));
            nm.getPaint().setFakeBoldText(true);
            rTop.addView(nm, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView vals = new TextView(this);
            vals.setText(mine + " 分　下限 " + floor + "　院均 " + trim(avg));
            vals.setTextSize(11.5f);
            vals.setTextColor(color(R.color.sc_text_dim));
            rTop.addView(vals);
            row.addView(rTop);

            ProgressBar mb = bar(mine, (int) Math.max(Math.max(mine, avg) * 1.15, floor + 1));
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
            mlp.topMargin = dp(8);
            row.addView(mb, mlp);

            TextView tip = new TextView(this);
            boolean ok = floor == 0 || mine >= floor;
            if (floor == 0) tip.setText("无单项要求");
            else if (ok) tip.setText("已达标 · 多 " + (mine - floor) + " 分");
            else tip.setText("差 " + (floor - mine) + " 分");
            tip.setTextSize(11.5f);
            tip.setTextColor(ok ? color(R.color.sc_ok) : color(R.color.sc_warn));
            tip.setPadding(0, dp(6), 0, 0);
            row.addView(tip);

            listBox.addView(row);
        }
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((int) v) : String.format(java.util.Locale.CHINA, "%.1f", v);
    }

    private static Map<String, Integer> floorsOf(String type) {
        Map<String, Integer> m = new HashMap<>();
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

    private ProgressBar bar(int value, int max) {
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(Math.max(1, max));
        pb.setProgress(Math.min(value, Math.max(1, max)));
        pb.setProgressTintList(ColorStateList.valueOf(color(R.color.sc_primary)));
        pb.setProgressBackgroundTintList(ColorStateList.valueOf(color(R.color.sc_line_soft)));
        return pb;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(color(R.color.sc_surface));
        bg.setStroke(dp(1), color(R.color.sc_line));
        c.setBackground(bg);
        c.setElevation(dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        c.setLayoutParams(lp);
        return c;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private int color(int id) {
        return getResources().getColor(id, getTheme());
    }
}
