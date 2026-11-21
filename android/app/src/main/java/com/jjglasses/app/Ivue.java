package com.jjglasses.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.media.MediaDataSource;
import android.media.MediaMetadataRetriever;
import android.util.Base64;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private final ExecutorService exThumb = Executors.newSingleThreadExecutor(); // miniaturas (uma por vez)
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
    /** Confirma que é mesmo o óculos (a página dele tem a pasta DCIM), e não outro aparelho no mesmo endereço. */
    private void confirm() throws Exception {
        try { if (get("/").contains("DCIM")) return; } catch (Exception ignored) { }
        String m = get("/DCIM/Movie");                       // 2ª checagem: a pasta de vídeos do óculos responde?
        if (m.contains("/DCIM/Movie") || ROW.matcher(m).find()) return;
        throw new Exception("não é o óculos");
    }

    public void listAll() {
        exInfo.submit(() -> {
            JSONObject r = new JSONObject();
            try {
                confirm();
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
                confirm();
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
    public void download(String path, String nome) { download(path, nome, false, null); }
    public void download(String path, String nome, boolean apagar) { download(path, nome, apagar, null); }

    private SharedPreferences dlPrefs() { return ctx.getSharedPreferences("ivue_dl", Context.MODE_PRIVATE); }

    /**
     * Baixa um arquivo do óculos para a Galeria do celular, em Filmes/JJGlasses/<pasta> ou Imagens/JJGlasses/<pasta>.
     * RETOMA: se a conexão cair, o pedaço já copiado fica guardado; na próxima vez continua de onde parou
     * (pede ao óculos só o resto, com "Range"). Só aparece na Galeria quando termina inteiro.
     * apagar = true (só o modo automático antigo): depois de conferir o tamanho, apaga do óculos.
     */
    public void download(String path, String nome, boolean apagar, String pasta) {
        cancel = false;
        ex.submit(() -> {
            Uri uri = null;
            boolean video = nome.toLowerCase().matches(".*\\.(mp4|mov|avi|ts)$");
            String mime = video ? "video/mp4" : "image/jpeg";
            String sub = "JJGlasses" + (pasta != null && pasta.trim().length() > 0 ? "/" + pasta.trim().replaceAll("[\\\\/:*?\"<>|]", "") : "");
            ContentResolver cr = ctx.getContentResolver();
            SharedPreferences sp = dlPrefs();
            try {
                long have = 0; File file = null;
                if (Build.VERSION.SDK_INT >= 29) {
                    String old = sp.getString(path, null);   // pedaço de uma tentativa anterior?
                    if (old != null) {
                        try (android.os.ParcelFileDescriptor fd = cr.openFileDescriptor(Uri.parse(old), "r")) { if (fd != null) { have = fd.getStatSize(); uri = Uri.parse(old); } }
                        catch (Exception e) { sp.edit().remove(path).apply(); have = 0; uri = null; }
                    }
                    if (uri == null) {
                        ContentValues cv = new ContentValues();
                        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, nome);
                        cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/" + sub);
                        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                        uri = cr.insert(video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                        if (uri == null) throw new Exception("sem espaço na galeria");
                        sp.edit().putString(path, uri.toString()).apply();
                        have = 0;
                    }
                } else {
                    File d = new File(ctx.getExternalFilesDir(video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES), sub);
                    d.mkdirs(); file = new File(d, nome); have = file.exists() ? file.length() : 0;
                }
                HttpURLConnection c = open(path, 30000);
                if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
                int code = c.getResponseCode();
                boolean resumed = have > 0 && code == 206;
                if (!resumed) have = 0;                                   // o óculos mandou o arquivo inteiro: começa do zero
                long total = resumed ? have + c.getContentLengthLong() : c.getContentLengthLong();
                OutputStream os = Build.VERSION.SDK_INT >= 29 ? cr.openOutputStream(uri, resumed ? "wa" : "wt") : new FileOutputStream(file, resumed);
                long done = have, last = 0;
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
                else uri = Uri.fromFile(file);
                sp.edit().remove(path).apply();
                boolean apagado = false;
                if (apagar && total > 0 && done == total) apagado = remove(path);
                prog(path, done, total, uri.toString(), null, apagado);
            } catch (Exception e) {
                String msg = String.valueOf(e.getMessage());
                if ("cancelado".equals(msg) && uri != null && Build.VERSION.SDK_INT >= 29) {   // a pessoa parou: joga fora o pedaço
                    try { cr.delete(uri, null, null); } catch (Exception ignored) { }
                    sp.edit().remove(path).apply();
                }
                prog(path, 0, 0, null, msg);   // caiu a conexão: o pedaço fica guardado para continuar depois
            }
        });
    }

    public void cancelDownload() { cancel = true; }

    // ------------------------------------------------------------ Vigia do Wi-Fi
    // Sempre que o celular entra (ou sai) de uma rede Wi-Fi, avisa a tela; ela confere se é o óculos.
    private ConnectivityManager.NetworkCallback watchCb;

    public void watch() {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null || watchCb != null) return;
        watchCb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { out.js("window.jjIvueWifi&&window.jjIvueWifi(true)"); }
            @Override public void onLost(Network n) { out.js("window.jjIvueWifi&&window.jjIvueWifi(false)"); }
        };
        try {
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)   // a rede do óculos não tem internet
                .build(), watchCb);
        } catch (Exception e) { watchCb = null; }
    }

    public void unwatch() {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null && watchCb != null) { try { cm.unregisterNetworkCallback(watchCb); } catch (Exception ignored) { } }
        watchCb = null;
    }

    // ------------------------------------------------------------ Miniaturas (o começo de cada vídeo)
    /** Lê pedaços do vídeo direto do óculos (com "Range"), sem baixar o arquivo inteiro. */
    private class RangeSource extends MediaDataSource {
        private static final int BLK = 512 * 1024;
        private final String path;
        private long size = -1;
        private final LinkedHashMap<Long, byte[]> cache = new LinkedHashMap<Long, byte[]>(16, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Long, byte[]> e) { return size() > 10; }
        };
        RangeSource(String path) { this.path = path; }

        private byte[] range(long from, long to) throws Exception {
            HttpURLConnection c = open(path, 15000);
            c.setRequestProperty("Range", "bytes=" + from + "-" + to);
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream b = new ByteArrayOutputStream()) {
                if (size < 0) {
                    String cr = c.getHeaderField("Content-Range");   // "bytes 0-0/123456"
                    if (cr != null && cr.contains("/")) size = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim());
                    else if (c.getResponseCode() == 200) size = c.getContentLengthLong();
                }
                byte[] buf = new byte[65536]; int k; long want = to - from + 1;
                while (b.size() < want && (k = in.read(buf, 0, (int) Math.min(buf.length, want - b.size()))) > 0) b.write(buf, 0, k);
                return b.toByteArray();
            } finally { c.disconnect(); }
        }
        @Override public long getSize() throws java.io.IOException {
            if (size < 0) { try { range(0, 0); } catch (Exception e) { throw new java.io.IOException(e); } }
            return size;
        }
        @Override public int readAt(long pos, byte[] buf, int off, int len) throws java.io.IOException {
            if (len == 0) return 0;
            if (pos >= getSize()) return -1;
            long b0 = pos / BLK * BLK;
            byte[] blk = cache.get(b0);
            if (blk == null) {
                try { blk = range(b0, Math.min(size, b0 + BLK) - 1); } catch (Exception e) { throw new java.io.IOException(e); }
                cache.put(b0, blk);
            }
            int o = (int) (pos - b0), n = Math.min(len, blk.length - o);
            if (n <= 0) return -1;
            System.arraycopy(blk, o, buf, off, n);
            return n;
        }
        @Override public void close() { cache.clear(); }
    }

    private static boolean dark(Bitmap b) {   // quadro preto? (média de brilho muito baixa)
        long sum = 0; int n = 0;
        for (int y = 0; y < 12; y++) for (int x = 0; x < 12; x++) {
            int p = b.getPixel(x * (b.getWidth() - 1) / 11, y * (b.getHeight() - 1) / 11);
            sum += ((p >> 16 & 255) * 3 + (p >> 8 & 255) * 6 + (p & 255)) / 10; n++;
        }
        return sum / n < 22;
    }

    private static Bitmap fit(Bitmap b, int w) {
        if (b == null || b.getWidth() <= w) return b;
        return Bitmap.createScaledBitmap(b, w, Math.max(1, b.getHeight() * w / b.getWidth()), true);
    }

    private static String b64(byte[] d) { return "data:image/jpeg;base64," + Base64.encodeToString(d, Base64.NO_WRAP); }

    private static byte[] jpeg(Bitmap b, int q) { ByteArrayOutputStream o = new ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, q, o); return o.toByteArray(); }

    private static String resLabel(int w, int h) {
        int m = Math.min(w, h), M = Math.max(w, h);
        if (M >= 2500 || m >= 1440) return "2K";
        if (m >= 1080) return "1080p";
        if (m >= 720) return "720p";
        return m > 0 ? m + "p" : "";
    }

    private static byte[] readFile(File f) throws Exception {
        try (InputStream in = new FileInputStream(f); ByteArrayOutputStream b = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384]; int k; while ((k = in.read(buf)) > 0) b.write(buf, 0, k); return b.toByteArray();
        }
    }

    /** Uma miniatura: vídeo = um quadro do começo (pula quadro preto); foto = miniatura da própria foto. */
    private JSONObject thumb(JSONObject o) throws Exception {
        String path = o.getString("path"), nome = o.optString("nome"), tam = o.optString("tamanho");
        File dir = new File(ctx.getCacheDir(), "ivthumbs"); dir.mkdirs();
        String key = (nome + "_" + tam).replaceAll("[^A-Za-z0-9._-]", "_");
        File jpg = new File(dir, key + ".jpg"), meta = new File(dir, key + ".json");
        if (jpg.exists() && meta.exists()) {
            JSONObject r = new JSONObject(new String(readFile(meta), "UTF-8"));
            r.put("path", path); r.put("img", b64(readFile(jpg))); return r;
        }
        JSONObject r = new JSONObject(); r.put("path", path);
        Bitmap bm = null;
        boolean video = nome.toLowerCase().matches(".*\\.(mp4|mov|avi|ts)$");
        if (video) {
            MediaMetadataRetriever mm = new MediaMetadataRetriever();
            try {
                mm.setDataSource(new RangeSource(path));
                String d = mm.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                String w = mm.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), h = mm.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                if (d != null) r.put("dur", Math.round(Long.parseLong(d) / 1000.0));
                if (w != null && h != null) { int W = Integer.parseInt(w), H = Integer.parseInt(h); r.put("w", W); r.put("h", H); r.put("res", resLabel(W, H)); }
                for (long t : new long[]{1_000_000L, 3_000_000L, 300_000L, 6_000_000L}) {   // 1 s, 3 s, 0,3 s, 6 s
                    Bitmap f = Build.VERSION.SDK_INT >= 27 ? mm.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320)
                                                          : fit(mm.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC), 320);
                    if (f == null) continue;
                    bm = f;
                    if (!dark(f)) break;
                }
            } finally { try { mm.release(); } catch (Exception ignored) { } }
        } else {
            RangeSource rs = new RangeSource(path);
            byte[] head = rs.range(0, 196607);                       // começo da foto: tem a miniatura do próprio óculos (EXIF)
            ExifInterface ex = new ExifInterface(new ByteArrayInputStream(head));
            int W = ex.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0), H = ex.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0);
            byte[] t = ex.getThumbnail();
            if (t != null) bm = BitmapFactory.decodeByteArray(t, 0, t.length);
            if (bm == null) {                                        // sem miniatura: baixa a foto e reduz
                byte[] all = rs.range(0, Math.max(0, rs.getSize() - 1));
                BitmapFactory.Options op = new BitmapFactory.Options(); op.inSampleSize = 8;
                bm = BitmapFactory.decodeByteArray(all, 0, all.length, op);
                if (W == 0 && bm != null) { W = bm.getWidth() * 8; H = bm.getHeight() * 8; }
            }
            if (W > 0) { r.put("w", W); r.put("h", H); r.put("res", resLabel(W, H)); }
        }
        if (bm == null) throw new Exception("sem imagem");
        byte[] j = jpeg(fit(bm, 320), 72);
        try (FileOutputStream fo = new FileOutputStream(jpg)) { fo.write(j); }
        try (FileOutputStream fo = new FileOutputStream(meta)) { JSONObject m = new JSONObject(r.toString()); m.remove("path"); fo.write(m.toString().getBytes("UTF-8")); }
        r.put("img", b64(j));
        return r;
    }

    /** A tela pede miniaturas: [{path,nome,tamanho}] -> window.jjIvueThumb({path,img,dur,w,h,res}) uma por uma. */
    public void thumbs(String json) {
        exThumb.submit(() -> {
            try {
                JSONArray a = new JSONArray(json);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i), r;
                    try { r = thumb(o); } catch (Throwable e) { r = new JSONObject(); r.put("path", o.optString("path")); r.put("erro", String.valueOf(e.getMessage())); }
                    out.js("window.jjIvueThumb&&window.jjIvueThumb(" + r + ")");
                }
            } catch (Exception ignored) { }
        });
    }

    /** Foto já baixada, reduzida (para a conversa ou para a nuvem): window.jjIvueImg({tag, img}). */
    public void imagem(String uri, int max, int q, String tag) {
        exThumb.submit(() -> {
            JSONObject r = new JSONObject();
            try {
                r.put("tag", tag);
                ContentResolver cr = ctx.getContentResolver();
                BitmapFactory.Options op = new BitmapFactory.Options(); op.inJustDecodeBounds = true;
                try (InputStream in = cr.openInputStream(Uri.parse(uri))) { BitmapFactory.decodeStream(in, null, op); }
                int s = 1; while (Math.max(op.outWidth, op.outHeight) / (s * 2) >= max) s *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options(); o2.inSampleSize = s;
                Bitmap b;
                try (InputStream in = cr.openInputStream(Uri.parse(uri))) { b = BitmapFactory.decodeStream(in, null, o2); }
                if (b == null) throw new Exception("foto ilegível");
                if (Math.max(b.getWidth(), b.getHeight()) > max) {
                    float k = max / (float) Math.max(b.getWidth(), b.getHeight());
                    b = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * k), Math.round(b.getHeight() * k), true);
                }
                r.put("img", b64(jpeg(b, q)));
            } catch (Exception e) { try { r.put("erro", String.valueOf(e.getMessage())); } catch (Exception ignored) { } }
            out.js("window.jjIvueImg&&window.jjIvueImg(" + r + ")");
        });
    }

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
