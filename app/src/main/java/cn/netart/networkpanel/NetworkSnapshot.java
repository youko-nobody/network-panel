package cn.netart.networkpanel;

final class NetworkSnapshot {
    final boolean connected;
    final String type;
    final String networkName;
    final String localIp;
    final int signalPercent;
    final long downBytesPerSecond;
    final long upBytesPerSecond;
    final long mobileRxBytes;
    final long mobileTxBytes;
    final long totalRxBytes;
    final long totalTxBytes;
    final long timestamp;

    NetworkSnapshot(boolean connected, String type, String networkName, String localIp,
            int signalPercent, long downBytesPerSecond, long upBytesPerSecond,
            long mobileRxBytes, long mobileTxBytes, long totalRxBytes, long totalTxBytes,
            long timestamp) {
        this.connected = connected;
        this.type = type;
        this.networkName = networkName;
        this.localIp = localIp;
        this.signalPercent = signalPercent;
        this.downBytesPerSecond = downBytesPerSecond;
        this.upBytesPerSecond = upBytesPerSecond;
        this.mobileRxBytes = mobileRxBytes;
        this.mobileTxBytes = mobileTxBytes;
        this.totalRxBytes = totalRxBytes;
        this.totalTxBytes = totalTxBytes;
        this.timestamp = timestamp;
    }
}
