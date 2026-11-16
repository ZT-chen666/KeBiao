package com.ncvt.kebiao.ext;

import android.app.Activity;

import com.ncvt.kebiao.R;
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
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** 二课项目页（对应网页版的「项目」）。只读。 */
public class ProjectListActivity extends Activity {

    private static final String BASE = "https://class.bigdata.ncvt.net";
    private static final String API = BASE + "/Student/Project/getProjectsCanApply.html";

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
        title.setText("二课项目");
        title.setTextSize(17);
        title.setTextColor(color(R.color.sc_text));
        title.getPaint().setFakeBoldText(true);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
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
                        .header("Referer", BASE + "/Student/Project/index.html")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .post(new FormBody.Builder()
                                .add("keywords", "").add("typeID", "")
                                .add("sortByTime", "").add("sortByScore", "").build())
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
                    JSONArray arr = new JSONArray(b.trim());
                    JSONArray items = arr.optJSONArray(1);
                    status.setText((items == null ? 0 : items.length()) + " 个项目");
                    for (int i = 0; items != null && i < items.length(); i++) {
                        JSONObject o = items.optJSONObject(i);
                        if (o != null) listBox.addView(card(o));
                    }
                } catch (Exception e) {
                    status.setText("会话已过期，请到「二课」页重新登录");
                }
            });
        });
    }

    private View card(JSONObject o) {
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

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(pill(o.optString("moduleName"), color(R.color.sc_primary),
                Color.argb(24, 37, 99, 235)));
        TextView score = new TextView(this);
        score.setText(o.optInt("score") + " 分");
        score.setTextSize(14);
        score.setTextColor(color(R.color.sc_primary));
        score.getPaint().setFakeBoldText(true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(8);
        top.addView(score, slp);
        c.addView(top);

        TextView name = new TextView(this);
        name.setText(o.optString("projectName"));
        name.setTextSize(15);
        name.setTextColor(color(R.color.sc_text));
        name.getPaint().setFakeBoldText(true);
        name.setPadding(0, dp(8), 0, dp(2));
        c.addView(name);

        TextView org = new TextView(this);
        org.setText("主办 · " + o.optString("organizerName"));
        org.setTextSize(12);
        org.setTextColor(color(R.color.sc_text_dim));
        c.addView(org);

        TextView time = new TextView(this);
        time.setText("截止 " + hhmm(o.optLong("endDate", 0)));
        time.setTextSize(12);
        time.setTextColor(color(R.color.sc_text_dim));
        time.setPadding(0, dp(4), 0, 0);
        c.addView(time);

        LinearLayout tags = new LinearLayout(this);
        tags.setPadding(0, dp(8), 0, 0);
        tags.addView(pill("已参与 " + o.optInt("totalApply") + " 人", color(R.color.sc_text_dim),
                color(R.color.sc_line_soft)));
        if ("1".equals(o.optString("contentRequired")))
            tags.addView(pill("需上传材料", color(R.color.sc_warn), color(R.color.sc_warn_bg)));
        if ("1".equals(o.optString("teacherCheckOn")))
            tags.addView(pill("需教师审核", color(R.color.sc_warn), color(R.color.sc_warn_bg)));
        c.addView(tags);

        String content = o.optString("content");
        if (!content.isEmpty()) {
            TextView body = new TextView(this);
            body.setText(content);
            body.setTextSize(12);
            body.setTextColor(color(R.color.sc_text_dim));
            body.setPadding(0, dp(6), 0, 0);
            c.addView(body);
        }
        return c;
    }

    private TextView pill(String text, int fg, int bgColor) {
        TextView tv = new TextView(this);
        tv.setText(text == null ? "" : text);
        tv.setTextSize(11);
        tv.setTextColor(fg);
        tv.setPadding(dp(8), dp(3), dp(8), dp(3));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(9));
        bg.setColor(bgColor);
        tv.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private static String hhmm(long ts) {
        if (ts <= 0) return "-";
        return new SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(new Date(ts * 1000));
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private int color(int id) {
        return getResources().getColor(id, getTheme());
    }
}
