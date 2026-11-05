package com.megster.cordova.ble.central;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.apache.cordova.LOG;

/*
 * Foreground service = "kotva" procesu po dobu zapnuteho automatickeho modu eCard.
 *
 * Nic nedela: zadny scan, zadny wakelock. Jen drzi proces v prioritni tride FGS, aby ho
 * system na pozadi nezmrazil (Android 11+ cached-apps freezer, Samsung Freecess) a aby
 * se mu doruciovaly broadcasty SCREEN_ON/OFF, na kterych stoji automaticky mod.
 * Bez ni je proces po otevreni nekolika dalsich aplikaci zmrazen a identifikace neprobehne
 * (docs/navrh-foreground-service.md, analyza sekce L).
 *
 * Zivot sluzby:
 *  - start jen z popredi (Android 12+ nedovoli start FGS z pozadi) - hlida BLECentralPlugin,
 *  - START_NOT_STICKY: po zabiti procesu ji system neobnovuje (bez JS nema co drzet),
 *  - onTaskRemoved (odsunuti z nedavnych): stopSelf - WebView zanika, sluzba by drzela mrtvy proces,
 *  - stop pres Context.stopService (BLECentralPlugin.onDestroy, akce stopKeepAlive).
 */
public class AktionKeepAliveService extends Service {
    private static final String TAG = "AktionKeepAlive";

    static final String ACTION_START = "cz.aktion.ecard.keepalive.START";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_TEXT = "text";

    private static final String CHANNEL_ID = "ecard_keepalive";
    private static final int NOTIFICATION_ID = 0x0ECA;

    private static volatile boolean running = false;

    static boolean isRunning() {
        return running;
    }

    /* Spusti sluzbu nebo (pokud uz bezi) jen prepise text notifikace. */
    static void start(Context context, String title, String text) {
        Intent intent = new Intent(context, AktionKeepAliveService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text);
        ContextCompat.startForegroundService(context, intent);
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, AktionKeepAliveService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            // restart systemem bez intentu (nemelo by nastat, START_NOT_STICKY) nebo neznama akce
            LOG.d(TAG, "onStartCommand without START action - stopping");
            stopSelf();
            return START_NOT_STICKY;
        }
        String title = intent.getStringExtra(EXTRA_TITLE);
        String text = intent.getStringExtra(EXTRA_TEXT);
        if (title == null || title.isEmpty()) {
            title = "Aktion eCard";
        }
        try {
            Notification notification = buildNotification(title, text == null ? "" : text);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            running = true;
            LOG.d(TAG, "foreground service running: " + text);
        } catch (Exception e) {
            // SecurityException (chybi BLUETOOTH_CONNECT/SCAN), ForegroundServiceStartNotAllowedException
            // (start z pozadi, API 31+) - sluzbu neni mozne drzet, uklidit a nechat JS zkusit znovu z popredi
            LOG.e(TAG, "startForeground failed: " + e);
            running = false;
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private Notification buildNotification(String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, title, NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableVibration(false);
            nm.createNotificationChannel(channel);
        }

        PendingIntent tap = null;
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) {
            launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                piFlags |= PendingIntent.FLAG_IMMUTABLE;
            }
            tap = PendingIntent.getActivity(this, 0, launch, piFlags);
        }

        int icon = getResources().getIdentifier("aktion_keepalive", "drawable", getPackageName());
        if (icon == 0) {
            icon = getApplicationInfo().icon;
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        if (tap != null) {
            builder.setContentIntent(tap);
        }
        return builder.build();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        LOG.d(TAG, "task removed - stopping foreground service");
        stopSelf();
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Exception e) {
            LOG.w(TAG, "stopForeground failed: " + e);
        }
        LOG.d(TAG, "foreground service destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
