package com.jjglasses.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import javax.net.SocketFactory;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tela "Ao vivo" do óculos iVUE Denali.
 * O óculos manda o vídeo ao vivo por RTSP ("protocolo de vídeo em tempo real") em rtsp://192.168.1.254/xxx.mov.
 * Usamos o ExoPlayer (o player de vídeo do Google) e "prendemos" a conexão na rede Wi-Fi do óculos,
 * porque essa rede não tem internet e o Android tentaria ir pelos dados móveis.
 * Só assiste: não grava, não muda nada no óculos.
 */
@OptIn(markerClass = UnstableApi.class)
public class LiveActivity extends Activity {
    static final String URL = "rtsp://192.168.1.254/xxx.mov";

    private ExoPlayer player;
    private PlayerView view;
    private TextView msg;
    private Button again;
    private TextView detail;
    private int attempt;          // 0 = vídeo pela conexão TCP; 1 = vídeo por UDP (o app inteiro preso no Wi-Fi do óculos)
    private boolean bound;
    private volatile String cmds = "";   // o que o óculos respondeu aos comandos (aparece se der erro)
    private int gen;                     // evita duas tentativas ao mesmo tempo

    private int dp(int v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        view = new PlayerView(this);
        view.setUseController(false);
        view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        view.setKeepContentOnPlayerReset(true);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));

        // selo "● AO VIVO" em cima, à esquerda
        TextView tag = new TextView(this);
        tag.setText("●  AO VIVO  ·  iVUE Denali");
        tag.setTextColor(Color.WHITE);
        tag.setTextSize(13);
        tag.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0xCCE53935); bg.setCornerRadius(dp(20));
        tag.setBackground(bg);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        tl.setMargins(dp(16), dp(40), 0, 0);
        root.addView(tag, tl);

        // botão fechar em cima, à direita
        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(Color.WHITE);
        close.setTextSize(22);
        close.setGravity(Gravity.CENTER);
        GradientDrawable cb = new GradientDrawable(); cb.setColor(0x66000000); cb.setShape(GradientDrawable.OVAL);
        close.setBackground(cb);
        close.setContentDescription("Fechar o ao vivo");
        close.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP | Gravity.END);
        cl.setMargins(0, dp(36), dp(16), 0);
        root.addView(close, cl);

        // mensagem no meio (conectando / erro) + tentar de novo
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.CENTER);
        msg = new TextView(this);
        msg.setTextColor(Color.WHITE);
        msg.setTextSize(15);
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(dp(24), 0, dp(24), dp(12));
        mid.addView(msg);
        detail = new TextView(this);
        detail.setTextColor(0x99FFFFFF);
        detail.setTextSize(11);
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(dp(24), 0, dp(24), dp(12));
        mid.addView(detail);
        again = new Button(this);
        again.setText("Tentar de novo");
        again.setAllCaps(false);
        again.setOnClickListener(v -> { attempt = 0; start(); });
        mid.addView(again);
        root.addView(mid, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        setContentView(root);
    }

    @Override protected void onStart() { super.onStart(); attempt = 0; start(); }
    @Override protected void onStop() {
        super.onStop();
        gen++;
        stop();
        Network n = Ivue.wifiNet(this);   // saiu da tela: desliga o ao vivo no óculos (volta como estava)
        if (n != null) new Thread(() -> cmd(n, "2015&par=0"), "jj-live-off").start();
    }

    private void show(String t, boolean retry) {
        msg.setText(t == null ? "" : t);
        msg.setVisibility(t == null ? View.GONE : View.VISIBLE);
        if (t == null || !retry) detail.setText("");
        again.setVisibility(retry ? View.VISIBLE : View.GONE);
    }

    /** Comando para o chip do óculos (Novatek). Devolve o Status: 0 = deu certo, -22 = "estado errado". */
    private static String cmd(Network n, String c) {
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) n.openConnection(new URL(Ivue.HOST + "/?custom=1&cmd=" + c));
            h.setConnectTimeout(3000); h.setReadTimeout(4000); h.setUseCaches(false);
            try (InputStream in = h.getInputStream()) {
                byte[] b = new byte[2048]; int k, t = 0; StringBuilder sb = new StringBuilder();
                while ((k = in.read(b)) > 0 && t < 8000) { sb.append(new String(b, 0, k, "UTF-8")); t += k; }
                Matcher m = Pattern.compile("<Status>(-?\\d+)</Status>").matcher(sb);
                return m.find() ? m.group(1) : "?";
            }
        } catch (Exception e) { return "x"; }
        finally { if (h != null) h.disconnect(); }
    }

    /** Liga o ao vivo no óculos (autorizado pelo Juan): modo vídeo + começar ao vivo. Depois abre o vídeo. */
    private void start() {
        stop();
        Network n = Ivue.wifiNet(this);
        if (n == null) { show("O celular não está no Wi-Fi do óculos.\nConecte no Wi-Fi \"iVUE\" e tente de novo.", true); return; }
        show("Ligando o ao vivo no óculos…", false);
        final int my = ++gen;
        new Thread(() -> {
            String s0 = cmd(n, "8001");   // "o app começou": o óculos só aceita comandos depois de o app se apresentar
            String a = cmd(n, "3016"), b = cmd(n, "3001&par=1");
            try { Thread.sleep(800); } catch (InterruptedException ignored) { }
            String c = cmd(n, "2015&par=1");
            try { Thread.sleep(1200); } catch (InterruptedException ignored) { }
            cmds = "apresentar " + s0 + " · vivo? " + a + " · modo vídeo " + b + " · ao vivo " + c;
            main().post(() -> { if (my == gen && !isFinishing()) play(n); });
        }, "jj-live-cmd").start();
    }

    private void play(Network n) {
        show("Conectando ao óculos…", false);
        SocketFactory sf = n.getSocketFactory();
        if (attempt == 1) {   // 2ª tentativa: prende o app inteiro no Wi-Fi do óculos e deixa o vídeo vir por UDP
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) bound = cm.bindProcessToNetwork(n);
        }

        // pouco "estoque" de vídeo (buffer) para o atraso ficar pequeno
        DefaultLoadControl lc = new DefaultLoadControl.Builder().setBufferDurationsMs(800, 2000, 200, 400).build();
        player = new ExoPlayer.Builder(this).setLoadControl(lc).build();
        view.setPlayer(player);
        RtspMediaSource.Factory f = new RtspMediaSource.Factory().setTimeoutMs(8000);
        if (attempt == 0) f.setForceUseRtpTcp(true).setSocketFactory(sf);   // vídeo pela mesma conexão (TCP), presa no Wi-Fi do óculos
        RtspMediaSource src = f.createMediaSource(MediaItem.fromUri(URL));
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int st) {
                if (st == Player.STATE_READY) show(null, false);
                else if (st == Player.STATE_BUFFERING) show("Carregando…", false);
            }
            @Override public void onPlayerError(PlaybackException e) {
                if (attempt == 0) { attempt = 1; main().post(() -> { if (player != null) { player.release(); player = null; } play(n); }); return; }   // tenta o outro jeito
                show("Não consegui ver o óculos ao vivo.\nConfira se ele está ligado e se o celular está no Wi-Fi \"iVUE\".", true);
                Throwable c = e.getCause();
                detail.setText("Código: " + e.getErrorCodeName() + (c != null && c.getMessage() != null ? "\n" + c.getMessage() : "") + "\nÓculos respondeu: " + cmds);
            }
        });
        player.setMediaSource(src);
        player.setPlayWhenReady(true);
        player.prepare();
    }

    private android.os.Handler main() { return new android.os.Handler(android.os.Looper.getMainLooper()); }

    private void stop() {
        if (player != null) { player.release(); player = null; }
        if (bound) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) cm.bindProcessToNetwork(null);   // devolve a internet normal para o app
            bound = false;
        }
        if (view != null) view.setPlayer(null);
    }
}
