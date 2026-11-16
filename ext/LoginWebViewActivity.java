package com.ncvt.kebiao.ext;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.ncvt.kebiao.R;

/**
 * 二课登录。
 *
 * 学校的实际情况：CAS 登录成功后会被带到校园门户（crp.ncvt.net），
 * 二课的会话是门户通过 SSO 票据发给 class.bigdata.ncvt.net 的。
 * 所以流程是：登录 → 门户 → 尝试自动点开「第二课堂」→ 到达二课页就抓 cookie 收工。
 *
 * JS 里不要写中文，统一用 unicode 转义，避免编码问题。
 */
public class LoginWebViewActivity extends Activity {

    static final String PREFS = "second_class";
    static final String KEY_COOKIE = "cookie_school";

    private static final String SCHOOL = "https://class.bigdata.ncvt.net";
    private static final String H5_PORTAL = "https://mobile.nnvtu.edu.cn/h5";
    private static final String CAS = "https://ids7.ncvt.net/authserver/login?service="
            + "https%3A%2F%2Fclass.bigdata.ncvt.net%2FStudent%2FActivity%2Findex.html";

    private WebView web;
    private TextView hint;
    private boolean triedH5;
    private int scanRound;
    private int autoTried;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getResources().getColor(R.color.sc_bg, getTheme()));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(getResources().getColor(R.color.sc_surface, getTheme()));
        bar.setElevation(dp(2));
        bar.setPadding(dp(12), dp(12), dp(14), dp(12));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(26);
        back.setTextColor(getResources().getColor(R.color.sc_primary, getTheme()));
        back.setPadding(dp(4), 0, dp(10), 0);
        back.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        bar.addView(back);

        TextView title = new TextView(this);
        title.setText("登录二课（学校账号）");
        title.setTextSize(16);
        title.setSingleLine(true);
        title.setTextColor(getResources().getColor(R.color.sc_text, getTheme()));
        title.getPaint().setFakeBoldText(true);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView done = new TextView(this);
        done.setText("完成");
        done.setTextSize(13);
        done.setTextColor(Color.WHITE);
        GradientDrawable dbg = new GradientDrawable();
        dbg.setCornerRadius(dp(16));
        dbg.setColor(getResources().getColor(R.color.sc_primary, getTheme()));
        done.setBackground(dbg);
        done.setPadding(dp(14), dp(7), dp(14), dp(7));
        done.setOnClickListener(v -> doFinish());
        bar.addView(done);
        root.addView(bar);

        hint = new TextView(this);
        hint.setTextSize(11.5f);
        hint.setTextColor(getResources().getColor(R.color.sc_text_dim, getTheme()));
        hint.setBackgroundColor(getResources().getColor(R.color.sc_primary_soft, getTheme()));
        hint.setPadding(dp(14), dp(8), dp(14), dp(8));
        hint.setText("登录成功后会被带到校园门户，正在尝试自动打开「第二课堂」…");
        root.addView(hint);

        FrameLayout container = new FrameLayout(this);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.getSettings().setSupportMultipleWindows(true);
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                          android.os.Message resultMsg) {
                final WebView probe = new WebView(view.getContext());
                probe.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, String u) {
                        if (u != null && !u.startsWith("about:")) {
                            hint.setText("正在打开第二课堂…");
                            web.loadUrl(u);
                        }
                        return true;
                    }

                    @Override
                    public void onPageStarted(WebView v, String u, android.graphics.Bitmap f) {
                        super.onPageStarted(v, u, f);
                        if (u != null && u.startsWith("http")) {
                            hint.setText("正在打开第二课堂…");
                            web.loadUrl(u);
                        }
                    }
                });
                ((WebView.WebViewTransport) resultMsg.obj).setWebView(probe);
                resultMsg.sendToTarget();
                return true;
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                onLoaded(url);
            }
        });
        container.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(container, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        ExtUi.applyInsets(this);

        if (cookie().isEmpty()) {
            web.loadUrl(CAS);
        } else {
            web.loadUrl(SCHOOL + "/Student/Activity/index.html");
        }
    }

    private String cookie() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_COOKIE, "");
    }

    /** 每次页面加载完判断该干什么。 */
    private void onLoaded(String url) {
        if (url == null) return;
        hintSet(url);
        scanRound = 0;

        if (url.startsWith(SCHOOL)) {
            final String ck = CookieManager.getInstance().getCookie(SCHOOL);
            if (ck != null && ck.contains("PHPSESSID")) {
                // 先存下来，避免用户中途离开丢会话
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(KEY_COOKIE, ck).apply();
                verify(ck, 0);
                return;
            }
        }
        if (url.contains("crp.ncvt.net")) {
            scanRound = 0;
            autoTried = 0;
            hint.setText("已登录，正在打开手机门户…");
            web.loadUrl(H5_PORTAL);
            return;
        }

        if (url.contains("ids7.ncvt.net") || url.contains("authserver")) {
            scanRound = 0;
            autoTried = 0;
            hint.setText("请在此页输入学号密码登录，登录成功后会自动点开「第二课堂」");
            return;
        }

        if (scanRound == 0) autoTried = 0;
        clickSecondClassEntry(url);
    }

    private void hintSet(String url) {
        String where;
        if (url.contains("ids7.ncvt.net")) where = "统一身份认证";
        else if (url.contains("crp.ncvt.net")) where = "校园门户";
        else if (url.startsWith(SCHOOL)) where = "第二课堂";
        else if (url.contains("nnvtu.edu.cn")) where = "门户 H5";
        else where = "页面";
        hint.setText("当前：" + where + "　登录后会自动进入第二课堂并保存会话");
    }

    /** 在页面里找二课入口并点它（含 iframe）。 */
    private void clickSecondClassEntry(String url) {
        if (autoTried >= 3) return;

        final String js = "(function(){"
                + "function scan(doc){"
                + " var kw=['\u7b2c\u4e8c\u8bfe\u5802','\u4e8c\u8bfe'];"
                + " var els=doc.querySelectorAll('a,li,div,span,img,button');"
                + " for(var i=0;i<els.length;i++){var e=els[i];"
                + "  var txt=(e.innerText||'')+' '+(e.getAttribute('title')||'')"
                + "    +' '+(e.getAttribute('alt')||'');"
                + "  for(var k2=0;k2<kw.length;k2++){ if(txt.indexOf(kw[k2])>=0){"
                + "    var t=(e.innerText||'').trim(); if(t.length>40) continue;"
                + "    var n=e, rr=null;"
                + "    for(var up=0;up<4&&n;up++){ var r2=n.getBoundingClientRect();"
                + "      if(r2.width>2&&r2.height>2){ rr=r2; } n=n.parentElement; }"
                + "    if(!rr) continue;"
                + "    return 'rect:'+Math.round(rr.left)+','+Math.round(rr.top)+','"
                + "      +Math.round(rr.width)+','+Math.round(rr.height); } } }"
                + " return ''; }"
                + " var h=scan(document); if(h) return h;"
                + " var ifs=document.querySelectorAll('iframe');"
                + " for(var i=0;i<ifs.length;i++){ try{ var d=ifs[i].contentDocument;"
                + "  if(d){ var h2=scan(d); if(h2) return h2; } }catch(e){} }"
                + " return '';})()";

        web.evaluateJavascript(js, value -> {
            String v = value == null ? "" : value.replace("\"", "");
            if (v.startsWith("rect:")) {
                autoTried++;
                String[] p = v.substring(5).split(",");
                try {
                    float scale = web.getScale();
                    float x = (Float.parseFloat(p[0]) + Float.parseFloat(p[2]) / 2f) * scale;
                    float y = (Float.parseFloat(p[1]) + Float.parseFloat(p[3]) / 2f) * scale;
                    hint.setText("已找到「第二课堂」，正在自动点击…");
                    tapWeb(x, y);
                } catch (Exception e) {
                    hint.setText("自动点击失败，请手动点一下「第二课堂」");
                }
                return;
            }
            if (scanRound >= 4 && scanRound < 8) {
                web.evaluateJavascript("(function(){"
                        + "var kw=['\u7b2c\u4e8c\u8bfe\u5802','\u4e8c\u8bfe'];"
                        + "var els=document.querySelectorAll('div,span,a,li');"
                        + "for(var i=0;i<els.length;i++){var e=els[i];"
                        + "var t=(e.innerText||'').trim(); if(t.length>40) continue;"
                        + "for(var k=0;k<kw.length;k++){ if(t.indexOf(kw[k])>=0){"
                        + "  try{ e.click(); }catch(x){}"
                        + "  var p=e.parentElement; if(p){ try{ p.click(); }catch(x){} }"
                        + "  return 'clicked'; } } }"
                        + "return 'none';})()", null);
            }
            if (scanRound < 12) {
                scanRound++;
                hint.setText("正在门户里查找「第二课堂」…（" + scanRound + "/12）");
                web.postDelayed(() -> {
                    if (!isFinishing()) clickSecondClassEntry(url);
                }, 1500);
                return;
            }
            if (url.contains("ids7.ncvt.net")) {
                hint.setText("请先输入学号密码登录，登录后会带你到门户");
            } else if (!url.startsWith(SCHOOL)) {
                hint.setText("没自动找到，请手动点开「第二课堂」，到达后会自动完成");
            }
        });
    }

    /** 往 WebView 注入一次真实触摸（DOWN + UP），Vue 组件才会当成用户点击。 */
    private void tapWeb(final float x, final float y) {
        final long down = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent d = android.view.MotionEvent.obtain(down, down,
                android.view.MotionEvent.ACTION_DOWN, x, y, 0);
        web.dispatchTouchEvent(d);
        d.recycle();
        web.postDelayed(() -> {
            long up = android.os.SystemClock.uptimeMillis();
            android.view.MotionEvent u = android.view.MotionEvent.obtain(down, up,
                    android.view.MotionEvent.ACTION_UP, x, y, 0);
            web.dispatchTouchEvent(u);
            u.recycle();
        }, 90);
    }

    /** 用真实接口确认会话是否已生效，SSO 往返需要几秒，所以轮询。 */
    private void verify(final String ck, final int round) {
        if (isFinishing()) return;
        hint.setText("正在确认二课会话…（第 " + (round + 1) + " 次）");
        String js = "window.__sc='pending';fetch('/Student/Activity/getActivityCanApply.html',{method:'POST',"
                + "headers:{'Content-Type':'application/x-www-form-urlencoded; charset=UTF-8',"
                + "'X-Requested-With':'XMLHttpRequest'},credentials:'include',"
                + "body:'moduleID=&typeID=&keywords=&sortByTime=&sortByScore='})"
                + ".then(function(r){return r.text();})"
                + ".then(function(t){window.__sc=(t.trim().charAt(0)==='[')?'ok':'no';})"
                + ".catch(function(){window.__sc='err';});";
        web.evaluateJavascript(js, null);
        web.postDelayed(() -> web.evaluateJavascript("window.__sc||'none'", v -> {
            boolean ok = v != null && v.contains("ok");
            if (ok) {
                save(ck);
            } else if (round < 5) {
                verify(ck, round + 1);
            } else {
                hint.setText("会话还没生效：请点一下门户里的「第二课堂」图标，加载完再按完成");
            }
        }), 1500);
    }

    private void doFinish() {
        String ck = CookieManager.getInstance().getCookie(SCHOOL);
        if (ck != null && ck.contains("PHPSESSID")) {
            save(ck);
        } else {
            Toast.makeText(this, "还没拿到二课会话，请点开「第二课堂」后再按完成", Toast.LENGTH_LONG).show();
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    private void save(String ck) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_COOKIE, ck).apply();
        Toast.makeText(this, "二课登录成功", Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
