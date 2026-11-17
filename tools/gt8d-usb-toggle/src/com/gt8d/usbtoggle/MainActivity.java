package com.gt8d.usbtoggle;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.UUID;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status, details, history;
    private Button toAdb, toCar, restore, refresh;
    private SharedPreferences saved;
    private String nonce, operation, requested, mode, usb;
    private boolean ready, busy, registered, changing;
    private long checkedAt;
    private final Runnable timeout = () -> {
        busy = false; ready = false; changing = false;
        status.setText("応答を確認できませんでした");
        details.setText("設定が変わった可能性があります。「状態を確認」で読み直してください。再起動は自動実行しません。"
                + "\n純正サービスが動いていない場合も、この表示になります。");
        nonce = null; updateButtons();
    };
    private final BroadcastReceiver result = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!busy || nonce == null || !nonce.equals(intent.getStringExtra("nonce"))
                    || !operation.equals(intent.getStringExtra("op"))) return;
            handler.removeCallbacks(timeout);
            String receivedOperation = operation;
            busy = false;
            mode = intent.getStringExtra("mode"); usb = intent.getStringExtra("usb");
            ready = "1000".equals(intent.getStringExtra("uid")) && ModeLogic.validMode(mode)
                    && ModeLogic.supported(intent.getStringExtra("custom"),
                    intent.getStringExtra("firmware"), intent.getStringExtra("sdk"));
            checkedAt = android.os.SystemClock.elapsedRealtime();
            int code = intent.getIntExtra("code", -1);
            Log.i("GT8DUsbToggle", "result op=" + receivedOperation + " uid=" + intent.getStringExtra("uid")
                    + " code=" + code + " mode=" + mode + " usb=" + usb + " supported=" + ready);
            showState(intent.getStringExtra("firmware"));
            if ("apply".equals(receivedOperation)) {
                if (code == 0 && ready && requested.equals(mode)) {
                    saved.edit().putString("lastApplied", requested).commit();
                    status.setText("設定を確認しました。再起動します…");
                    send("reboot", requested);
                    return;
                }
                changing = false;
                status.setText("切り替えを中止しました（" + code + "）");
                details.append(code == 12 ? "\n元の値への復元も確認できません。再起動せず状態を確認してください。"
                        : "\n設定変更または読み戻しに失敗しました。再起動していません。");
            } else if ("reboot".equals(receivedOperation)) {
                changing = false; ready = false;
                status.setText(code == 0 ? "再起動を要求しました" : "再起動できませんでした（" + code + "）");
                details.append("\n再起動しない場合は「状態を確認」からやり直してください。");
            } else changing = false;
            nonce = null; updateButtons();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        saved = getSharedPreferences("mode", MODE_PRIVATE);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(16, 25, 35));
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(20), dp(28), dp(24)); scroll.addView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            root.setPadding(dp(28) + bars.left, dp(20) + bars.top, dp(28) + bars.right, dp(24) + bars.bottom);
            return insets;
        });
        TextView title = text(root, "GT8D USB切替", 28);
        title.setTextColor(Color.rgb(102, 225, 204));
        status = text(root, "状態を確認しています…", 23);
        details = text(root, "", 16);
        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(actions);
        toAdb = button(actions, "USB ADBへ切り替え", v -> confirm("1"));
        toCar = button(actions, "車載モードへ戻す", v -> confirm("0"));
        text(root, "切り替えると端末が再起動します。USB ADBモード中は車との接続が使えません。", 16);
        refresh = button(root, "状態を確認", v -> probe());
        restore = button(root, "前回変更前の設定に戻す", v -> confirm(saved.getString("previous", "")));
        history = text(root, "", 15);
        text(root, "GT8D 5G / X21-SC162 A15専用　v0.1.0\nこのアプリを削除してもUSBモードは戻りません。", 14);
        setContentView(scroll); updateButtons();
    }
    @Override protected void onStart() {
        super.onStart();
        registerReceiver(result, new IntentFilter(ModeLogic.ACTION), Context.RECEIVER_EXPORTED);
        registered = true; probe();
    }
    @Override protected void onStop() {
        super.onStop();
        if (registered) { unregisterReceiver(result); registered = false; }
        handler.removeCallbacks(timeout); nonce = null; ready = false; busy = false; changing = false;
    }
    private void probe() { if (!busy) { ready = false; status.setText("状態を確認しています…"); send("probe", null); } }
    private void send(String op, String target) {
        operation = op; nonce = UUID.randomUUID().toString().replace("-", ""); busy = true;
        String command = "probe".equals(op) ? ModeLogic.probe(nonce)
                : "apply".equals(op) ? ModeLogic.apply(nonce, mode, target) : ModeLogic.reboot(nonce, target);
        Intent commandIntent = new Intent("com.suding.main.ACTION_EXECUTE_SH");
        commandIntent.setPackage("com.carsyso.main"); commandIntent.putExtra("cmd", command);
        try { sendBroadcast(commandIntent); handler.postDelayed(timeout, 15000); }
        catch (RuntimeException error) {
            Log.e("GT8DUsbToggle", "send failed", error); timeout.run();
        }
        updateButtons();
    }
    private void confirm(String target) {
        if (!ready || busy || !ModeLogic.validMode(target)) return;
        if (android.os.SystemClock.elapsedRealtime() - checkedAt > 30000) { probe(); return; }
        String name = "1".equals(target) ? "USB ADBモード" : "車載モード";
        new AlertDialog.Builder(this).setTitle(name + "に切り替えますか？")
                .setMessage("現在の設定を保存し、変更を確認してから再起動します。"
                        + ("1".equals(target) ? "\n車との接続は使えなくなります。" : "\nUSBのADB・scrcpy接続は切れます。"))
                .setNegativeButton("キャンセル", null).setPositiveButton("切り替えて再起動", (dialog, which) -> {
                    if (!ready || busy) return;
                    if (!saved.edit().putString("previous", mode).putString("previousUsb", usb)
                            .putLong("savedAt", System.currentTimeMillis()).commit()) {
                        status.setText("現在値を保存できませんでした。中止しました。"); return;
                    }
                    requested = target; changing = true; status.setText("設定を変更・確認しています…"); send("apply", target);
                }).show();
    }
    private void showState(String firmware) {
        status.setText(ready ? "現在：" + ModeLogic.actualMode(usb) : "この端末では切り替えできません");
        details.setText("次回起動の設定：" + ("1".equals(mode) ? "USB ADB" : "0".equals(mode) ? "車載" : "不明")
                + "\nファームウェア：" + firmware + "\n設定値：" + mode
                + (ready ? "\n純正サービスとの通信を確認しました。" : "\n対応端末・権限・設定値のいずれかを確認できません。"));
    }
    private void updateButtons() {
        if (toAdb == null) return;
        boolean active = ready && !busy && !changing;
        toAdb.setEnabled(active && !("1".equals(mode) && ModeLogic.USB_ADB.equals(usb)));
        toCar.setEnabled(active && !("0".equals(mode) && !ModeLogic.USB_ADB.equals(usb)));
        refresh.setEnabled(!busy);
        String previous = saved.getString("previous", "");
        restore.setEnabled(active && ModeLogic.validMode(previous));
        history.setText(ModeLogic.validMode(previous) ? "保存済みの変更前設定：" + ("1".equals(previous) ? "USB ADB" : "車載")
                : "変更前の設定は、最初の切り替え時に保存します。");
    }
    private TextView text(LinearLayout parent, String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setTextColor(Color.rgb(232, 239, 246)); view.setPadding(0, dp(7), 0, dp(7)); parent.addView(view); return view;
    }
    private Button button(LinearLayout parent, String value, View.OnClickListener listener) {
        Button view = new Button(this); view.setText(value); view.setTextSize(18); view.setAllCaps(false);
        view.setMinHeight(dp(58)); view.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                parent.getOrientation() == LinearLayout.HORIZONTAL ? 0 : -1, -2,
                parent.getOrientation() == LinearLayout.HORIZONTAL ? 1 : 0);
        params.setMargins(dp(3), dp(4), dp(3), dp(4)); parent.addView(view, params); return view;
    }
    private int dp(int size) { return Math.round(size * getResources().getDisplayMetrics().density); }
}
