package com.jjglasses.app;

import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * "Caixa-preta" do app: se ele fechar sozinho por erro, guarda o motivo
 * e manda para a tabela crash_reports do Supabase, para o desenvolvedor ver e corrigir.
 */
final class CrashReport {
    private static final String SB_URL = "https://rfbrflmibivnajsbexeb.supabase.co";
    private static final String SB_KEY = BuildConfigKeys.SB_KEY;

    private CrashReport() { }

    static void install(Context context) {
        Context app = context.getApplicationContext();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                String text = describe(app, error);
                save(app, text);
                Thread t = new Thread(() -> upload(app, text));
                t.start();
                t.join(3000);
            } catch (Throwable ignored) { }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    /** Erro que não derruba o app, mas que vale registrar. */
    static void note(Context context, Throwable error) {
        Context app = context.getApplicationContext();
        String text = "AVISO (app continuou aberto)\n" + describe(app, error);
        new Thread(() -> upload(app, text)).start();
    }

    /** Manda o erro guardado da última vez que o app fechou. */
    static void sendPending(Context context) {
        Context app = context.getApplicationContext();
        File f = file(app);
        if (!f.exists()) return;
        new Thread(() -> {
            try {
                String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                if (upload(app, text)) f.delete();
            } catch (Throwable ignored) { }
        }).start();
    }

    private static File file(Context app) { return new File(app.getFilesDir(), "ultimo-erro.txt"); }

    private static void save(Context app, String text) {
        try (FileOutputStream out = new FileOutputStream(file(app))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) { }
    }

    private static String version(Context app) {
        try { return app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName; }
        catch (Throwable e) { return "?"; }
    }

    private static String describe(Context app, Throwable error) {
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        return "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") " + Build.MANUFACTURER + " " + Build.MODEL
            + "\nCasca " + version(app) + "\n\n" + sw;
    }

    private static boolean upload(Context app, String text) {
        try {
            JSONObject row = new JSONObject()
                .put("app_version", version(app))
                .put("device", Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE)
                .put("report", text.length() > 19000 ? text.substring(0, 19000) : text);
            HttpURLConnection c = (HttpURLConnection) new URL(SB_URL + "/rest/v1/crash_reports").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setDoOutput(true);
            c.setRequestProperty("apikey", SB_KEY);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Prefer", "return=minimal");
            c.getOutputStream().write(row.toString().getBytes(StandardCharsets.UTF_8));
            int code = c.getResponseCode();
            c.disconnect();
            return code >= 200 && code < 300;
        } catch (Throwable e) {
            return false;
        }
    }
}
