package com.jjglasses.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Casca do JJGlasses.
 * O app de verdade é um index.html (HTML5) que vem do site e se atualiza sozinho.
 * Esta casca só: abre o index.html em tela cheia, confere se tem versão nova, e empresta
 * ao HTML o que só o Android faz bem (microfone com reconhecimento de voz, voz do celular, permissões).
 */
public class MainActivity extends Activity {

    // Site (GitHub Pages) de onde vêm o app e o version.json.
    static final String BASE = "https://timeplayta.github.io/JJGlasses/";
    static final String VERSION_URL = BASE + "version.json";
    static final String INDEX_URL = BASE + "index.html";

    private static final long CHECK_EVERY_MS = 10 * 60 * 1000L; // checa versão nova a cada 10 min com o app aberto
    private static final int REQ_MEDIA = 31, REQ_LOCATION = 32, REQ_FILE = 33, REQ_MIC = 34;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private TextView splash;
    private SharedPreferences prefs;
    private boolean loaded = false;
    private String notesToShow = null;     // "Atualizado!" para mostrar depois de abrir
    private JSONObject staged = null;      // versão nova já baixada, esperando "Atualizar agora"

    private PermissionRequest pendingWebPermission;
    private GeolocationPermissions.Callback pendingGeo;
    private String pendingGeoOrigin;
    private ValueCallback<Uri[]> pendingFiles;
    private int pendingListenMode = -1;

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private SpeechRecognizer recognizer;

    // ---------------------------------------------------------------- início

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        CrashReport.install(this);      // se o app fechar sozinho, guarda o erro e manda para o Supabase
        CrashReport.sendPending(this);
        prefs = getSharedPreferences("shell", MODE_PRIVATE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        splash = new TextView(this);
        splash.setText("JJGlasses");
        splash.setTextColor(Color.WHITE);
        splash.setTextSize(28);
        splash.setGravity(Gravity.CENTER);
        splash.setBackgroundColor(Color.parseColor("#0F1218"));
        root.addView(splash, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        try { fullScreen(); } catch (Throwable e) { CrashReport.note(this, e); }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                    // localStorage: conversas e login ficam salvos
        s.setMediaPlaybackRequiresUserGesture(false);    // áudio toca sem precisar de toque
        s.setSupportMultipleWindows(false);              // links "_blank" passam pelo filtro abaixo
        s.setGeolocationEnabled(true);
        s.setAllowFileAccess(false);
        web.addJavascriptInterface(new Bridge(), "JJ");
        web.setWebViewClient(new ShellClient());
        web.setWebChromeClient(new ShellChrome());

        try {
            tts = new TextToSpeech(this, status -> {
                ttsReady = status == TextToSpeech.SUCCESS;
                try { if (ttsReady && tts != null) tts.setLanguage(new Locale("pt", "BR")); }
                catch (Throwable e) { ttsReady = false; }
            }, googleTts());
        } catch (Throwable e) {
            CrashReport.note(this, e);
        }

        // Ao abrir: confere o version.json com tempo curto. Depois de no máximo 4 s, abre de qualquer jeito.
        new Thread(() -> {
            try { startupCheck(); } catch (Throwable ignored) { }
            main.post(this::openApp);
        }, "jj-start").start();
        main.postDelayed(this::openApp, 4000);
    }

    private String googleTts() {
        try {
            getPackageManager().getPackageInfo("com.google.android.tts", 0);
            return "com.google.android.tts";
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private void fullScreen() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) { try { fullScreen(); } catch (Throwable ignored) { } }
    }

    private final Runnable periodic = new Runnable() {
        @Override public void run() {
            new Thread(() -> {
                try { backgroundCheck(); } catch (Throwable ignored) { }
            }, "jj-check").start();
            main.postDelayed(this, CHECK_EVERY_MS);
        }
    };

    @Override protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        main.removeCallbacks(periodic);
        main.postDelayed(periodic, 30_000);
    }

    @Override protected void onPause() {
        super.onPause();
        main.removeCallbacks(periodic);
        if (web != null) web.onPause();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (recognizer != null) recognizer.destroy();
        if (tts != null) tts.shutdown();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        web.evaluateJavascript("window.jjBack?String(window.jjBack()):'false'", r -> {
            if (!"\"true\"".equals(r)) moveTaskToBack(true);
        });
    }

    // ---------------------------------------------------------------- versões

    private File currentFile() { return new File(new File(getFilesDir(), "web"), "current.html"); }
    private File stagedFile() { return new File(new File(getFilesDir(), "web"), "staged.html"); }

    /** Versão que veio dentro do APK (assets/version.json). */
    private long bundledVersion() {
        try { return new JSONObject(readStream(getAssets().open("version.json"))).optLong("v", 0); }
        catch (Exception e) { return 0; }
    }

    /** Versão salva no celular (ou a do APK, se ainda não baixou nenhuma). */
    private long savedVersion() {
        long saved = prefs.getLong("v", 0);
        if (!currentFile().exists() || bundledVersion() > saved) return bundledVersion();
        return saved;
    }

    private void startupCheck() throws Exception {
        JSONObject remote = fetchJson(VERSION_URL, 2500, 2500);
        if (remote == null || remote.optLong("v", 0) <= savedVersion()) return;
        if (download(remote, 4000, 15000, currentFile())) {
            prefs.edit().putLong("v", remote.optLong("v")).apply();
            notesToShow = String.valueOf(remote.optJSONObject("notes"));
        }
    }

    private void backgroundCheck() throws Exception {
        JSONObject remote = fetchJson(VERSION_URL, 5000, 8000);
        if (remote == null || remote.optLong("v", 0) <= savedVersion()) return;
        if (staged != null && staged.optLong("v") == remote.optLong("v")) return;
        if (download(remote, 8000, 60000, stagedFile())) {
            staged = remote;
            String notes = String.valueOf(remote.optJSONObject("notes"));
            main.post(() -> web.evaluateJavascript("window.jjUpdateReady&&window.jjUpdateReady(" + notes + ")", null));
        }
    }

    /** "Atualizar agora": troca pela versão já baixada e reabre. */
    private void applyStaged() {
        JSONObject remote = staged;
        File file = stagedFile();
        if (remote == null || !file.exists()) { reload(); return; }
        File target = currentFile();
        if (target.exists()) target.delete();
        if (!file.renameTo(target)) return;
        prefs.edit().putLong("v", remote.optLong("v")).apply();
        notesToShow = String.valueOf(remote.optJSONObject("notes"));
        staged = null;
        reload();
    }

    /** Baixa o index.html novo e só aceita se veio inteiro (tamanho e "impressão digital" SHA-256 batem). */
    private boolean download(JSONObject remote, int connectMs, int readMs, File target) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(INDEX_URL + "?v=" + remote.optLong("v")).openConnection();
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setUseCaches(false);
            if (c.getResponseCode() != 200) return false;
            byte[] data;
            try (InputStream in = c.getInputStream()) { data = readAll(in); }
            finally { c.disconnect(); }
            long bytes = remote.optLong("bytes", -1);
            String sha = remote.optString("sha256", "");
            if (bytes > 0 && data.length != bytes) return false;
            if (!sha.isEmpty() && !sha.equalsIgnoreCase(sha256(data))) return false;
            if (!new String(data, StandardCharsets.UTF_8).contains("</html>")) return false;
            File dir = target.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            File tmp = new File(dir, target.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(data); }
            if (target.exists()) target.delete();
            return tmp.renameTo(target);
        } catch (Exception e) {
            return false;
        }
    }

    private JSONObject fetchJson(String url, int connectMs, int readMs) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url + "?t=" + System.currentTimeMillis()).openConnection();
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setUseCaches(false);
            try {
                if (c.getResponseCode() != 200) return null;
                return new JSONObject(readStream(c.getInputStream()));
            } finally { c.disconnect(); }
        } catch (Exception e) {
            return null;
        }
    }

    private void openApp() {
        if (loaded) return;
        loaded = true;
        reload();
    }

    private void reload() {
        String html = null;
        try {
            if (currentFile().exists() && prefs.getLong("v", 0) >= bundledVersion()) html = readStream(new java.io.FileInputStream(currentFile()));
        } catch (Exception ignored) { }
        if (html == null) {
            try { html = readStream(getAssets().open("index.html")); }
            catch (Exception e) { html = "<html><body style='background:#000;color:#fff;font-family:sans-serif;padding:40px'>Não achei o app. Conecte a internet e abra de novo.</body></html>"; }
        }
        // Base fixa = mesmo "endereço" sempre, assim o login e as conversas salvas não se perdem entre versões.
        web.loadDataWithBaseURL(BASE, html, "text/html", "utf-8", null);
    }

    // ---------------------------------------------------------------- navegador embutido

    private class ShellClient extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (url.startsWith(BASE) || url.startsWith("data:") || url.startsWith("blob:") || url.startsWith("about:")) return false;
            openOutside(url); // links externos (loja, sites) abrem no navegador do celular
            return true;
        }

        // Se o "motor" do navegador embutido cair, recarrega em vez de fechar o app.
        @Override public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
            CrashReport.note(MainActivity.this, new RuntimeException("WebView caiu (crash=" + detail.didCrash() + ")"));
            recreate();
            return true;
        }

        @Override public void onPageFinished(WebView view, String url) {
            splash.setVisibility(View.GONE);
            if (notesToShow != null && !"null".equals(notesToShow)) {
                String notes = notesToShow;
                notesToShow = null;
                view.evaluateJavascript("window.jjShowUpdated&&window.jjShowUpdated(" + notes + ")", null);
            }
        }
    }

    private void openOutside(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (ActivityNotFoundException ignored) { }
    }

    private class ShellChrome extends WebChromeClient {
        @Override public void onPermissionRequest(PermissionRequest request) {
            ArrayList<String> need = new ArrayList<>();
            for (String r : request.getResources()) {
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && !has(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA);
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && !has(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO);
            }
            if (need.isEmpty()) { request.grant(request.getResources()); return; }
            pendingWebPermission = request;
            requestPermissions(need.toArray(new String[0]), REQ_MEDIA);
        }

        @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            if (has(Manifest.permission.ACCESS_COARSE_LOCATION)) { callback.invoke(origin, true, false); return; }
            pendingGeo = callback;
            pendingGeoOrigin = origin;
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
        }

        @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (pendingFiles != null) pendingFiles.onReceiveValue(null);
            pendingFiles = callback;
            // Respeita o tipo pedido pela página: só fotos (Galeria) ou qualquer arquivo (Adicionar arquivos).
            String type = "*/*";
            String[] accept = params.getAcceptTypes();
            if (accept != null && accept.length > 0 && accept[0] != null && !accept[0].trim().isEmpty()) type = accept[0].trim();
            Intent pick = new Intent(Intent.ACTION_GET_CONTENT).setType(type).addCategory(Intent.CATEGORY_OPENABLE);
            try { startActivityForResult(Intent.createChooser(pick, type.startsWith("image") ? "Escolher foto" : "Escolher arquivo"), REQ_FILE); }
            catch (Exception e) { pendingFiles = null; return false; }
            return true;
        }
    }

    private boolean has(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == REQ_MEDIA && pendingWebPermission != null) {
            ArrayList<String> ok = new ArrayList<>();
            for (String r : pendingWebPermission.getResources()) {
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && has(Manifest.permission.CAMERA)) ok.add(r);
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && has(Manifest.permission.RECORD_AUDIO)) ok.add(r);
            }
            if (ok.isEmpty()) pendingWebPermission.deny(); else pendingWebPermission.grant(ok.toArray(new String[0]));
            pendingWebPermission = null;
        } else if (code == REQ_LOCATION && pendingGeo != null) {
            pendingGeo.invoke(pendingGeoOrigin, has(Manifest.permission.ACCESS_COARSE_LOCATION), false);
            pendingGeo = null;
        } else if (code == REQ_MIC) {
            int mode = pendingListenMode;
            pendingListenMode = -1;
            if (has(Manifest.permission.RECORD_AUDIO) && mode >= 0) startListening(mode == 1);
            else heard(null, "permissao");
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == REQ_FILE && pendingFiles != null) {
            Uri uri = (result == RESULT_OK && data != null) ? data.getData() : null;
            pendingFiles.onReceiveValue(uri == null ? null : new Uri[]{uri});
            pendingFiles = null;
        }
    }

    // ---------------------------------------------------------------- microfone e voz do celular

    private void js(String code) { main.post(() -> web.evaluateJavascript(code, null)); }

    private void heard(String text, String error) {
        js("window.jjHeard&&window.jjHeard(" + JSONObject.quote(text == null ? "" : text) + "," + JSONObject.quote(error == null ? "" : error) + ")");
    }

    private void startListening(boolean quick) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { heard(null, "sem_reconhecimento"); return; }
        if (recognizer != null) recognizer.destroy();
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rms) {
                float level = Math.max(0f, Math.min(1f, (rms + 2f) / 12f));
                js("window.jjLevel&&window.jjLevel(" + level + ")");
            }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onError(int error) {
                boolean silence = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
                heard(null, silence ? "sem_fala" : "erro_" + error);
            }
            @Override public void onResults(Bundle results) {
                ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String text = list == null || list.isEmpty() ? null : list.get(0);
                heard(text, text == null ? "sem_fala" : null);
            }
            @Override public void onPartialResults(Bundle partial) { }
            @Override public void onEvent(int type, Bundle params) { }
        });
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        if (quick) {
            // Ligação: fecha a frase depois de ~1 s de silêncio, para responder rápido.
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1100L);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L);
        } else {
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
        }
        recognizer.startListening(intent);
    }

    /** Ponte: funções do Android que o index.html pode chamar como window.JJ.algumaCoisa(). */
    public class Bridge {
        @JavascriptInterface public String version() { return String.valueOf(savedVersion()); }

        @JavascriptInterface public String shellVersion() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
            catch (Exception e) { return ""; }
        }

        @JavascriptInterface public void ready() { }

        @JavascriptInterface public void applyUpdate() { main.post(MainActivity.this::applyStaged); }

        @JavascriptInterface public void openExternal(String url) { main.post(() -> openOutside(url)); }

        @JavascriptInterface public void keepScreenOn(boolean on) {
            main.post(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface public void listen(int mode) {
            main.post(() -> {
                if (!has(Manifest.permission.RECORD_AUDIO)) {
                    pendingListenMode = mode;
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                    return;
                }
                startListening(mode == 1);
            });
        }

        @JavascriptInterface public void stopListen() {
            main.post(() -> { if (recognizer != null) { recognizer.cancel(); } });
        }

        @JavascriptInterface public void speak(String text, double pitch, double rate) {
            main.post(() -> {
                if (!ttsReady) { js("window.jjSpoken&&window.jjSpoken()"); return; }
                tts.setPitch((float) pitch);
                tts.setSpeechRate((float) rate);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { }
                    @Override public void onDone(String id) { js("window.jjSpoken&&window.jjSpoken()"); }
                    @Override @Deprecated public void onError(String id) { js("window.jjSpoken&&window.jjSpoken()"); }
                });
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jj");
            });
        }

        @JavascriptInterface public void stopSpeak() { main.post(() -> { if (tts != null) tts.stop(); }); }
    }

    // ---------------------------------------------------------------- utilidades

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static String readStream(InputStream in) throws Exception {
        try (InputStream s = in) { return new String(readAll(s), StandardCharsets.UTF_8); }
    }

    private static String sha256(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
