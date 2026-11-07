package com.jjglasses.app;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Óculos W AI 4 (HeyCyan) ligado ao celular.
 * - Bluetooth BLE ("Bluetooth de baixo consumo"): lê bateria e sinal, e se reconecta sozinho se cair.
 * - Perfil de fone (headset): o som da IA sai no óculos e o microfone do óculos grava a sua fala.
 * - Toque no óculos (botão de mídia): avisa o app para começar/parar de ouvir.
 */
@SuppressLint("MissingPermission")
public class Glasses {
    interface Out { void state(String json); void tap(); }

    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int CMD_BATTERY = 0x42, CMD_CONTROL = 0x41;

    private final Context ctx;
    private final Out out;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager audio;

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar, cmdChar;
    private final ArrayDeque<BluetoothGattDescriptor> notifyQueue = new ArrayDeque<>();
    private BluetoothDevice target;
    private boolean scanning = false, ready = false, connecting = false, wanted = false, micOn = false;
    private int battery = -1, rssi = 0, tries = 0;
    private long connectedAt = 0, lastBatteryAt = 0, firstBatteryAt = 0;
    private int firstBattery = -1;
    private String name = "", lastError = "";
    private MediaSession session;

    Glasses(Context ctx, Out out) {
        this.ctx = ctx.getApplicationContext();
        this.out = out;
        this.audio = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
    }

    private BluetoothAdapter adapter() {
        BluetoothManager m = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        return m == null ? null : m.getAdapter();
    }

    // ------------------------------------------------------------ estado para o app

    String stateJson() {
        try {
            JSONObject j = new JSONObject();
            BluetoothAdapter a = adapter();
            j.put("bluetooth", a != null && a.isEnabled());
            j.put("connected", ready);
            j.put("connecting", connecting);
            j.put("scanning", scanning);
            j.put("name", name.isEmpty() ? "W AI 4" : name);
            j.put("battery", battery);
            j.put("rssi", rssi);
            j.put("since", connectedAt);
            j.put("firstBattery", firstBattery);
            j.put("firstBatteryAt", firstBatteryAt);
            j.put("lastBatteryAt", lastBatteryAt);
            j.put("audio", audioToGlasses());
            j.put("mic", micOn);
            j.put("error", lastError);
            return j.toString();
        } catch (Exception e) { return "{}"; }
    }

    private void push() { out.state(stateJson()); }

    /** O som do celular está indo para um fone/óculos Bluetooth? */
    boolean audioToGlasses() {
        try {
            for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        || (Build.VERSION.SDK_INT >= 31 && t == AudioDeviceInfo.TYPE_BLE_HEADSET)) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    // ------------------------------------------------------------ ligar / procurar

    void start() {
        wanted = true;
        startMediaButtons();
        BluetoothAdapter a = adapter();
        if (a == null || !a.isEnabled()) { lastError = "Bluetooth desligado"; push(); return; }
        if (ready || connecting) { push(); return; }
        // 1º: se o óculos já está pareado, liga direto (mais rápido)
        try {
            for (BluetoothDevice d : a.getBondedDevices()) {
                if (score(d.getName(), null) >= 50) { connect(d); return; }
            }
        } catch (Exception e) { lastError = "Sem permissão do Bluetooth"; }
        scan();
    }

    void stop() {
        wanted = false;
        stopScan();
        main.removeCallbacksAndMessages(null);
        if (gatt != null) { try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) { } }
        gatt = null; ready = false; connecting = false;
        mic(false);
        push();
    }

    void scan() {
        BluetoothAdapter a = adapter();
        if (a == null || a.getBluetoothLeScanner() == null) { lastError = "Bluetooth indisponível"; push(); return; }
        if (scanning) return;
        scanning = true; lastError = ""; push();
        try {
            a.getBluetoothLeScanner().startScan(null,
                    new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb);
        } catch (Exception e) { scanning = false; lastError = "Sem permissão do Bluetooth"; push(); return; }
        main.postDelayed(() -> { if (scanning) { stopScan(); if (!ready && !connecting) { lastError = "Óculos não encontrado. Ligue o óculos e deixe perto do celular."; push(); retryLater(); } } }, 15_000);
    }

    private void stopScan() {
        if (!scanning) return;
        scanning = false;
        try { adapter().getBluetoothLeScanner().stopScan(scanCb); } catch (Exception ignored) { }
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) {
            String n = r.getDevice().getName();
            if (n == null && r.getScanRecord() != null) n = r.getScanRecord().getDeviceName();
            List<String> uu = new ArrayList<>();
            if (r.getScanRecord() != null && r.getScanRecord().getServiceUuids() != null)
                for (android.os.ParcelUuid p : r.getScanRecord().getServiceUuids()) uu.add(p.getUuid().toString());
            if (score(n, uu) >= 50) { stopScan(); rssi = r.getRssi(); connect(r.getDevice()); }
        }
        @Override public void onScanFailed(int code) { scanning = false; lastError = "Falha ao procurar (" + code + ")"; push(); }
    };

    static int score(String n, List<String> uuids) {
        int s = 0;
        String name = n == null ? "" : n.toLowerCase(Locale.US);
        for (String k : new String[]{"heycyan", "microwear", "glass", "oculos", "óculos", "w ai", "wai", "cyan", "m02", "a02"})
            if (name.contains(k)) s += 50;
        if (uuids != null) for (String u : uuids)
            if (u.contains("de5bf728") || u.contains("ae30") || u.contains("ae3a")) s += 80;
        return s;
    }

    private void connect(BluetoothDevice d) {
        if (connecting || ready) return;
        target = d; connecting = true; tries = 1;
        name = d.getName() == null ? "W AI 4" : d.getName();
        push();
        openGatt();
    }

    private void openGatt() {
        if (target == null) return;
        try { if (gatt != null) gatt.close(); } catch (Exception ignored) { }
        gatt = target.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE);
    }

    private void retryLater() {
        if (!wanted) return;
        main.postDelayed(() -> { if (wanted && !ready && !connecting && !scanning) start(); }, 12_000);
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                try { g.requestMtu(247); } catch (Exception ignored) { }
                g.discoverServices();
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                boolean was = ready;
                ready = false; writeChar = null; cmdChar = null;
                try { g.close(); } catch (Exception ignored) { }
                if (g == gatt) gatt = null;
                main.removeCallbacks(tick);
                if (wanted && target != null && tries < 4) {
                    tries++; connecting = true;
                    main.postDelayed(Glasses.this::openGatt, 800L * tries);
                } else {
                    connecting = false;
                    if (was) lastError = "O óculos desconectou. Tentando de novo...";
                    retryLater();
                }
                push();
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            List<BluetoothGattCharacteristic> all = new ArrayList<>();
            for (BluetoothGattService s : g.getServices()) all.addAll(s.getCharacteristics());
            writeChar = pick(all, "de5bf72a", "ff02", "ae01");
            cmdChar = pick(all, "ff02");
            if (cmdChar == null) cmdChar = writeChar;
            notifyQueue.clear();
            for (String hint : new String[]{"de5bf729", "ff03", "ae02", "c551c36a"}) {
                for (BluetoothGattCharacteristic c : all) {
                    if (c.getUuid().toString().contains(hint) && (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                        g.setCharacteristicNotification(c, true);
                        BluetoothGattDescriptor d = c.getDescriptor(CCCD);
                        if (d != null) notifyQueue.add(d);
                        break;
                    }
                }
            }
            if (writeChar == null) { lastError = "Óculos diferente do esperado"; connecting = false; push(); return; }
            nextNotify(g);
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if (!notifyQueue.isEmpty()) { nextNotify(g); return; }
            opened(g);
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v) { rx(v); }
        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) { rx(c.getValue()); }

        @Override public void onReadRemoteRssi(BluetoothGatt g, int value, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) { rssi = value; push(); }
        }
    };

    private void nextNotify(BluetoothGatt g) {
        BluetoothGattDescriptor d = notifyQueue.poll();
        if (d == null) { opened(g); return; }
        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        else { setDesc(d); g.writeDescriptor(d); }
    }

    @SuppressWarnings("deprecation")
    private static void setDesc(BluetoothGattDescriptor d) { d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); }

    private void opened(BluetoothGatt g) {
        if (ready) return;
        ready = true; connecting = false; tries = 0; lastError = "";
        connectedAt = System.currentTimeMillis();
        try { if (target != null && target.getBondState() == BluetoothDevice.BOND_NONE) target.createBond(); } catch (Exception ignored) { }
        main.removeCallbacks(tick);
        main.postDelayed(tick, 700);
        push();
    }

    /** A cada 5 s mede o sinal; a cada 30 s pede a bateria. */
    private int tickCount = 0;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!ready || gatt == null) return;
            try { gatt.readRemoteRssi(); } catch (Exception ignored) { }
            if (tickCount++ % 6 == 0) send(frame(CMD_BATTERY, new byte[0]));
            main.postDelayed(this, 5_000);
        }
    };

    private void rx(byte[] v) {
        if (v == null || v.length < 2 || v[0] != (byte) 0xBC) return;
        int cmd = v[1] & 0xFF;
        if (cmd == CMD_BATTERY && v.length >= 7) {
            int len = (v[2] & 0xFF) | ((v[3] & 0xFF) << 8);
            int lvl = v[6] & 0xFF;
            if (len > 0 && lvl <= 100) {
                battery = lvl; lastBatteryAt = System.currentTimeMillis();
                if (firstBattery < 0 || lvl > firstBattery) { firstBattery = lvl; firstBatteryAt = lastBatteryAt; }
                push();
            }
        }
    }

    /** Faz o óculos apitar/piscar para achar. */
    void find() { send(frame(CMD_CONTROL, new byte[]{0x02, 0x01, 0x0D})); }

    private void send(byte[] data) {
        BluetoothGattCharacteristic c = cmdChar != null ? cmdChar : writeChar;
        if (gatt == null || c == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 33) gatt.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            else legacyWrite(c, data);
        } catch (Exception ignored) { }
    }

    @SuppressWarnings("deprecation")
    private void legacyWrite(BluetoothGattCharacteristic c, byte[] data) { c.setValue(data); gatt.writeCharacteristic(c); }

    private static BluetoothGattCharacteristic pick(List<BluetoothGattCharacteristic> all, String... hints) {
        for (String h : hints) for (BluetoothGattCharacteristic c : all) if (c.getUuid().toString().contains(h)) return c;
        return null;
    }

    static byte[] frame(int cmd, byte[] payload) {
        int crc = 0xFFFF;
        if (payload.length > 0) {
            for (byte b : payload) {
                crc ^= (b & 0xFF);
                for (int i = 0; i < 8; i++) crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xA001 : crc >>> 1;
            }
        }
        byte[] o = new byte[6 + payload.length];
        o[0] = (byte) 0xBC; o[1] = (byte) cmd;
        o[2] = (byte) (payload.length & 0xFF); o[3] = (byte) ((payload.length >> 8) & 0xFF);
        o[4] = (byte) (crc & 0xFF); o[5] = (byte) ((crc >> 8) & 0xFF);
        System.arraycopy(payload, 0, o, 6, payload.length);
        return o;
    }

    // ------------------------------------------------------------ microfone do óculos

    /** Liga/desliga o microfone do óculos (modo "chamada" do Bluetooth). */
    @SuppressWarnings("deprecation")
    void mic(boolean on) {
        try {
            if (on) {
                audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
                boolean done = false;
                if (Build.VERSION.SDK_INT >= 31) {
                    for (AudioDeviceInfo d : audio.getAvailableCommunicationDevices()) {
                        if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || d.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                            done = audio.setCommunicationDevice(d); break;
                        }
                    }
                }
                if (!done) { audio.startBluetoothSco(); audio.setBluetoothScoOn(true); }
                micOn = true;
            } else if (micOn) {
                if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice();
                try { audio.stopBluetoothSco(); audio.setBluetoothScoOn(false); } catch (Exception ignored) { }
                audio.setMode(AudioManager.MODE_NORMAL);
                micOn = false;
            }
        } catch (Exception e) { lastError = "Não consegui usar o microfone do óculos"; }
        push();
    }

    // ------------------------------------------------------------ toque no óculos

    private void startMediaButtons() {
        if (session != null) return;
        try {
            session = new MediaSession(ctx, "JJGlasses");
            session.setCallback(new MediaSession.Callback() {
                @Override public boolean onMediaButtonEvent(Intent i) {
                    KeyEvent k = i.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                    if (k == null || k.getAction() != KeyEvent.ACTION_DOWN) return true;
                    int c = k.getKeyCode();
                    if (c == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || c == KeyEvent.KEYCODE_HEADSETHOOK
                            || c == KeyEvent.KEYCODE_MEDIA_PLAY || c == KeyEvent.KEYCODE_MEDIA_PAUSE) { out.tap(); return true; }
                    return super.onMediaButtonEvent(i);
                }
            });
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f).build());
            session.setActive(true);
        } catch (Exception ignored) { }
    }

    void release() {
        stop();
        if (session != null) { try { session.release(); } catch (Exception ignored) { } session = null; }
    }

    boolean isReady() { return ready; }
}
