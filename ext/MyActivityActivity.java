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
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.ncvt.kebiao.R;

/**
 * 我的活动：直接用官方页面（报名中 / 未开始 / 活动中 / 已结束 / 其它）。
 * 详情、签到签退、取消报名都在官方页里完成，这里只负责带上登录态。
 */
public class MyActivityActivity extends Activity {

    private static final String BASE = "https://class.bigdata.ncvt.net";
    private static final String URL = BASE + "/Student/My/myActivity.html";

    private WebView web;
    private ProgressBar bar;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getResources().getColor(R.color.sc_bg, getTheme()));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(getResources().getColor(R.color.sc_surface, getTheme()));
        head.setElevation(dp(2));
        head.setPadding(dp(12), dp(12), dp(14), dp(12));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(26);
        back.setTextColor(getResources().getColor(R.color.sc_primary, getTheme()));
        back.setPadding(dp(4), 0, dp(10), 0);
        back.setOnClickListener(v -> finish());
        head.addView(back);

        TextView title = new TextView(this);
        title.setText("我的活动");
        title.setTextSize(16);
        title.setTextColor(getResources().getColor(R.color.sc_text, getTheme()));
        title.getPaint().setFakeBoldText(true);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView segScore = new TextView(this);
        segScore.setText("积分");
        segScore.setTextSize(12);
        segScore.setTextColor(getResources().getColor(R.color.sc_primary, getTheme()));
        GradientDrawable sbg = new GradientDrawable();
        sbg.setCornerRadius(dp(16));
        sbg.setColor(getResources().getColor(R.color.sc_primary_soft, getTheme()));
        segScore.setBackground(sbg);
        segScore.setPadding(dp(12), dp(5), dp(12), dp(5));
        segScore.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.rightMargin = dp(8);
        segScore.setLayoutParams(slp);
        head.addView(segScore);

        TextView segAct = new TextView(this);
        segAct.setText("我的活动");
        segAct.setTextSize(12);
        segAct.setTextColor(0xFFFFFFFF);
        GradientDrawable abg = new GradientDrawable();
        abg.setCornerRadius(dp(16));
        abg.setColor(getResources().getColor(R.color.sc_primary, getTheme()));
        segAct.setBackground(abg);
        segAct.setPadding(dp(12), dp(5), dp(12), dp(5));
        head.addView(segAct);
        root.addView(head);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setMax(100);
        int h = dp(3);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
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
            public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                bar.setVisibility(View.GONE);
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        ExtUi.applyInsets(this);
        web.loadUrl(URL);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
