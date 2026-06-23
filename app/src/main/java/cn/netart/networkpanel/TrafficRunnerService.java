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
import android.os.SystemClock;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class TrafficRunnerService extends Service {
    static final String ACTION_START = "cn.netart.networkpanel.TRAFFIC_START";
    static final String ACTION_PAUSE = "cn.netart.networkpanel.TRAFFIC_PAUSE";
    static final String ACTION_STOP = "cn.netart.networkpanel.TRAFFIC_STOP";
    static final String ACTION_RESET = "cn.netart.networkpanel.TRAFFIC_RESET";
    static final String ACTION_SWITCH = "cn.netart.networkpanel.TRAFFIC_SWITCH";
    static final String CHANNEL_ID = "traffic_runner";
    static final int NOTIFICATION_ID = 1818;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final AtomicLong SESSION_BYTES = new AtomicLong(0);
    private static final AtomicLong RATE = new AtomicLong(0);
    private static final AtomicInteger ACTIVE_WORKERS = new AtomicInteger(0);
    private static final AtomicInteger LAST_WORKERS = new AtomicInteger(0);
    private static final AtomicInteger RUN_ID = new AtomicInteger(0);
    private static volatile int targetCount;
    private static volatile String activeTarget = "--";
    private static volatile String message = "待开始";
    private static final List<Listener> LISTENERS = new ArrayList<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService executor;
    private PowerManager.WakeLock wakeLock;
    private final Object rateLimitLock = new Object();
    private long lastBytes;
    private long lastPersistBytes;
    private long lastRateTime;
    private long limitBytes;
    private long rateLimitBytesPerSecond;
    private long rateWindowStart;
    private long rateWindowBytes;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateRate();
            notifyListeners();
            if (RUNNING.get()) {
                startForeground(NOTIFICATION_ID, buildNotification());
                if (limitBytes > 0 && SESSION_BYTES.get() >= limitBytes) {
                    message = "已达到流量上限";
                    stopWorkers(false);
                    return;
                }
                handler.postDelayed(this, 1000L);
            }
        }
    };

    interface Listener {
        void onTrafficUpdate(TrafficStatsState state);
    }

    static void addListener(Listener listener) {
        synchronized (LISTENERS) {
            if (!LISTENERS.contains(listener)) {
                LISTENERS.add(listener);
            }
        }
        listener.onTrafficUpdate(currentState(null));
    }

    static void removeListener(Listener listener) {
        synchronized (LISTENERS) {
            LISTENERS.remove(listener);
        }
    }

    static TrafficStatsState currentState(Context context) {
        long total = context == null ? 0L : TrafficPrefs.readTotalBytes(context);
        return new TrafficStatsState(
                RUNNING.get(),
                SESSION_BYTES.get(),
                total,
                RATE.get(),
                RUNNING.get() ? Math.max(0, ACTIVE_WORKERS.get()) : Math.max(0, LAST_WORKERS.get()),
                targetCount,
                activeTarget,
                message
        );
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_PAUSE.equals(action)) {
            message = "已暂停";
            stopWorkers(false);
            return START_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            message = "已停止";
            stopWorkers(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_RESET.equals(action)) {
            SESSION_BYTES.set(0);
            RATE.set(0);
            lastBytes = 0;
            lastPersistBytes = 0;
            lastRateTime = System.currentTimeMillis();
            TrafficPrefs.resetTotalBytes(this);
            message = "流量已清零";
            notifyListeners();
            if (RUNNING.get()) {
                startForeground(NOTIFICATION_ID, buildNotification());
                return START_STICKY;
            }
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_SWITCH.equals(action)) {
            if (RUNNING.get()) {
                message = "切换线路中";
                stopWorkers(false);
                startRunning();
            } else {
                refreshSelectedTarget();
                message = "已选择线路";
                notifyListeners();
            }
            return START_STICKY;
        }
        startRunning();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopWorkers(true);
        super.onDestroy();
    }

    @Override
    public void onTimeout(int type, int reason) {
        message = "\u7cfb\u7edf\u5df2\u6682\u505c\u540e\u53f0\u8fd0\u884c";
        stopWorkers(true);
        stopSelf();
    }

    private void startRunning() {
        if (RUNNING.get()) {
            return;
        }
        List<TrafficTarget> targets = selectedTargets();
        if (targets.isEmpty()) {
            message = "没有启用的链接";
            startForeground(NOTIFICATION_ID, buildNotification());
            notifyListeners();
            return;
        }

        long configuredLimitBytes = TrafficPrefs.readLimitMb(this) <= 0 ? 0L : TrafficPrefs.readLimitMb(this) * 1024L * 1024L;
        if (configuredLimitBytes > 0 && SESSION_BYTES.get() >= configuredLimitBytes) {
            SESSION_BYTES.set(0);
        }
        int runId = RUN_ID.incrementAndGet();
        long sessionStart = SESSION_BYTES.get();
        RUNNING.set(true);
        RATE.set(0);
        ACTIVE_WORKERS.set(0);
        LAST_WORKERS.set(0);
        targetCount = targets.size();
        activeTarget = targets.get(0).displayName();
        message = "运行中";
        lastBytes = sessionStart;
        lastPersistBytes = sessionStart;
        lastRateTime = System.currentTimeMillis();
        limitBytes = configuredLimitBytes;
        int rateLimitMbps = TrafficPrefs.readRateLimitMbps(this);
        rateLimitBytesPerSecond = rateLimitMbps <= 0 ? 0L : rateLimitMbps * 1_000_000L / 8L;
        resetRateLimiter();
        updateWakeLock();

        executor = Executors.newCachedThreadPool();
        for (TrafficTarget target : targets) {
            int threads = TrafficPrefs.readEnhanced(this) ? Math.max(1, target.threads) : 1;
            for (int i = 0; i < threads; i++) {
                executor.execute(() -> worker(target, runId));
            }
        }
        startForeground(NOTIFICATION_ID, buildNotification());
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    private List<TrafficTarget> selectedTargets() {
        List<TrafficTarget> all = TrafficPrefs.readTargets(this);
        List<TrafficTarget> out = new ArrayList<>();
        if (all.isEmpty()) {
            return out;
        }
        int index = TrafficPrefs.readActiveIndex(this, all.size());
        TrafficTarget selected = all.get(index);
        if (selected.url != null && !selected.url.trim().isEmpty()) {
            out.add(selected);
        }
        return out;
    }

    private void refreshSelectedTarget() {
        List<TrafficTarget> targets = selectedTargets();
        targetCount = targets.size();
        activeTarget = targets.isEmpty() ? "--" : targets.get(0).displayName();
    }

    private void worker(TrafficTarget target, int runId) {
        if (!RUNNING.get() || RUN_ID.get() != runId) {
            return;
        }
        ACTIVE_WORKERS.incrementAndGet();
        try {
            byte[] buffer = new byte[64 * 1024];
            while (RUNNING.get() && RUN_ID.get() == runId) {
                HttpURLConnection conn = null;
                try {
                    activeTarget = target.displayName();
                    URL url = new URL(withCacheBuster(normalizeUrl(target.url)));
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    conn.setUseCaches(false);
                    conn.setRequestProperty("Cache-Control", "no-cache");
                    conn.setRequestProperty("Accept-Encoding", "identity");
                    conn.setRequestProperty("User-Agent", "NativeNetworkPanel/TrafficRunner");
                    try (InputStream input = new BufferedInputStream(conn.getInputStream())) {
                        int read;
                        while (RUNNING.get() && RUN_ID.get() == runId && (read = input.read(buffer)) != -1) {
                            if (!RUNNING.get() || RUN_ID.get() != runId) {
                                break;
                            }
                            SESSION_BYTES.addAndGet(read);
                            throttleRate(read, runId);
                            if (limitBytes > 0 && SESSION_BYTES.get() >= limitBytes) {
                                break;
                            }
                        }
                    }
                } catch (IOException e) {
                    if (RUN_ID.get() == runId) {
                        message = target.displayName() + " 重连：" + safeMessage(e);
                    }
                    sleepQuietly(700);
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
            }
        } finally {
            if (RUN_ID.get() == runId) {
                decrementActiveWorkers();
                notifyListeners();
            }
        }
    }

    private void stopWorkers(boolean removeNotification) {
        boolean wasRunning = RUNNING.getAndSet(false);
        if (!wasRunning) {
            if (removeNotification) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            }
            releaseWakeLock();
            notifyListeners();
            return;
        }
        RUN_ID.incrementAndGet();
        persistDelta();
        handler.removeCallbacks(tick);
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        LAST_WORKERS.set(Math.max(0, ACTIVE_WORKERS.get()));
        ACTIVE_WORKERS.set(0);
        RATE.set(0);
        releaseWakeLock();
        if (removeNotification) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification());
        }
        notifyListeners();
    }

    private void resetRateLimiter() {
        synchronized (rateLimitLock) {
            rateWindowStart = SystemClock.elapsedRealtime();
            rateWindowBytes = 0L;
        }
    }

    private void throttleRate(int bytes, int runId) {
        long limit = rateLimitBytesPerSecond;
        if (limit <= 0 || bytes <= 0) {
            return;
        }
        long sleepMs;
        synchronized (rateLimitLock) {
            long now = SystemClock.elapsedRealtime();
            if (rateWindowStart <= 0 || now - rateWindowStart >= 1000L) {
                rateWindowStart = now;
                rateWindowBytes = 0L;
            }
            rateWindowBytes += bytes;
            long expectedElapsed = rateWindowBytes * 1000L / limit;
            long actualElapsed = Math.max(0L, now - rateWindowStart);
            sleepMs = expectedElapsed - actualElapsed;
        }
        if (sleepMs > 0 && RUNNING.get() && RUN_ID.get() == runId) {
            sleepQuietly(sleepMs);
        }
    }

    private void decrementActiveWorkers() {
        while (true) {
            int current = ACTIVE_WORKERS.get();
            if (current <= 0) {
                return;
            }
            if (ACTIVE_WORKERS.compareAndSet(current, current - 1)) {
                LAST_WORKERS.set(Math.max(0, current - 1));
                return;
            }
        }
    }

    private void updateRate() {
        long now = System.currentTimeMillis();
        long current = SESSION_BYTES.get();
        long deltaBytes = Math.max(0, current - lastBytes);
        long deltaMs = Math.max(1, now - lastRateTime);
        RATE.set(deltaBytes * 1000L / deltaMs);
        lastBytes = current;
        lastRateTime = now;
        persistDelta();
    }

    private void persistDelta() {
        long current = SESSION_BYTES.get();
        long delta = Math.max(0, current - lastPersistBytes);
        if (delta > 0) {
            TrafficPrefs.addTotalBytes(this, delta);
            lastPersistBytes = current;
        }
    }

    private void notifyListeners() {
        TrafficStatsState state = currentState(this);
        synchronized (LISTENERS) {
            for (Listener listener : new ArrayList<>(LISTENERS)) {
                MAIN.post(() -> listener.onTrafficUpdate(state));
            }
        }
    }

    private Notification buildNotification() {
        Intent launchIntent = new Intent(this, MainActivity.class);
        PendingIntent launch = PendingIntent.getActivity(
                this,
                10,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        PendingIntent pause = serviceIntent(ACTION_PAUSE, 11);
        PendingIntent start = serviceIntent(ACTION_START, 12);
        PendingIntent stop = serviceIntent(ACTION_STOP, 13);

        String title = RUNNING.get() ? "流量任务运行中" : "流量任务已暂停";
        String text = Formatters.bytes(SESSION_BYTES.get())
                + " · " + Formatters.bitrate(RATE.get())
                + " · " + Math.max(0, ACTIVE_WORKERS.get()) + "线程";
        int rateLimitMbps = TrafficPrefs.readRateLimitMbps(this);
        if (rateLimitMbps > 0) {
            text += " · 上限" + rateLimitMbps + "Mbps";
        }

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text + "\n" + activeTarget))
                .setContentIntent(launch)
                .setOngoing(RUNNING.get())
                .setOnlyAlertOnce(true)
                .setShowWhen(false);
        if (RUNNING.get()) {
            builder.addAction(0, "暂停", pause);
        } else {
            builder.addAction(0, "继续", start);
        }
        builder.addAction(0, "停止", stop);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    private PendingIntent serviceIntent(String action, int requestCode) {
        Intent intent = new Intent(this, TrafficRunnerService.class);
        intent.setAction(action);
        return PendingIntent.getService(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private void updateWakeLock() {
        if (!TrafficPrefs.readKeepAwake(this)) {
            releaseWakeLock();
            return;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NetworkPanel:TrafficRunner");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
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
                "流量任务",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("持续请求选定线路并显示流量进度");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private String normalizeUrl(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.toLowerCase(Locale.US).startsWith("http://")
                || trimmed.toLowerCase(Locale.US).startsWith("https://")) {
            return trimmed;
        }
        return "https://" + trimmed;
    }

    private String withCacheBuster(String value) {
        String join = value.contains("?") ? "&" : "?";
        return value + join + "np_t=" + System.currentTimeMillis()
                + "_" + Thread.currentThread().getId();
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
