package cn.netart.networkpanel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

public class MonitorService extends Service {
    static final String ACTION_START = "cn.netart.networkpanel.START";
    static final String ACTION_STOP = "cn.netart.networkpanel.STOP";
    static final String EXTRA_KEEP_AWAKE = "keep_awake";
    static final String CHANNEL_ID = "network_panel_monitor";
    static final int NOTIFICATION_ID = 1717;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private NetworkMonitor monitor;
    private PowerManager.WakeLock wakeLock;
    private boolean started;
    private boolean keepAwake;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!started) {
                return;
            }
            NetworkSnapshot snapshot = monitor.sample();
            startForeground(NOTIFICATION_ID, buildNotification(snapshot));
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        monitor = new NetworkMonitor(this);
        ensureChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopMonitoring();
            stopSelf();
            return START_NOT_STICKY;
        }
        keepAwake = intent == null || intent.getBooleanExtra(EXTRA_KEEP_AWAKE, AppPrefs.read(this).keepScreenAwake);
        started = true;
        updateWakeLock();
        NetworkSnapshot snapshot = monitor.sample();
        startForeground(NOTIFICATION_ID, buildNotification(snapshot));
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 1000L);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopMonitoring();
        super.onDestroy();
    }

    private void stopMonitoring() {
        started = false;
        handler.removeCallbacks(tick);
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private void updateWakeLock() {
        if (!keepAwake) {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            wakeLock = null;
            return;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NetworkPanel:Monitor");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
    }

    private Notification buildNotification(NetworkSnapshot snapshot) {
        Intent launchIntent = new Intent(this, MainActivity.class);
        PendingIntent launch = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, MonitorService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String title = snapshot.type + "  " + snapshot.localIp;
        String text = "下行 " + Formatters.bitrate(snapshot.downBytesPerSecond)
                + " · 上行 " + Formatters.bitrate(snapshot.upBytesPerSecond)
                + " · 信号 " + Formatters.percent(snapshot.signalPercent);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(launch)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .addAction(0, "停止", stop);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "网络监控",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("显示实时上传、下载速度和网络状态");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }
}
