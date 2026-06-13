package cn.netart.networkpanel;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

final class TrafficPrefs {
    private static final String NAME = "traffic_runner";
    private static final String KEY_TARGETS = "targets";
    private static final String KEY_LIMIT_MB = "limit_mb";
    private static final String KEY_RATE_LIMIT_MBPS = "rate_limit_mbps";
    private static final String KEY_TOTAL_BYTES = "total_bytes";
    private static final String KEY_KEEP_AWAKE = "traffic_keep_awake";
    private static final String KEY_ENHANCED = "traffic_enhanced";
    private static final String KEY_ACTIVE_INDEX = "active_index";

    private TrafficPrefs() {
    }

    static SharedPreferences open(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    static List<TrafficTarget> readTargets(Context context) {
        SharedPreferences prefs = open(context);
        if (!prefs.contains(KEY_TARGETS)) {
            return new ArrayList<>();
        }
        String raw = prefs.getString(KEY_TARGETS, "");
        List<TrafficTarget> targets = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return targets;
        }
        String[] lines = raw.split("\\n");
        for (String line : lines) {
            String[] parts = line.split("\\t", -1);
            if (parts.length >= 5) {
                targets.add(new TrafficTarget(
                        decode(parts[0]),
                        decode(parts[1]),
                        clamp(parseInt(parts[2], 4), 1, 32),
                        "1".equals(parts[3]),
                        "1".equals(parts[4])
                ));
            }
        }
        return targets;
    }

    static void writeTargets(Context context, List<TrafficTarget> targets) {
        StringBuilder builder = new StringBuilder();
        for (TrafficTarget target : targets) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(encode(target.name)).append('\t')
                    .append(encode(target.url)).append('\t')
                    .append(clamp(target.threads, 1, 32)).append('\t')
                    .append(target.enhanced ? "1" : "0").append('\t')
                    .append(target.enabled ? "1" : "0");
        }
        open(context).edit().putString(KEY_TARGETS, builder.toString()).commit();
    }

    static int readLimitMb(Context context) {
        return open(context).getInt(KEY_LIMIT_MB, 0);
    }

    static void writeLimitMb(Context context, int limitMb) {
        open(context).edit().putInt(KEY_LIMIT_MB, Math.max(0, limitMb)).apply();
    }

    static int readRateLimitMbps(Context context) {
        return open(context).getInt(KEY_RATE_LIMIT_MBPS, 0);
    }

    static void writeRateLimitMbps(Context context, int mbps) {
        open(context).edit().putInt(KEY_RATE_LIMIT_MBPS, Math.max(0, mbps)).apply();
    }

    static boolean readKeepAwake(Context context) {
        return open(context).getBoolean(KEY_KEEP_AWAKE, true);
    }

    static void writeKeepAwake(Context context, boolean keepAwake) {
        open(context).edit().putBoolean(KEY_KEEP_AWAKE, keepAwake).apply();
    }

    static boolean readEnhanced(Context context) {
        return open(context).getBoolean(KEY_ENHANCED, true);
    }

    static void writeEnhanced(Context context, boolean enhanced) {
        open(context).edit().putBoolean(KEY_ENHANCED, enhanced).apply();
    }

    static long readTotalBytes(Context context) {
        return open(context).getLong(KEY_TOTAL_BYTES, 0L);
    }

    static void addTotalBytes(Context context, long bytes) {
        if (bytes <= 0) {
            return;
        }
        SharedPreferences prefs = open(context);
        prefs.edit().putLong(KEY_TOTAL_BYTES, Math.max(0, prefs.getLong(KEY_TOTAL_BYTES, 0L) + bytes)).apply();
    }

    static void resetTotalBytes(Context context) {
        open(context).edit().putLong(KEY_TOTAL_BYTES, 0L).apply();
    }

    static int readActiveIndex(Context context, int size) {
        if (size <= 0) {
            return 0;
        }
        int index = open(context).getInt(KEY_ACTIVE_INDEX, 0);
        return Math.max(0, Math.min(size - 1, index));
    }

    static void writeActiveIndex(Context context, int index) {
        open(context).edit().putInt(KEY_ACTIVE_INDEX, Math.max(0, index)).commit();
    }

    private static String encode(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n");
    }

    private static String decode(String value) {
        StringBuilder out = new StringBuilder();
        boolean escape = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (escape) {
                if (ch == 't') {
                    out.append('\t');
                } else if (ch == 'n') {
                    out.append('\n');
                } else {
                    out.append(ch);
                }
                escape = false;
            } else if (ch == '\\') {
                escape = true;
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

}
