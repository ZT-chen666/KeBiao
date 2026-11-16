package com.ncvt.kebiao.ext;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

/** 二课活动的本地提醒（活动开始前 30 分钟）。 */
public class ActivityReminderReceiver extends BroadcastReceiver {

    static final String CHANNEL = "second_class_reminder";

    @Override
    public void onReceive(Context context, Intent intent) {
        String name = intent.getStringExtra("name");
        String id = intent.getStringExtra("id");
        if (name == null) name = "二课活动";

        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "二课提醒",
                    NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(ch);
        }

        Intent open = new Intent(context, ApplyWebViewActivity.class);
        open.putExtra(ApplyWebViewActivity.EXTRA_ID, id);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(context, id == null ? 0 : id.hashCode(),
                open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(com.ncvt.kebiao.R.drawable.ic_sc)
                .setContentTitle("二课活动 30 分钟后开始")
                .setContentText(name)
                .setAutoCancel(true)
                .setContentIntent(pi);
        nm.notify(id == null ? 1 : id.hashCode(), b.build());
    }
}
