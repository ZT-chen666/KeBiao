package com.ncvt.kebiao.ext;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.ncvt.kebiao.R;

/** 关于 / 声明 / 用户协议。 */
public class AboutActivity extends Activity {

    static final String AGREEMENT = ""
            + "【重要声明】\n"
            + "本软件仅供学习与技术交流参考，禁止任何形式的商业用途。\n"
            + "本软件完全免费。任何人以任何名义向你售卖本软件、本软件的安装包或功能，"
            + "都是骗子，请勿付款。\n"
            + "\n【用户协议】\n"
            + "1. 本软件只读取你本人账号在校园系统中的数据，不代替你提交报名、签到等需要本人确认的操作。\n"
            + "2. 自动学习功能由你自行决定开启，开启后产生的学习记录、评价等结果由你本人承担。\n"
            + "3. 你的账号与密码只在你手机上的学校登录页输入，不经过任何第三方服务器；"
            + "登录会话保存在本机，可随时通过重新登录覆盖。\n"
            + "4. 所有数据以学校系统为准，本软件不对数据的完整性、准确性作任何保证。\n"
            + "5. 因使用或无法使用本软件造成的任何直接或间接损失，由使用者自行承担。\n"
            + "6. 若你不同意上述条款，请停止使用并卸载本软件。\n"
            + "\n【联系方式】\n"
            + "开源地址：\n"
            + "https://github.com/ZT-chen666/KeBiao\n"
            + "\n"
            + "作者一：2406609483@qq.com\n"
            + "作者二：3188615046@qq.com";

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
        title.setText("关于 · 声明");
        title.setTextSize(16);
        title.setTextColor(getResources().getColor(R.color.sc_text, getTheme()));
        title.getPaint().setFakeBoldText(true);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(bar);

        ScrollView sc = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(32));

        TextView warn = new TextView(this);
        warn.setText("仅供学习参考 · 完全免费\n任何售卖都是骗子");
        warn.setTextSize(15);
        warn.setTextColor(getResources().getColor(R.color.sc_red, getTheme()));
        warn.getPaint().setFakeBoldText(true);
        warn.setGravity(Gravity.CENTER);
        warn.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable wbg = new GradientDrawable();
        wbg.setCornerRadius(dp(14));
        wbg.setColor(getResources().getColor(R.color.sc_red_bg, getTheme()));
        wbg.setStroke(dp(1), 0x55DC2626);
        warn.setBackground(wbg);
        TextView ver = new TextView(this);
        ver.setText("版本 " + ExtUi.VERSION);
        ver.setTextSize(11);
        ver.setGravity(Gravity.CENTER);
        ver.setTextColor(getResources().getColor(R.color.sc_text_hint, getTheme()));
        ver.setPadding(0, dp(8), 0, 0);
        ver.setOnClickListener(v -> UpdateChecker.checkManually(this));
        box.addView(ver);

        box.addView(warn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView body = new TextView(this);
        body.setText(AGREEMENT);
        body.setTextSize(13);
        body.setLineSpacing(dp(4), 1f);
        body.setTextColor(getResources().getColor(R.color.sc_text, getTheme()));
        body.setPadding(0, dp(18), 0, 0);
        box.addView(body);

        sc.addView(box);
        root.addView(sc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        ExtUi.applyInsets(this);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
