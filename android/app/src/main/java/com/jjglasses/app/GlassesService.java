package com.jjglasses.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * "Serviço em primeiro plano" (foreground service): mantém o app vivo com a tela apagada
 * enquanto o óculos está conectado, para ele continuar falando e ouvindo com o celular no bolso.
 * Aparece como uma notificação fixa "JJGlasses ligado ao óculos".
 */
public class GlassesService extends Service {
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("oculos", "Óculos conectado", NotificationManager.IMPORTANCE_LOW));
            PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(this, "oculos")
                    .setContentTitle("JJGlasses ligado ao óculos")
                    .setContentText("Toque no óculos para falar com a IA.")
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setContentIntent(open).setOngoing(true).build();
            if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(7, n);
        } catch (Throwable e) { stopSelf(); }
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
