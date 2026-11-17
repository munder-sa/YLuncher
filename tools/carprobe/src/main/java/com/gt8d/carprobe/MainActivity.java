package com.gt8d.carprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class MainActivity extends Activity {
    private static final int COMMAND_TIMEOUT_MS = 15000;
    private static final int LOG_TIMEOUT_MS = 60000;
    private static final String FILTERED_LOG = "logcat -d -v threadtime -s SdAAReverse:V";
    private static final String LOG_TAG = "SdAAReverse: ";
    private static final Pattern FOCUS_LINE = Pattern.compile("\\bnumber=13\\b|\\bfocus_type\\b");
    private static final ExecutorService COMMANDS = Executors.newSingleThreadExecutor();

    // Initialize Android state only when posting, so the pure helpers work in JVM tests.
    private static final class MainHandler {
        private static final Handler INSTANCE = new Handler(Looper.getMainLooper());
    }

    // Process-lifetime session: recreation must not lose the log or allow a second command.
    private static WeakReference<MainActivity> current = new WeakReference<>(null);
    private static volatile boolean busy;
    private static volatile boolean rootChecked;
    private static volatile boolean rootAvailable;
    private static volatile String rootStatus = "adb: checking...";
    private static volatile String sessionPath;
    private static volatile String resultText = "Checking adb...";
    private static long lastFileTime;

    private final List<Button> buttons = new ArrayList<>();
    private Button retryButton;
    private TextView rootView;
    private TextView sessionView;
    private TextView resultView;
    private ScrollView resultScroll;
    private String displayedResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout page = column();
        page.setBackgroundColor(0xff101418);
        int padding = dp(12);
        page.setPadding(padding, padding, padding, padding);
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout());
            view.setPadding(padding + bars.left, padding + bars.top,
                    padding + bars.right, padding + bars.bottom);
            return insets;
        });

        rootView = text(18);
        sessionView = text(18);
        page.addView(rootView);
        page.addView(sessionView);

        LinearLayout controls = column();
        addRow(controls,
                button("Save Boot Log & Services", () ->
                        startOperation("Save Boot Log & Services", MainActivity::saveBootLog)),
                button("Run a0call status", () ->
                        startOperation("Run a0call status", () -> runA0("status", false))));

        LinearLayout toggles = new LinearLayout(this);
        addCell(toggles, button("toggle 800ms", () ->
                startOperation("Run a0call toggle 800", () -> runA0("toggle 800", true))));
        addCell(toggles, button("toggle 3000ms", () ->
                startOperation("Run a0call toggle 3000", () -> runA0("toggle 3000", true))));
        addRow(controls,
                button("Run a0call native", () ->
                        startOperation("Run a0call native", () -> runA0("native", true))),
                toggles);
        retryButton = new Button(this);
        retryButton.setText("Retry ADB");
        retryButton.setAllCaps(false);
        retryButton.setTextSize(22);
        retryButton.setMinHeight(dp(120));
        retryButton.setOnClickListener(view -> {
            if (canRetryAdb(rootAvailable, busy)) {
                rootStatus = "adb: checking...";
                startOperation("Checking adb", MainActivity::checkRoot);
            }
        });
        addRow(controls, retryButton,
                button("Finish & Disable ADB (Normal Mode)", this::confirmFinish));

        ScrollView controlScroll = new ScrollView(this);
        controlScroll.addView(controls);
        page.addView(controlScroll, new LinearLayout.LayoutParams(-1, 0, 3));

        resultView = text(18);
        resultView.setTypeface(Typeface.MONOSPACE);
        resultView.setTextIsSelectable(true);
        resultView.setPadding(padding, padding, padding, padding);
        resultView.setBackgroundColor(0xff1c242c);
        resultScroll = new ScrollView(this);
        resultScroll.setFillViewport(true);
        resultScroll.addView(resultView);
        page.addView(resultScroll, new LinearLayout.LayoutParams(-1, 0, 2));
        setContentView(page);
        page.requestApplyInsets();

        current = new WeakReference<>(this);
        render();
        if (!rootChecked && !busy) {
            startOperation("Checking adb", MainActivity::checkRoot);
        }
    }

    @Override
    protected void onDestroy() {
        if (current.get() == this) {
            current.clear();
        }
        super.onDestroy();
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView text(int sp) {
        TextView view = new TextView(this);
        view.setTextSize(sp);
        view.setTextColor(0xffeeeeee);
        return view;
    }

    static boolean canRunCommand(boolean rootAvailable, boolean busy) {
        return rootAvailable && !busy;
    }

    static boolean canRetryAdb(boolean rootAvailable, boolean busy) {
        return !rootAvailable && !busy;
    }

    private Button button(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(22);
        button.setMinHeight(dp(120));
        button.setOnClickListener(view -> {
            if (canRunCommand(rootAvailable, busy)) {
                action.run();
            }
        });
        buttons.add(button);
        return button;
    }

    private void addRow(LinearLayout parent, View... cells) {
        LinearLayout row = new LinearLayout(this);
        for (View cell : cells) {
            addCell(row, cell);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(128));
        params.topMargin = dp(4);
        parent.addView(row, params);
    }

    private void addCell(LinearLayout row, View view) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
        params.setMargins(dp(2), 0, dp(2), 0);
        row.addView(view, params);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void render() {
        rootView.setText(rootStatus);
        sessionView.setText("Session: " + (sessionPath == null ? "(not saved yet)" : sessionPath));
        if (!resultText.equals(displayedResult)) {
            displayedResult = resultText;
            resultView.setText(displayedResult);
            resultScroll.scrollTo(0, 0);
        }
        for (Button button : buttons) {
            button.setEnabled(canRunCommand(rootAvailable, busy));
        }
        if (retryButton != null) {
            retryButton.setEnabled(canRetryAdb(rootAvailable, busy));
        }
    }

    private static void refresh() {
        MainActivity activity = current.get();
        if (activity != null) {
            activity.render();
        }
    }

    private static void show(String text) {
        resultText = text;
        MainHandler.INSTANCE.post(MainActivity::refresh);
    }

    private void startOperation(String label, Operation operation) {
        if (busy) {
            return;
        }
        busy = true;
        resultText = label + "\nRunning...";
        render();
        COMMANDS.execute(() -> {
            try {
                operation.run();
            } catch (Exception e) {
                show(resultText + "\nFailed: " + e);
            } finally {
                MainHandler.INSTANCE.post(() -> {
                    busy = false;
                    refresh();
                });
            }
        });
    }

    private interface Operation {
        void run() throws Exception;
    }

    private static void checkRoot() {
        AdbClient.Result result = AdbClient.run("id", COMMAND_TIMEOUT_MS);
        rootAvailable = result.ok && result.stdout.contains("uid=0");
        rootChecked = true;
        String reason = result.stdout.trim().replace('\n', ' ');
        if (result.ok && !rootAvailable) {
            reason = "uid=0 not found: " + reason;
        }
        rootStatus = rootAvailable ? "adb: OK (uid=0 @localabstract:gt8d-ipv6-guard)"
                : "adb: NO (" + reason + ")";
        show(describe("id", result));
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(new Date());
    }

    private static void newSession(String header) throws IOException {
        for (int attempt = 0; attempt < 10; attempt++) {
            lastFileTime = Math.max(System.currentTimeMillis(), lastFileTime + 1000);
            String name = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date(lastFileTime));
            String path = "/sdcard/Download/rav4_probe_" + name + ".log";
            String quoted = AdbClient.quote(path);
            // Keep the outer shell alive so AdbClient can collect the collision exit code.
            AdbClient.Result result = AdbClient.run("mkdir -p /sdcard/Download && "
                    + "if [ -e " + quoted + " ]; then (exit 73); else "
                    + "(set -C; printf '%s\\n' " + AdbClient.quote(header) + " > " + quoted
                    + "); fi", COMMAND_TIMEOUT_MS);
            if (result.ok) {
                sessionPath = path;
                MainHandler.INSTANCE.post(MainActivity::refresh);
                return;
            }
            if (result.exitCode != 73) {
                throw new IOException(describe("Create " + path, result));
            }
        }
        throw new IOException("Log filename collision (10 attempts)");
    }

    private static void ensureSession() throws IOException {
        if (sessionPath == null) {
            newSession("RAV4 Probe session\nCreated: " + timestamp());
        }
    }

    private static AdbClient.Result appendLog(String label, String text) {
        String entry = "\n=== " + timestamp() + " | " + label + " ===\n" + text + "\n";
        return AdbClient.run("printf '%s\\n' " + AdbClient.quote(entry) + " >> "
                + AdbClient.quote(sessionPath), COMMAND_TIMEOUT_MS);
    }

    private static String describe(String label, AdbClient.Result result) {
        return label + ": " + (result.ok ? "OK" : "FAILED")
                + " (exit=" + result.exitCode + ", via " + result.via + ")\n"
                + result.stdout.trim();
    }

    private static void saveBootLog() throws IOException {
        AdbClient.Result filtered = AdbClient.run(FILTERED_LOG, LOG_TIMEOUT_MS);
        ServiceSummary summary = ServiceSummary.parse(filtered.ok ? filtered.stdout : "");
        String header = summary.toText() + "\nSaved: " + timestamp();
        if (!filtered.ok) {
            header += "\n" + describe("Summary log", filtered);
        }
        newSession(header + "\n\n=== Full logcat (-b all) ===");
        // The full log never travels through the app's stdout or heap.
        AdbClient.Result saved = AdbClient.run("logcat -d -v threadtime -b all >> "
                + AdbClient.quote(sessionPath), LOG_TIMEOUT_MS);
        AdbClient.Result size = AdbClient.run("wc -c < " + AdbClient.quote(sessionPath),
                COMMAND_TIMEOUT_MS);
        String text = "Save: " + (filtered.ok && saved.ok && size.ok ? "OK" : "FAILED")
                + "\n" + sessionPath + "\nSize: "
                + (size.ok ? size.stdout.trim() + " bytes" : "unknown")
                + "\n\n" + header;
        if (!saved.ok) {
            text += "\n" + describe("Full log (file may be incomplete)", saved);
        }
        if (!size.ok) {
            text += "\n" + describe("File size", size);
        }
        show(text);
    }

    private static void runA0(String arguments, boolean includeFocus) throws IOException {
        ensureSession();
        String command = "/data/local/tmp/a0call " + arguments;
        String started = timestamp();
        AdbClient.Result run = AdbClient.run(command, COMMAND_TIMEOUT_MS);
        String text = started + "\n" + describe(command, run);
        if (includeFocus) {
            AdbClient.Result log = AdbClient.run(FILTERED_LOG, LOG_TIMEOUT_MS);
            text += "\n\nLatest NAV_FOCUS log (may include earlier actions):\n";
            text += log.ok ? lastFocusLines(log.stdout) : describe("Focus log", log);
        }
        AdbClient.Result appended = appendLog("a0call " + arguments, text);
        show(text + "\n\nSession: " + sessionPath + "\n"
                + describe("Append log", appended));
    }

    private static String lastFocusLines(String log) {
        ArrayDeque<String> lines = new ArrayDeque<>();
        for (String line : log.split("\\r?\\n")) {
            int tag = line.indexOf(LOG_TAG);
            if (tag >= 0 && FOCUS_LINE.matcher(line.substring(tag + LOG_TAG.length())).find()) {
                if (lines.size() == 6) {
                    lines.removeFirst();
                }
                lines.addLast(line);
            }
        }
        return lines.isEmpty() ? "number=13 / focus_type の行が見つかりません" :
                String.join("\n", lines);
    }

    private void confirmFinish() {
        new AlertDialog.Builder(this)
                .setMessage("ADB を OFF にして通常の車載モードへ戻します。よろしいですか")
                .setPositiveButton("OK", (dialog, which) ->
                        startOperation("Finish & Disable ADB", MainActivity::finishAdb))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void finishAdb() throws Exception {
        ensureSession();
        String notice = "注意: GT8D-IPv6-Guard の自動適用が有効な場合、その起動時・15分周期ジョブ・アプリ起動時に adbd と gt8d-ipv6-guard ソケットが再起動され、ADB は再び有効になります。完全に止めるには GT8D-IPv6-Guard で「自動適用を停止」してください。";
        StringBuilder text = new StringBuilder("Finish & Disable ADB\nSession: ")
                .append(sessionPath).append('\n');
        // (1) Root-only settings go through the adbd shell. Confirmed on this device: neither
        // adb_wifi_enabled=0 nor adb_enabled=0 stops adbd or closes @gt8d-ipv6-guard, so the
        // transport stays alive for this step (one round-trip).
        String settingsCommand = "settings put global adb_wifi_enabled 0; "
                + "settings put global adb_enabled 0";
        showBeforeAdbCommand(text + "\n" + settingsCommand + ": RUNNING");
        AdbClient.Result settings = AdbClient.run(settingsCommand, COMMAND_TIMEOUT_MS);
        text.append('\n').append(describe(settingsCommand, settings)).append('\n');
        appendLog("Finish settings", describe(settingsCommand, settings) + "\n" + notice);
        showBeforeAdbCommand(text.toString());
        // (2) setprop is callable directly from the app (same as Bootstrap). Run these app-side,
        // NOT through the adbd shell, so tearing down adbd never surfaces as an EOFException.
        // Clear the transport first, then stop the daemon.
        AdbClient.Result cleared = AdbClient.setPropertyLocal("service.adb.listen_addrs", "");
        AdbClient.Result stopped = AdbClient.setPropertyLocal("ctl.stop", "adbd");
        rootAvailable = false;
        rootStatus = "adb: NO (停止済み)";
        boolean ok = settings.ok && cleared.ok && stopped.ok;
        text.append('\n').append(describe("setprop service.adb.listen_addrs \"\" (app)", cleared))
                .append('\n').append(describe("setprop ctl.stop adbd (app)", stopped)).append('\n')
                .append('\n').append(ok ? "ADB OFF 完了。通常モードです。"
                        : "ADB OFF に一部失敗があります。応答を確認してください。")
                .append('\n').append(notice);
        show(text.toString());
    }

    private static void showBeforeAdbCommand(String text) throws Exception {
        CountDownLatch applied = new CountDownLatch(1);
        MainHandler.INSTANCE.post(() -> {
            MainActivity activity = current.get();
            resultText = text;
            if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                activity.render();
                applied.countDown();
            }
        });
        if (!applied.await(5, TimeUnit.SECONDS)) {
            throw new IOException("画面に結果を反映できないため、残りの ADB 操作を中止しました。");
        }
    }
}
