package com.ncvt.kebiao.ext;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.ncvt.kebiao.R;

import java.util.HashSet;
import java.util.Set;

/**
 * 我的学习课程：直接把学校页面放进 WebView，右侧悬浮按钮切换「自动 / 手动」。
 *
 * 自动模式的循环（全部走页面自己的接口，等于替用户点了一遍）：
 *   课程列表 → 点第一门未处理的课 → 资源列表 → 点第一个资源（视频页）
 *   → 读页面红字「还需学习 N 秒」→ 调 getSecondsAndRemmberStartTime 开始计时
 *   → 本地倒计时 N 秒（官方计时器依赖视频播放，这里不等它）
 *   → 调 remmberStudyTime 提交并刷新 → 页面出现评价区
 *   → 点 5 星 + 填评价 + 提交 → 回到列表，下一门
 */
public class StudyActivity extends Activity {

    private static final String BASE = "https://class.bigdata.ncvt.net";
    private static final String LIST_URL = BASE + "/Student/My/myCourse.html";
    private static final String DEFAULT_COMMENT = "课程内容讲解清晰，重点突出，收获很大，感谢老师。";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Set<String> handled = new HashSet<>();
    private final StringBuilder log = new StringBuilder();

    private WebView web;
    private TextView fab;
    private TextView status;
    private boolean auto;
    private int countdown;
    private Runnable ticker;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getResources().getColor(R.color.sc_bg, getTheme()));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(getResources().getColor(R.color.sc_surface, getTheme()));
        bar.setElevation(dp(2));
        bar.setPadding(dp(12), dp(12), dp(16), dp(12));
        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(26);
        back.setTextColor(getResources().getColor(R.color.sc_primary, getTheme()));
        back.setPadding(dp(4), 0, dp(12), 0);
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        TextView title = new TextView(this);
        title.setText("我的学习课程");
        title.setTextSize(16);
        title.setTextColor(getResources().getColor(R.color.sc_text, getTheme()));
        title.getPaint().setFakeBoldText(true);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView logBtn = new TextView(this);
        logBtn.setText("日志");
        logBtn.setTextSize(12);
        logBtn.setTextColor(getResources().getColor(R.color.sc_primary, getTheme()));
        logBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
        logBtn.setOnClickListener(v -> showLog());
        bar.addView(logBtn);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setTextSize(11.5f);
        status.setTextColor(getResources().getColor(R.color.sc_text_hint, getTheme()));
        status.setPadding(dp(16), dp(6), dp(16), dp(6));
        status.setText("手动模式 · 自己点也行");
        root.addView(status);

        FrameLayout container = new FrameLayout(this);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
        String cookie = getSharedPreferences(LoginWebViewActivity.PREFS, MODE_PRIVATE)
                .getString(LoginWebViewActivity.KEY_COOKIE, "");
        if (cookie != null && !cookie.isEmpty()) {
            for (String part : cookie.split(";")) {
                if (!part.trim().isEmpty()) cm.setCookie(BASE, part.trim());
            }
            cm.flush();
        }
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (auto) ui.postDelayed(() -> step(url), 1200);
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        container.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        fab = new TextView(this);
        fab.setText("手动");
        fab.setTextSize(12.5f);
        fab.setGravity(Gravity.CENTER);
        fab.setTextColor(Color.WHITE);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(dp(76), dp(76),
                Gravity.BOTTOM | Gravity.END);
        flp.setMargins(0, 0, dp(16), dp(24));
        fab.setLayoutParams(flp);
        fab.setOnClickListener(v -> toggleAuto());
        container.addView(fab);
        paintFab();

        root.addView(container, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        ExtUi.applyInsets(this);

        web.loadUrl(LIST_URL);
    }

    private void paintFab() {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(auto ? 0xFF0E9F6E : 0xFF2563EB);
        fab.setBackground(bg);
        fab.setText(auto ? "自动\n运行中" : "手动");
        fab.setTextSize(auto ? 11f : 12.5f);
    }

    private void toggleAuto() {
        auto = !auto;
        paintFab();
        if (auto) {
            handled.clear();
            status.setText("自动模式 · 开始");
            append("== 自动开始 ==");
            web.loadUrl(LIST_URL);
        } else {
            stopTicker();
            status.setText("手动模式 · 自己点也行");
            append("== 已暂停 ==");
        }
    }


    private void step(String url) {
        if (url == null) return;
        if (url.contains("/Student/My/myCourse.html")) {
            status.setText("自动 · 找没学完的课…");
            js("(function(){var done=" + doneArray() + ";var bs=['ajaxbox1','ajaxbox2'];"
                    + "for(var b=0;b<bs.length;b++){var box=document.getElementById(bs[b]);if(!box)continue;"
                    + "var as=box.querySelectorAll('a[href*=\"courseResourceList\"]');"
                    + "for(var i=0;i<as.length;i++){var h=as[i].getAttribute('href');"
                    + "var m=h.match(/courseID=(\\d+)/);var id=m?m[1]:'';"
                    + "if(done.indexOf(id)>=0)continue;as[i].click();return 'enter:'+id;}}"
                    + "return 'none';})()", r -> {
                if (r != null && r.contains("enter:")) {
                    append("进入课程 " + r.replace("enter:", "").replace("\"", ""));
                } else {
                    status.setText("自动 · 没有待学的课了");
                    toast("没有待学的课程了");
                    auto = false;
                    paintFab();
                }
            });
            return;
        }
        if (url.contains("courseResourceList.html")) {
            status.setText("自动 · 进资源列表…");
            js("(function(){var a=document.querySelector('a[href*=\"study.html\"]');"
                    + "if(a){a.click();return 'res';}return 'none';})()", r -> {
                if (r == null || !r.contains("res")) {
                    append("该课程无视频资源，跳过");
                    backToList();
                }
            });
            return;
        }
        if (url.contains("/Student/Course/study.html")) {
            status.setText("自动 · 读取还需学习时长…");
            js("(function(){var c=document.getElementById('divComment');"
                    + "var n=document.getElementById('num');"
                    + "var vis=c&&getComputedStyle(c).display!=='none';"
                    + "return (vis?'done':'need')+'|'+(n?n.textContent.trim():'')+'|'"
                    + "+(document.getElementById('txtCommont')?'has':'no');})()", r -> {
                if (r == null) return;
                String[] parts = r.replace("\"", "").split("\\|", -1);
                String state = parts[0];
                String num = parts.length > 1 ? parts[1] : "";
                if ("done".equals(state)) {
                    append("已学完，开始评价");
                    evaluateAndFinish(url);
                } else {
                    int remain = parseInt(num, 0);
                    String cid = courseId(url);
                    startClock(cid, remain);
                }
            });
        }
    }

    /** 开始计时：先按页面逻辑记录开始时间，再本地倒计时。 */
    private void startClock(String cid, int remain) {
        if (remain <= 0) {
            markDone(cid);
            return;
        }
        status.setText("自动 · 等待 " + remain + " 秒");
        append("课程 " + cid + " 还需 " + remain + " 秒");
        js("fetch('/Student/Course/getSecondsAndRemmberStartTime.html',{method:'POST',"
                + "headers:{'Content-Type':'application/x-www-form-urlencoded'},"
                + "credentials:'include',body:'courseID=" + cid + "'});", null);
        startTicker(cid, remain);
    }

    private void startTicker(String cid, int total) {
        stopTicker();
        final int[] left = {total};
        ticker = new Runnable() {
            @Override
            public void run() {
                if (!auto) return;
                left[0]--;
                status.setText("自动 · 等待 " + Math.max(0, left[0]) + " 秒（" + cid + "）");
                if (left[0] > 0) {
                    ui.postDelayed(this, 1000);
                } else {
                    markDone(cid);
                }
            }
        };
        ui.postDelayed(ticker, 1000);
    }

    private void stopTicker() {
        if (ticker != null) {
            ui.removeCallbacks(ticker);
            ticker = null;
        }
    }

    /** 时间到：按页面暂停时的逻辑提交，然后刷新页面进入评价态。 */
    private void markDone(String cid) {
        stopTicker();
        status.setText("自动 · 提交学习时长…");
        append("提交 " + cid + " 学习时长");
        js("fetch('/Student/Course/remmberStudyTime.html',{method:'POST',"
                + "headers:{'Content-Type':'application/x-www-form-urlencoded'},"
                + "credentials:'include',body:'courseID=" + cid + "&restTime=0'})"
                + ".then(function(){location.reload();});", null);
    }

    /** 点 5 星 + 填评价 + 提交，然后回列表下一门。 */
    private void evaluateAndFinish(String url) {
        String cid = courseId(url);
        status.setText("自动 · 评价中…");
        js("(function(){var s=document.getElementById('stars');if(!s)return 'nostars';"
                + "var ic=document.querySelectorAll('.icons i');"
                + "for(var i=0;i<ic.length&&i<5;i++){ic[i].click();}"
                + "s.value=5;"
                + "var t=document.getElementById('txtCommont');"
                + "if(t&&!t.value)t.value=" + jsString(DEFAULT_COMMENT) + ";"
                + "if(typeof comment==='function'){comment();return 'commented';}"
                + "return 'nocm';})()", r -> {
            append("评价结果 " + (r == null ? "null" : r));
            handled.add(cid);
            ui.postDelayed(this::backToList, 2500);
        });
    }

    private void backToList() {
        if (!auto) return;
        status.setText("自动 · 下一门…");
        web.loadUrl(LIST_URL);
    }


    private void js(String code, java.util.function.Consumer<String> cb) {
        if (web == null) return;
        web.evaluateJavascript(code, value -> {
            if (cb != null) cb.accept(value);
        });
    }

    private String doneArray() {
        StringBuilder sb = new StringBuilder("[");
        for (String id : handled) {
            if (sb.length() > 1) sb.append(',');
            sb.append('"').append(id).append('"');
        }
        return sb.append(']').toString();
    }

    private static String jsString(String s) {
        return "'" + s.replace("'", "\\'") + "'";
    }

    private static String courseId(String url) {
        if (url == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("courseID=(\\d+)").matcher(url);
        return m.find() ? m.group(1) : "";
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return def;
        }
    }

    private void append(String line) {
        log.append('[').append(new java.text.SimpleDateFormat("HH:mm:ss",
                java.util.Locale.CHINA).format(new java.util.Date())).append("] ")
                .append(line).append('\n');
    }

    private void showLog() {
        ScrollView sc = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(log.length() == 0 ? "暂无日志" : log.toString());
        tv.setTextSize(11);
        tv.setTextColor(getResources().getColor(R.color.sc_text, getTheme()));
        tv.setPadding(dp(16), dp(16), dp(16), dp(16));
        sc.addView(tv);
        new android.app.AlertDialog.Builder(this)
                .setTitle("运行日志")
                .setView(sc)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    @Override
    protected void onDestroy() {
        stopTicker();
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
