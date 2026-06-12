package cn.netart.networkpanel;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class SpeedTestEngine {
    interface Listener {
        void onProgress(TestPhase phase, long bytes, double bytesPerSecond, int percent);

        void onLog(String message);

        void onComplete(TestResult result);

        void onError(String message);
    }

    enum TestPhase {
        LATENCY,
        DOWNLOAD,
        UPLOAD,
        FINISHED
    }

    static final class TestResult {
        final long latencyMs;
        final long downloadedBytes;
        final long uploadedBytes;
        final double downloadBytesPerSecond;
        final double uploadBytesPerSecond;

        TestResult(long latencyMs, long downloadedBytes, long uploadedBytes,
                double downloadBytesPerSecond, double uploadBytesPerSecond) {
            this.latencyMs = latencyMs;
            this.downloadedBytes = downloadedBytes;
            this.uploadedBytes = uploadedBytes;
            this.downloadBytesPerSecond = downloadBytesPerSecond;
            this.uploadBytesPerSecond = uploadBytesPerSecond;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancel = new AtomicBoolean(false);
    private ExecutorService executor;

    boolean isRunning() {
        return running.get();
    }

    void cancel() {
        cancel.set(true);
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    void start(AppPrefs.Settings settings, Listener listener) {
        if (!running.compareAndSet(false, true)) {
            listener.onError("测速正在进行中");
            return;
        }
        cancel.set(false);
        executor = Executors.newCachedThreadPool();
        executor.execute(() -> {
            try {
                runTest(settings, listener);
            } catch (Exception e) {
                postError(listener, "测速失败：" + safeMessage(e));
            } finally {
                running.set(false);
                if (executor != null) {
                    executor.shutdownNow();
                }
            }
        });
    }

    private void runTest(AppPrefs.Settings settings, Listener listener) throws Exception {
        URL downloadUrl = new URL(normalizeUrl(settings.downloadUrl));
        URL uploadUrl = new URL(normalizeUrl(settings.uploadUrl));

        postLog(listener, "连接节点：" + downloadUrl.getHost());
        long latency = measureLatency(downloadUrl, listener);
        if (cancel.get()) {
            return;
        }

        postLog(listener, "下载测速：" + settings.threads + " 线程 / " + settings.durationSeconds + " 秒");
        TransferStats download = download(downloadUrl, settings.threads, settings.durationSeconds, listener);
        if (cancel.get()) {
            return;
        }

        postLog(listener, "上传测速：" + Math.max(1, settings.threads / 2) + " 线程 / " + Math.max(3, settings.durationSeconds / 2) + " 秒");
        TransferStats upload = upload(uploadUrl, Math.max(1, settings.threads / 2), Math.max(3, settings.durationSeconds / 2), listener);
        if (cancel.get()) {
            return;
        }

        TestResult result = new TestResult(latency, download.bytes, upload.bytes,
                download.bytesPerSecond, upload.bytesPerSecond);
        postProgress(listener, TestPhase.FINISHED, download.bytes + upload.bytes, 0, 100);
        main.post(() -> listener.onComplete(result));
    }

    private long measureLatency(URL url, Listener listener) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3 && !cancel.get(); i++) {
            HttpURLConnection conn = null;
            long start = SystemClock.elapsedRealtime();
            try {
                conn = (HttpURLConnection) new URL(withCacheBuster(url.toString())).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setUseCaches(false);
                conn.setRequestMethod("HEAD");
                int code = conn.getResponseCode();
                long elapsed = SystemClock.elapsedRealtime() - start;
                if (code >= 200 && code < 500) {
                    best = Math.min(best, elapsed);
                    postLog(listener, "延迟样本 " + (i + 1) + "：" + elapsed + " ms");
                }
            } catch (IOException e) {
                postLog(listener, "延迟样本 " + (i + 1) + " 失败：" + safeMessage(e));
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
            postProgress(listener, TestPhase.LATENCY, 0, 0, (i + 1) * 33);
        }
        if (best == Long.MAX_VALUE) {
            return 0;
        }
        return best;
    }

    private TransferStats download(URL url, int threads, int durationSeconds, Listener listener) throws InterruptedException {
        AtomicLong bytes = new AtomicLong();
        long start = SystemClock.elapsedRealtime();
        long end = start + durationSeconds * 1000L;
        for (int i = 0; i < threads; i++) {
            final int index = i;
            executor.execute(() -> downloadWorker(url, index, end, bytes, listener));
        }
        return waitForTransfer(TestPhase.DOWNLOAD, durationSeconds, bytes, listener);
    }

    private void downloadWorker(URL url, int index, long end, AtomicLong bytes, Listener listener) {
        byte[] buffer = new byte[32 * 1024];
        while (!cancel.get() && SystemClock.elapsedRealtime() < end) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(withCacheBuster(url.toString())).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setUseCaches(false);
                conn.setRequestProperty("Cache-Control", "no-cache");
                conn.setRequestProperty("Accept-Encoding", "identity");
                conn.setRequestProperty("User-Agent", "NativeNetworkPanel/1.0");
                try (InputStream input = new BufferedInputStream(conn.getInputStream())) {
                    int read;
                    while (!cancel.get() && SystemClock.elapsedRealtime() < end && (read = input.read(buffer)) != -1) {
                        bytes.addAndGet(read);
                    }
                }
            } catch (IOException e) {
                postLog(listener, "下载线程 " + (index + 1) + " 重连：" + safeMessage(e));
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
    }

    private TransferStats upload(URL url, int threads, int durationSeconds, Listener listener) throws InterruptedException {
        AtomicLong bytes = new AtomicLong();
        long start = SystemClock.elapsedRealtime();
        long end = start + durationSeconds * 1000L;
        byte[] payload = new byte[64 * 1024];
        new Random(7).nextBytes(payload);
        for (int i = 0; i < threads; i++) {
            final int index = i;
            executor.execute(() -> uploadWorker(url, index, end, payload, bytes, listener));
        }
        return waitForTransfer(TestPhase.UPLOAD, durationSeconds, bytes, listener);
    }

    private void uploadWorker(URL url, int index, long end, byte[] payload, AtomicLong bytes, Listener listener) {
        while (!cancel.get() && SystemClock.elapsedRealtime() < end) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setDoOutput(true);
                conn.setUseCaches(false);
                conn.setRequestMethod("POST");
                conn.setFixedLengthStreamingMode(payload.length);
                conn.setRequestProperty("Content-Type", "application/octet-stream");
                conn.setRequestProperty("User-Agent", "NativeNetworkPanel/1.0");
                try (OutputStream output = new BufferedOutputStream(conn.getOutputStream())) {
                    output.write(payload);
                    output.flush();
                    bytes.addAndGet(payload.length);
                }
                drain(conn);
            } catch (IOException e) {
                postLog(listener, "上传线程 " + (index + 1) + " 重连：" + safeMessage(e));
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
    }

    private TransferStats waitForTransfer(TestPhase phase, int durationSeconds, AtomicLong bytes, Listener listener)
            throws InterruptedException {
        long start = SystemClock.elapsedRealtime();
        long lastTime = start;
        long lastBytes = 0;
        double lastRate = 0;
        while (!cancel.get()) {
            long now = SystemClock.elapsedRealtime();
            long elapsed = now - start;
            if (elapsed >= durationSeconds * 1000L) {
                break;
            }
            long current = bytes.get();
            long deltaBytes = Math.max(0, current - lastBytes);
            long deltaMs = Math.max(1, now - lastTime);
            lastRate = deltaBytes * 1000d / deltaMs;
            int percent = (int) Math.min(99, elapsed * 100L / Math.max(1, durationSeconds * 1000L));
            postProgress(listener, phase, current, lastRate, percent);
            lastBytes = current;
            lastTime = now;
            TimeUnit.MILLISECONDS.sleep(500);
        }
        long totalMs = Math.max(1, SystemClock.elapsedRealtime() - start);
        long totalBytes = bytes.get();
        double average = totalBytes * 1000d / totalMs;
        postProgress(listener, phase, totalBytes, average > 0 ? average : lastRate, 100);
        return new TransferStats(totalBytes, average);
    }

    private void drain(HttpURLConnection conn) {
        InputStream input = null;
        try {
            input = conn.getInputStream();
            byte[] scratch = new byte[1024];
            while (input.read(scratch) != -1) {
                // Drain response to let the upload request complete cleanly.
            }
        } catch (IOException ignored) {
            try {
                input = conn.getErrorStream();
                if (input != null) {
                    byte[] scratch = new byte[1024];
                    while (input.read(scratch) != -1) {
                        // Drain error stream too.
                    }
                }
            } catch (IOException ignoredAgain) {
                // Best effort only.
            }
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                    // Best effort only.
                }
            }
        }
    }

    private String normalizeUrl(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            return AppPrefs.DEFAULT_DOWNLOAD_URL;
        }
        if (!trimmed.toLowerCase(Locale.US).startsWith("http://")
                && !trimmed.toLowerCase(Locale.US).startsWith("https://")) {
            return "https://" + trimmed;
        }
        return trimmed;
    }

    private String withCacheBuster(String value) {
        String join = value.contains("?") ? "&" : "?";
        return value + join + "np_t=" + SystemClock.elapsedRealtime()
                + "_" + Thread.currentThread().getId();
    }

    private void postProgress(Listener listener, TestPhase phase, long bytes, double bytesPerSecond, int percent) {
        main.post(() -> listener.onProgress(phase, bytes, bytesPerSecond, percent));
    }

    private void postLog(Listener listener, String message) {
        main.post(() -> listener.onLog("[" + Formatters.timeNow() + "] " + message));
    }

    private void postError(Listener listener, String message) {
        main.post(() -> listener.onError(message));
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static final class TransferStats {
        final long bytes;
        final double bytesPerSecond;

        TransferStats(long bytes, double bytesPerSecond) {
            this.bytes = bytes;
            this.bytesPerSecond = bytesPerSecond;
        }
    }
}
