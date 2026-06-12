package cn.netart.networkpanel;

import android.content.Context;
import android.content.SharedPreferences;

final class AppPrefs {
    static final String DEFAULT_DOWNLOAD_URL = "https://speed.cloudflare.com/__down?bytes=100000000";
    static final String DEFAULT_UPLOAD_URL = "https://speed.cloudflare.com/__up";
    static final int THEME_COUNT = 6;

    private static final String NAME = "network_panel";
    private static final String KEY_DOWNLOAD_URL = "download_url";
    private static final String KEY_UPLOAD_URL = "upload_url";
    private static final String KEY_THREADS = "threads";
    private static final String KEY_DURATION = "duration";
    private static final String KEY_KEEP_SCREEN = "keep_screen";
    private static final String KEY_NOTIFY = "notify";
    private static final String KEY_THEME = "theme";

    private AppPrefs() {
    }

    static SharedPreferences open(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    static Settings read(Context context) {
        SharedPreferences prefs = open(context);
        return new Settings(
                prefs.getString(KEY_DOWNLOAD_URL, DEFAULT_DOWNLOAD_URL),
                prefs.getString(KEY_UPLOAD_URL, DEFAULT_UPLOAD_URL),
                clamp(prefs.getInt(KEY_THREADS, 4), 1, 16),
                clamp(prefs.getInt(KEY_DURATION, 8), 3, 30),
                prefs.getBoolean(KEY_KEEP_SCREEN, false),
                prefs.getBoolean(KEY_NOTIFY, true)
        );
    }

    static void write(Context context, Settings settings) {
        open(context).edit()
                .putString(KEY_DOWNLOAD_URL, nonBlank(settings.downloadUrl, DEFAULT_DOWNLOAD_URL))
                .putString(KEY_UPLOAD_URL, nonBlank(settings.uploadUrl, DEFAULT_UPLOAD_URL))
                .putInt(KEY_THREADS, clamp(settings.threads, 1, 16))
                .putInt(KEY_DURATION, clamp(settings.durationSeconds, 3, 30))
                .putBoolean(KEY_KEEP_SCREEN, settings.keepScreenAwake)
                .putBoolean(KEY_NOTIFY, settings.notificationEnabled)
                .apply();
    }

    static int readTheme(Context context) {
        return clamp(open(context).getInt(KEY_THEME, 0), 0, THEME_COUNT - 1);
    }

    static void writeTheme(Context context, int theme) {
        open(context).edit().putInt(KEY_THEME, clamp(theme, 0, THEME_COUNT - 1)).apply();
    }

    private static String nonBlank(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value.trim();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static final class Settings {
        final String downloadUrl;
        final String uploadUrl;
        final int threads;
        final int durationSeconds;
        final boolean keepScreenAwake;
        final boolean notificationEnabled;

        Settings(String downloadUrl, String uploadUrl, int threads, int durationSeconds,
                boolean keepScreenAwake, boolean notificationEnabled) {
            this.downloadUrl = downloadUrl;
            this.uploadUrl = uploadUrl;
            this.threads = threads;
            this.durationSeconds = durationSeconds;
            this.keepScreenAwake = keepScreenAwake;
            this.notificationEnabled = notificationEnabled;
        }

        Settings withKeepScreenAwake(boolean value) {
            return new Settings(downloadUrl, uploadUrl, threads, durationSeconds, value, notificationEnabled);
        }

        Settings withNotificationEnabled(boolean value) {
            return new Settings(downloadUrl, uploadUrl, threads, durationSeconds, keepScreenAwake, value);
        }
    }
}
