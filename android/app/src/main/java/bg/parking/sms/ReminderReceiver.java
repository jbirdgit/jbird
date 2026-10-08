package bg.parking.sms;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Напомняния 5 минути преди и при изтичане на паркирането, дори при затворено приложение. */
public class ReminderReceiver extends BroadcastReceiver {

    private static final String CHANNEL = "parking";
    private static final int WARN = 1;
    private static final int EXPIRED = 2;

    static void schedule(Context ctx, long end) {
        cancel(ctx);
        long warnAt = end - 5 * 60_000L;
        if (warnAt > System.currentTimeMillis()) set(ctx, WARN, warnAt);
        set(ctx, EXPIRED, end);
    }

    static void cancel(Context ctx) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        am.cancel(pending(ctx, WARN));
        am.cancel(pending(ctx, EXPIRED));
        ctx.getSystemService(NotificationManager.class).cancelAll();
    }

    private static void set(Context ctx, int kind, long at) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        PendingIntent pi = pending(ctx, kind);
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    private static PendingIntent pending(Context ctx, int kind) {
        Intent i = new Intent(ctx, ReminderReceiver.class).putExtra("kind", kind);
        return PendingIntent.getBroadcast(ctx, kind, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "Изтичане на паркиране", NotificationManager.IMPORTANCE_HIGH);
        channel.enableVibration(true);
        nm.createNotificationChannel(channel);

        String plate = ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getString("session.plate", "");
        boolean expired = intent.getIntExtra("kind", WARN) == EXPIRED;

        PendingIntent open = PendingIntent.getActivity(ctx, 0,
                new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(expired ? "Паркирането изтече!" : "Паркирането изтича след 5 минути")
                .setContentText(plate + " · отворете, за да удължите")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        nm.notify(1, n);
    }
}
