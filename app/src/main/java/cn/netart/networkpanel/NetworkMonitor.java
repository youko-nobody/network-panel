package cn.netart.networkpanel;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Locale;

final class NetworkMonitor {
    private final Context appContext;
    private long lastRx = TrafficStats.getTotalRxBytes();
    private long lastTx = TrafficStats.getTotalTxBytes();
    private long lastTime = System.currentTimeMillis();

    NetworkMonitor(Context context) {
        appContext = context.getApplicationContext();
    }

    NetworkSnapshot sample() {
        long now = System.currentTimeMillis();
        long rx = safeBytes(TrafficStats.getTotalRxBytes());
        long tx = safeBytes(TrafficStats.getTotalTxBytes());
        long deltaMs = Math.max(1, now - lastTime);
        long downRate = Math.max(0, (rx - lastRx) * 1000L / deltaMs);
        long upRate = Math.max(0, (tx - lastTx) * 1000L / deltaMs);
        lastRx = rx;
        lastTx = tx;
        lastTime = now;

        ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network = cm == null ? null : cm.getActiveNetwork();
        NetworkCapabilities caps = network == null || cm == null ? null : cm.getNetworkCapabilities(network);
        LinkProperties props = network == null || cm == null ? null : cm.getLinkProperties(network);

        boolean connected = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        String type = typeName(caps);
        String name = networkName(caps);
        String ip = localIp(props);
        int signal = signalPercent(caps);

        return new NetworkSnapshot(
                connected,
                type,
                name,
                ip,
                signal,
                downRate,
                upRate,
                safeBytes(TrafficStats.getMobileRxBytes()),
                safeBytes(TrafficStats.getMobileTxBytes()),
                rx,
                tx,
                now
        );
    }

    private String typeName(NetworkCapabilities caps) {
        if (caps == null) {
            return "离线";
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return "Wi-Fi";
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return "蜂窝网络";
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return "以太网";
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return "VPN";
        }
        return "其他网络";
    }

    private String networkName(NetworkCapabilities caps) {
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            WifiManager wifi = (WifiManager) appContext.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                try {
                    WifiInfo info = wifi.getConnectionInfo();
                    if (info != null && info.getSSID() != null) {
                        String ssid = info.getSSID().replace("\"", "");
                        if (!ssid.isEmpty() && !"<unknown ssid>".equalsIgnoreCase(ssid)) {
                            return ssid;
                        }
                    }
                } catch (SecurityException ignored) {
                    return "Wi-Fi";
                }
            }
        }
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return "移动数据";
        }
        return caps == null ? "未连接" : typeName(caps);
    }

    private String localIp(LinkProperties props) {
        if (props == null) {
            return "--";
        }
        for (LinkAddress address : props.getLinkAddresses()) {
            InetAddress inet = address.getAddress();
            if (inet instanceof Inet4Address && !inet.isLoopbackAddress()) {
                return inet.getHostAddress();
            }
        }
        return "--";
    }

    private int signalPercent(NetworkCapabilities caps) {
        if (caps == null) {
            return 0;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int strength = caps.getSignalStrength();
            if (strength > Integer.MIN_VALUE) {
                return Math.max(0, Math.min(100, strength));
            }
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            WifiManager wifi = (WifiManager) appContext.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null && appContext.checkSelfPermission(Manifest.permission.ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED) {
                try {
                    WifiInfo info = wifi.getConnectionInfo();
                    if (info != null) {
                        return Math.max(0, Math.min(100, WifiManager.calculateSignalLevel(info.getRssi(), 101)));
                    }
                } catch (RuntimeException ignored) {
                    return 0;
                }
            }
        }
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? 100 : 60;
    }

    private long safeBytes(long value) {
        return value == TrafficStats.UNSUPPORTED ? 0 : Math.max(0, value);
    }
}
