package com.gcode.ai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.net.InetSocketAddress;
import java.net.Socket;

public class HibbanDaemonService extends Service {
    private static final String CHANNEL_ID = "hibban_engine_channel";
    private static final int NOTIF_ID = 20128;
    private static volatile PowerManager.WakeLock sPersistentWakeLock = null;
    private Thread watchdogThread;
    private volatile boolean isRunning = false;
    private static volatile HibbanDaemonService instance;

    public static synchronized void acquirePersistentWakeLock(Context context) {
        try {
            if (sPersistentWakeLock == null && context != null) {
                PowerManager pm = (PowerManager) context.getApplicationContext().getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    sPersistentWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.gcode.ai:OmniRouteKeepAlive");
                    sPersistentWakeLock.setReferenceCounted(false);
                }
            }
            if (sPersistentWakeLock != null && !sPersistentWakeLock.isHeld()) {
                sPersistentWakeLock.acquire();
            }
        } catch (Throwable ignored) {}
    }

    public static HibbanDaemonService getInstance() {
        return instance;
    }

    public static void start(Context context) {
        try {
            acquirePersistentWakeLock(context);
            Intent intent = new Intent(context, HibbanDaemonService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        isRunning = true;
        acquirePersistentWakeLock(this);

        createNotificationChannel();
        Notification notification = buildNotification("Nexhib Engine Active", "OmniRoute Gateway & Background Execution on Port 20128");
        
        boolean foregroundStarted = false;
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                java.lang.reflect.Method m = Service.class.getMethod("startForeground", int.class, Notification.class, int.class);
                m.invoke(this, NOTIF_ID, notification, 1); // 1 = FOREGROUND_SERVICE_TYPE_DATA_SYNC
                foregroundStarted = true;
            } catch (Throwable ignored) {}
        }
        if (!foregroundStarted) {
            startForeground(NOTIF_ID, notification);
        }

        startWatchdog();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Nexhib Engine Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Maintains background OmniRoute AI Gateway and autonomous task execution.");
            channel.setShowBadge(false);

            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String title, String content) {
        Intent launchIntent = new Intent(this, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setContentTitle(title)
               .setContentText(content)
               .setSmallIcon(R.drawable.icon)
               .setContentIntent(pi)
               .setOngoing(true);

        return builder.build();
    }

    private void startWatchdog() {
        watchdogThread = new Thread(new Runnable() {
            @Override
            public void run() {
                WebAppInterface bridge = new WebAppInterface(getApplicationContext());
                while (isRunning) {
                    try {
                        boolean isPortListening = false;
                        try (Socket socket = new Socket()) {
                            socket.connect(new InetSocketAddress("127.0.0.1", 20128), 500);
                            isPortListening = true;
                        } catch (Exception ignored) {}

                        if (!isPortListening) {
                            bridge.ensureOmniRouteAsync();
                        }
                    } catch (Throwable ignored) {}

                    try {
                        Thread.sleep(4000);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        });
        watchdogThread.setDaemon(true);
        watchdogThread.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        acquirePersistentWakeLock(this);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        instance = null;
        if (watchdogThread != null) {
            watchdogThread.interrupt();
        }
        super.onDestroy();

        // Auto restart service if Android ever stops it
        start(getApplicationContext());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
