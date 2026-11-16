package com.ncvt.kebiao.ext;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 二课报名页：把已登录的会话注入 WebView，直接打开官方报名页。
 * 只新增，不改课表逻辑。
 */
public class ApplyWebViewActivity extends Activity {

    static final String EXTRA_ID = "activityID";
    static final String EXTRA_URL = "url";
    static final String EXTRA_NAME = "name";
    static final String EXTRA_START = "start";
    static final String EXTRA_END = "end";
    static final String EXTRA_PLACE = "place";

    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")

    private boolean saved;
    private boolean polling;
    private long pollStart;

    /** 报名成功不跳转页面，只能定时轮询「已报名」。 */
    private void startPolling(final android.webkit.WebView view) {
        if (polling || saved) return;
        polling = true;
        pollStart = System.currentTimeMillis();
        tick(view);
    }

    private void tick(final android.webkit.WebView view) {
        if (saved || web == null) return;
        if (System.currentTimeMillis() - pollStart > 120000) return;   // 最多轮询 2 分钟
        checkRegistered(view);
        if (!saved) {
            web.postDelayed(() -> tick(view), 2000);
        }
    }


    private String lastPlace = "";

    /** 轮询：详情页先记住地点；出现「已报名/报名成功」立刻入课表。 */
    private void checkRegistered(android.webkit.WebView view) {
        if (saved) return;
        view.evaluateJavascript(
                "(function(){var t=document.body?document.body.innerText:'';"
                        + "var ok=(t.indexOf('报名成功')>=0)?'1':'0';"
                        + "var m=t.match(/活动地点[:：]?\\s*([^\\n]{1,30})/);"
                        + "var p=m?m[1].trim():'';"
                        + "return ok+'|'+p;})()",
                value -> {
                    try {
                        if (value == null) return;
                        String v = value.replace("\"", "");
                        int bar = v.indexOf('|');
                        String flag = bar >= 0 ? v.substring(0, bar) : v;
                        String place = bar >= 0 ? v.substring(bar + 1).trim() : "";
                        if (!place.isEmpty() && !"null".equals(place)) lastPlace = place;
                        if (flag.contains("1") && !saved) {
                            saved = true;
                            saveRegistered();
                        }
                    } catch (Exception ignored) {
                    }
                });
    }

    private void saveRegistered() {
        try {
            String id = getIntent().getStringExtra(EXTRA_ID);
        String extraPlaceValue = getIntent().getStringExtra(EXTRA_PLACE);
        if (extraPlaceValue != null && !extraPlaceValue.isEmpty()) lastPlace = extraPlaceValue;
            String name = getIntent().getStringExtra(EXTRA_NAME);
            long start = getIntent().getLongExtra(EXTRA_START, 0);
            long end = getIntent().getLongExtra(EXTRA_END, 0);
            if (id == null || start <= 0) return;

            android.content.SharedPreferences sp =
                    getSharedPreferences("app_meta", MODE_PRIVATE);
            org.json.JSONArray arr = new org.json.JSONArray(sp.getString("registered_acts", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o != null && id.equals(o.optString("id"))) {
                    if (lastPlace != null && !lastPlace.isEmpty()) o.put("place", lastPlace);
                    o.put("start", start);
                    o.put("end", end);
                    arr.put(i, o);
                    getSharedPreferences("app_meta", MODE_PRIVATE)
                            .edit().putString("registered_acts", arr.toString()).apply();
                    android.widget.Toast.makeText(this, "课表已更新", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("id", id);
            o.put("name", name == null ? "二课活动" : name);
            if (lastPlace != null && !lastPlace.isEmpty()) o.put("place", lastPlace);
            o.put("start", start);
            o.put("end", end);
            arr.put(o);
            sp.edit().putString("registered_acts", arr.toString()).apply();

            if (false) {
                web.evaluateJavascript(
                        "(function(){var t=document.body?document.body.innerText:'';"
                                + "var m=t.match(/活动地点[:：]?\\s*([^\\n]{1,30})/);"
                                + "return m?m[1].trim():'';})()",
                        v -> extraPlace(id, v));
            }
            android.widget.Toast.makeText(this, "已加入课表", android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String id = getIntent().getStringExtra(EXTRA_ID);
        String extraPlaceValue = getIntent().getStringExtra(EXTRA_PLACE);
        if (extraPlaceValue != null && !extraPlaceValue.isEmpty()) lastPlace = extraPlaceValue;
        String cookie = getSharedPreferences(LoginWebViewActivity.PREFS, MODE_PRIVATE)
                .getString(LoginWebViewActivity.KEY_COOKIE, "");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getResources().getColor(com.ncvt.kebiao.R.color.background, getTheme()));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(getResources().getColor(com.ncvt.kebiao.R.color.surface, getTheme()));
        bar.setElevation(dp(2));
        bar.setPadding(dp(12), dp(12), dp(16), dp(12));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(26);
        back.setTextColor(getResources().getColor(com.ncvt.kebiao.R.color.primary, getTheme()));
        back.setPadding(dp(4), 0, dp(12), 0);
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        TextView title = new TextView(this);
        title.setText("二课报名");
        title.setTextSize(16);
        title.setTextColor(getResources().getColor(com.ncvt.kebiao.R.color.text_primary, getTheme()));
        title.getPaint().setFakeBoldText(true);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
        if (cookie != null && !cookie.isEmpty()) {
            for (String part : cookie.split(";")) {
                cm.setCookie("https://class.bigdata.ncvt.net", part.trim());
            }
            cm.flush();
        }
        web.setWebViewClient(new WebViewClient());
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        ExtUi.applyInsets(this);

        String override = getIntent().getStringExtra(EXTRA_URL);
        if (override != null && !override.isEmpty()) {
            web.loadUrl(override);
        } else if (id != null && !id.isEmpty()) {
            web.setWebViewClient(new android.webkit.WebViewClient() {
                @Override
                public void onPageFinished(android.webkit.WebView view, String url) {
                    super.onPageFinished(view, url);
                    startPolling(view);   // 报名是 AJAX，页面不跳转，必须轮询
                }
            });
            web.loadUrl("https://class.bigdata.ncvt.net/Student/Activity/apply.html?activityID=" + id);
        }
    }


    private long openAt = System.currentTimeMillis();

    /** 报名页：没写入课表前不让返回，避免用户提前离开导致漏记。 */
    @Override
    public void onBackPressed() {
        boolean tooLong = System.currentTimeMillis() - openAt > 20000;   // 20 秒兜底
        if (!saved && !tooLong) {
            android.widget.Toast.makeText(this, "正在写入课表…", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }


    /** 把地点补进那条记录。 */
    private void extraPlace(String id, String raw) {
        try {
            if (raw == null) return;
            String place = raw.replace("\\u003C", "<").replace("\"", "").trim();
            if (place.isEmpty() || "null".equals(place)) return;
            android.content.SharedPreferences sp = getSharedPreferences("app_meta", MODE_PRIVATE);
            org.json.JSONArray arr = new org.json.JSONArray(sp.getString("registered_acts", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o != null && id.equals(o.optString("id"))) {
                    o.put("place", place);
                    arr.put(i, o);
                }
            }
            sp.edit().putString("registered_acts", arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
