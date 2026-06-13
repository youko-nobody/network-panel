package cn.netart.networkpanel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Space;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import cn.netart.networkpanel.AppPrefs;
import cn.netart.networkpanel.Formatters;
import cn.netart.networkpanel.R;
import cn.netart.networkpanel.SpeedTestEngine;
import cn.netart.networkpanel.TrafficPrefs;
import cn.netart.networkpanel.TrafficRunnerService;
import cn.netart.networkpanel.TrafficStatsState;
import cn.netart.networkpanel.TrafficTarget;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity
extends Activity
implements SpeedTestEngine.Listener,
TrafficRunnerService.Listener {
    private static final int REQ_NOTIFICATIONS = 41;
    private int TEXT;
    private int MUTED;
    private int BLUE;
    private int CYAN;
    private int GREEN;
    private int RED;
    private int HINT;
    private int DIVIDER;
    private int MARK_MUTED;
    private int currentThemeId;
    private ThemePalette theme;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SpeedTestEngine speedTest = new SpeedTestEngine();
    private AppPrefs.Settings settings;
    private final List<TrafficTarget> targets = new ArrayList<TrafficTarget>();
    private ScrollView contentScroll;
    private TextView trafficTotalText;
    private TextView trafficRateMbText;
    private TextView trafficRateMbpsText;
    private TextView rateLimitText;
    private TextView trafficWorkersText;
    private TextView routeSummaryText;
    private TextView sessionText;
    private View regionLatencyPanel;
    private View domesticLatencyRow;
    private TextView domesticLatencyText;
    private TextView domesticLatencyRegionText;
    private View foreignLatencyRow;
    private TextView foreignLatencyText;
    private TextView foreignLatencyRegionText;
    private LinearLayout targetListContainer;
    private TextView latencyText;
    private TextView downloadResultText;
    private TextView uploadResultText;
    private TextView phaseText;
    private ProgressBar progressBar;
    private Button startTrafficButton;
    private Button resetTrafficButton;
    private Spinner targetSpinner;
    private EditText linkNameEdit;
    private EditText linkUrlEdit;
    private Switch enhancedSwitch;
    private Switch wakeSwitch;
    private Switch notificationSwitch;
    private boolean trafficRunning;
    private boolean restoringTargetSelection;
    private boolean latencyRequestInFlight;
    private boolean settingsPageVisible;
    private int activeTargetIndex;
    private int currentThreadValue = 4;
    private static final String IP_INFO_URL = "https://app.netart.cn/network-panel/ip.ajax";
    private static final String DOMESTIC_LATENCY_URL = "https://connectivitycheck.platform.hicloud.com/generate_204";
    private static final String CLOUDFLARE_TRACE_URL = "https://cp.cloudflare.com/cdn-cgi/trace";
    private static final String[] TRAFFIC_LIMIT_UNITS = new String[]{"MB", "GB", "TB"};
    private static final int[] TRAFFIC_LIMIT_UNIT_FACTORS_MB = new int[]{1, 1024, 0x100000};
    private final Runnable latencyRefreshRunnable = new Runnable(){

        @Override
        public void run() {
            MainActivity.this.refreshRegionLatency();
            MainActivity.this.handler.postDelayed((Runnable)this, 30000L);
        }
    };
    private static final int MATCH = -1;
    private static final int WRAP = -2;

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.resolvePalette();
        this.settings = AppPrefs.read((Context)this);
        this.targets.clear();
        this.targets.addAll(TrafficPrefs.readTargets((Context)this));
        this.configureWindow();
        this.registerSystemBackHandler();
        this.setContentView(this.buildContent());
        this.applySavedState();
        this.maybeRequestNotificationPermission();
        this.appendLog("\u6d41\u91cf\u4efb\u52a1\u9762\u677f\u5df2\u542f\u52a8");
    }

    protected void onResume() {
        super.onResume();
        TrafficRunnerService.addListener(this);
        this.refreshRateLimitLabel();
        this.handler.post(this.latencyRefreshRunnable);
    }

    protected void onPause() {
        TrafficRunnerService.removeListener(this);
        this.handler.removeCallbacks(this.latencyRefreshRunnable);
        super.onPause();
    }

    protected void onDestroy() {
        if (this.speedTest.isRunning()) {
            this.speedTest.cancel();
        }
        super.onDestroy();
    }

    public void onBackPressed() {
        if (this.handlePageBack()) {
            return;
        }
        super.onBackPressed();
    }

    private void registerSystemBackHandler() {
        if (Build.VERSION.SDK_INT >= 33) {
            this.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, () -> {
                if (!this.handlePageBack()) {
                    this.finish();
                }
            });
        }
    }

    private boolean handlePageBack() {
        if (!this.settingsPageVisible) {
            return false;
        }
        this.returnHome();
        return true;
    }

    @Override
    public void onTrafficUpdate(TrafficStatsState state) {
        this.updateMetric(this.trafficTotalText, Formatters.bytes(TrafficPrefs.readTotalBytes((Context)this)));
        this.updateMetric(this.sessionText, Formatters.bytes(state.sessionBytes));
        this.updateMetric(this.trafficRateMbText, Formatters.megabytesPerSecond(state.bytesPerSecond));
        this.updateMetric(this.trafficRateMbpsText, Formatters.megabitsPerSecond(state.bytesPerSecond));
        this.updateMetric(this.trafficWorkersText, state.activeWorkers + " \u7ebf\u7a0b");
        this.trafficRunning = state.running;
        this.updateStartButtonState(state.running);
    }

    @Override
    public void onProgress(SpeedTestEngine.TestPhase phase, long bytes, double bytesPerSecond, int percent) {
        this.progressBar.setProgress(percent);
        this.phaseText.setText((CharSequence)this.phaseName(phase));
        if (phase == SpeedTestEngine.TestPhase.DOWNLOAD) {
            this.downloadResultText.setText((CharSequence)Formatters.bitrate(bytesPerSecond));
        } else if (phase == SpeedTestEngine.TestPhase.UPLOAD) {
            this.uploadResultText.setText((CharSequence)Formatters.bitrate(bytesPerSecond));
        }
    }

    @Override
    public void onLog(String message) {
        this.appendLog(message);
    }

    @Override
    public void onComplete(SpeedTestEngine.TestResult result) {
        this.latencyText.setText((CharSequence)Formatters.latency(result.latencyMs));
        this.downloadResultText.setText((CharSequence)Formatters.bitrate(result.downloadBytesPerSecond));
        this.uploadResultText.setText((CharSequence)Formatters.bitrate(result.uploadBytesPerSecond));
        this.phaseText.setText((CharSequence)"\u6d4b\u901f\u5b8c\u6210");
        this.progressBar.setProgress(100);
        this.appendLog("\u6d4b\u901f\u5b8c\u6210\uff1a\u4e0b\u884c " + Formatters.bitrate(result.downloadBytesPerSecond) + " / \u4e0a\u884c " + Formatters.bitrate(result.uploadBytesPerSecond));
    }

    @Override
    public void onError(String message) {
        this.appendLog(message);
        Toast.makeText((Context)this, (CharSequence)message, (int)1).show();
    }

    private View buildContent() {
        ScrollView scrollView;
        LinearLayout screen = new LinearLayout((Context)this);
        screen.setOrientation(1);
        screen.setClipChildren(false);
        screen.setClipToPadding(false);
        screen.setBackground(this.screenBackground());
        screen.addView(this.buildHeader(), (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-1, -2));
        this.contentScroll = scrollView = new ScrollView((Context)this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        screen.addView((View)scrollView, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-1, 0, 1.0f));
        LinearLayout root = new LinearLayout((Context)this);
        root.setOrientation(1);
        root.setGravity(1);
        root.setClipToPadding(false);
        root.setClipChildren(false);
        root.setPadding(this.dp(16), this.dp(12), this.dp(16), this.dp(20));
        scrollView.addView((View)root, (ViewGroup.LayoutParams)new FrameLayout.LayoutParams(-1, -2));
        root.addView(this.buildTrafficCard(), (ViewGroup.LayoutParams)this.centeredTopMargin(0));
        return screen;
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout((Context)this);
        header.setOrientation(0);
        header.setGravity(16);
        header.setClipToPadding(false);
        header.setPadding(this.dp(12), this.dp(10) + this.statusBarHeight(), this.dp(8), this.dp(8));
        header.setBackgroundColor(0);
        LinearLayout brand = new LinearLayout((Context)this);
        brand.setGravity(16);
        header.addView((View)brand, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(0, -2, 1.0f));
        TextView title = this.text("\u7f51\u7edc\u9762\u677f", 18, this.TEXT, 1);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-2, -2);
        titleParams.leftMargin = this.dp(2);
        brand.addView((View)title, (ViewGroup.LayoutParams)titleParams);
        Button themeButton = this.headerButton(this.themeChoiceName(this.currentThemeId));
        themeButton.setContentDescription((CharSequence)"\u5207\u6362\u4e3b\u9898");
        themeButton.setOnClickListener(v -> this.cycleTheme());
        themeButton.setOnLongClickListener(v -> {
            this.showThemeChooser();
            return true;
        });
        LinearLayout.LayoutParams themeParams = new LinearLayout.LayoutParams(this.dp(104), this.dp(38));
        themeParams.rightMargin = this.dp(4);
        header.addView((View)themeButton, (ViewGroup.LayoutParams)themeParams);
        Button app = this.headerButton("\u8bbe\u7f6e");
        app.setOnClickListener(v -> this.showSettingsPage());
        header.addView((View)app, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(this.dp(38), this.dp(38)));
        return header;
    }

    private View buildTrafficCard() {
        LinearLayout panel = this.vertical();
        panel.addView(this.buildCommandCenter(), (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-1, this.dp(322)));
        panel.addView(this.buildControlDeck(), (ViewGroup.LayoutParams)this.topMargin(12));
        panel.addView(this.buildRegionLatencyPanel(), (ViewGroup.LayoutParams)this.topMargin(14));
        this.startTrafficButton.setOnClickListener(v -> this.toggleTraffic());
        this.updateMetric(this.trafficTotalText, Formatters.bytes(TrafficPrefs.readTotalBytes((Context)this)));
        this.updateMetric(this.sessionText, "--");
        this.updateMetric(this.trafficRateMbText, "-- MB/s");
        this.updateMetric(this.trafficRateMbpsText, "-- Mbps");
        this.updateMetric(this.trafficWorkersText, this.currentThreads() + " \u7ebf\u7a0b");
        this.updateMetric(this.routeSummaryText, this.currentRouteName());
        this.updateStartButtonState(false);
        return panel;
    }

    private View buildCommandCenter() {
        FrameLayout stage = new FrameLayout((Context)this);
        stage.setBackground(this.cockpitBackground());
        stage.setPadding(0, 0, 0, 0);
        NetworkPulseView pulse = new NetworkPulseView((Context)this);
        stage.addView((View)pulse, (ViewGroup.LayoutParams)new FrameLayout.LayoutParams(-1, -1));
        this.lift(stage, 4);
        LinearLayout center = new LinearLayout((Context)this);
        center.setOrientation(1);
        center.setPadding(this.dp(18), this.dp(16), this.dp(18), this.dp(16));
        stage.addView((View)center, (ViewGroup.LayoutParams)new FrameLayout.LayoutParams(-1, -1));
        LinearLayout top = new LinearLayout((Context)this);
        top.setOrientation(0);
        top.setGravity(16);
        center.addView((View)top, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-1, -2));
        LinearLayout titles = this.vertical();
        TextView eyebrow = this.text("\u7ebf\u4e0a\u4f53\u611f", 12, this.theme.onPrimary, 1);
        eyebrow.setPadding(this.dp(10), this.dp(4), this.dp(10), this.dp(4));
        eyebrow.setBackground(this.rounded(this.theme.secondary, 999, 0, 0));
        TextView hint = this.text("\u9891\u6b21\u3001\u5e26\u5bbd\u3001\u8054\u901a\u5ea6", 11, this.theme.muted, 1);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-2, -2);
        hintParams.topMargin = this.dp(8);
        titles.addView((View)eyebrow, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-2, -2));
        titles.addView((View)hint, (ViewGroup.LayoutParams)hintParams);
        top.addView((View)titles, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(0, -2, 1.0f));
        this.rateLimitText = this.text("", 11, this.theme.onPrimary, 1);
        this.rateLimitText.setGravity(17);
        this.rateLimitText.setSingleLine(true);
        this.rateLimitText.setPadding(this.dp(12), this.dp(6), this.dp(12), this.dp(6));
        this.rateLimitText.setBackground(this.rounded(this.theme.primary, 999, 0, 0));
        this.rateLimitText.setOnClickListener(v -> this.showRateLimitDialog());
        top.addView((View)this.rateLimitText, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-2, -2));
        this.refreshRateLimitLabel();
        LinearLayout speedRow = new LinearLayout((Context)this);
        speedRow.setOrientation(0);
        speedRow.setGravity(16);
        LinearLayout.LayoutParams speedRowParams = new LinearLayout.LayoutParams(-1, 0, 1.0f);
        speedRowParams.topMargin = this.dp(8);
        center.addView((View)speedRow, (ViewGroup.LayoutParams)speedRowParams);
        this.trafficRateMbText = this.text("--", 42, this.theme.text, 1);
        this.trafficRateMbText.setGravity(17);
        this.trafficRateMbText.setSingleLine(true);
        this.trafficRateMbText.setEllipsize(TextUtils.TruncateAt.END);
        this.enableAutoSize(this.trafficRateMbText, 22, 42);
        speedRow.addView((View)this.trafficRateMbText, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(0, -2, 1.35f));
        View speedDivider = new View((Context)this);
        speedDivider.setBackgroundColor(this.theme.divider);
        LinearLayout.LayoutParams speedDividerParams = new LinearLayout.LayoutParams(this.dp(1), this.dp(34));
        speedDividerParams.leftMargin = this.dp(10);
        speedDividerParams.rightMargin = this.dp(10);
        speedDividerParams.gravity = 16;
        speedRow.addView(speedDivider, (ViewGroup.LayoutParams)speedDividerParams);
        this.trafficRateMbpsText = this.text("MB/s", 13, this.theme.muted, 1);
        this.trafficRateMbpsText.setGravity(17);
        this.trafficRateMbpsText.setSingleLine(true);
        this.trafficRateMbpsText.setEllipsize(TextUtils.TruncateAt.END);
        this.enableAutoSize(this.trafficRateMbpsText, 11, 13);
        speedRow.addView((View)this.trafficRateMbpsText, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(0, -2, 0.5f));
        LinearLayout strip = new LinearLayout((Context)this);
        strip.setOrientation(0);
        strip.setGravity(16);
        strip.setBackground(this.rounded(this.theme.surface, 18, 1, this.theme.line));
        strip.setPadding(this.dp(12), this.dp(10), this.dp(12), this.dp(10));
        LinearLayout.LayoutParams stripParams = new LinearLayout.LayoutParams(-1, this.dp(66));
        stripParams.topMargin = this.dp(10);
        center.addView((View)strip, (ViewGroup.LayoutParams)stripParams);
        this.trafficTotalText = this.commandMetric(strip, "\u603b\u6d88\u8017", Formatters.bytes(TrafficPrefs.readTotalBytes((Context)this)), null);
        View divider = new View((Context)this);
        divider.setBackgroundColor(this.theme.divider);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(this.dp(1), -1);
        dividerParams.setMargins(this.dp(10), 0, this.dp(10), 0);
        strip.addView(divider, (ViewGroup.LayoutParams)dividerParams);
        this.sessionText = this.commandMetric(strip, "\u672c\u6b21", "--", v -> this.showLimitDialog());
        return stage;
    }

    private TextView commandMetric(LinearLayout row, String label, String value, View.OnClickListener click) {
        LinearLayout item = this.vertical();
        item.setGravity(17);
        item.setClickable(click != null);
        item.setPadding(this.dp(2), 0, this.dp(2), 0);
        if (click != null) {
            item.setOnClickListener(click);
        }
        TextView labelView = this.text(label, 10, this.MUTED, 1);
        labelView.setGravity(17);
        item.addView((View)labelView, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-1, -2));
        TextView valueView = this.text(value, 16, this.theme.text, 1);
        valueView.setGravity(17);
        valueView.setSingleLine(true);
        valueView.setEllipsize(TextUtils.TruncateAt.END);
        this.enableAutoSize(valueView, 12, 16);
        item.addView((View)valueView, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-1, -2));
        row.addView((View)item, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(0, -1, 1.0f));
        return valueView;
    }

    private View buildControlDeck() {
        LinearLayout deck = this.vertical();
        deck.setBackground(this.sectionBackground());
        deck.setPadding(this.dp(14), this.dp(14), this.dp(14), this.dp(14));
        LinearLayout head = new LinearLayout((Context)this);
        head.setOrientation(0);
        head.setGravity(16);
        TextView title = this.text("\u63a7\u5236\u53f0", 15, this.theme.text, 1);
        head.addView((View)title, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(0, -2, 1.0f));
        TextView tag = this.text("\u4e00\u952e\u8fd0\u884c", 11, this.theme.onPrimary, 1);
        tag.setPadding(this.dp(10), this.dp(4), this.dp(10), this.dp(4));
        tag.setBackground(this.rounded(this.theme.secondary, 999, 0, 0));
        head.addView((View)tag, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-2, -2));
        deck.addView((View)head, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-1, -2));
        LinearLayout cells = new LinearLayout((Context)this);
        cells.setOrientation(0);
        cells.setGravity(16);
        LinearLayout.LayoutParams cellsParams = new LinearLayout.LayoutParams(-1, this.dp(86));
        cellsParams.topMargin = this.dp(10);
        deck.addView((View)cells, (ViewGroup.LayoutParams)cellsParams);
        this.routeSummaryText = this.controlCell(cells, "\u7ebf\u8def", this.currentRouteName(), "\u9009\u62e9", v -> this.showTargetChooser());
        cells.addView((View)new Space((Context)this), (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(this.dp(10), 1));
        this.trafficWorkersText = this.controlCell(cells, "\u7ebf\u7a0b", this.currentThreads() + " \u7ebf\u7a0b", "\u8bbe\u7f6e", v -> this.showThreadDialog());
        this.startTrafficButton = this.runButton("\u5f00\u59cb");
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(-1, this.dp(64));
        startParams.topMargin = this.dp(14);
        deck.addView((View)this.startTrafficButton, (ViewGroup.LayoutParams)startParams);
        return deck;
    }

    private TextView controlCell(LinearLayout parent, String label, String value, String actionText, View.OnClickListener click) {
        LinearLayout cell = this.vertical();
        cell.setGravity(16);
        cell.setBackground(this.rounded(this.theme.surface, 18, 1, this.theme.line));
        cell.setPadding(this.dp(14), this.dp(12), this.dp(14), this.dp(12));
        cell.setClickable(true);
        cell.setOnClickListener(click);
        LinearLayout top = new LinearLayout((Context)this);
        top.setOrientation(0);
        top.setGravity(16);
        TextView labelView = this.text(label, 11, this.MUTED, 1);
        top.addView((View)labelView, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(0, -2, 1.0f));
        TextView action = this.text(actionText, 11, this.theme.onPrimary, 1);
        action.setGravity(17);
        action.setPadding(this.dp(8), this.dp(2), this.dp(8), this.dp(2));
        action.setBackground(this.rounded(this.theme.primary, 999, 0, 0));
        action.setOnClickListener(click);
        top.addView((View)action);
        cell.addView((View)top, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-1, -2));
        TextView valueView = this.text(value, 17, this.theme.text, 1);
        valueView.setGravity(17);
        valueView.setSingleLine(true);
        valueView.setEllipsize(TextUtils.TruncateAt.END);
        this.enableAutoSize(valueView, 12, 17);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(-1, 0, 1.0f);
        valueParams.topMargin = this.dp(4);
        cell.addView((View)valueView, (ViewGroup.LayoutParams)valueParams);
        parent.addView((View)cell, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(0, -1, 1.0f));
        return valueView;
    }

    private View buildSettingsPanel() {
        LinearLayout panel = this.vertical();
        panel.setBackgroundColor(0);
        panel.setPadding(this.dp(0), this.dp(0), this.dp(0), this.dp(0));
        panel.addView((View)this.sectionHeader("\u8fd0\u884c\u5f00\u5173"), (ViewGroup.LayoutParams)this.topMargin(0));
        this.enhancedSwitch = this.switchView("\u589e\u5f3a\u5e76\u53d1");
        panel.addView((View)this.enhancedSwitch, (ViewGroup.LayoutParams)this.topMargin(4));
        this.wakeSwitch = this.switchView("\u9501\u5c4f\u8fd0\u884c");
        panel.addView((View)this.wakeSwitch, (ViewGroup.LayoutParams)this.topMargin(8));
        this.notificationSwitch = this.switchView("\u901a\u77e5\u680f\u64cd\u4f5c\u6309\u94ae");
        panel.addView((View)this.notificationSwitch, (ViewGroup.LayoutParams)this.topMargin(8));
        this.resetTrafficButton = this.actionButton("\u6e05\u96f6\u7d2f\u8ba1\u6d41\u91cf", false);
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(-1, this.dp(42));
        resetParams.setMargins(0, this.dp(14), 0, 0);
        panel.addView((View)this.resetTrafficButton, (ViewGroup.LayoutParams)resetParams);
        this.resetTrafficButton.setOnClickListener(v -> this.service("cn.netart.networkpanel.TRAFFIC_RESET"));
        panel.addView((View)this.sectionHeader("\u7ebf\u8def\u7ba1\u7406"), (ViewGroup.LayoutParams)this.topMargin(14));
        panel.addView(this.buildLinkPanel());
        panel.addView((View)this.sectionHeader("\u6d4b\u901f"), (ViewGroup.LayoutParams)this.topMargin(14));
        panel.addView(this.buildSpeedPanel());
        this.enhancedSwitch.setChecked(TrafficPrefs.readEnhanced((Context)this));
        this.wakeSwitch.setChecked(TrafficPrefs.readKeepAwake((Context)this));
        this.notificationSwitch.setChecked(this.settings.notificationEnabled);
        this.refreshTargetList();
        this.enhancedSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> TrafficPrefs.writeEnhanced((Context)this, isChecked));
        this.wakeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            TrafficPrefs.writeKeepAwake((Context)this, isChecked);
            this.applyWakeFlag(isChecked);
        });
        this.notificationSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            this.settings = this.settings.withNotificationEnabled(isChecked);
            AppPrefs.write((Context)this, this.settings);
            if (isChecked) {
                this.maybeRequestNotificationPermission();
            }
        });
        return panel;
    }

    private void showSettingsPage() {
        this.settingsPageVisible = true;
        this.setContentView(this.buildSettingsContent());
        this.applyWakeFlag(this.isKeepAwakeEnabled());
    }

    private void returnHome() {
        this.settingsPageVisible = false;
        this.setContentView(this.buildContent());
        this.applySavedState();
        this.refreshRateLimitLabel();
    }

    private View buildSettingsContent() {
        LinearLayout screen = new LinearLayout((Context)this);
        screen.setOrientation(1);
        screen.setClipChildren(false);
        screen.setClipToPadding(false);
        screen.setBackground(this.screenBackground());
        LinearLayout header = new LinearLayout((Context)this);
        header.setOrientation(0);
        header.setGravity(16);
        header.setPadding(this.dp(16), this.dp(12) + this.statusBarHeight(), this.dp(14), this.dp(8));
        screen.addView((View)header, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-1, -2));
        Button back = this.headerButton("\u8fd4\u56de");
        back.setOnClickListener(v -> this.returnHome());
        header.addView((View)back, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(this.dp(72), this.dp(34)));
        TextView title = this.text("\u8bbe\u7f6e", 20, this.TEXT, 1);
        title.setGravity(17);
        header.addView((View)title, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(0, -2, 1.0f));
        TextView spacer = this.text("", 1, this.TEXT, 0);
        header.addView((View)spacer, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(this.dp(72), this.dp(34)));
        ScrollView scroll = new ScrollView((Context)this);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        screen.addView((View)scroll, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-1, 0, 1.0f));
        LinearLayout root = this.vertical();
        root.setPadding(this.dp(16), this.dp(4), this.dp(16), this.dp(20));
        scroll.addView((View)root, (ViewGroup.LayoutParams)new FrameLayout.LayoutParams(-1, -2));
        root.addView(this.buildSettingsPanel(), (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-1, -2));
        return screen;
    }

    private Drawable cockpitBackground() {
        return this.gradient(GradientDrawable.Orientation.TL_BR, this.theme.heroStart, this.theme.heroEnd, 26, this.theme.line);
    }

    private final class NetworkPulseView extends View {
        private final Paint gridPaint = new Paint(1);
        private final Paint ringPaint = new Paint(1);
        private final Paint wavePaint = new Paint(1);
        private final Paint pulsePaint = new Paint(1);
        private final Paint barPaint = new Paint(1);
        private final RectF ringRect = new RectF();

        NetworkPulseView(Context context) {
            super(context);
            this.gridPaint.setStyle(Paint.Style.STROKE);
            this.ringPaint.setStyle(Paint.Style.STROKE);
            this.wavePaint.setStyle(Paint.Style.STROKE);
            this.wavePaint.setStrokeCap(Paint.Cap.ROUND);
            this.pulsePaint.setStyle(Paint.Style.FILL);
            this.barPaint.setStyle(Paint.Style.FILL);
        }

        protected void onDraw(Canvas canvas) {
            int width = this.getWidth();
            int height = this.getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }
            float phase = (float)(SystemClock.uptimeMillis() % 2600L) / 2600.0f;
            float density = MainActivity.this.getResources().getDisplayMetrics().density;
            canvas.drawColor(Color.TRANSPARENT);
            this.gridPaint.setStrokeWidth(density);
            this.gridPaint.setColor(Color.argb(18, Color.red(MainActivity.this.theme.secondary), Color.green(MainActivity.this.theme.secondary), Color.blue(MainActivity.this.theme.secondary)));
            int stepX = MainActivity.this.dp(28);
            int stepY = MainActivity.this.dp(24);
            for (int x = 0; x <= width + stepX; x += stepX) {
                canvas.drawLine((float)x, 0.0f, (float)x, (float)height, this.gridPaint);
            }
            for (int y = 0; y <= height + stepY; y += stepY) {
                canvas.drawLine(0.0f, (float)y, (float)width, (float)y, this.gridPaint);
            }
            this.ringPaint.setStrokeWidth(MainActivity.this.dp(2.5f));
            this.ringPaint.setColor(Color.argb(42, Color.red(MainActivity.this.theme.primary), Color.green(MainActivity.this.theme.primary), Color.blue(MainActivity.this.theme.primary)));
            float ring = Math.min(width, height) * 0.72f;
            this.ringRect.set(width - ring * 0.94f, -ring * 0.16f, width + ring * 0.18f, ring * 0.95f);
            canvas.drawArc(this.ringRect, 192.0f, 116.0f, false, this.ringPaint);
            this.ringPaint.setColor(Color.argb(28, Color.red(MainActivity.this.theme.secondary), Color.green(MainActivity.this.theme.secondary), Color.blue(MainActivity.this.theme.secondary)));
            this.ringRect.set(-ring * 0.34f, height - ring * 0.62f, ring * 0.52f, height + ring * 0.15f);
            canvas.drawArc(this.ringRect, 18.0f, 146.0f, false, this.ringPaint);
            this.wavePaint.setStrokeWidth(MainActivity.this.dp(3.2f));
            this.wavePaint.setColor(Color.argb(92, Color.red(MainActivity.this.theme.activeEnd), Color.green(MainActivity.this.theme.activeEnd), Color.blue(MainActivity.this.theme.activeEnd)));
            float baseY = height * 0.78f;
            float amp = MainActivity.this.dp(14);
            float startX = MainActivity.this.dp(18);
            float usable = width - MainActivity.this.dp(36);
            float x1 = startX;
            float x2 = startX + usable * 0.18f;
            float x3 = startX + usable * 0.36f;
            float x4 = startX + usable * 0.55f;
            float x5 = startX + usable * 0.73f;
            float x6 = startX + usable * 0.92f;
            float y1 = baseY + (float)Math.sin((phase + 0.0f) * 6.2831855f) * amp * 0.2f;
            float y2 = baseY - amp * 0.85f + (float)Math.sin((phase + 0.12f) * 6.2831855f) * amp * 0.55f;
            float y3 = baseY + amp * 0.3f + (float)Math.sin((phase + 0.26f) * 6.2831855f) * amp * 0.65f;
            float y4 = baseY - amp * 1.05f + (float)Math.sin((phase + 0.42f) * 6.2831855f) * amp * 0.55f;
            float y5 = baseY + amp * 0.08f + (float)Math.sin((phase + 0.64f) * 6.2831855f) * amp * 0.6f;
            float y6 = baseY - amp * 0.4f + (float)Math.sin((phase + 0.82f) * 6.2831855f) * amp * 0.38f;
            canvas.drawLine(x1, y1, x2, y2, this.wavePaint);
            canvas.drawLine(x2, y2, x3, y3, this.wavePaint);
            canvas.drawLine(x3, y3, x4, y4, this.wavePaint);
            canvas.drawLine(x4, y4, x5, y5, this.wavePaint);
            canvas.drawLine(x5, y5, x6, y6, this.wavePaint);
            this.barPaint.setColor(Color.argb(34, Color.red(MainActivity.this.theme.primary), Color.green(MainActivity.this.theme.primary), Color.blue(MainActivity.this.theme.primary)));
            int bars = 5;
            float barW = MainActivity.this.dp(10);
            float gap = MainActivity.this.dp(8);
            float totalBars = bars * barW + (bars - 1) * gap;
            float left = (width - totalBars) * 0.5f;
            float bottom = height * 0.58f;
            for (int i = 0; i < bars; ++i) {
                float factor = 0.35f + 0.65f * (float)Math.sin((phase + (float)i * 0.13f) * 6.2831855f);
                float barH = MainActivity.this.dp(18) + factor * MainActivity.this.dp(34);
                float x = left + (barW + gap) * (float)i;
                canvas.drawRoundRect(new RectF(x, bottom - barH, x + barW, bottom), MainActivity.this.dp(10), MainActivity.this.dp(10), this.barPaint);
            }
            this.pulsePaint.setColor(Color.argb(72, Color.red(MainActivity.this.theme.success), Color.green(MainActivity.this.theme.success), Color.blue(MainActivity.this.theme.success)));
            float px = width * (0.12f + 0.78f * phase);
            float py = height * 0.2f + (float)Math.sin((phase + 0.35f) * 6.2831855f) * MainActivity.this.dp(18);
            canvas.drawCircle(px, py, MainActivity.this.dp(3.4f), this.pulsePaint);
            canvas.drawCircle(width - px * 0.38f, height * 0.18f, MainActivity.this.dp(2.4f), this.pulsePaint);
            if (Build.VERSION.SDK_INT >= 16) {
                this.postInvalidateOnAnimation();
            } else {
                this.postInvalidateDelayed(16L);
            }
        }
    }

    private void showThemeChooser() {
        AlertDialog dialog;
        ScrollView scroll = new ScrollView((Context)this);
        scroll.setFillViewport(false);
        scroll.setBackground(this.panelBackground());
        LinearLayout panel = this.vertical();
        panel.setPadding(this.dp(16), this.dp(10), this.dp(16), this.dp(16));
        panel.setBackground(this.panelBackground());
        scroll.addView((View)panel, (ViewGroup.LayoutParams)new FrameLayout.LayoutParams(-1, -2));
        AlertDialog[] dialogRef = new AlertDialog[1];
        for (int i = 0; i < AppPrefs.THEME_COUNT; ++i) {
            int themeId = i;
            panel.addView(this.choiceRow(this.themeChoiceName(i), this.themeChoiceDesc(i), i == this.currentThemeId, v -> {
                if (dialogRef[0] != null) {
                    dialogRef[0].dismiss();
                }
                if (themeId != this.currentThemeId) {
                    AppPrefs.writeTheme((Context)this, themeId);
                    this.recreate();
                }
            }), (ViewGroup.LayoutParams)this.topMargin(i == 0 ? 0 : 8));
        }
        dialogRef[0] = dialog = new AlertDialog.Builder((Context)this).setView((View)scroll).show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable((Drawable)new ColorDrawable(0));
        }
    }

    private void showTargetChooser() {
        AlertDialog dialog;
        if (this.targets.isEmpty()) {
            Toast.makeText((Context)this, (CharSequence)"\u6682\u65e0\u7ebf\u8def", (int)0).show();
            return;
        }
        ScrollView scroll = new ScrollView((Context)this);
        scroll.setFillViewport(false);
        scroll.setBackground(this.panelBackground());
        LinearLayout panel = this.vertical();
        panel.setPadding(this.dp(16), this.dp(10), this.dp(16), this.dp(16));
        scroll.addView((View)panel, (ViewGroup.LayoutParams)new FrameLayout.LayoutParams(-1, -2));
        AlertDialog[] dialogRef = new AlertDialog[1];
        for (int i = 0; i < this.targets.size(); ++i) {
            TrafficTarget target = this.targets.get(i);
            int index = i;
            String detail = target.threads + " \u7ebf\u7a0b";
            if (target.enhanced) {
                detail = detail + " \u00b7 \u589e\u5f3a\u5e76\u53d1";
            }
            if (!target.enabled) {
                detail = detail + " \u00b7 \u505c\u7528";
            }
            panel.addView(this.choiceRow(target.displayName(), detail, i == this.activeTargetIndex, v -> {
                if (dialogRef[0] != null) {
                    dialogRef[0].dismiss();
                }
                if (index != this.activeTargetIndex) {
                    this.selectTarget(index);
                }
            }), (ViewGroup.LayoutParams)this.topMargin(i == 0 ? 0 : 8));
        }
        dialogRef[0] = dialog = new AlertDialog.Builder((Context)this).setView((View)scroll).show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable((Drawable)new ColorDrawable(0));
        }
    }

    private View choiceRow(String title, String detail, boolean selected, View.OnClickListener click) {
        LinearLayout row = new LinearLayout((Context)this);
        row.setOrientation(0);
        row.setGravity(16);
        row.setBackground(selected ? this.selectedChoiceBackground() : this.showItemBackground());
        row.setPadding(this.dp(14), this.dp(11), this.dp(12), this.dp(11));
        row.setOnClickListener(click);
        LinearLayout texts = this.vertical();
        TextView titleView = this.text(title, 15, this.TEXT, 1);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView((View)titleView, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-1, -2));
        TextView detailView = this.text(detail, 12, selected ? this.theme.secondary : this.MUTED, 1);
        detailView.setSingleLine(true);
        detailView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
        detailParams.topMargin = this.dp(2);
        texts.addView((View)detailView, (ViewGroup.LayoutParams)detailParams);
        row.addView((View)texts, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(0, -2, 1.0f));
        TextView mark = this.text(selected ? "\u5f53\u524d" : "", 12, selected ? this.theme.primary : this.MUTED, 1);
        mark.setGravity(17);
        mark.setPadding(this.dp(10), this.dp(3), this.dp(10), this.dp(3));
        if (selected) {
            mark.setBackground(this.chipBackground());
        }
        row.addView((View)mark, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private void showActionDialog(String titleText, View content, Runnable saveAction) {
        LinearLayout root = this.vertical();
        root.setPadding(this.dp(20), this.dp(18), this.dp(20), this.dp(18));
        root.setBackground(this.panelBackground());
        TextView title = this.text(titleText, 20, this.TEXT, 1);
        title.setPadding(this.dp(2), this.dp(0), this.dp(2), this.dp(10));
        root.addView((View)title, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-1, -2));
        root.addView(content, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-1, -2));
        LinearLayout buttons = new LinearLayout((Context)this);
        buttons.setOrientation(0);
        LinearLayout.LayoutParams buttonRowParams = new LinearLayout.LayoutParams(-1, -2);
        buttonRowParams.topMargin = this.dp(18);
        root.addView((View)buttons, (ViewGroup.LayoutParams)buttonRowParams);
        TextView cancel = this.dialogActionButton("\u53d6\u6d88", false);
        buttons.addView((View)cancel, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(0, this.dp(42), 1.0f));
        buttons.addView((View)new Space((Context)this), (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(this.dp(10), 1));
        TextView save = this.dialogActionButton("\u4fdd\u5b58", true);
        buttons.addView((View)save, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(0, this.dp(42), 1.0f));
        AlertDialog dialog = new AlertDialog.Builder((Context)this).setView((View)root).show();
        cancel.setOnClickListener(v -> dialog.dismiss());
        save.setOnClickListener(v -> {
            saveAction.run();
            dialog.dismiss();
        });
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable((Drawable)new ColorDrawable(0));
        }
    }

    private View buildLinkPanel() {
        LinearLayout panel = this.vertical();
        panel.setBackground(this.sectionBackground());
        panel.setPadding(this.dp(14), this.dp(12), this.dp(14), this.dp(12));
        this.targetListContainer = this.vertical();
        panel.addView((View)this.targetListContainer, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-1, -2));
        this.linkNameEdit = this.input("\u540d\u79f0\uff0c\u4f8b\u5982\uff1a\u54aa\u5495\u89c6\u9891 / Cloudflare");
        this.linkUrlEdit = this.input("\u4efb\u610f HTTP/HTTPS \u4e0b\u8f7d\u94fe\u63a5");
        panel.addView((View)this.label("\u540d\u79f0"), (ViewGroup.LayoutParams)this.topMargin(10));
        panel.addView((View)this.linkNameEdit, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-1, this.dp(52)));
        panel.addView((View)this.label("\u94fe\u63a5"), (ViewGroup.LayoutParams)this.topMargin(10));
        panel.addView((View)this.linkUrlEdit, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-1, this.dp(52)));
        LinearLayout buttons = new LinearLayout((Context)this);
        buttons.setOrientation(0);
        panel.addView((View)buttons, (ViewGroup.LayoutParams)this.topMargin(12));
        Button add = this.actionButton("\u6dfb\u52a0\u94fe\u63a5", true);
        buttons.addView((View)add, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(0, this.dp(44), 1.0f));
        buttons.addView((View)new Space((Context)this), (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(this.dp(10), 1));
        Button clear = this.actionButton("\u6e05\u7a7a\u94fe\u63a5", false);
        buttons.addView((View)clear, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(0, this.dp(44), 1.0f));
        add.setOnClickListener(v -> this.addTarget());
        clear.setOnClickListener(v -> {
            this.targets.clear();
            TrafficPrefs.writeTargets((Context)this, this.targets);
            this.refreshTargetList();
            this.appendLog("\u94fe\u63a5\u5217\u8868\u5df2\u6e05\u7a7a");
        });
        return panel;
    }

    private View buildSpeedPanel() {
        LinearLayout panel = this.vertical();
        panel.setBackground(this.sectionBackground());
        panel.setPadding(this.dp(14), this.dp(12), this.dp(14), this.dp(12));
        GridLayout resultGrid = this.grid(1);
        panel.addView((View)resultGrid, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-1, -2));
        this.latencyText = this.addShowItem(resultGrid, "\u5ef6\u8fdf", "-- ms", "ms", false);
        this.downloadResultText = this.addShowItem(resultGrid, "\u4e0b\u8f7d\u901f\u5ea6", "--", "D", false);
        this.uploadResultText = this.addShowItem(resultGrid, "\u53c2\u8003\u4e0a\u4f20", "--", "U", false);
        this.phaseText = this.text("\u5f85\u5f00\u59cb", 14, this.TEXT, 1);
        this.phaseText.setGravity(17);
        panel.addView((View)this.phaseText, (ViewGroup.LayoutParams)this.topMargin(12));
        this.progressBar = new ProgressBar((Context)this, null, 16842872);
        this.progressBar.setMax(100);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, this.dp(10));
        progressParams.setMargins(this.dp(20), this.dp(8), this.dp(20), 0);
        panel.addView((View)this.progressBar, (ViewGroup.LayoutParams)progressParams);
        Button speed = this.actionButton("\u6d4b\u8bd5\u5f53\u524d\u7ebf\u8def", true);
        panel.addView((View)speed, (ViewGroup.LayoutParams)this.topMargin(12));
        speed.setOnClickListener(v -> {
            TrafficTarget target = this.targets.isEmpty() ? new TrafficTarget("\u9ed8\u8ba4\u6d4b\u901f", "https://speed.cloudflare.com/__down?bytes=100000000", 4, true, true) : this.targets.get(TrafficPrefs.readActiveIndex((Context)this, this.targets.size()));
            this.settings = new AppPrefs.Settings(target.url, "https://speed.cloudflare.com/__up", Math.max(1, Math.min(8, target.threads)), 10, this.isKeepAwakeEnabled(), this.isNotificationEnabled());
            this.phaseText.setText((CharSequence)"\u51c6\u5907\u4e2d");
            this.progressBar.setProgress(0);
            this.appendLog("\u4f7f\u7528\u5f53\u524d\u7ebf\u8def\u6d4b\u901f\uff1a" + target.displayName());
            this.speedTest.start(this.settings, this);
        });
        return panel;
    }

    private void applySavedState() {
        this.activeTargetIndex = TrafficPrefs.readActiveIndex((Context)this, this.targets.size());
        this.currentThreadValue = this.targets.isEmpty() ? this.currentThreadValue : this.targets.get((int)this.activeTargetIndex).threads;
        this.refreshTargetList();
        this.applyWakeFlag(this.isKeepAwakeEnabled());
    }

    private void toggleTraffic() {
        if (this.trafficRunning) {
            this.service("cn.netart.networkpanel.TRAFFIC_PAUSE");
            this.appendLog("\u5df2\u6682\u505c\u6d41\u91cf\u4efb\u52a1");
            return;
        }
        this.startTraffic();
    }

    private void startTraffic() {
        if (this.enabledTargetCount() == 0) {
            Toast.makeText((Context)this, (CharSequence)"\u8bf7\u5148\u6dfb\u52a0\u6216\u542f\u7528\u81f3\u5c11\u4e00\u4e2a\u94fe\u63a5", (int)0).show();
            return;
        }
        this.activeTargetIndex = TrafficPrefs.readActiveIndex((Context)this, this.targets.size());
        TrafficPrefs.writeTargets((Context)this, this.targets);
        TrafficPrefs.writeActiveIndex((Context)this, this.activeTargetIndex);
        TrafficPrefs.writeEnhanced((Context)this, this.isEnhancedEnabled());
        TrafficPrefs.writeKeepAwake((Context)this, this.isKeepAwakeEnabled());
        if (this.isNotificationEnabled()) {
            this.maybeRequestNotificationPermission();
        }
        this.service("cn.netart.networkpanel.TRAFFIC_START");
        this.appendLog("\u5f00\u59cb\u6d41\u91cf\u4efb\u52a1");
    }

    private void selectTarget(int index) {
        if (this.targets.isEmpty()) {
            return;
        }
        this.activeTargetIndex = Math.max(0, Math.min(this.targets.size() - 1, index));
        this.currentThreadValue = this.targets.get((int)this.activeTargetIndex).threads;
        TrafficPrefs.writeActiveIndex((Context)this, this.activeTargetIndex);
        this.refreshTargetList();
        this.appendLog("\u5df2\u5207\u6362\u7ebf\u8def\uff1a" + this.targets.get(this.activeTargetIndex).displayName());
        if (this.trafficRunning) {
            this.service("cn.netart.networkpanel.TRAFFIC_SWITCH");
        }
    }

    private void service(String action) {
        Intent intent = new Intent((Context)this, TrafficRunnerService.class);
        intent.setAction(action);
        if (Build.VERSION.SDK_INT >= 26 && "cn.netart.networkpanel.TRAFFIC_START".equals(action)) {
            this.startForegroundService(intent);
        } else {
            this.startService(intent);
        }
    }

    private void refreshRegionLatency() {
        if (this.latencyRequestInFlight || this.regionLatencyPanel == null) {
            return;
        }
        this.latencyRequestInFlight = true;
        new Thread(() -> {
            LatencyResult domestic = this.queryDomesticLatency();
            LatencyResult foreign = this.queryForeignLatency();
            this.handler.post(() -> this.applyRegionLatency(domestic, foreign));
        }, "region-latency").start();
    }

    private LatencyResult queryDomesticLatency() {
        long latency = this.measureHttpLatency(DOMESTIC_LATENCY_URL, "HEAD", 2500);
        if (latency <= 0L) {
            return null;
        }
        JSONObject data = this.fetchIpInfo(null);
        if (data == null || !"CN".equalsIgnoreCase(this.countryCode(data))) {
            return null;
        }
        String label = this.buildIpLabel(data, false);
        return TextUtils.isEmpty((CharSequence)label) ? null : new LatencyResult(latency, label);
    }

    private LatencyResult queryForeignLatency() {
        HttpTextResult trace = this.fetchText(CLOUDFLARE_TRACE_URL, "GET", 3500);
        if (trace == null || TextUtils.isEmpty((CharSequence)trace.body)) {
            return null;
        }
        String ip = this.parseTraceValue(trace.body, "ip");
        if (TextUtils.isEmpty((CharSequence)ip)) {
            return null;
        }
        JSONObject data = this.fetchIpInfo(ip);
        if (data == null || "CN".equalsIgnoreCase(this.countryCode(data))) {
            return null;
        }
        String label = this.buildIpLabel(data, true);
        return TextUtils.isEmpty((CharSequence)label) ? null : new LatencyResult(trace.latencyMillis, label);
    }

    private JSONObject fetchIpInfo(String ip) {
        try {
            HttpTextResult result;
            String endpoint = IP_INFO_URL;
            if (!TextUtils.isEmpty((CharSequence)ip)) {
                endpoint = endpoint + "?ip=" + URLEncoder.encode(ip, "UTF-8");
            }
            if ((result = this.fetchText(endpoint, "GET", 3500)) == null || TextUtils.isEmpty((CharSequence)result.body)) {
                return null;
            }
            JSONObject root = new JSONObject(result.body);
            return root.optJSONObject("data");
        }
        catch (Exception ignored) {
            return null;
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private long measureHttpLatency(String url, String method, int timeoutMs) {
        HttpURLConnection conn = null;
        long start = SystemClock.elapsedRealtime();
        try {
            conn = this.openConnection(url, method, timeoutMs);
            int code = conn.getResponseCode();
            if (code > 0) {
                long l = Math.max(1L, SystemClock.elapsedRealtime() - start);
                return l;
            }
        }
        catch (Exception ignored) {
            long l = -1L;
            return l;
        }
        finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return -1L;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private HttpTextResult fetchText(String url, String method, int timeoutMs) {
        HttpURLConnection conn = null;
        long start = SystemClock.elapsedRealtime();
        try {
            conn = this.openConnection(url, method, timeoutMs);
            int code = conn.getResponseCode();
            if (code <= 0) {
                HttpTextResult httpTextResult = null;
                return httpTextResult;
            }
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = this.readBody(stream);
            HttpTextResult httpTextResult = new HttpTextResult(Math.max(1L, SystemClock.elapsedRealtime() - start), body);
            return httpTextResult;
        }
        catch (Exception ignored) {
            HttpTextResult httpTextResult = null;
            return httpTextResult;
        }
        finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private HttpURLConnection openConnection(String url, String method, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)new URL(url).openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setUseCaches(false);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestMethod(method);
        conn.setRequestProperty("Cache-Control", "no-cache");
        conn.setRequestProperty("Pragma", "no-cache");
        conn.setRequestProperty("Connection", "close");
        conn.setRequestProperty("User-Agent", "NativeNetworkPanel/1.0");
        return conn;
    }

    private String readBody(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));){
            String line;
            while ((line = reader.readLine()) != null) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private String parseTraceValue(String body, String key) {
        String[] lines;
        if (TextUtils.isEmpty((CharSequence)body) || TextUtils.isEmpty((CharSequence)key)) {
            return "";
        }
        String prefix = key + "=";
        for (String line : lines = body.split("\\r?\\n")) {
            if (!line.startsWith(prefix)) continue;
            return line.substring(prefix.length()).trim();
        }
        return "";
    }

    private String countryCode(JSONObject data) {
        JSONObject country = data == null ? null : data.optJSONObject("country");
        return country == null ? "" : country.optString("code", "");
    }

    private String buildIpLabel(JSONObject data, boolean foreign) {
        JSONObject as;
        ArrayList<String> parts = new ArrayList<String>();
        JSONObject country = data.optJSONObject("country");
        if (foreign && country != null) {
            this.appendPart(parts, country.optString("name", ""));
        }
        if (!this.appendJsonParts(parts, data.opt("regions_short"))) {
            this.appendJsonParts(parts, data.opt("regions"));
        }
        if ((as = data.optJSONObject("as")) != null) {
            String asInfo = as.optString("info", "");
            this.appendPart(parts, TextUtils.isEmpty((CharSequence)asInfo) ? as.optString("name", "") : asInfo);
        }
        this.appendPart(parts, data.optString("type", ""));
        if (parts.isEmpty()) {
            this.appendPart(parts, data.optString("ip", ""));
        }
        if (parts.isEmpty()) {
            return "";
        }
        return TextUtils.join((CharSequence)" ", parts);
    }

    private boolean appendJsonParts(List<String> parts, Object value) {
        int before = parts.size();
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray)value;
            for (int i = 0; i < array.length(); ++i) {
                this.appendPart(parts, array.optString(i, ""));
            }
        } else if (value instanceof String) {
            this.appendPart(parts, (String)value);
        }
        return parts.size() > before;
    }

    private void appendPart(List<String> parts, String value) {
        if (value == null) {
            return;
        }
        String clean = value.trim();
        if (clean.isEmpty() || "null".equalsIgnoreCase(clean) || parts.contains(clean)) {
            return;
        }
        parts.add(clean);
    }

    private void applyRegionLatency(LatencyResult domestic, LatencyResult foreign) {
        boolean showDomestic = this.updateLatencyRow(this.domesticLatencyRow, this.domesticLatencyText, this.domesticLatencyRegionText, domestic);
        boolean showForeign = this.updateLatencyRow(this.foreignLatencyRow, this.foreignLatencyText, this.foreignLatencyRegionText, foreign);
        if (this.regionLatencyPanel != null) {
            this.regionLatencyPanel.setVisibility(showDomestic || showForeign ? 0 : 8);
        }
        this.latencyRequestInFlight = false;
    }

    private boolean updateLatencyRow(View row, TextView latencyView, TextView regionView, LatencyResult result) {
        boolean visible;
        boolean bl = visible = result != null && result.latencyMillis > 0L && !TextUtils.isEmpty((CharSequence)result.label);
        if (row != null) {
            row.setVisibility(visible ? 0 : 8);
        }
        if (visible) {
            latencyView.setText((CharSequence)(result.latencyMillis + "ms"));
            regionView.setText((CharSequence)result.label);
        }
        return visible;
    }

    private int enabledTargetCount() {
        int count = 0;
        for (TrafficTarget target : this.targets) {
            if (!target.enabled) continue;
            ++count;
        }
        return count;
    }

    private void addTarget() {
        String url = this.clean(this.linkUrlEdit);
        if (url.isEmpty()) {
            Toast.makeText((Context)this, (CharSequence)"\u8bf7\u8f93\u5165\u94fe\u63a5", (int)0).show();
            return;
        }
        this.targets.add(new TrafficTarget(this.clean(this.linkNameEdit), url, this.currentThreads(), this.isEnhancedEnabled(), true));
        this.activeTargetIndex = this.targets.size() - 1;
        this.currentThreadValue = this.targets.get((int)this.activeTargetIndex).threads;
        TrafficPrefs.writeTargets((Context)this, this.targets);
        TrafficPrefs.writeActiveIndex((Context)this, this.activeTargetIndex);
        this.linkNameEdit.setText((CharSequence)"");
        this.linkUrlEdit.setText((CharSequence)"");
        this.refreshTargetList();
        this.appendLog("\u5df2\u6dfb\u52a0\u94fe\u63a5\uff1a" + url);
    }

    private void refreshTargetList() {
        if (this.targetListContainer == null) {
            this.refreshTargetSpinner();
            return;
        }
        this.targetListContainer.removeAllViews();
        if (this.targets.isEmpty()) {
            TextView empty = this.text("\u6682\u65e0\u94fe\u63a5\uff0c\u8bf7\u6dfb\u52a0\u4e00\u4e2a\u4e0b\u8f7d\u94fe\u63a5\u3002", 13, this.MUTED, 0);
            empty.setPadding(this.dp(2), this.dp(2), this.dp(2), this.dp(6));
            this.targetListContainer.addView((View)empty, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-1, -2));
            this.refreshTargetSpinner();
        } else {
            this.activeTargetIndex = TrafficPrefs.readActiveIndex((Context)this, this.targets.size());
            TrafficTarget selected = this.targets.get(this.activeTargetIndex);
            this.currentThreadValue = selected.threads;
            this.refreshTargetSpinner();
            for (int i = 0; i < this.targets.size(); ++i) {
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
                rowParams.topMargin = i == 0 ? 0 : this.dp(8);
                this.targetListContainer.addView(this.targetRow(i), (ViewGroup.LayoutParams)rowParams);
            }
        }
        if (this.trafficWorkersText != null) {
            this.updateMetric(this.trafficWorkersText, this.targets.isEmpty() ? "--" : this.currentThreadValue + " \u7ebf\u7a0b");
        }
        if (this.routeSummaryText != null) {
            this.updateMetric(this.routeSummaryText, this.currentRouteName());
        }
    }

    private View targetRow(final int index) {
        final TrafficTarget target = this.targets.get(index);
        boolean selected = index == this.activeTargetIndex;
        LinearLayout row = this.vertical();
        row.setBackground(selected ? this.selectedChoiceBackground() : this.showItemBackground());
        row.setPadding(this.dp(12), this.dp(10), this.dp(12), this.dp(10));
        row.setClickable(true);
        row.setOnClickListener(v -> this.selectTarget(index));
        LinearLayout top = new LinearLayout((Context)this);
        top.setOrientation(0);
        top.setGravity(16);
        row.addView((View)top, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-1, -2));
        TextView title = this.text((index + 1) + ". " + target.displayName(), 14, this.TEXT, 1);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        top.addView((View)title, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(0, -2, 1.0f));
        if (selected) {
            TextView current = this.text("\u5f53\u524d", 11, this.theme.primary, 1);
            current.setGravity(17);
            current.setPadding(this.dp(9), this.dp(3), this.dp(9), this.dp(3));
            current.setBackground(this.chipBackground());
            LinearLayout.LayoutParams currentParams = new LinearLayout.LayoutParams(-2, -2);
            currentParams.leftMargin = this.dp(6);
            top.addView((View)current, (ViewGroup.LayoutParams)currentParams);
        }
        TextView edit = this.targetAction("\u7f16\u8f91", this.theme.primary);
        LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(-2, this.dp(30));
        editParams.leftMargin = this.dp(6);
        top.addView((View)edit, (ViewGroup.LayoutParams)editParams);
        TextView delete = this.targetAction("\u5220\u9664", this.theme.danger);
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(-2, this.dp(30));
        deleteParams.leftMargin = this.dp(6);
        top.addView((View)delete, (ViewGroup.LayoutParams)deleteParams);
        edit.setOnClickListener(v -> this.showEditTargetDialog(index));
        delete.setOnClickListener(v -> this.confirmDeleteTarget(index));
        String detail = (target.enabled ? "\u542f\u7528" : "\u505c\u7528") + " \u00b7 " + target.threads + "\u7ebf\u7a0b" + (target.enhanced ? " \u00b7 \u589e\u5f3a\u5e76\u53d1" : "");
        TextView detailView = this.text(detail, 12, selected ? this.theme.secondary : this.MUTED, 1);
        detailView.setSingleLine(true);
        detailView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
        detailParams.topMargin = this.dp(5);
        row.addView((View)detailView, (ViewGroup.LayoutParams)detailParams);
        TextView urlView = this.text(target.url == null ? "" : target.url, 12, selected ? this.theme.secondary : this.MUTED, 0);
        urlView.setSingleLine(true);
        urlView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams urlParams = new LinearLayout.LayoutParams(-1, -2);
        urlParams.topMargin = this.dp(2);
        row.addView((View)urlView, (ViewGroup.LayoutParams)urlParams);
        return row;
    }

    private TextView targetAction(String text, int color) {
        TextView button = this.text(text, 12, color, 1);
        button.setGravity(17);
        button.setClickable(true);
        button.setMinWidth(this.dp(48));
        button.setPadding(this.dp(10), 0, this.dp(10), 0);
        button.setBackground(this.chipBackground());
        return button;
    }

    private void showEditTargetDialog(final int index) {
        if (index < 0 || index >= this.targets.size()) {
            return;
        }
        TrafficTarget target = this.targets.get(index);
        LinearLayout content = this.vertical();
        final EditText nameEdit = this.input("\u540d\u79f0");
        nameEdit.setText((CharSequence)(target.name == null ? "" : target.name));
        final EditText urlEdit = this.input("\u94fe\u63a5");
        urlEdit.setText((CharSequence)(target.url == null ? "" : target.url));
        content.addView((View)this.label("\u540d\u79f0"), (ViewGroup.LayoutParams)this.topMargin(0));
        content.addView((View)nameEdit, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-1, this.dp(52)));
        content.addView((View)this.label("\u94fe\u63a5"), (ViewGroup.LayoutParams)this.topMargin(10));
        content.addView((View)urlEdit, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-1, this.dp(52)));
        this.showActionDialog("\u7f16\u8f91\u7ebf\u8def", (View)content, () -> {
            String url = this.clean(urlEdit);
            if (url.isEmpty()) {
                Toast.makeText((Context)this, (CharSequence)"\u8bf7\u8f93\u5165\u94fe\u63a5", (int)0).show();
                return;
            }
            if (index < 0 || index >= this.targets.size()) {
                return;
            }
            TrafficTarget old = this.targets.get(index);
            this.targets.set(index, new TrafficTarget(this.clean(nameEdit), url, old.threads, old.enhanced, old.enabled));
            TrafficPrefs.writeTargets((Context)this, this.targets);
            this.refreshTargetList();
            this.appendLog("\u5df2\u7f16\u8f91\u7ebf\u8def\uff1a" + url);
            if (this.trafficRunning && index == this.activeTargetIndex) {
                this.service("cn.netart.networkpanel.TRAFFIC_SWITCH");
            }
        });
    }

    private void confirmDeleteTarget(final int index) {
        if (index < 0 || index >= this.targets.size()) {
            return;
        }
        TrafficTarget target = this.targets.get(index);
        AlertDialog dialog = new AlertDialog.Builder((Context)this)
                .setTitle((CharSequence)"\u5220\u9664\u7ebf\u8def")
                .setMessage((CharSequence)("\u786e\u5b9a\u5220\u9664\u300c" + target.displayName() + "\u300d\u5417\uff1f"))
                .setNegativeButton((CharSequence)"\u53d6\u6d88", null)
                .setPositiveButton((CharSequence)"\u5220\u9664", (dialogInterface, which) -> this.deleteTarget(index))
                .show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable((Drawable)new ColorDrawable(this.theme.surface));
        }
    }

    private void deleteTarget(int index) {
        if (index < 0 || index >= this.targets.size()) {
            return;
        }
        TrafficTarget removed = this.targets.remove(index);
        if (this.targets.isEmpty()) {
            this.activeTargetIndex = 0;
            if (this.trafficRunning) {
                this.service("cn.netart.networkpanel.TRAFFIC_PAUSE");
            }
        } else {
            if (index < this.activeTargetIndex) {
                --this.activeTargetIndex;
            } else if (this.activeTargetIndex >= this.targets.size()) {
                this.activeTargetIndex = this.targets.size() - 1;
            }
        }
        TrafficPrefs.writeTargets((Context)this, this.targets);
        TrafficPrefs.writeActiveIndex((Context)this, this.activeTargetIndex);
        this.refreshTargetList();
        this.appendLog("\u5df2\u5220\u9664\u7ebf\u8def\uff1a" + removed.displayName());
        if (this.trafficRunning && !this.targets.isEmpty()) {
            this.service("cn.netart.networkpanel.TRAFFIC_SWITCH");
        }
    }

    private TextView sectionLabel(String text) {
        TextView view = this.text(text, 14, this.TEXT, 1);
        view.setPadding(0, 0, 0, this.dp(2));
        return view;
    }

    private TextView sectionHeader(String text) {
        TextView view = this.text(text, 15, this.TEXT, 1);
        view.setPadding(this.dp(2), this.dp(10), this.dp(2), this.dp(8));
        return view;
    }

    private void refreshTargetSpinner() {
        if (this.targetSpinner == null) {
            return;
        }
        ArrayList<String> names = new ArrayList<String>();
        for (TrafficTarget target : this.targets) {
            names.add(target.displayName());
        }
        if (names.isEmpty()) {
            names.add("\u6682\u65e0\u7ebf\u8def");
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>((Context)this, 17367048, names){

            public View getView(int position, View convertView, ViewGroup parent) {
                return MainActivity.this.spinnerTextView((String)this.getItem(position), convertView, true);
            }

            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                return MainActivity.this.spinnerTextView((String)this.getItem(position), convertView, false);
            }
        };
        this.restoringTargetSelection = true;
        this.targetSpinner.setAdapter((SpinnerAdapter)adapter);
        this.targetSpinner.setEnabled(!this.targets.isEmpty());
        this.targetSpinner.setSelection(this.targets.isEmpty() ? 0 : this.activeTargetIndex, false);
        this.restoringTargetSelection = false;
    }

    private TextView spinnerTextView(String value, View convertView, boolean selected) {
        TextView view = convertView instanceof TextView ? (TextView)convertView : new TextView((Context)this);
        view.setText((CharSequence)(value == null ? "" : value));
        view.setTextColor(this.TEXT);
        view.setTextSize(selected ? 16.0f : 15.0f);
        view.setTypeface(Typeface.DEFAULT, 1);
        view.setGravity(17);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setIncludeFontPadding(true);
        view.setPadding(this.dp(14), selected ? 0 : this.dp(12), this.dp(14), selected ? 0 : this.dp(12));
        return view;
    }

    private void showLimitDialog() {
        LinearLayout panel = this.vertical();
        panel.setPadding(0, this.dp(2), 0, 0);
        int currentMb = TrafficPrefs.readLimitMb((Context)this);
        int[] unitIndex = new int[]{this.defaultTrafficLimitUnit(currentMb)};
        TextView hint = this.text("\u8bbe\u7f6e\u672c\u6b21\u6700\u591a\u6d88\u8017\u7684\u6d41\u91cf\uff0c\u652f\u6301 MB / GB / TB\u3002\u586b 0 \u6216\u7559\u7a7a\u8868\u793a\u4e0d\u9650\u3002", 13, this.MUTED, 0);
        hint.setLineSpacing((float)this.dp(2), 1.0f);
        panel.addView((View)hint, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-1, -2));
        EditText input = this.input("\u4f8b\u5982\uff1a1");
        input.setInputType(8194);
        input.setGravity(17);
        input.setTextSize(18.0f);
        input.setTypeface(Typeface.DEFAULT, 1);
        input.setText((CharSequence)this.trafficLimitInputValue(currentMb, unitIndex[0]));
        input.setSelectAllOnFocus(true);
        input.setPadding(this.dp(12), 0, this.dp(12), 0);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(-1, this.dp(54));
        inputParams.topMargin = this.dp(14);
        panel.addView((View)input, (ViewGroup.LayoutParams)inputParams);
        LinearLayout unitRow = new LinearLayout((Context)this);
        unitRow.setOrientation(0);
        TextView[] unitViews = new TextView[TRAFFIC_LIMIT_UNITS.length];
        for (int i = 0; i < TRAFFIC_LIMIT_UNITS.length; ++i) {
            int nextUnit = i;
            TextView unit = this.limitUnitButton(TRAFFIC_LIMIT_UNITS[i], nextUnit == unitIndex[0]);
            unit.setOnClickListener(v -> {
                if (nextUnit == unitIndex[0]) {
                    return;
                }
                int currentLimitMb = this.parseTrafficLimitMb(input.getText().toString(), unitIndex[0]);
                unitIndex[0] = nextUnit;
                input.setText((CharSequence)this.trafficLimitInputValue(currentLimitMb, nextUnit));
                input.setSelection(input.getText().length());
                this.refreshLimitUnitButtons(unitViews, nextUnit);
            });
            unitViews[i] = unit;
            LinearLayout.LayoutParams unitParams = new LinearLayout.LayoutParams(0, this.dp(38), 1.0f);
            if (i > 0) {
                unitParams.leftMargin = this.dp(8);
            }
            unitRow.addView((View)unit, (ViewGroup.LayoutParams)unitParams);
        }
        LinearLayout.LayoutParams unitRowParams = new LinearLayout.LayoutParams(-1, -2);
        unitRowParams.topMargin = this.dp(10);
        panel.addView((View)unitRow, (ViewGroup.LayoutParams)unitRowParams);
        this.showActionDialog("\u672c\u6b21\u6d41\u91cf\u4e0a\u9650", (View)panel, () -> {
            int value = this.parseTrafficLimitMb(input.getText().toString(), unitIndex[0]);
            TrafficPrefs.writeLimitMb((Context)this, value);
            this.appendLog("\u6d41\u91cf\u4e0a\u9650\uff1a" + this.limitLabel(value));
            if (this.trafficRunning) {
                this.service("cn.netart.networkpanel.TRAFFIC_SWITCH");
            }
        });
    }

    private void showRateLimitDialog() {
        LinearLayout panel = this.vertical();
        panel.setPadding(0, this.dp(2), 0, 0);
        int current = TrafficPrefs.readRateLimitMbps((Context)this);
        TextView hint = this.text("\u8f93\u5165\u8dd1\u6d41\u91cf\u7684\u901f\u7387\u4e0a\u9650\uff0c\u5355\u4f4d Mbps\u3002\u586b 0 \u6216\u7559\u7a7a\u8868\u793a\u4e0d\u9650\u3002", 13, this.MUTED, 0);
        hint.setLineSpacing((float)this.dp(2), 1.0f);
        panel.addView((View)hint, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-1, -2));
        EditText input = this.input("\u4f8b\u5982\uff1a100");
        input.setInputType(2);
        input.setGravity(17);
        input.setTextSize(18.0f);
        input.setTypeface(Typeface.DEFAULT, 1);
        input.setText((CharSequence)(current <= 0 ? "" : String.valueOf(current)));
        input.setSelectAllOnFocus(true);
        input.setPadding(this.dp(12), 0, this.dp(12), 0);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(-1, this.dp(54));
        inputParams.topMargin = this.dp(14);
        panel.addView((View)input, (ViewGroup.LayoutParams)inputParams);
        this.showActionDialog("\u901f\u7387\u4e0a\u9650", (View)panel, () -> {
            int value = this.parseManualLimit(input.getText().toString());
            TrafficPrefs.writeRateLimitMbps((Context)this, value);
            this.refreshRateLimitLabel();
            this.appendLog("\u901f\u7387\u4e0a\u9650\uff1a" + this.rateLimitLabel(value));
            if (this.trafficRunning) {
                this.service("cn.netart.networkpanel.TRAFFIC_SWITCH");
            }
        });
    }

    private void showThreadDialog() {
        if (this.targets.isEmpty()) {
            Toast.makeText((Context)this, (CharSequence)"\u8bf7\u5148\u6dfb\u52a0\u7ebf\u8def", (int)0).show();
            return;
        }
        LinearLayout panel = this.vertical();
        panel.setPadding(0, this.dp(2), 0, 0);
        int current = this.currentThreads();
        TextView label = this.text("\u5f53\u524d\u7ebf\u7a0b\u6570", 13, this.MUTED, 1);
        label.setGravity(17);
        panel.addView((View)label, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-1, -2));
        final TextView valueText = this.text(current + " \u7ebf\u7a0b", 30, this.TEXT, 1);
        valueText.setGravity(17);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(-1, -2);
        valueParams.topMargin = this.dp(4);
        panel.addView((View)valueText, (ViewGroup.LayoutParams)valueParams);
        SeekBar seekBar = new SeekBar((Context)this);
        seekBar.setMax(31);
        seekBar.setProgress(Math.max(0, current - 1));
        this.tintSeekBar(seekBar);
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(-1, -2);
        seekParams.setMargins(this.dp(6), this.dp(16), this.dp(6), 0);
        panel.addView((View)seekBar, (ViewGroup.LayoutParams)seekParams);
        LinearLayout rangeRow = new LinearLayout((Context)this);
        rangeRow.setOrientation(0);
        TextView min = this.text("1", 12, this.MUTED, 1);
        TextView max = this.text("32", 12, this.MUTED, 1);
        max.setGravity(5);
        rangeRow.addView((View)min, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(0, -2, 1.0f));
        rangeRow.addView((View)max, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(0, -2, 1.0f));
        LinearLayout.LayoutParams rangeParams = new LinearLayout.LayoutParams(-1, -2);
        rangeParams.setMargins(this.dp(14), 0, this.dp(14), 0);
        panel.addView((View)rangeRow, (ViewGroup.LayoutParams)rangeParams);
        final int[] selected = new int[]{current};
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                selected[0] = progress + 1;
                valueText.setText((CharSequence)(selected[0] + " \u7ebf\u7a0b"));
            }

            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        this.showActionDialog("\u7ebf\u7a0b\u8bbe\u7f6e", (View)panel, () -> this.updateSelectedTargetThreads(selected[0]));
    }

    private void updateSelectedTargetThreads(int threads) {
        if (this.targets.isEmpty()) {
            return;
        }
        int value = Math.max(1, Math.min(32, threads));
        TrafficTarget old = this.targets.get(this.activeTargetIndex);
        this.targets.set(this.activeTargetIndex, new TrafficTarget(old.name, old.url, value, this.isEnhancedEnabled(), old.enabled));
        this.currentThreadValue = value;
        TrafficPrefs.writeTargets((Context)this, this.targets);
        this.refreshTargetList();
        this.appendLog("\u7ebf\u7a0b\u8bbe\u7f6e\uff1a" + value + " \u7ebf\u7a0b");
        if (this.trafficRunning) {
            this.service("cn.netart.networkpanel.TRAFFIC_SWITCH");
        }
    }

    private String limitLabel(int mb) {
        if (mb <= 0) {
            return "\u4e0d\u9650";
        }
        if (mb >= TRAFFIC_LIMIT_UNIT_FACTORS_MB[2]) {
            return this.compactNumber((double)mb / (double)TRAFFIC_LIMIT_UNIT_FACTORS_MB[2]) + " TB";
        }
        if (mb >= TRAFFIC_LIMIT_UNIT_FACTORS_MB[1]) {
            return this.compactNumber((double)mb / (double)TRAFFIC_LIMIT_UNIT_FACTORS_MB[1]) + " GB";
        }
        return mb + " MB";
    }

    private String rateLimitLabel(int mbps) {
        return mbps <= 0 ? "\u4e0d\u9650" : mbps + " Mbps";
    }

    private void refreshRateLimitLabel() {
        if (this.rateLimitText != null) {
            this.rateLimitText.setText((CharSequence)("\u901f\u7387\u4e0a\u9650 " + this.rateLimitLabel(TrafficPrefs.readRateLimitMbps((Context)this))));
        }
    }

    private int parseManualLimit(String raw) {
        String value;
        String string = value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return 0;
        }
        try {
            long parsed = Long.parseLong(value);
            return (int)Math.max(0L, Math.min(100000L, parsed));
        }
        catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private int defaultTrafficLimitUnit(int mb) {
        if (mb > 0 && mb % TRAFFIC_LIMIT_UNIT_FACTORS_MB[2] == 0) {
            return 2;
        }
        if (mb > 0 && mb % TRAFFIC_LIMIT_UNIT_FACTORS_MB[1] == 0) {
            return 1;
        }
        return 0;
    }

    private String trafficLimitInputValue(int mb, int unitIndex) {
        if (mb <= 0) {
            return "";
        }
        int factor = TRAFFIC_LIMIT_UNIT_FACTORS_MB[Math.max(0, Math.min(unitIndex, TRAFFIC_LIMIT_UNIT_FACTORS_MB.length - 1))];
        return this.compactNumber((double)mb / (double)factor);
    }

    private int parseTrafficLimitMb(String raw, int unitIndex) {
        String value;
        String string = value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return 0;
        }
        try {
            double parsed = Double.parseDouble(value);
            if (Double.isNaN(parsed) || Double.isInfinite(parsed) || parsed <= 0.0) {
                return 0;
            }
            int factor = TRAFFIC_LIMIT_UNIT_FACTORS_MB[Math.max(0, Math.min(unitIndex, TRAFFIC_LIMIT_UNIT_FACTORS_MB.length - 1))];
            long mb = Math.round(parsed * (double)factor);
            return (int)Math.max(0L, Math.min(Integer.MAX_VALUE, mb));
        }
        catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String compactNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 1.0E-6) {
            return String.valueOf((long)Math.rint(value));
        }
        String out = String.format(Locale.US, "%.2f", value);
        while (out.endsWith("0")) {
            out = out.substring(0, out.length() - 1);
        }
        if (out.endsWith(".")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private TextView limitUnitButton(String value, boolean selected) {
        TextView view = this.text(value, 13, selected ? this.theme.onPrimary : this.TEXT, 1);
        view.setGravity(17);
        view.setBackground(selected ? this.selectedChoiceBackground() : this.softButtonBackground());
        view.setClickable(true);
        view.setPadding(this.dp(8), 0, this.dp(8), 0);
        return view;
    }

    private void refreshLimitUnitButtons(TextView[] views, int selected) {
        for (int i = 0; i < views.length; ++i) {
            boolean isSelected = i == selected;
            views[i].setTextColor(isSelected ? this.theme.onPrimary : this.TEXT);
            views[i].setBackground(isSelected ? this.selectedChoiceBackground() : this.softButtonBackground());
        }
    }

    private void updateMetric(TextView view, String value) {
        if (view == null || value == null) {
            return;
        }
        this.setMetricText(view, value);
    }

    private void setMetricText(TextView view, String value) {
        if (view == null || value == null) {
            return;
        }
        view.animate().cancel();
        view.setSelected(true);
        view.setTranslationY(0.0f);
        view.setShadowLayer(0.0f, 0.0f, 0.0f, 0);
        view.setAlpha(1.0f);
        view.setText((CharSequence)value);
    }

    private void maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && this.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0) {
            this.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 41);
        }
    }

    private void applyWakeFlag(boolean keepAwake) {
        Window window = this.getWindow();
        if (keepAwake) {
            window.addFlags(128);
        } else {
            window.clearFlags(128);
        }
    }

    private void configureWindow() {
        if (Build.VERSION.SDK_INT >= 21) {
            this.getWindow().setStatusBarColor(0);
            this.getWindow().setNavigationBarColor(this.theme.backgroundBottom);
        }
        if (Build.VERSION.SDK_INT >= 23) {
            int flags = this.getWindow().getDecorView().getSystemUiVisibility();
            flags = this.theme.dark ? (flags &= 0xFFFFDFFF) : (flags |= 0x2000);
            if (Build.VERSION.SDK_INT >= 26) {
                flags = this.theme.dark ? (flags &= 0xFFFFFFEF) : (flags |= 0x10);
            }
            this.getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    private void resolvePalette() {
        this.currentThemeId = AppPrefs.readTheme((Context)this);
        this.theme = this.themeFor(this.currentThemeId);
        this.TEXT = this.theme.text;
        this.MUTED = this.theme.muted;
        this.BLUE = this.theme.primary;
        this.CYAN = this.theme.secondary;
        this.GREEN = this.theme.success;
        this.RED = this.theme.danger;
        this.HINT = this.theme.hint;
        this.DIVIDER = this.theme.divider;
        this.MARK_MUTED = this.theme.markMuted;
    }

    private void cycleTheme() {
        AppPrefs.writeTheme((Context)this, (this.currentThemeId + 1) % AppPrefs.THEME_COUNT);
        this.recreate();
    }

    private ThemePalette themeFor(int id) {
        switch (id) {
            case 0: {
                return new ThemePalette("\u51b0\u5ddd\u84dd", false, this.rgb("#F3FAFF"), this.rgb("#E6F0FF"), this.rgb("#FFFFFF"), this.rgb("#F7FBFF"), this.rgb("#102A43"), this.rgb("#627D98"), this.rgb("#91A9C2"), this.rgb("#2563EB"), this.rgb("#0891B2"), this.rgb("#059669"), this.rgb("#DC2626"), this.rgb("#D8E7F8"), this.rgb("#DCEBFA"), this.rgb("#7289A3"), this.rgb("#EEF7FF"), this.rgb("#F0FDF4"), this.rgb("#BBF7D0"), this.rgb("#DBEAFE"), this.rgb("#E0F2FE"), this.rgb("#3B82F6"), this.rgb("#06B6D4"), this.rgb("#0F766E"), this.rgb("#2563EB"), this.rgb("#FFFFFF"));
            }
            case 1: {
                return new ThemePalette("\u591c", true, this.rgb("#0B1220"), this.rgb("#111827"), this.rgb("#111827"), this.rgb("#172033"), this.rgb("#E8EEF7"), this.rgb("#A7B4C7"), this.rgb("#8392A8"), this.rgb("#60A5FA"), this.rgb("#22D3EE"), this.rgb("#4ADE80"), this.rgb("#F87171"), this.rgb("#27344A"), this.rgb("#314157"), this.rgb("#B6C0CF"), this.rgb("#172033"), this.rgb("#14261E"), this.rgb("#245B3C"), this.rgb("#172033"), this.rgb("#101A2C"), this.rgb("#4F46E5"), this.rgb("#38BDF8"), this.rgb("#14B8A6"), this.rgb("#2563EB"), this.rgb("#F8FAFC"));
            }
            case 2: {
                return new ThemePalette("\u5f71", false, this.rgb("#FBF5EC"), this.rgb("#EFE2D2"), this.rgb("#FFFCF6"), this.rgb("#F6EBDD"), this.rgb("#302B27"), this.rgb("#8E8071"), this.rgb("#B4A28E"), this.rgb("#B85F3D"), this.rgb("#56756F"), this.rgb("#56756F"), this.rgb("#A8452F"), this.rgb("#E4D4C0"), this.rgb("#D8C5AE"), this.rgb("#713A2B"), this.rgb("#FFF7EA"), this.rgb("#E8F1EA"), this.rgb("#C8D8D3"), this.rgb("#FFF9EE"), this.rgb("#EBD7BD"), this.rgb("#B85F3D"), this.rgb("#713A2B"), this.rgb("#56756F"), this.rgb("#252A31"), this.rgb("#FFFDF8"));
            }
            case 3: {
                return new ThemePalette("\u7c89", false, this.rgb("#FFF3F6"), this.rgb("#EEF5FF"), this.rgb("#FFFFFF"), this.rgb("#FBF7FA"), this.rgb("#1E293B"), this.rgb("#667085"), this.rgb("#9AA6B5"), this.rgb("#D85C8A"), this.rgb("#5B8FC9"), this.rgb("#4C9F70"), this.rgb("#D95B68"), this.rgb("#E7DDE5"), this.rgb("#DCE5EF"), this.rgb("#8A7280"), this.rgb("#FFF8FB"), this.rgb("#EEF9F2"), this.rgb("#C9E7D4"), this.rgb("#FFFFFF"), this.rgb("#F0F6FF"), this.rgb("#D85C8A"), this.rgb("#6F9FD8"), this.rgb("#F06A9B"), this.rgb("#6E8FE5"), this.rgb("#FFFFFF"));
            }
            case 4: {
                return new ThemePalette("\u79cb", false, this.rgb("#FFF7E6"), this.rgb("#FDEDD3"), this.rgb("#FFFFFF"), this.rgb("#FFF8ED"), this.rgb("#2D2218"), this.rgb("#7C6A55"), this.rgb("#B79C78"), this.rgb("#B7791F"), this.rgb("#C0842B"), this.rgb("#5F8A45"), this.rgb("#C75A45"), this.rgb("#E8D7BE"), this.rgb("#EADDCB"), this.rgb("#9B8469"), this.rgb("#FFF9EB"), this.rgb("#F3F9E8"), this.rgb("#CFE5B5"), this.rgb("#FFF1C7"), this.rgb("#F8D98A"), this.rgb("#F6B45C"), this.rgb("#D98A2B"), this.rgb("#E97451"), this.rgb("#C45A35"), this.rgb("#FFFFFF"));
            }
            case 5: {
                return new ThemePalette("\u6d77", false, this.rgb("#EAFBFF"), this.rgb("#DFF7EF"), this.rgb("#FFFFFF"), this.rgb("#F2FCFA"), this.rgb("#123038"), this.rgb("#5C7880"), this.rgb("#8AA4AA"), this.rgb("#0E7490"), this.rgb("#0D9488"), this.rgb("#16A34A"), this.rgb("#E11D48"), this.rgb("#CBEAF0"), this.rgb("#D6EEF2"), this.rgb("#6E8A92"), this.rgb("#E8F9F7"), this.rgb("#ECFDF5"), this.rgb("#A7F3D0"), this.rgb("#CCFBF1"), this.rgb("#BAE6FD"), this.rgb("#06B6D4"), this.rgb("#14B8A6"), this.rgb("#0EA5E9"), this.rgb("#10B981"), this.rgb("#FFFFFF"));
            }
            case 6: {
                return new ThemePalette("\u6f6e", true, this.rgb("#061A24"), this.rgb("#082F3A"), this.rgb("#0B2430"), this.rgb("#103342"), this.rgb("#E6FFFB"), this.rgb("#95C9D4"), this.rgb("#6DA8B7"), this.rgb("#22D3EE"), this.rgb("#2DD4BF"), this.rgb("#34D399"), this.rgb("#FB7185"), this.rgb("#1B5160"), this.rgb("#256575"), this.rgb("#8BB7C0"), this.rgb("#113944"), this.rgb("#0D3B31"), this.rgb("#1F7A65"), this.rgb("#0E7490"), this.rgb("#0F766E"), this.rgb("#06B6D4"), this.rgb("#2DD4BF"), this.rgb("#2563EB"), this.rgb("#06B6D4"), this.rgb("#ECFEFF"));
            }
            case 7: {
                return new ThemePalette("\u6781", true, this.rgb("#07111F"), this.rgb("#0E1B2A"), this.rgb("#0F2233"), this.rgb("#162C3E"), this.rgb("#EAF6FF"), this.rgb("#A9C2D8"), this.rgb("#7896AE"), this.rgb("#7DD3FC"), this.rgb("#A7F3D0"), this.rgb("#4ADE80"), this.rgb("#FDA4AF"), this.rgb("#27445B"), this.rgb("#31516A"), this.rgb("#92AFC4"), this.rgb("#172F43"), this.rgb("#123C35"), this.rgb("#2A6F61"), this.rgb("#164E63"), this.rgb("#1D4ED8"), this.rgb("#0EA5E9"), this.rgb("#22D3EE"), this.rgb("#14B8A6"), this.rgb("#3B82F6"), this.rgb("#F8FAFC"));
            }
            case 8: {
                return new ThemePalette("\u66dc", true, this.rgb("#1C2024"), this.rgb("#262D32"), this.rgb("#242A2E"), this.rgb("#2E363C"), this.rgb("#F9FAFC"), this.rgb("#C6D2D8"), this.rgb("#8DA7B4"), this.rgb("#8DA7B4"), this.rgb("#9BD8BE"), this.rgb("#76D7A5"), this.rgb("#FF8A8A"), this.rgb("#3B4044"), this.rgb("#465057"), this.rgb("#A9B7BE"), this.rgb("#313A40"), this.rgb("#203B33"), this.rgb("#4E9279"), this.rgb("#2F3B42"), this.rgb("#7190A1"), this.rgb("#8DA7B4"), this.rgb("#9BD8BE"), this.rgb("#6ED4A6"), this.rgb("#5AA3BE"), this.rgb("#101418"));
            }
            case 9: {
                return new ThemePalette("\u78b3", true, this.rgb("#0B0F14"), this.rgb("#11161D"), this.rgb("#151C22"), this.rgb("#1A2128"), this.rgb("#E8F0F4"), this.rgb("#9BA8B2"), this.rgb("#73828C"), this.rgb("#2ED7B2"), this.rgb("#92B8AD"), this.rgb("#43C9A0"), this.rgb("#DA6A73"), this.rgb("#29343C"), this.rgb("#34404A"), this.rgb("#AEBBC2"), this.rgb("#1A232A"), this.rgb("#173229"), this.rgb("#224A3E"), this.rgb("#172028"), this.rgb("#0F141A"), this.rgb("#2ED7B2"), this.rgb("#59E0C4"), this.rgb("#39C49A"), this.rgb("#45D8B8"), this.rgb("#F4FAFC"));
            }
            case 10: {
                return new ThemePalette("\u94f6", true, this.rgb("#090D12"), this.rgb("#11161D"), this.rgb("#151A21"), this.rgb("#1A2028"), this.rgb("#E7EDF4"), this.rgb("#A0ACB8"), this.rgb("#7F8C98"), this.rgb("#6EA8FF"), this.rgb("#A7BACF"), this.rgb("#7DB0FF"), this.rgb("#E2E8F0"), this.rgb("#2A3540"), this.rgb("#343F4A"), this.rgb("#B1BDCA"), this.rgb("#1B2129"), this.rgb("#12212D"), this.rgb("#203142"), this.rgb("#182029"), this.rgb("#10161D"), this.rgb("#6EA8FF"), this.rgb("#84B6FF"), this.rgb("#6DD6C0"), this.rgb("#4A89E8"), this.rgb("#F5F8FC"));
            }
            case 11: {
                return new ThemePalette("\u7d2b", true, this.rgb("#0A0712"), this.rgb("#120D1C"), this.rgb("#171321"), this.rgb("#1D182A"), this.rgb("#EFEAFB"), this.rgb("#B2A9C6"), this.rgb("#8F84A5"), this.rgb("#8B7CFF"), this.rgb("#6E8DFF"), this.rgb("#C47CFF"), this.rgb("#F27A92"), this.rgb("#2B243E"), this.rgb("#382D52"), this.rgb("#BEB2DA"), this.rgb("#1E192E"), this.rgb("#17152B"), this.rgb("#2A3D6A"), this.rgb("#1D1830"), this.rgb("#11101A"), this.rgb("#9A8CFF"), this.rgb("#7A7CFF"), this.rgb("#5B63FF"), this.rgb("#8B7CFF"), this.rgb("#F7F3FF"));
            }
            case 12: {
                return new ThemePalette("\u91d1", true, this.rgb("#0D0B09"), this.rgb("#15110E"), this.rgb("#1A1712"), this.rgb("#201B16"), this.rgb("#F5E7C9"), this.rgb("#BFA98A"), this.rgb("#9A8667"), this.rgb("#E0A84A"), this.rgb("#C8A15A"), this.rgb("#DCA24B"), this.rgb("#E58E7A"), this.rgb("#33281E"), this.rgb("#423528"), this.rgb("#C9B68C"), this.rgb("#211B14"), this.rgb("#322010"), this.rgb("#5C4520"), this.rgb("#2A231A"), this.rgb("#15110C"), this.rgb("#E0A84A"), this.rgb("#D9B15A"), this.rgb("#E0A84A"), this.rgb("#C7882A"), this.rgb("#FFF5DD"));
            }
            case 13: {
                return new ThemePalette("\u8584", true, this.rgb("#09110F"), this.rgb("#101916"), this.rgb("#121B18"), this.rgb("#17211D"), this.rgb("#E5FBF5"), this.rgb("#A3CDBF"), this.rgb("#7BA996"), this.rgb("#63E6C7"), this.rgb("#86DCC8"), this.rgb("#4CBD9E"), this.rgb("#DF6B7A"), this.rgb("#23312D"), this.rgb("#2D3C38"), this.rgb("#A7CFC3"), this.rgb("#17231F"), this.rgb("#13322A"), this.rgb("#244C42"), this.rgb("#17241F"), this.rgb("#0F1613"), this.rgb("#63E6C7"), this.rgb("#7EEAD1"), this.rgb("#58D0B1"), this.rgb("#49E0C0"), this.rgb("#F3FFFB"));
            }
            case 14: {
                return new ThemePalette("\u96fe", true, this.rgb("#0C1117"), this.rgb("#111720"), this.rgb("#151B22"), this.rgb("#1A212A"), this.rgb("#EAF0F6"), this.rgb("#A9B9C7"), this.rgb("#8496A6"), this.rgb("#8DB8D8"), this.rgb("#9DB3CA"), this.rgb("#4BB7A7"), this.rgb("#DC6B77"), this.rgb("#27323E"), this.rgb("#33404C"), this.rgb("#B2C0CC"), this.rgb("#17202A"), this.rgb("#123129"), this.rgb("#20483F"), this.rgb("#18212B"), this.rgb("#10161C"), this.rgb("#8DB8D8"), this.rgb("#A5C9E6"), this.rgb("#57C7B0"), this.rgb("#86C2E2"), this.rgb("#F5FAFE"));
            }
            case 15: {
                return new ThemePalette("\u7ea2", true, this.rgb("#10090B"), this.rgb("#161013"), this.rgb("#1A1214"), this.rgb("#20171A"), this.rgb("#F3E7EA"), this.rgb("#B79BA5"), this.rgb("#8F7A84"), this.rgb("#D46B7A"), this.rgb("#A58AA0"), this.rgb("#C96C7E"), this.rgb("#E58C8C"), this.rgb("#2F2328"), this.rgb("#3D2E34"), this.rgb("#BDA8AF"), this.rgb("#22181D"), this.rgb("#2B1820"), this.rgb("#4A2530"), this.rgb("#27171B"), this.rgb("#140E11"), this.rgb("#D46B7A"), this.rgb("#C05F6C"), this.rgb("#D46B7A"), this.rgb("#A64D5D"), this.rgb("#FAF0F2"));
            }
        }
        return this.themeFor(0);
    }

    private Drawable screenBackground() {
        return this.gradient(GradientDrawable.Orientation.TOP_BOTTOM, this.theme.backgroundTop, this.theme.backgroundBottom, 0, 0);
    }

    private Drawable panelBackground() {
        return this.rounded(this.theme.surface, 18, 0, 0);
    }

    private Drawable sectionBackground() {
        return this.rounded(this.theme.surfaceAlt, 14, 1, this.theme.line);
    }

    private Drawable heroBackground() {
        return this.gradient(GradientDrawable.Orientation.TL_BR, this.theme.heroStart, this.theme.heroEnd, 20, this.theme.line);
    }

    private Drawable cardBackground() {
        return this.rounded(this.theme.surface, 14, 1, this.theme.line);
    }

    private Drawable showItemBackground() {
        return this.rounded(this.theme.surfaceAlt, 12, 1, this.theme.line);
    }

    private Drawable selectedChoiceBackground() {
        return this.gradient(GradientDrawable.Orientation.LEFT_RIGHT, this.theme.heroStart, this.theme.heroEnd, 12, this.theme.primary);
    }

    private Drawable chipBackground() {
        return this.rounded(this.theme.chip, 12, 1, this.theme.line);
    }

    private Drawable softButtonBackground() {
        return this.rounded(this.theme.surfaceAlt, 12, 0, 0);
    }

    private Drawable inputBackground() {
        return this.rounded(this.theme.surface, 12, 1, this.theme.divider);
    }

    private Drawable latencyBadgeBackground() {
        return this.rounded(this.theme.successBg, 12, 1, this.theme.successLine);
    }

    private Drawable runButtonBackground(boolean running) {
        if (running) {
            GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{this.rgb("#FF3D5A"), this.rgb("#FFB14A")});
            fill.setCornerRadius((float)this.dp(20));
            GradientDrawable border = new GradientDrawable();
            border.setColor(0);
            border.setCornerRadius((float)this.dp(19));
            border.setStroke(this.dp(1), this.rgb("#FFEA9E"));
            LayerDrawable layer = new LayerDrawable(new Drawable[]{fill, border});
            int inset = this.dp(2);
            layer.setLayerInset(1, inset, inset, inset, inset);
            return layer;
        }
        return this.gradient(GradientDrawable.Orientation.LEFT_RIGHT, this.theme.primaryStart, this.theme.primaryEnd, 16, 0);
    }

    private Drawable rounded(int fill, int radiusDp, int strokeDp, int strokeColor) {
        GradientDrawable drawable2 = new GradientDrawable();
        drawable2.setColor(fill);
        drawable2.setCornerRadius((float)this.dp(radiusDp));
        if (strokeDp > 0) {
            drawable2.setStroke(this.dp(strokeDp), strokeColor);
        }
        return drawable2;
    }

    private Drawable gradient(GradientDrawable.Orientation orientation, int start, int end, int radiusDp, int strokeColor) {
        GradientDrawable drawable2 = new GradientDrawable(orientation, new int[]{start, end});
        drawable2.setCornerRadius((float)this.dp(radiusDp));
        if (strokeColor != 0) {
            drawable2.setStroke(this.dp(1), strokeColor);
        }
        return drawable2;
    }

    private int rgb(String value) {
        return Color.parseColor((String)value);
    }

    private String themeChoiceName(int id) {
        switch (id) {
            case 0: {
                return "\u51b0\u5ddd\u84dd";
            }
            case 1: {
                return "\u6697\u591c";
            }
            case 2: {
                return "\u5f71\u50cf";
            }
            case 3: {
                return "\u7c89\u84dd";
            }
            case 4: {
                return "\u6d45\u79cb";
            }
            case 5: {
                return "\u6d77\u76d0\u8584\u8377";
            }
            case 6: {
                return "\u6df1\u6d77\u6f6e\u6c50";
            }
            case 7: {
                return "\u6781\u591c\u51b7\u5149";
            }
            case 8: {
                return "\u66dc\u77f3\u84dd\u7eff";
            }
            case 9: {
                return "\u78b3\u7070\u9752\u7eff";
            }
            case 10: {
                return "\u77f3\u58a8\u84dd\u94f6";
            }
            case 11: {
                return "\u6697\u7d2b\u7535\u5149";
            }
            case 12: {
                return "\u7425\u73c0\u9ed1\u91d1";
            }
            case 13: {
                return "\u8584\u8377\u9ed1";
            }
            case 14: {
                return "\u96fe\u84dd\u7070";
            }
            case 15: {
                return "\u9152\u7ea2\u9ed1";
            }
        }
        return "\u51b0\u5ddd\u84dd";
    }

    private String themeChoiceDesc(int id) {
        switch (id) {
            case 0: {
                return "\u9ed8\u8ba4\u51b0\u84dd\u4e3b\u9898\uff0c\u6e05\u723d\u901a\u900f";
            }
            case 1: {
                return "\u6df1\u8272\u4f4e\u4eae\u5ea6\uff0c\u9002\u5408\u591c\u95f4\u957f\u65f6\u95f4\u8fd0\u884c";
            }
            case 2: {
                return "\u6696\u7eb8\u5f20\u548c\u80f6\u7247\u611f";
            }
            case 3: {
                return "\u6d45\u7c89\u4e0e\u96fe\u84dd";
            }
            case 4: {
                return "\u6de1\u674f\u9ec4\u3001\u9ea6\u7a57\u91d1\u548c\u67d4\u548c\u67ab\u6a59";
            }
            case 5: {
                return "\u6d77\u76d0\u767d\u3001\u8584\u8377\u7eff\u548c\u6d45\u6c34\u84dd";
            }
            case 6: {
                return "\u4f4e\u4eae\u5ea6\u9752\u84dd\u4e0e\u7eff\u677e\u77f3\u9ad8\u5149";
            }
            case 7: {
                return "\u51b7\u84dd\u80cc\u666f\u548c\u51b0\u5149\u5f3a\u8c03\u8272";
            }
            case 8: {
                return "\u6df1\u7070\u3001\u84dd\u7070\u548c\u9752\u7eff\u8272";
            }
            case 9: {
                return "\u78b3\u7070\u5e95\u4e0e\u4f4e\u9971\u548c\u9752\u7eff\u8272";
            }
            case 10: {
                return "\u77f3\u58a8\u9ed1\u4e0e\u51b7\u84dd\u94f6\uff0c\u9ad8\u7ea7\u7406\u6027";
            }
            case 11: {
                return "\u514b\u5236\u7d2b\u84dd\u9ad8\u5149\uff0c\u79d1\u6280\u611f\u66f4\u5f3a";
            }
            case 12: {
                return "\u70ad\u9ed1\u4e0e\u7425\u73c0\u91d1\uff0c\u7a33\u91cd\u6709\u54c1\u724c\u611f";
            }
            case 13: {
                return "\u9ed1\u8272\u5e95\u914d\u6e05\u723d\u8584\u8377\u7eff";
            }
            case 14: {
                return "\u96fe\u84dd\u7070\u7cfb\u7edf\u98ce\uff0c\u5b89\u9759\u5e72\u51c0";
            }
            case 15: {
                return "\u4f4e\u9971\u548c\u9152\u7ea2\u5f3a\u8c03\uff0c\u6210\u719f\u514b\u5236";
            }
        }
        return "\u9ed8\u8ba4\u51b0\u5ddd\u84dd\u4e3b\u9898";
    }

    private String phaseName(SpeedTestEngine.TestPhase phase) {
        switch (phase) {
            case LATENCY: {
                return "\u5ef6\u8fdf\u6d4b\u8bd5";
            }
            case DOWNLOAD: {
                return "\u4e0b\u8f7d\u6d4b\u901f";
            }
            case UPLOAD: {
                return "\u4e0a\u4f20\u6d4b\u901f";
            }
            case FINISHED: {
                return "\u5b8c\u6210";
            }
        }
        return "\u8fd0\u884c\u4e2d";
    }

    private void appendLog(String message) {
    }

    private TextView labeledSeek(LinearLayout panel, String label, final int min, int max, int value, final ValueCallback callback) {
        LinearLayout row = new LinearLayout((Context)this);
        row.setOrientation(0);
        row.setGravity(16);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.setMargins(this.dp(20), this.dp(14), this.dp(20), 0);
        panel.addView((View)row, (ViewGroup.LayoutParams)rowParams);
        TextView name = this.text(label, 14, this.MUTED, 1);
        row.addView((View)name, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(0, -2, 1.0f));
        TextView valueText = this.text(String.valueOf(value), 14, this.TEXT, 1);
        row.addView((View)valueText);
        SeekBar seekBar = new SeekBar((Context)this);
        seekBar.setMax(max - min);
        seekBar.setProgress(value - min);
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(-1, -2);
        seekParams.setMargins(this.dp(14), this.dp(2), this.dp(14), 0);
        panel.addView((View)seekBar, (ViewGroup.LayoutParams)seekParams);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                callback.onValue(min + progress);
            }

            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        return valueText;
    }

    private TextView tableRow(LinearLayout panel, String name, String value) {
        LinearLayout row = new LinearLayout((Context)this);
        row.setOrientation(0);
        row.setGravity(16);
        row.setPadding(this.dp(8), this.dp(9), this.dp(8), this.dp(9));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.topMargin = this.dp(6);
        panel.addView((View)row, (ViewGroup.LayoutParams)rowParams);
        TextView left = this.text(name, 14, this.MUTED, 0);
        row.addView((View)left, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(0, -2, 1.0f));
        TextView right = this.text(value, 15, this.TEXT, 1);
        right.setGravity(5);
        row.addView((View)right);
        return right;
    }

    private View card(String title, View child) {
        LinearLayout card = new LinearLayout((Context)this);
        card.setOrientation(1);
        card.setBackground(this.cardBackground());
        card.setClipToPadding(false);
        card.setClipChildren(false);
        card.setPadding(this.dp(14), this.dp(14), this.dp(14), this.dp(14));
        if (title != null) {
            TextView titleView = this.text(title, 18, this.TEXT, 1);
            card.addView((View)titleView, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-1, -2));
        }
        LinearLayout.LayoutParams childParams = new LinearLayout.LayoutParams(-1, -2);
        if (title != null) {
            childParams.topMargin = this.dp(10);
        }
        card.addView(child, (ViewGroup.LayoutParams)childParams);
        return card;
    }

    private GridLayout grid(int columns) {
        GridLayout grid = new GridLayout((Context)this);
        grid.setColumnCount(columns);
        grid.setUseDefaultMargins(false);
        return grid;
    }

    private View buildSpeedHero() {
        LinearLayout hero = new LinearLayout((Context)this);
        hero.setOrientation(1);
        hero.setGravity(16);
        hero.setBackground(this.heroBackground());
        hero.setPadding(this.dp(16), this.dp(12), this.dp(16), this.dp(12));
        LinearLayout top = new LinearLayout((Context)this);
        top.setOrientation(0);
        top.setGravity(16);
        hero.addView((View)top, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-1, -2));
        TextView labelView = this.text("实时网速", 14, this.BLUE, 1);
        top.addView((View)labelView, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-2, -2));
        this.rateLimitText = this.text("", 12, this.CYAN, 1);
        this.rateLimitText.setGravity(17);
        this.rateLimitText.setSingleLine(true);
        this.rateLimitText.setPadding(this.dp(8), this.dp(3), this.dp(8), this.dp(3));
        this.rateLimitText.setBackground(this.chipBackground());
        this.rateLimitText.setOnClickListener(v -> this.showRateLimitDialog());
        LinearLayout.LayoutParams rateParams = new LinearLayout.LayoutParams(-2, -2);
        rateParams.leftMargin = this.dp(8);
        top.addView((View)this.rateLimitText, (ViewGroup.LayoutParams)rateParams);
        this.refreshRateLimitLabel();
        this.trafficRateMbText = this.text("-- MB/s", 38, this.TEXT, 1);
        this.trafficRateMbText.setGravity(17);
        this.trafficRateMbText.setSingleLine(true);
        this.trafficRateMbText.setEllipsize(TextUtils.TruncateAt.END);
        this.enableAutoSize(this.trafficRateMbText, 24, 38);
        LinearLayout.LayoutParams mbParams = new LinearLayout.LayoutParams(-1, 0, 1.0f);
        mbParams.topMargin = this.dp(4);
        hero.addView((View)this.trafficRateMbText, (ViewGroup.LayoutParams)mbParams);
        LinearLayout bottom = new LinearLayout((Context)this);
        bottom.setOrientation(0);
        bottom.setGravity(16);
        hero.addView((View)bottom, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-1, -2));
        this.trafficRateMbpsText = this.text("-- Mbps", 15, this.MUTED, 1);
        this.trafficRateMbpsText.setGravity(17);
        this.trafficRateMbpsText.setSingleLine(true);
        this.trafficRateMbpsText.setEllipsize(TextUtils.TruncateAt.END);
        this.enableAutoSize(this.trafficRateMbpsText, 12, 15);
        bottom.addView((View)this.trafficRateMbpsText, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(0, -2, 1.0f));
        return hero;
    }
    private TextView addUsageCard(LinearLayout row, String label, String value, String actionText, View.OnClickListener action) {
        LinearLayout card = new LinearLayout((Context)this);
        card.setOrientation(1);
        card.setGravity(16);
        card.setBackground(this.cardBackground());
        card.setPadding(this.dp(16), this.dp(12), this.dp(16), this.dp(12));
        LinearLayout top = new LinearLayout((Context)this);
        top.setOrientation(0);
        top.setGravity(16);
        TextView labelView = this.text(label, 13, this.MUTED, 1);
        top.addView((View)labelView, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(0, -2, 1.0f));
        if (actionText != null && action != null) {
            TextView actionView = this.text(actionText, 12, this.CYAN, 1);
            actionView.setGravity(17);
            actionView.setPadding(this.dp(8), this.dp(3), this.dp(8), this.dp(3));
            actionView.setBackground(this.chipBackground());
            actionView.setOnClickListener(action);
            top.addView((View)actionView);
        }
        card.addView((View)top, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-1, -2));
        TextView valueView = this.text(value, 24, this.TEXT, 1);
        valueView.setGravity(17);
        valueView.setSingleLine(true);
        valueView.setEllipsize(TextUtils.TruncateAt.END);
        this.enableAutoSize(valueView, 16, 24);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(-1, 0, 1.0f);
        valueParams.topMargin = this.dp(4);
        card.addView((View)valueView, (ViewGroup.LayoutParams)valueParams);
        row.addView((View)card, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(0, -1, 1.0f));
        return valueView;
    }

    private TextView buildThreadParam(LinearLayout panel) {
        FrameLayout card = new FrameLayout((Context)this);
        card.setBackground(this.cardBackground());
        card.setPadding(this.dp(16), this.dp(10), this.dp(16), this.dp(10));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, this.dp(54));
        params.topMargin = this.dp(12);
        panel.addView((View)card, (ViewGroup.LayoutParams)params);
        TextView labelView = this.text("\u8fd0\u884c\u7ebf\u7a0b", 13, this.MUTED, 1);
        FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(-2, -2);
        labelParams.gravity = 8388627;
        card.addView((View)labelView, (ViewGroup.LayoutParams)labelParams);
        TextView valueView = this.text("--", 18, this.TEXT, 1);
        valueView.setGravity(17);
        valueView.setSingleLine(true);
        valueView.setEllipsize(TextUtils.TruncateAt.END);
        valueView.setPadding(this.dp(110), 0, this.dp(110), 0);
        FrameLayout.LayoutParams valueParams = new FrameLayout.LayoutParams(-1, -2);
        valueParams.gravity = 17;
        card.addView((View)valueView, (ViewGroup.LayoutParams)valueParams);
        TextView actionView = this.text("\u8bbe\u7f6e", 12, this.CYAN, 1);
        actionView.setGravity(17);
        actionView.setPadding(this.dp(10), this.dp(4), this.dp(10), this.dp(4));
        actionView.setBackground(this.chipBackground());
        actionView.setOnClickListener(v -> this.showThreadDialog());
        FrameLayout.LayoutParams actionParams = new FrameLayout.LayoutParams(-2, -2);
        actionParams.gravity = 8388629;
        card.addView((View)actionView, (ViewGroup.LayoutParams)actionParams);
        return valueView;
    }

    private View buildRegionLatencyPanel() {
        LinearLayout panel = new LinearLayout((Context)this);
        panel.setOrientation(1);
        panel.setBackground(this.cardBackground());
        panel.setPadding(this.dp(14), this.dp(11), this.dp(14), this.dp(11));
        panel.setVisibility(8);
        this.regionLatencyPanel = panel;
        TextView title = this.text("\u5730\u533a\u5ef6\u8fdf", 13, this.MUTED, 1);
        panel.addView((View)title, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-1, -2));
        LinearLayout rows = new LinearLayout((Context)this);
        rows.setOrientation(1);
        LinearLayout.LayoutParams rowsParams = new LinearLayout.LayoutParams(-1, -2);
        rowsParams.topMargin = this.dp(6);
        panel.addView((View)rows, (ViewGroup.LayoutParams)rowsParams);
        LatencyRow domestic = this.latencyRow(rows);
        this.domesticLatencyRow = domestic.row;
        this.domesticLatencyText = domestic.latency;
        this.domesticLatencyRegionText = domestic.region;
        LatencyRow foreign = this.latencyRow(rows);
        this.foreignLatencyRow = foreign.row;
        this.foreignLatencyText = foreign.latency;
        this.foreignLatencyRegionText = foreign.region;
        return panel;
    }

    private String currentRouteName() {
        if (this.targets.isEmpty()) {
            return "\u6682\u65e0\u7ebf\u8def";
        }
        int index = this.activeTargetIndex;
        if (index < 0 || index >= this.targets.size()) {
            index = 0;
        }
        return this.targets.get(index).displayName();
    }

    private LatencyRow latencyRow(LinearLayout parent) {
        LinearLayout row = new LinearLayout((Context)this);
        row.setOrientation(0);
        row.setGravity(48);
        row.setPadding(0, this.dp(3), 0, this.dp(3));
        row.setVisibility(8);
        parent.addView((View)row, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-1, -2));
        TextView latencyView = this.text("", 14, this.GREEN, 1);
        latencyView.setGravity(17);
        latencyView.setBackground(this.latencyBadgeBackground());
        row.addView((View)latencyView, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(this.dp(76), -2));
        TextView regionView = this.text("", 14, this.TEXT, 0);
        regionView.setSingleLine(false);
        regionView.setMaxLines(2);
        regionView.setEllipsize(TextUtils.TruncateAt.END);
        regionView.setLineSpacing(0.0f, 1.05f);
        this.enableAutoSize(regionView, 11, 14);
        LinearLayout.LayoutParams regionParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        regionParams.leftMargin = this.dp(12);
        row.addView((View)regionView, (ViewGroup.LayoutParams)regionParams);
        return new LatencyRow((View)row, latencyView, regionView);
    }

    private TextView addShowItem(GridLayout grid, String label, String value, String mark, boolean main) {
        return this.addShowItem(grid, label, value, mark, main, null, null);
    }

    private TextView addShowItem(GridLayout grid, String label, String value, String mark, boolean main, String actionText, View.OnClickListener action) {
        LinearLayout box = new LinearLayout((Context)this);
        box.setOrientation(1);
        box.setGravity(16);
        box.setBackground(this.showItemBackground());
        box.setClipToPadding(false);
        box.setClipChildren(false);
        box.setPadding(this.dp(16), this.dp(12), this.dp(16), this.dp(12));
        LinearLayout top = new LinearLayout((Context)this);
        top.setOrientation(0);
        top.setGravity(16);
        TextView labelView = this.text(label, 13, this.MUTED, 1);
        top.addView((View)labelView, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(0, -2, 1.0f));
        if (actionText != null && action != null) {
            TextView actionView = this.text(actionText, 12, this.CYAN, 1);
            actionView.setGravity(17);
            actionView.setPadding(this.dp(10), this.dp(4), this.dp(10), this.dp(4));
            actionView.setBackground(this.chipBackground());
            actionView.setOnClickListener(action);
            top.addView((View)actionView);
        } else if (mark != null) {
            TextView markView = this.text(mark, 13, main ? this.CYAN : this.MARK_MUTED, 1);
            markView.setGravity(5);
            top.addView((View)markView);
        }
        box.addView((View)top, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-1, -2));
        TextView valueView = this.text(value, main ? 26 : 23, this.TEXT, 1);
        valueView.setGravity(17);
        valueView.setSingleLine(true);
        valueView.setEllipsize(TextUtils.TruncateAt.END);
        valueView.setSelected(true);
        valueView.setHorizontallyScrolling(false);
        this.enableAutoSize(valueView, main ? 18 : 15, main ? 26 : 23);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(-1, 0, 1.0f);
        valueParams.topMargin = this.dp(2);
        box.addView((View)valueView, (ViewGroup.LayoutParams)valueParams);
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = this.dp(96);
        params.columnSpec = GridLayout.spec((int)Integer.MIN_VALUE, (float)1.0f);
        params.setMargins(this.dp(5), this.dp(7), this.dp(5), this.dp(7));
        grid.addView((View)box, (ViewGroup.LayoutParams)params);
        return valueView;
    }

    private void addSpeedShowItem(GridLayout grid, String label) {
        LinearLayout box = new LinearLayout((Context)this);
        box.setOrientation(1);
        box.setGravity(16);
        box.setBackground(this.showItemBackground());
        box.setClipToPadding(false);
        box.setClipChildren(false);
        box.setPadding(this.dp(18), this.dp(13), this.dp(18), this.dp(14));
        LinearLayout top = new LinearLayout((Context)this);
        top.setOrientation(0);
        top.setGravity(16);
        TextView labelView = this.text(label, 15, this.TEXT, 1);
        top.addView((View)labelView, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(0, -2, 1.0f));
        box.addView((View)top, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(-1, -2));
        LinearLayout valueRow = new LinearLayout((Context)this);
        valueRow.setOrientation(0);
        valueRow.setGravity(17);
        valueRow.setPadding(0, this.dp(2), 0, 0);
        this.trafficRateMbText = this.speedValue("-- MB/s");
        this.trafficRateMbpsText = this.speedValue("-- Mbps");
        valueRow.addView((View)this.trafficRateMbText, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(0, -1, 1.0f));
        View divider = new View((Context)this);
        divider.setBackgroundColor(this.DIVIDER);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(this.dp(1), this.dp(30));
        dividerParams.gravity = 16;
        valueRow.addView(divider, (ViewGroup.LayoutParams)dividerParams);
        valueRow.addView((View)this.trafficRateMbpsText, (ViewGroup.LayoutParams)new LinearLayout.LayoutParams(0, -1, 1.0f));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, 0, 1.0f);
        box.addView((View)valueRow, (ViewGroup.LayoutParams)rowParams);
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = this.dp(104);
        params.columnSpec = GridLayout.spec((int)Integer.MIN_VALUE, (float)1.0f);
        params.setMargins(this.dp(5), this.dp(7), this.dp(5), this.dp(7));
        grid.addView((View)box, (ViewGroup.LayoutParams)params);
    }

    private TextView speedValue(String value) {
        TextView valueView = this.text(value, 22, this.TEXT, 1);
        valueView.setGravity(17);
        valueView.setSingleLine(true);
        valueView.setEllipsize(TextUtils.TruncateAt.END);
        valueView.setSelected(true);
        valueView.setHorizontallyScrolling(false);
        this.enableAutoSize(valueView, 16, 22);
        return valueView;
    }

    private LinearLayout vertical() {
        LinearLayout layout2 = new LinearLayout((Context)this);
        layout2.setOrientation(1);
        return layout2;
    }

    private TextView label(String text) {
        TextView label = this.text(text, 13, this.MUTED, 1);
        label.setPadding(0, 0, 0, this.dp(6));
        return label;
    }

    private EditText input(String hint) {
        EditText edit = new EditText((Context)this);
        edit.setSingleLine(true);
        edit.setTextSize(13.0f);
        edit.setTextColor(this.TEXT);
        edit.setHintTextColor(this.HINT);
        edit.setHint((CharSequence)hint);
        edit.setInputType(16);
        edit.setImeOptions(6);
        edit.setGravity(16);
        edit.setPadding(this.dp(14), 0, this.dp(14), 0);
        edit.setIncludeFontPadding(false);
        edit.setMinHeight(0);
        edit.setMinimumHeight(0);
        edit.setBackground(this.inputBackground());
        return edit;
    }

    private Switch switchView(String text) {
        Switch sw = new Switch((Context)this);
        sw.setText((CharSequence)text);
        sw.setTextColor(this.TEXT);
        sw.setTextSize(15.0f);
        sw.setTypeface(Typeface.DEFAULT, 1);
        sw.setBackground(this.showItemBackground());
        sw.setPadding(this.dp(14), this.dp(8), this.dp(14), this.dp(8));
        return sw;
    }

    private Button actionButton(String text, boolean primary) {
        Button button = new Button((Context)this);
        button.setText((CharSequence)text);
        button.setAllCaps(false);
        button.setTextSize(14.0f);
        button.setTypeface(Typeface.DEFAULT, 1);
        button.setTextColor(primary ? this.theme.onPrimary : this.TEXT);
        button.setBackground(primary ? this.runButtonBackground(false) : this.softButtonBackground());
        this.styleFlatButton(button, 14, 11, 14);
        return button;
    }

    private TextView dialogActionButton(String value, boolean primary) {
        TextView button = this.text(value, 14, primary ? this.theme.onPrimary : this.TEXT, 1);
        button.setGravity(17);
        button.setBackground(primary ? this.runButtonBackground(false) : this.softButtonBackground());
        button.setClickable(true);
        button.setPadding(this.dp(14), 0, this.dp(14), 0);
        return button;
    }

    private Button headerButton(String text) {
        Button button = new Button((Context)this);
        button.setText((CharSequence)text);
        button.setAllCaps(false);
        button.setTextSize(11.0f);
        button.setTextColor(this.theme.primary);
        button.setTypeface(Typeface.DEFAULT, 1);
        button.setBackground(this.chipBackground());
        this.styleFlatButton(button, 8, 8, 8);
        return button;
    }

    private Button runButton(String text) {
        Button button = new Button((Context)this);
        button.setText((CharSequence)text);
        button.setAllCaps(false);
        button.setTextSize(19.0f);
        button.setTypeface(Typeface.DEFAULT, 1);
        button.setTextColor(this.theme.onPrimary);
        button.setBackground(this.runButtonBackground(false));
        this.styleFlatButton(button, 18, 14, 18);
        return button;
    }

    private void updateStartButtonState(boolean running) {
        if (this.startTrafficButton == null) {
            return;
        }
        this.startTrafficButton.setText((CharSequence)(running ? "\u6682\u505c" : "\u5f00\u59cb"));
        this.startTrafficButton.setBackground(this.runButtonBackground(running));
        this.startTrafficButton.setTextColor(running ? this.rgb("#FFFFFF") : this.theme.onPrimary);
        this.startTrafficButton.setScaleX(1.0f);
        this.startTrafficButton.setScaleY(1.0f);
        this.startTrafficButton.setAlpha(1.0f);
        if (Build.VERSION.SDK_INT >= 21) {
            this.startTrafficButton.setElevation(0.0f);
            this.startTrafficButton.setTranslationZ(0.0f);
        }
        this.styleFlatButton(this.startTrafficButton, 18, 14, 18);
    }

    private Button roundButton(String text, boolean primary) {
        Button button = new Button((Context)this);
        button.setText((CharSequence)text);
        button.setAllCaps(false);
        button.setTextSize(primary ? 26.0f : 16.0f);
        button.setTypeface(Typeface.DEFAULT, 1);
        button.setTextColor(primary ? this.theme.onPrimary : this.TEXT);
        button.setBackground(primary ? this.runButtonBackground(false) : this.softButtonBackground());
        this.styleFlatButton(button, primary ? 18 : 14, primary ? 16 : 12, primary ? 18 : 14);
        return button;
    }

    private void styleFlatButton(Button button, int leftDp, int topDp, int rightDp) {
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setGravity(17);
        button.setIncludeFontPadding(false);
        button.setPadding(this.dp(leftDp), this.dp(topDp), this.dp(rightDp), this.dp(topDp));
        if (Build.VERSION.SDK_INT >= 21) {
            button.setBackgroundTintList(null);
            button.setStateListAnimator(null);
            button.setElevation(0.0f);
            button.setTranslationZ(0.0f);
        }
    }

    private TextView text(String text, int sp, int color, int style) {
        TextView view = new TextView((Context)this);
        view.setText((CharSequence)text);
        view.setTextSize((float)sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        view.setIncludeFontPadding(true);
        return view;
    }

    private LinearLayout.LayoutParams topMargin(int dp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = this.dp(dp);
        return params;
    }

    private LinearLayout.LayoutParams centeredTopMargin(int marginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = this.dp(marginDp);
        return params;
    }

    private String clean(EditText editText) {
        return editText == null ? "" : editText.getText().toString().trim();
    }

    private int currentThreads() {
        return Math.max(1, Math.min(32, this.currentThreadValue));
    }

    private boolean isEnhancedEnabled() {
        return this.enhancedSwitch == null ? TrafficPrefs.readEnhanced((Context)this) : this.enhancedSwitch.isChecked();
    }

    private boolean isKeepAwakeEnabled() {
        return this.wakeSwitch == null ? TrafficPrefs.readKeepAwake((Context)this) : this.wakeSwitch.isChecked();
    }

    private boolean isNotificationEnabled() {
        return this.notificationSwitch == null ? this.settings.notificationEnabled : this.notificationSwitch.isChecked();
    }

    private int color(int id) {
        return Build.VERSION.SDK_INT >= 23 ? this.getColor(id) : this.getResources().getColor(id);
    }

    private void lift(View view, int dp) {
        if (Build.VERSION.SDK_INT >= 21) {
            view.setElevation((float)this.dp(dp));
            view.setTranslationZ((float)this.dp((float)dp / 4.0f));
        }
    }

    private void enableAutoSize(TextView view, int minSp, int maxSp) {
        if (Build.VERSION.SDK_INT >= 26) {
            view.setAutoSizeTextTypeUniformWithConfiguration(minSp, maxSp, 1, 2);
        }
    }

    private void tintSeekBar(SeekBar seekBar) {
        if (Build.VERSION.SDK_INT >= 21) {
            ColorStateList active = ColorStateList.valueOf((int)this.theme.primary);
            ColorStateList inactive = ColorStateList.valueOf((int)this.theme.divider);
            seekBar.setProgressTintList(active);
            seekBar.setThumbTintList(active);
            seekBar.setProgressBackgroundTintList(inactive);
        }
    }

    private int dp(float value) {
        return Math.round(value * this.getResources().getDisplayMetrics().density);
    }

    private int dp(int value) {
        return Math.round((float)value * this.getResources().getDisplayMetrics().density);
    }

    private int statusBarHeight() {
        int id = this.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? this.getResources().getDimensionPixelSize(id) : 0;
    }

    private static final class ThemePalette {
        final String shortName;
        final boolean dark;
        final int backgroundTop;
        final int backgroundBottom;
        final int surface;
        final int surfaceAlt;
        final int text;
        final int muted;
        final int hint;
        final int primary;
        final int secondary;
        final int success;
        final int danger;
        final int line;
        final int divider;
        final int markMuted;
        final int chip;
        final int successBg;
        final int successLine;
        final int heroStart;
        final int heroEnd;
        final int primaryStart;
        final int primaryEnd;
        final int activeStart;
        final int activeEnd;
        final int onPrimary;

        ThemePalette(String shortName, boolean dark, int backgroundTop, int backgroundBottom, int surface, int surfaceAlt, int text, int muted, int hint, int primary, int secondary, int success, int danger, int line, int divider, int markMuted, int chip, int successBg, int successLine, int heroStart, int heroEnd, int primaryStart, int primaryEnd, int activeStart, int activeEnd, int onPrimary) {
            this.shortName = shortName;
            this.dark = dark;
            this.backgroundTop = backgroundTop;
            this.backgroundBottom = backgroundBottom;
            this.surface = surface;
            this.surfaceAlt = surfaceAlt;
            this.text = text;
            this.muted = muted;
            this.hint = hint;
            this.primary = primary;
            this.secondary = secondary;
            this.success = success;
            this.danger = danger;
            this.line = line;
            this.divider = divider;
            this.markMuted = markMuted;
            this.chip = chip;
            this.successBg = successBg;
            this.successLine = successLine;
            this.heroStart = heroStart;
            this.heroEnd = heroEnd;
            this.primaryStart = primaryStart;
            this.primaryEnd = primaryEnd;
            this.activeStart = activeStart;
            this.activeEnd = activeEnd;
            this.onPrimary = onPrimary;
        }
    }

    private static final class LatencyResult {
        final long latencyMillis;
        final String label;

        LatencyResult(long latencyMillis, String label) {
            this.latencyMillis = latencyMillis;
            this.label = label;
        }
    }

    private static final class HttpTextResult {
        final long latencyMillis;
        final String body;

        HttpTextResult(long latencyMillis, String body) {
            this.latencyMillis = latencyMillis;
            this.body = body;
        }
    }

    private static interface ValueCallback {
        public void onValue(int var1);
    }

    private static final class LatencyRow {
        final View row;
        final TextView latency;
        final TextView region;

        LatencyRow(View row, TextView latency, TextView region) {
            this.row = row;
            this.latency = latency;
            this.region = region;
        }
    }
}
