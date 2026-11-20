package com.jjglasses.app;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Atualização automática do APK (sem precisar mandar link para ninguém).
 *
 * Como funciona:
 * 1. O robô do GitHub publica o APK novo e o arquivo apk.json (com o número da versão e a "impressão digital").
 * 2. De tempos em tempos (a cada 4 h, mesmo com o app fechado) o celular olha o apk.json.
 *    Se tem versão nova, aparece a notificação "Nova atualização do seu JJGlasses".
 * 3. A pessoa abre o app e toca em "Autorizar e atualizar": o app baixa o APK (barra de 1% a 100%),
 *    confere se veio inteiro e instala por cima, sem apagar nada (conversas, login e ajustes ficam).
 *
 * Na primeira vez o Android pede para permitir "instalar apps desta fonte" para o JJGlasses.
 * Do Android 12 em diante, depois da primeira atualização feita pelo próprio app, as próximas nem pedem confirmação.
 */
public class Updater {
    public interface Out { void js(String code); }

    static final String INFO_URL = MainActivity.BASE + "apk.json";
    static final String APK_URL = MainActivity.BASE + "JJGlasses.apk";
    static final int JOB_ID = 4242, NOTIF_ID = 4243, DONE_ID = 4244;
    static final String CH = "atualizacao";

    static volatile Out out;          // a tela do app (para mostrar a barra)
    static volatile boolean busy;

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("updater", Context.MODE_PRIVATE); }

    @SuppressWarnings("deprecation")
    static long installedCode(Context c) {
        try {
            android.content.pm.PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) { return 0; }
    }

    static JSONObject fetchInfo() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(INFO_URL + "?t=" + System.currentTimeMillis()).openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(8000); c.setUseCaches(false);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream b = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096]; int k;
                while ((k = in.read(buf)) > 0) b.write(buf, 0, k);
                return new JSONObject(b.toString("UTF-8"));
            }
        } catch (Exception e) { return null; }
        finally { if (c != null) c.disconnect(); }
    }

    static boolean isNewer(Context c, JSONObject i) { return i != null && i.optLong("code", 0) > installedCode(c); }

    // ------------------------------------------------------------ olhar em segundo plano + notificação

    /** Marca a checagem a cada 4 h (continua depois de reiniciar o celular). */
    static void schedule(Context c) {
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js == null || js.getPendingJob(JOB_ID) != null) return;
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, Job.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(4 * 60 * 60 * 1000L)
                .setPersisted(true)
                .build());
        } catch (Exception e) { CrashReport.note(c, e); }
    }

    /** "Trabalho" que o Android roda de tempos em tempos, mesmo com o app fechado. */
    public static class Job extends JobService {
        @Override public boolean onStartJob(JobParameters p) {
            new Thread(() -> {
                try { check(getApplicationContext()); } catch (Throwable ignored) { }
                jobFinished(p, false);
            }, "jj-update-job").start();
            return true;
        }
        @Override public boolean onStopJob(JobParameters p) { return true; }
    }

    static void check(Context c) {
        JSONObject i = fetchInfo();
        if (isNewer(c, i)) notifyUpdate(c, i);
    }

    private static void channel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(new NotificationChannel(CH, "Atualizações do JJGlasses", NotificationManager.IMPORTANCE_DEFAULT));
    }

    private static PendingIntent openApp(Context c, boolean update) {
        Intent it = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (update) it.putExtra("jjAtualizar", true);
        return PendingIntent.getActivity(c, update ? 1 : 2, it, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Notificação "Nova atualização do seu JJGlasses" (uma vez por versão, e lembra de novo depois de 1 dia). */
    static void notifyUpdate(Context c, JSONObject i) {
        long code = i.optLong("code");
        SharedPreferences p = sp(c);
        if (p.getLong("avisado", 0) == code && System.currentTimeMillis() - p.getLong("avisadoEm", 0) < 24 * 3600_000L) return;
        p.edit().putLong("avisado", code).putLong("avisadoEm", System.currentTimeMillis()).apply();
        channel(c);
        JSONObject n = i.optJSONObject("notes");
        String what = n != null && n.optString("t").length() > 0 ? "Novidade: " + n.optString("t") + ". " : "";
        Notification nt = new Notification.Builder(c, CH)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Nova atualização do seu JJGlasses")
            .setContentText("Abra o app e autorize para atualizar.")
            .setStyle(new Notification.BigTextStyle().bigText(what + "Abra o app e autorize para atualizar. Suas conversas e ajustes continuam salvos."))
            .setContentIntent(openApp(c, true))
            .setAutoCancel(true)
            .build();
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        try { if (nm != null) nm.notify(NOTIF_ID, nt); } catch (SecurityException ignored) { } // sem permissão de notificação
    }

    /** Depois que o APK novo instalou (o Android avisa com MY_PACKAGE_REPLACED). */
    static void notifyDone(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.cancel(NOTIF_ID);
        if (!sp(c).getBoolean("instalando", false)) return;   // só avisa quando foi o próprio app que atualizou
        sp(c).edit().putBoolean("instalando", false).apply();
        channel(c);
        Notification nt = new Notification.Builder(c, CH)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("JJGlasses atualizado ✓")
            .setContentText("Toque para abrir.")
            .setContentIntent(openApp(c, false))
            .setAutoCancel(true)
            .build();
        try { nm.notify(DONE_ID, nt); } catch (SecurityException ignored) { }
    }

    // ------------------------------------------------------------ baixar e instalar

    private static void prog(int pct, String fase) {
        Out o = out;
        if (o != null) o.js("window.jjApkProg&&window.jjApkProg({pct:" + pct + ",fase:" + JSONObject.quote(fase) + "})");
    }

    /** A pessoa autorizou na tela do app: baixa com barra de progresso e instala por cima. */
    static void start(Activity a) {
        if (busy) return;
        Context c = a.getApplicationContext();
        // 1ª vez: o Android precisa deixar o JJGlasses instalar a própria atualização
        if (Build.VERSION.SDK_INT >= 26 && !c.getPackageManager().canRequestPackageInstalls()) {
            sp(c).edit().putBoolean("continuar", true).apply();
            prog(0, "permissao");
            try { a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.getPackageName()))); }
            catch (Exception e) { prog(0, "erro"); }
            return;
        }
        sp(c).edit().putBoolean("continuar", false).apply();
        busy = true;
        new Thread(() -> install(c), "jj-update").start();
    }

    /** Voltou das configurações do Android: se a permissão foi dada, continua sozinho. */
    static void resume(Activity a) {
        Context c = a.getApplicationContext();
        if (!sp(c).getBoolean("continuar", false)) return;
        if (Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls()) start(a);
        else { sp(c).edit().putBoolean("continuar", false).apply(); prog(0, "semPermissao"); }
    }

    private static void install(Context c) {
        PackageInstaller.Session s = null;
        try {
            prog(1, "baixando");
            JSONObject info = fetchInfo();
            String want = info != null ? info.optString("sha256", "") : "";
            PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            sp.setAppPackageName(c.getPackageName());
            if (Build.VERSION.SDK_INT >= 31) sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            int id = pi.createSession(sp);
            s = pi.openSession(id);

            HttpURLConnection h = (HttpURLConnection) new URL(APK_URL + "?t=" + System.currentTimeMillis()).openConnection();
            h.setConnectTimeout(10000); h.setReadTimeout(30000); h.setUseCaches(false);
            long total = h.getContentLengthLong(), done = 0, last = 0;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = h.getInputStream(); OutputStream os = s.openWrite("base.apk", 0, total > 0 ? total : -1)) {
                byte[] buf = new byte[65536]; int k;
                while ((k = in.read(buf)) > 0) {
                    os.write(buf, 0, k); md.update(buf, 0, k); done += k;
                    long now = System.currentTimeMillis();
                    if (total > 0 && now - last > 250) { last = now; prog((int) Math.max(1, Math.min(94, done * 94 / total)), "baixando"); }
                }
                s.fsync(os);
            } finally { h.disconnect(); }
            if (total > 0 && done != total) throw new Exception("veio incompleto");
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) hex.append(String.format("%02x", b));
            if (want.length() > 0 && !want.equalsIgnoreCase(hex.toString())) throw new Exception("arquivo diferente do publicado");

            prog(96, "instalando");
            sp(c).edit().putBoolean("instalando", true).apply();
            Intent st = new Intent(c, Status.class);
            PendingIntent pend = PendingIntent.getBroadcast(c, id, st, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
            s.commit(pend.getIntentSender());
            s.close(); s = null;
        } catch (Exception e) {
            if (s != null) { try { s.abandon(); } catch (Exception ignored) { } }
            busy = false;
            sp(c).edit().putBoolean("instalando", false).apply();
            prog(0, "erro");
        }
    }

    /** Resposta do Android depois de mandar instalar. */
    public static class Status extends BroadcastReceiver {
        @SuppressWarnings("deprecation")
        @Override public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                // o Android quer que a pessoa confirme (1ª vez): abre a janela "Atualizar este app?"
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                prog(98, "confirmar");
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { c.startActivity(confirm); } catch (Exception e) { busy = false; prog(0, "erro"); }
                }
            } else if (st == PackageInstaller.STATUS_SUCCESS) {
                busy = false;
                prog(100, "pronto");
            } else {
                busy = false;
                sp(c).edit().putBoolean("instalando", false).apply();
                prog(0, st == PackageInstaller.STATUS_FAILURE_ABORTED ? "cancelado" : "erro");
            }
        }
    }
}
