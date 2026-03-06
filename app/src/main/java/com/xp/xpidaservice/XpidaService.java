package com.xp.xpidaservice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

public class XpidaService extends Service implements XpidaTcpServer.StatusListener {

    private static final String TAG = "XpidaService";
    private static final String CHANNEL_ID = "xpida_service_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final String ACTION_STOP = "com.xp.xpidaservice.STOP";
    private static final long STATS_INTERVAL_MS = 2000;

    private XpidaTcpServer tcpServer;
    private XpidaCommandExecutor commandExecutor;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private NotificationManager notificationManager;

    private String currentStatus = "初始化中";
    private String currentListenAddr = null;
    private int currentClients = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastBytesSent = 0;
    private long lastBytesReceived = 0;
    private long sendSpeed = 0;
    private long recvSpeed = 0;

    private final Runnable statsUpdater = new Runnable() {
        @Override
        public void run() {
            if (tcpServer != null && tcpServer.isRunning()) {
                long sent = tcpServer.getTotalBytesSent();
                long received = tcpServer.getTotalBytesReceived();

                long intervalSec = STATS_INTERVAL_MS / 1000;
                if (intervalSec < 1) intervalSec = 1;
                sendSpeed = (sent - lastBytesSent) / intervalSec;
                recvSpeed = (received - lastBytesReceived) / intervalSec;

                lastBytesSent = sent;
                lastBytesReceived = received;
            }

            updateNotification();
            handler.postDelayed(this, STATS_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service onCreate");

        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        acquireWakeLocks();

        commandExecutor = new XpidaCommandExecutor();
        tcpServer = new XpidaTcpServer(XpidaTcpServer.DEFAULT_PORT, commandExecutor, this);
        tcpServer.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Log.i(TAG, "Stop requested via notification");
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Service onDestroy");
        handler.removeCallbacks(statsUpdater);
        if (tcpServer != null) {
            tcpServer.stop();
        }
        releaseWakeLocks();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.i(TAG, "Task removed, restarting service");
        Intent restartIntent = new Intent(getApplicationContext(), XpidaService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartIntent);
        } else {
            startService(restartIntent);
        }
        super.onTaskRemoved(rootIntent);
    }

    // --- StatusListener callbacks (called from TCP threads, post to main) ---

    @Override
    public void onServerStarted(int port) {
        handler.post(() -> {
            String ip = getLocalIpAddress();
            currentListenAddr = ip + ":" + port;
            currentStatus = "运行中";

            lastBytesSent = 0;
            lastBytesReceived = 0;
            sendSpeed = 0;
            recvSpeed = 0;
            handler.postDelayed(statsUpdater, STATS_INTERVAL_MS);

            updateNotification();
        });
    }

    @Override
    public void onServerStopped() {
        handler.post(() -> {
            currentStatus = "已停止";
            currentListenAddr = null;
            handler.removeCallbacks(statsUpdater);
            sendSpeed = 0;
            recvSpeed = 0;
            updateNotification();
        });
    }

    @Override
    public void onClientConnected(String addr) {
        Log.i(TAG, "Client connected: " + addr);
    }

    @Override
    public void onClientDisconnected(String addr) {
        Log.i(TAG, "Client disconnected: " + addr);
    }

    @Override
    public void onError(String message) {
        handler.post(() -> {
            currentStatus = "错误: " + message;
            updateNotification();
        });
    }

    @Override
    public void onConnectionCountChanged(int count) {
        handler.post(() -> {
            currentClients = count;
            updateNotification();
        });
    }

    // --- Notification ---

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Xpida Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Xpida TCP bridge service status");
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, XpidaService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = "Xpida · " + currentStatus;

        String contentText;
        if ("运行中".equals(currentStatus)) {
            String speedText = "↑ " + formatSpeed(sendSpeed) + "  ↓ " + formatSpeed(recvSpeed);
            if (currentClients > 0) {
                contentText = currentClients + " 连接 | " + speedText;
            } else {
                contentText = speedText;
            }
        } else {
            contentText = currentClients > 0 ? currentClients + " 连接" : "";
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(R.drawable.ic_stop, "停止", stopPi)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(false)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);

        if (currentListenAddr != null) {
            builder.setSubText(currentListenAddr);
        }

        return builder.build();
    }

    private void updateNotification() {
        notificationManager.notify(NOTIFICATION_ID, buildNotification());
    }

    // --- Wake locks for keep-alive ---

    private void acquireWakeLocks() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xpida:tcp_server");
        wakeLock.acquire();

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "xpida:wifi");
        wifiLock.acquire();
    }

    private void releaseWakeLocks() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
    }

    // --- Util ---

    private String formatSpeed(long bytesPerSec) {
        if (bytesPerSec < 1024) return bytesPerSec + " B/s";
        if (bytesPerSec < 1024 * 1024)
            return String.format("%.1f KB/s", bytesPerSec / 1024.0);
        if (bytesPerSec < 1024L * 1024 * 1024)
            return String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024));
        return String.format("%.1f GB/s", bytesPerSec / (1024.0 * 1024 * 1024));
    }

    private String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : interfaces) {
                if (ni.isLoopback() || !ni.isUp()) continue;
                List<InetAddress> addrs = Collections.list(ni.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (addr.getHostAddress().contains(":")) continue;
                    String ip = addr.getHostAddress();
                    if (ip != null && !ip.startsWith("127.")) return ip;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get IP", e);
        }
        return "0.0.0.0";
    }
}
