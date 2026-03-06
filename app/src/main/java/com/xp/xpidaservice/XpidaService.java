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
import android.os.IBinder;
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

    private XpidaTcpServer tcpServer;
    private XpidaCommandExecutor commandExecutor;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private NotificationManager notificationManager;

    private String currentStatus = "初始化中...";
    private int currentClients = 0;

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

    // --- StatusListener callbacks ---

    @Override
    public void onServerStarted(int port) {
        String ip = getLocalIpAddress();
        currentStatus = "监听中 " + ip + ":" + port;
        updateNotification();
    }

    @Override
    public void onServerStopped() {
        currentStatus = "已停止";
        updateNotification();
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
        currentStatus = "错误: " + message;
        updateNotification();
    }

    @Override
    public void onConnectionCountChanged(int count) {
        currentClients = count;
        updateNotification();
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

        String text = currentStatus;
        if (currentClients > 0) {
            text += " | 连接数: " + currentClients;
        }

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Xpida Service")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(R.drawable.ic_stop, "停止", stopPi)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
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

    private String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : interfaces) {
                if (ni.isLoopback() || !ni.isUp()) continue;
                List<InetAddress> addrs = Collections.list(ni.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (addr.getHostAddress().contains(":")) continue; // skip IPv6
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
