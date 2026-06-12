package cn.netart.networkpanel;

final class TrafficTarget {
    final String name;
    final String url;
    final int threads;
    final boolean enhanced;
    final boolean enabled;

    TrafficTarget(String name, String url, int threads, boolean enhanced, boolean enabled) {
        this.name = name;
        this.url = url;
        this.threads = threads;
        this.enhanced = enhanced;
        this.enabled = enabled;
    }

    String displayName() {
        if (name != null && !name.trim().isEmpty()) {
            return name.trim();
        }
        return url == null ? "未命名链接" : url;
    }
}
