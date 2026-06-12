package cn.netart.networkpanel;

final class TrafficStatsState {
    final boolean running;
    final long sessionBytes;
    final long totalBytes;
    final long bytesPerSecond;
    final int activeWorkers;
    final int targetCount;
    final String activeTarget;
    final String message;

    TrafficStatsState(boolean running, long sessionBytes, long totalBytes, long bytesPerSecond,
            int activeWorkers, int targetCount, String activeTarget, String message) {
        this.running = running;
        this.sessionBytes = sessionBytes;
        this.totalBytes = totalBytes;
        this.bytesPerSecond = bytesPerSecond;
        this.activeWorkers = activeWorkers;
        this.targetCount = targetCount;
        this.activeTarget = activeTarget;
        this.message = message;
    }
}
