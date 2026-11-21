package com.jjglasses.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
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
    private final ExecutorService ex = Executors.newSingleThreadExecutor();      // downloads (podem demorar)
    private final ExecutorService exInfo = Executors.newSingleThreadExecutor();  // lista e estado (rápidos)
    private volatile boolean cancel = false;

    Ivue(Context ctx, Out out) { this.ctx = ctx.getApplicationContext(); this.out = out; }

    /** A rede Wi-Fi em que o celular está (a do óculos). */
    private Network wifi() { return wifiNet(ctx); }

    // ------------------------------------------------------------ Conectar direto pelo app
    // O app procura o Wi-Fi do óculos e conecta só ele (o resto do celular continua na internet normal).
    static volatile Network direct;                     // rede do óculos aberta pelo "Conectar"
    private ConnectivityManager.NetworkCallback cb;
    private volatile String estado = "desligado";

    private void net(String st) {
        estado = st;
        out.js("window.jjIvueNet&&window.jjIvueNet({estado:'" + st + "',rssi:" + rssi() + "})");
    }

    /** Força do sinal do Wi-Fi do óculos (em dBm, ex.: -55 é bom, -80 é fraco). 0 = não sei. */
    private int rssi() {
        try {
            Network n = wifiNet(ctx);
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities nc = n == null || cm == null ? null : cm.getNetworkCapabilities(n);
            if (nc != null && Build.VERSION.SDK_INT >= 29) { int r = nc.getSignalStrength(); return r == Integer.MIN_VALUE ? 0 : r; }
        } catch (Exception ignored) { }
        return 0;
    }

    /** Toque em "Conectar": o Android procura a rede (ssid) e mostra "Conectar ao iVUE?". */
    public void connect(String ssid, String senha) {
        if (Build.VERSION.SDK_INT < 29) { net("antigo"); return; }      // Android 9 ou menos: conectar pelas configurações
        if (wifiNet(ctx) != null && "conectado".equals(estado)) { net("conectado"); return; }
        disconnect(false);
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) { net("naoAchou"); return; }
        try {
            WifiNetworkSpecifier.Builder sb = new WifiNetworkSpecifier.Builder().setSsid(ssid);
            if (senha != null && senha.length() >= 8) sb.setWpa2Passphrase(senha);
            NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)   // a rede do óculos não tem internet
                .setNetworkSpecifier(sb.build())
                .build();
            cb = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) { direct = n; net("conectado"); status(); }
                @Override public void onUnavailable() { direct = null; net("naoAchou"); }
                @Override public void onLost(Network n) { if (n.equals(direct)) direct = null; net("desligado"); }
            };
            net("procurando");
            cm.requestNetwork(req, cb, 60000);   // procura por até 1 minuto
        } catch (Exception e) { net("naoAchou"); }
    }

    public void disconnect(boolean avisar) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null && cb != null) { try { cm.unregisterNetworkCallback(cb); } catch (Exception ignored) { } }
        cb = null; direct = null;
        if (avisar) net("desligado");
    }

    /** Estado da conexão agora (para a tela): conectado se o celular está no Wi-Fi do óculos. */
    public void netState() { net(direct != null ? "conectado" : "procurando".equals(estado) ? "procurando" : "desligado"); }

    static Network wifiNet(Context ctx) {
        Network d = direct;
        if (d != null) return d;
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
        exInfo.submit(() -> {
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

    private volatile Network apresentado;   // rede em que o app já mandou o "o app começou" (8001)
    private static final Pattern ST = Pattern.compile("<Status>(-?\\d+)</Status>");
    private static final Pattern VAL = Pattern.compile("<Value>(-?\\d+)</Value>");

    /** Pergunta de LEITURA ao chip Novatek do óculos. Devolve o número (Value) ou null se ele não respondeu. */
    private Long cmd(int c) {
        try {
            String x = get("/?custom=1&cmd=" + c);
            Matcher s = ST.matcher(x), v = VAL.matcher(x);
            if (s.find() && "0".equals(s.group(1)) && v.find()) return Long.parseLong(v.group(1));
        } catch (Exception ignored) { }
        return null;
    }

    /** "12.91 MB" -> bytes */
    static long bytes(String t) {
        Matcher m = Pattern.compile("([\\d.]+)\\s*([KMGT]?B)", Pattern.CASE_INSENSITIVE).matcher(t == null ? "" : t);
        if (!m.find()) return 0;
        double n = Double.parseDouble(m.group(1));
        switch (m.group(2).toUpperCase()) { case "KB": n *= 1024; break; case "MB": n *= 1048576; break; case "GB": n *= 1073741824d; break; case "TB": n *= 1099511627776d; break; }
        return (long) n;
    }

    /** Estado do óculos para a área do iVUE: conectado, bateria, espaço e quantos vídeos/fotos.
     *  Responde em window.jjIvueSt({ok, videos[], fotos[], usado, livre?, bateria?, cartao?}). Só lê. */
    public void status() {
        exInfo.submit(() -> {
            JSONObject r = new JSONObject();
            try {
                JSONArray v = new JSONArray(), f = new JSONArray();
                list("/DCIM/Movie", v, 0);
                try { list("/DCIM/Photo", f, 0); } catch (Exception ignored) { }
                long usado = 0;
                for (int i = 0; i < v.length(); i++) usado += bytes(v.getJSONObject(i).optString("tamanho"));
                for (int i = 0; i < f.length(); i++) usado += bytes(f.getJSONObject(i).optString("tamanho"));
                r.put("ok", true); r.put("videos", v); r.put("fotos", f); r.put("usado", usado); r.put("rssi", rssi());
                Network nw = wifi();
                if (nw != null && !nw.equals(apresentado)) { get("/?custom=1&cmd=8001"); apresentado = nw; }   // o app se apresenta ao óculos (1 vez por conexão)
                Long bat = cmd(3019);  if (bat != null) r.put("bateria", bat);   // 0 cheia ... 5 carregando
                Long livre = cmd(3017); if (livre != null) r.put("livre", livre); // bytes livres no cartão
                Long card = cmd(3024);  if (card != null) r.put("cartao", card);  // 1 = cartão colocado
            } catch (Exception e) {
                try { r.put("ok", false); r.put("erro", String.valueOf(e.getMessage())); } catch (Exception ignored) { }
            }
            out.js("window.jjIvueSt&&window.jjIvueSt(" + r + ")");
        });
    }

    /** Baixa um arquivo do óculos para a galeria do celular (Filmes/JJGlasses ou Imagens/JJGlasses). */
    public void download(String path, String nome) { download(path, nome, false); }

    /** apagar = true: depois de baixar TUDO certinho (tamanho conferido), apaga o arquivo do óculos para liberar espaço.
     *  Se o download falhar ou vier incompleto, NÃO apaga nada. */
    public void download(String path, String nome, boolean apagar) {
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
                if (total > 0 && done != total) throw new Exception("incompleto");
                if (Build.VERSION.SDK_INT >= 29) { ContentValues cv = new ContentValues(); cv.put(MediaStore.MediaColumns.IS_PENDING, 0); cr.update(uri, cv, null, null); }
                else if (file != null) uri = Uri.fromFile(file);
                boolean apagado = false;
                if (apagar && total > 0 && done == total) apagado = remove(path);
                prog(path, done, total, uri.toString(), null, apagado);
            } catch (Exception e) {
                if (uri != null && Build.VERSION.SDK_INT >= 29) { try { ctx.getContentResolver().delete(uri, null, null); } catch (Exception ignored) { } }
                prog(path, 0, 0, null, String.valueOf(e.getMessage()));
            }
        });
    }

    public void cancelDownload() { cancel = true; }

    /** Apaga um arquivo do óculos (o mesmo "Remove" da página dele). Só é usado no modo automático. */
    private boolean remove(String path) {
        try {
            HttpURLConnection c = open(path + "?del=1", 8000);
            try { int code = c.getResponseCode(); return code >= 200 && code < 400; } finally { c.disconnect(); }
        } catch (Exception e) { return false; }
    }

    /** Modo automático: o arquivo já tinha sido baixado inteiro antes; agora só libera o espaço no óculos. */
    public void apagar(String path) {
        exInfo.submit(() -> {
            boolean ok = remove(path);
            try { JSONObject r = new JSONObject(); r.put("path", path); r.put("ok", ok); out.js("window.jjIvueDel&&window.jjIvueDel(" + r + ")"); } catch (Exception ignored) { }
        });
    }

    private void prog(String path, long done, long total, String uri, String erro) { prog(path, done, total, uri, erro, false); }

    private void prog(String path, long done, long total, String uri, String erro, boolean apagado) {
        try {
            JSONObject p = new JSONObject();
            p.put("path", path); p.put("feito", done); p.put("total", total);
            if (uri != null) p.put("uri", uri);
            if (erro != null) p.put("erro", erro);
            if (apagado) p.put("apagado", true);
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
