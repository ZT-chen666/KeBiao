package com.ncvt.kebiao.ext;

import android.app.Activity;
import android.app.AlertDialog;
import java.io.File;
import android.widget.Toast;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 免费更新：从自己的 GitHub 仓库读一个 update.json 比对版本。
 *
 * 仓库里放一个 update.json（main 分支根目录）：
 * {
 *   "versionCode": 109,
 *   "versionName": "1.9",
 *   "url": "https://github.com/你的用户名/仓库名/releases/download/v1.9/app-release.apk",
 *   "notes": "1. 修复xxx\n2. 新增xxx"
 * }
 *
 * 第一次发布把下面的 OWNER / REPO 改成你自己的即可，不需要任何服务器、不需要 token。
 */
final class UpdateChecker {

    static final String OWNER = "ZT-chen666";
    static final String REPO = "KeBiao";
    static final String BRANCH = "main";

    /** 与 ExtUi.VERSION 对应的整数版本，发布新版时 +1。 */
    static final int VERSION_CODE = 118;
    static final String VERSION_NAME = "1.18";

    private static final String CHECKED_AT = "update_checked_at";
    private static final long SIX_HOURS = 6 * 60 * 60 * 1000L;
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private UpdateChecker() {
    }

    /** 依次尝试的更新源（国内网络经常连不上 raw.githubusercontent.com）。 */
    private static String[] urls() {
        // 顺序很重要：前两个是不缓存的国内代理，拿到了就是最新；
        // jsDelivr 有 12 小时缓存，只作最后兜底。
        long stamp = System.currentTimeMillis() / 60000;
        String raw = "https://raw.githubusercontent.com/" + OWNER + "/" + REPO + "/"
                + BRANCH + "/update.json?t=" + stamp;
        String jsdelivr = "https://cdn.jsdelivr.net/gh/" + OWNER + "/" + REPO + "@"
                + BRANCH + "/update.json?t=" + stamp;
        return new String[]{
                "https://ghproxy.net/" + raw,
                "https://gh-proxy.com/" + raw,
                raw,
                jsdelivr,
        };
    }

    static boolean configured() {
        return !OWNER.startsWith("YOUR_");
    }

    private static boolean AUTO_CHECKED;

    /** 启动时调用：每次进应用都查一次（同一进程只查一次）。 */
    static void checkQuietly(final Activity activity) {
        if (!configured() || AUTO_CHECKED) return;
        AUTO_CHECKED = true;
        run(activity, false);
    }

    /** 手动点「检查更新」。 */
    static void checkManually(final Activity activity) {
        android.widget.Toast.makeText(activity, "正在检查更新…", android.widget.Toast.LENGTH_SHORT).show();
        if (!configured()) {
            new AlertDialog.Builder(activity)
                    .setTitle("还没配置仓库")
                    .setMessage("更新功能要先把 UpdateChecker 里的 OWNER / REPO 改成你自己的 GitHub 仓库。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        run(activity, true);
    }

    /** "v1.15" -> 115；"v2.0.1" -> 201。解析失败返回 0。 */
    private static int tagToCode(String tag) {
        if (tag == null) return 0;
        String t = tag.trim().toLowerCase().replace("v", "");
        String[] parts = t.split("\\.");
        try {
            int major = parts.length > 0 ? Integer.parseInt(parts[0]) : 0;
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major * 100 + minor;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 每条源的探测结果，供诊断用。 */
    private static boolean DIALOG_OPEN;
    private static final java.util.List<String> DIAG = new java.util.ArrayList<>();

    private static void run(final Activity activity, final boolean manual) {
        final String[] sources = urls();
        new Thread(() -> {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .build();

            String best = null;
            int bestCode = 0;
            synchronized (DIAG) { DIAG.clear(); }

            for (String src : sources) {
                String host = hostOf(src);
                try {
                    Request req = new Request.Builder().url(src).build();
                    try (Response resp = client.newCall(req).execute()) {
                        if (!resp.isSuccessful() || resp.body() == null) {
                            addDiag(host + "：HTTP " + resp.code());
                            continue;
                        }
                        String t = resp.body().string();
                        if (t == null || !t.trim().startsWith("{")) {
                            addDiag(host + "：返回不是 JSON");
                            continue;
                        }
                        JSONObject o = new JSONObject(t);
                        int code = o.optInt("versionCode", 0);
                        addDiag(host + "：v" + o.optString("versionName", "?") + " (" + code + ")");
                        if (code > bestCode) {
                            bestCode = code;
                            best = t;
                        }
                    }
                } catch (Exception e) {
                    addDiag(host + "：失败 " + shortErr(e));
                }
            }

            try {
                Request r2 = new Request.Builder()
                        .url("https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases/latest")
                        .header("Accept", "application/vnd.github+json").build();
                try (Response rr = client.newCall(r2).execute()) {
                    if (rr.isSuccessful() && rr.body() != null) {
                        JSONObject rel = new JSONObject(rr.body().string());
                        String tag = rel.optString("tag_name", "");
                        int num = tagToCode(tag);
                        addDiag("GitHub API：最新 " + tag);
                        if (num > bestCode) {
                            JSONObject o = new JSONObject();
                            o.put("versionCode", num);
                            o.put("versionName", tag.replace("v", ""));
                            o.put("url", rel.optString("html_url", ""));
                            o.put("notes", "");
                            best = o.toString();
                            bestCode = num;
                        }
                    }
                }
            } catch (Exception e) {
                addDiag("GitHub API：失败 " + shortErr(e));
            }

            final String b = best;
            UI.post(() -> handle(activity, b, manual));
        }).start();
    }

    private static void addDiag(String line) {
        synchronized (DIAG) { DIAG.add(line); }
    }

    private static String shortErr(Exception e) {
        String m = e.getMessage();
        if (m == null) m = e.getClass().getSimpleName();
        return m.length() > 40 ? m.substring(0, 40) : m;
    }

    private static String hostOf(String url) {
        try {
            int a = url.indexOf("://") + 3;
            int b = url.indexOf('/', a);
            return b > a ? url.substring(a, b) : url;
        } catch (Exception e) {
            return url;
        }
    }

    /** 诊断信息（给设置页长按用）。 */
    static String diagText() {
        StringBuilder sb = new StringBuilder();
        sb.append("当前版本：").append(VERSION_NAME).append(" (").append(VERSION_CODE).append(")\n\n");
        synchronized (DIAG) {
            if (DIAG.isEmpty()) sb.append("还没有检查记录，先点一次「检查更新」。");
            for (String l : DIAG) sb.append("· ").append(l).append("\n");
        }
        return sb.toString();
    }

    private static void handle(Activity activity, String body, boolean manual) {
        if (activity.isFinishing()) return;
        if (body == null) {
            if (manual) {
                Toast.makeText(activity, "网络不通，换个网络再试（长按可看诊断）",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        try {
            JSONObject o = new JSONObject(body);
            int code = o.optInt("versionCode", 0);
            final String name = o.optString("versionName", "");
            final String apk = o.optString("url", "");
            final String mirror = o.optString("mirror", "");
            String notes = o.optString("notes", "");
            if (code > VERSION_CODE && !apk.isEmpty()) {
                if (DIALOG_OPEN) return;
                DIALOG_OPEN = true;
                final String first = mirror.isEmpty() ? apk : mirror;
                final String second = mirror.isEmpty() ? "" : apk;
                AlertDialog.Builder b = new AlertDialog.Builder(activity)
                        .setTitle("发现新版本 " + name)
                        .setMessage(notes.isEmpty() ? "有新版本可用。" : notes)
                        .setPositiveButton("立即更新", (d, w) -> {
                            DIALOG_OPEN = false;
                            download(activity, first, second);
                        })
                        .setNegativeButton("稍后", (d, w) -> DIALOG_OPEN = false)
                        .setOnCancelListener(d -> DIALOG_OPEN = false);
                if (!second.isEmpty()) {
                    b.setNeutralButton("改用 GitHub", (d, w) -> download(activity, second, ""));
                }
                b.show();
            } else if (manual) {
                Toast.makeText(activity, "已是最新版本（" + VERSION_NAME + "）",
                        Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            if (manual) {
                Toast.makeText(activity, "解析失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    /** 测速：取响应最快的下载地址。 */
    private static String fastestUrl(Activity activity, String primary) {
        java.util.List<String> cand = new java.util.ArrayList<>();
        if (primary != null && !primary.isEmpty()) cand.add(primary);
        String plain = primary == null ? "" : primary.replace("https://ghproxy.net/", "")
                .replace("https://gh-proxy.com/", "");
        if (plain.startsWith("https://github.com/")) {
            cand.add("https://gh-proxy.com/" + plain);
            cand.add("https://ghfast.top/" + plain);
            cand.add("https://gh.llkk.cc/" + plain);
            cand.add("https://ghproxy.net/" + plain);
            cand.add(plain);
        }
        OkHttpClient probe = new OkHttpClient.Builder()
                .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
        String best = primary;
        long bestMs = Long.MAX_VALUE;
        for (String u : cand) {
            long t0 = System.currentTimeMillis();
            try {
                Request r = new Request.Builder().url(u)
                        .header("Range", "bytes=0-2047").build();
                try (Response resp = probe.newCall(r).execute()) {
                    if (resp.isSuccessful() && resp.body() != null) {
                        resp.body().bytes();
                        long ms = System.currentTimeMillis() - t0;
                        addDiag("测速 " + hostOf(u) + "：" + ms + "ms");
                        if (ms < bestMs) {
                            bestMs = ms;
                            best = u;
                        }
                    }
                }
            } catch (Exception e) {
                addDiag("测速 " + hostOf(u) + "：失败");
            }
        }
        return best == null ? primary : best;
    }

    /** 应用内下载 APK，下完直接拉起安装。 */
    private static void download(final Activity activity, final String url, final String fallback) {
        if (url == null || url.isEmpty()) return;
        final ProgressDialog pd = new ProgressDialog(activity);
        pd.setTitle("正在下载更新");
        pd.setMessage("0%");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setCancelable(false);
        pd.show();

        new Thread(() -> {
            String best = fastestUrl(activity, url);
            final File out = new File(activity.getCacheDir(), "kebiao-update.apk");
            String err = null;
            try {
                if (out.exists() && !out.delete()) {
                }
                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .build();
                Request req = new Request.Builder().url(best).build();
                try (Response resp = client.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        throw new java.io.IOException("HTTP " + resp.code());
                    }
                    long total = resp.body().contentLength();
                    java.io.InputStream in = resp.body().byteStream();
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                    byte[] buf = new byte[8192];
                    long done = 0;
                    long t0 = System.currentTimeMillis();
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                        done += n;
                        if (fallback != null && !fallback.isEmpty() && total > 0
                                && done < total * 5 / 100
                                && System.currentTimeMillis() - t0 > 5000) {
                            fos.close();
                            throw new java.io.IOException("源太慢");
                        }
                        if (total > 0) {
                            final int pct = (int) (done * 100 / total);
                            UI.post(() -> {
                                pd.setMessage(pct + "%");
                                pd.setProgress(pct);
                            });
                        }
                    }
                    fos.flush();
                    fos.close();
                    in.close();
                }
                if (out.length() < 1024 * 1024) {
                    throw new java.io.IOException("下载不完整");
                }
            } catch (Exception e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final String ferr = err;
            UI.post(() -> {
                try { pd.dismiss(); } catch (Exception ignored) { }
                if (ferr == null) {
                    install(activity, out);
                } else if (fallback != null && !fallback.isEmpty()) {
                    Toast.makeText(activity, "镜像失败（" + ferr + "），改用 GitHub…",
                            Toast.LENGTH_SHORT).show();
                    download(activity, fallback, "");
                } else {
                    new AlertDialog.Builder(activity).setTitle("下载失败")
                            .setMessage("原因：" + ferr + "\n\n可以到 Releases 页面手动下载。")
                            .setPositiveButton("好", null).show();
                }
            });
        }).start();
    }

    /** 用 FileProvider 拉起系统安装器。 */
    private static void install(Activity activity, File apk) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26
                    && !activity.getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(activity, "请允许安装未知来源应用后重试", Toast.LENGTH_LONG).show();
                Intent s = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(s);
                return;
            }
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    activity, activity.getPackageName() + ".fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(activity, "安装失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
