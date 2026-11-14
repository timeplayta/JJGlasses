package com.jjglasses.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Lembretes e alarmes do JJGlasses.
 * O app manda a lista (JSON) e aqui cada um vira um "alarme do sistema" (AlarmManager):
 * toca na hora certa mesmo com o app fechado e o celular bloqueado.
 */
public class Reminders extends BroadcastReceiver {
    private static final String PREFS = "lembretes";

    /** Recebe a lista inteira do app, cancela os antigos e marca os novos. */
    static void sync(Context ctx, String json) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray old = new JSONArray(sp.getString("list", "[]"));
            for (int i = 0; i < old.length(); i++) cancel(ctx, old.getJSONObject(i).optString("id"));
            JSONArray list = new JSONArray(json == null ? "[]" : json);
            sp.edit().putString("list", list.toString()).apply();
            for (int i = 0; i < list.length(); i++) schedule(ctx, list.getJSONObject(i));
        } catch (Exception e) { CrashReport.note(ctx, e); }
    }

    /** Depois que o celular reinicia, marca tudo de novo. */
    static void rescheduleAll(Context ctx) {
        try {
            JSONArray list = new JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("list", "[]"));
            for (int i = 0; i < list.length(); i++) schedule(ctx, list.getJSONObject(i));
        } catch (Exception e) { CrashReport.note(ctx, e); }
    }

    private static PendingIntent pi(Context ctx, String id, JSONObject r) {
        Intent i = new Intent(ctx, Reminders.class).setAction("jj.lembrete." + id);
        if (r != null) i.putExtra("r", r.toString());
        return PendingIntent.getBroadcast(ctx, id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void cancel(Context ctx, String id) {
        if (id == null || id.isEmpty()) return;
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pi(ctx, id, null));
    }

    private static void schedule(Context ctx, JSONObject r) {
        long when = r.optLong("when", 0);
        String repeat = r.optString("repeat", "nao");
        long step = "diario".equals(repeat) ? 86_400_000L : "semanal".equals(repeat) ? 604_800_000L : 0;
        long now = System.currentTimeMillis();
        if (when < now - 60_000) { if (step == 0) return; while (when < now) when += step; }
        try { r.put("when", when); } catch (Exception ignored) { }
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        PendingIntent p = pi(ctx, r.optString("id"), r);
        try {
            boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
            if (exact) {
                PendingIntent show = PendingIntent.getActivity(ctx, 1, new Intent(ctx, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(Math.max(when, now + 1000), show), p);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, Math.max(when, now + 1000), p);
            }
        } catch (Exception e) { CrashReport.note(ctx, e); }
    }

    @Override public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || "android.intent.action.MY_PACKAGE_REPLACED".equals(a)) { rescheduleAll(ctx); return; }
        try {
            JSONObject r = new JSONObject(intent.getStringExtra("r"));
            boolean alarm = "alarme".equals(r.optString("type"));
            notify(ctx, r.optString("id"), alarm, r.optString("text", "Lembrete"));
            // se repete, já marca o próximo
            if (!"nao".equals(r.optString("repeat", "nao"))) { r.put("when", r.optLong("when") + 1000); schedule(ctx, r); }
        } catch (Exception e) { CrashReport.note(ctx, e); }
    }

    private static void notify(Context ctx, String id, boolean alarm, String text) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        String ch = alarm ? "alarmes" : "lembretes";
        if (nm.getNotificationChannel(ch) == null) {
            NotificationChannel c = new NotificationChannel(ch, alarm ? "Alarmes" : "Lembretes", NotificationManager.IMPORTANCE_HIGH);
            Uri sound = RingtoneManager.getDefaultUri(alarm ? RingtoneManager.TYPE_ALARM : RingtoneManager.TYPE_NOTIFICATION);
            c.setSound(sound, new AudioAttributes.Builder().setUsage(alarm ? AudioAttributes.USAGE_ALARM : AudioAttributes.USAGE_NOTIFICATION).build());
            c.enableVibration(true);
            c.setVibrationPattern(alarm ? new long[]{0, 800, 400, 800, 400, 800} : new long[]{0, 300, 150, 300});
            nm.createNotificationChannel(c);
        }
        PendingIntent open = PendingIntent.getActivity(ctx, id.hashCode(), new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(ctx, ch)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(alarm ? "⏰ Alarme" : "🔔 Lembrete")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(alarm ? Notification.CATEGORY_ALARM : Notification.CATEGORY_REMINDER)
                .setContentIntent(open).setAutoCancel(true);
        if (alarm) b.setFullScreenIntent(open, true);
        Notification n = b.build();
        if (alarm) n.flags |= Notification.FLAG_INSISTENT; // alarme toca até a pessoa mexer
        try { nm.notify(id.hashCode(), n); } catch (SecurityException e) { CrashReport.note(ctx, e); }
    }
}
