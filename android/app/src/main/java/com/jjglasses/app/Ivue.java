package com.jjglasses.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Óculos iVUE Denali pelo Wi-Fi.
 * O óculos cria a própria rede Wi-Fi ("iVUE") e fica no endereço 192.168.1.254.
 * Lá ele tem um servidor de arquivos (HTTP) com as pastas DCIM/Movie (vídeos) e DCIM/Photo (fotos).
 * Aqui o app lista esses arquivos e baixa para a galeria do celular. Nunca apaga nada do óculos.
 *
 * Detalhe importante: a rede do óculos não tem internet, então o Android tenta mandar tudo pelos
 * dados móveis. Por isso cada pedido é feito "preso" na rede Wi-Fi (network.openConnection).
 */
public class Ivue {
    public interface Out { void js(String code); }

    static final String HOST = "http://192.168.1.254";
    private final Context ctx;
    private final Out out;
    private final ExecutorService ex = Executors.newSingleThreadExecutor();
    private volatile boolean cancel = false;

    Ivue(Context ctx, Out out) { this.ctx = ctx.getApplicationContext(); this.out = out; }

    /** A rede Wi-Fi em que o celular está (a do óculos). */
    private Network wifi() {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
        }
        return null;
    }

    private HttpURLConnection open(String path, int readTimeout) throws Exception {
        URL u = new URL(HOST + path);
        Network n = wifi();
        HttpURLConnection c = (HttpURLConnection) (n != null ? n.openConnection(u) : u.openConnection());
        c.setConnectTimeout(4000);
        c.setReadTimeout(readTimeout);
        c.setUseCaches(false);
        return c;
    }

    private String get(String path) throws Exception {
        HttpURLConnection c = open(path, 8000);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream b = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int k;
            while ((k = in.read(buf)) > 0) { b.write(buf, 0, k); if (b.size() > 2_000_000) break; }
            return b.toString(StandardCharsets.UTF_8.name());
        } finally { c.disconnect(); }
    }

    // linha da página do óculos: <a href="/DCIM/Movie/x.MP4"><b>x.MP4</b></a><td align=right> 120 MB<td align=right>2026/11/19 16:44:20
    private static final Pattern ROW = Pattern.compile(
        "<a href=\"([^\"]+)\"><b>([^<]+)</b></a><td align=(?:right|center)>\\s*(?:<i>(folder)</i>|([^<]*))<td align=right>([^<]*)");

    /** Lista os arquivos de uma pasta do óculos (entra uma vez em subpastas). */
    private void list(String dir, JSONArray outArr, int depth) throws Exception {
        String html = get(dir);
        Matcher m = ROW.matcher(html);
        while (m.find()) {
            String href = m.group(1), name = m.group(2).trim();
            if (m.group(3) != null) { if (depth < 1 && !name.startsWith("System")) list(href, outArr, depth + 1); continue; }
            JSONObject f = new JSONObject();
            f.put("path", href); f.put("nome", name);
            f.put("tamanho", m.group(4) == null ? "" : m.group(4).trim());
            f.put("data", m.group(5) == null ? "" : m.group(5).trim());
            outArr.put(f);
        }
    }

    /** O app pede a lista: responde em window.jjIvue({ok, videos, fotos}) ou ({ok:false, erro}). */
    public void listAll() {
        ex.submit(() -> {
            JSONObject r = new JSONObject();
            try {
                JSONArray v = new JSONArray(), f = new JSONArray();
                list("/DCIM/Movie", v, 0);
                try { list("/DCIM/Photo", f, 0); } catch (Exception ignored) { }
                r.put("ok", true); r.put("videos", v); r.put("fotos", f);
            } catch (Exception e) {
                try { r.put("ok", false); r.put("erro", String.valueOf(e.getMessage())); } catch (Exception ignored) { }
            }
            out.js("window.jjIvue&&window.jjIvue(" + r + ")");
        });
    }

    /** Baixa um arquivo do óculos para a galeria do celular (Filmes/JJGlasses ou Imagens/JJGlasses). */
    public void download(String path, String nome) {
        cancel = false;
        ex.submit(() -> {
            JSONObject p = new JSONObject();
            Uri uri = null;
            try {
                p.put("path", path);
                boolean video = nome.toLowerCase().matches(".*\\.(mp4|mov|avi|ts)$");
                String mime = video ? "video/mp4" : "image/jpeg";
                HttpURLConnection c = open(path, 30000);
                long total = c.getContentLengthLong();
                OutputStream os; File file = null;
                ContentResolver cr = ctx.getContentResolver();
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, nome);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/JJGlasses");
                    cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    uri = cr.insert(video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                    os = cr.openOutputStream(uri);
                } else {
                    File d = new File(ctx.getExternalFilesDir(video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES), "JJGlasses");
                    d.mkdirs(); file = new File(d, nome); os = new FileOutputStream(file);
                }
                long done = 0, last = 0;
                try (InputStream in = c.getInputStream(); OutputStream o = os) {
                    byte[] buf = new byte[65536]; int k;
                    while ((k = in.read(buf)) > 0) {
                        if (cancel) throw new Exception("cancelado");
                        o.write(buf, 0, k); done += k;
                        long now = System.currentTimeMillis();
                        if (now - last > 400) { last = now; prog(path, done, total, null, null); }
                    }
                } finally { c.disconnect(); }
                if (Build.VERSION.SDK_INT >= 29) { ContentValues cv = new ContentValues(); cv.put(MediaStore.MediaColumns.IS_PENDING, 0); cr.update(uri, cv, null, null); }
                else if (file != null) uri = Uri.fromFile(file);
                prog(path, done, total, uri.toString(), null);
            } catch (Exception e) {
                if (uri != null && Build.VERSION.SDK_INT >= 29) { try { ctx.getContentResolver().delete(uri, null, null); } catch (Exception ignored) { } }
                prog(path, 0, 0, null, String.valueOf(e.getMessage()));
            }
        });
    }

    public void cancelDownload() { cancel = true; }

    private void prog(String path, long done, long total, String uri, String erro) {
        try {
            JSONObject p = new JSONObject();
            p.put("path", path); p.put("feito", done); p.put("total", total);
            if (uri != null) p.put("uri", uri);
            if (erro != null) p.put("erro", erro);
            out.js("window.jjIvueProg&&window.jjIvueProg(" + p + ")");
        } catch (Exception ignored) { }
    }

    /** Abre o vídeo ou a foto baixada no player/galeria do celular. */
    public void openFile(String uri, String mime) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.parse(uri), mime);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try { ctx.startActivity(i); } catch (Exception ignored) { }
    }
}
