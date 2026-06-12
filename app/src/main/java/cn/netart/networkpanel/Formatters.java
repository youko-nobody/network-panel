package cn.netart.networkpanel;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class Formatters {
    private static final DecimalFormat ONE_DECIMAL = new DecimalFormat("0.0");
    private static final DecimalFormat TWO_DECIMAL = new DecimalFormat("0.00");
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);

    private Formatters() {
    }

    static String bitrate(double bytesPerSecond) {
        double bits = Math.max(0, bytesPerSecond) * 8d;
        if (bits >= 1_000_000_000d) {
            return TWO_DECIMAL.format(bits / 1_000_000_000d) + " Gbps";
        }
        if (bits >= 1_000_000d) {
            return TWO_DECIMAL.format(bits / 1_000_000d) + " Mbps";
        }
        if (bits >= 1_000d) {
            return ONE_DECIMAL.format(bits / 1_000d) + " Kbps";
        }
        return Math.round(bits) + " bps";
    }

    static String megabytesPerSecond(double bytesPerSecond) {
        double mb = Math.max(0, bytesPerSecond) / 1_000_000d;
        return TWO_DECIMAL.format(mb) + " MB/s";
    }

    static String megabitsPerSecond(double bytesPerSecond) {
        double mbps = Math.max(0, bytesPerSecond) * 8d / 1_000_000d;
        return TWO_DECIMAL.format(mbps) + " Mbps";
    }

    static String bytes(long bytes) {
        double value = Math.max(0, bytes);
        if (value >= 1_125_899_906_842_624d) {
            return TWO_DECIMAL.format(value / 1_125_899_906_842_624d) + " PB";
        }
        if (value >= 1_099_511_627_776d) {
            return TWO_DECIMAL.format(value / 1_099_511_627_776d) + " TB";
        }
        if (value >= 1_073_741_824d) {
            return TWO_DECIMAL.format(value / 1_073_741_824d) + " GB";
        }
        if (value >= 1_048_576d) {
            return TWO_DECIMAL.format(value / 1_048_576d) + " MB";
        }
        if (value >= 1024d) {
            return ONE_DECIMAL.format(value / 1024d) + " KB";
        }
        return Math.round(value) + " B";
    }

    static String latency(long millis) {
        if (millis <= 0) {
            return "-- ms";
        }
        return millis + " ms";
    }

    static String percent(int value) {
        return value + "%";
    }

    static String timeNow() {
        return TIME.format(new Date());
    }
}
