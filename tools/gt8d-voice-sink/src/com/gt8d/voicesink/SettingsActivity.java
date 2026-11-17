package com.gt8d.voicesink;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.Date;

public final class SettingsActivity extends Activity {
    private TextView state, routes, counts;

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(16, 25, 35));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(20), dp(28), dp(24));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            root.setPadding(dp(28) + bars.left, dp(20) + bars.top, dp(28) + bars.right, dp(24) + bars.bottom);
            return insets;
        });
        scroll.addView(root);
        TextView title = text(root, "GT8D 音声キー抑止", 28);
        title.setTextColor(Color.rgb(102, 225, 204));
        state = text(root, "", 23);
        routes = text(root, "", 17);
        text(root, "Android標準の音声検索を受け取り、画面も音声認識も起動せずに終了します。\nGT8D純正のGPTLive呼び出しは、このアプリでは変更しません。", 17);
        button(root, "通常時の受け口を選ぶ", () -> choose(RecognizerIntent.ACTION_WEB_SEARCH));
        button(root, "ロック中の受け口を選ぶ", () -> choose(RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE));
        text(root, "選択画面が出たら「GT8D 音声キー抑止」を選び、「常時」を押します。\n既に別のアプリが既定の場合は、そのアプリが開くことがあります。", 16);
        button(root, "状態を更新", this::refresh);
        button(root, "このアプリの既定指定を解除", () -> new AlertDialog.Builder(this)
                .setTitle("音声キーの抑止を解除しますか？")
                .setMessage("このアプリに設定した既定の受け口だけを解除します。Googleが再び起動する場合があります。")
                .setNegativeButton("キャンセル", null)
                .setPositiveButton("解除", (dialog, which) -> {
                    getPackageManager().clearPackagePreferredActivities(getPackageName());
                    refresh();
                }).show());
        counts = text(root, "", 15);
        text(root, "v0.1.0 / Android 15用\nマイク・ネットワーク・常駐処理・root権限は使用しません。\n長押しによるGPTLiveの重複呼び出しは別の問題です。", 14);
        setContentView(scroll);
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    private boolean isSink(ResolveInfo info) {
        return info != null && info.activityInfo != null
                && new ComponentName(this, SinkActivity.class).equals(
                new ComponentName(info.activityInfo.packageName, info.activityInfo.name));
    }

    private ResolveInfo resolve(String action) {
        return getPackageManager().resolveActivity(new Intent(action), PackageManager.MATCH_DEFAULT_ONLY);
    }

    private String describe(ResolveInfo info) {
        if (isSink(info)) return "抑止アプリ";
        if (info == null || info.activityInfo == null) return "受け口なし";
        if ("android".equals(info.activityInfo.packageName)) return "未指定（選択画面）";
        return info.loadLabel(getPackageManager()) + "（" + info.activityInfo.packageName + "）";
    }

    private void refresh() {
        if (state == null) return;
        ResolveInfo normal = resolve(RecognizerIntent.ACTION_WEB_SEARCH);
        ResolveInfo locked = resolve(RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE);
        state.setText(isSink(normal) && isSink(locked) ? "標準音声検索を抑止する設定です"
                : isSink(normal) || isSink(locked) ? "一部の受け口だけ設定されています" : "既定の受け口は未設定です");
        routes.setText("通常時：" + describe(normal) + "\nロック中：" + describe(locked));
        SharedPreferences prefs = getSharedPreferences(SinkActivity.PREFS, MODE_PRIVATE);
        long last = prefs.getLong("last_received", 0);
        counts.setText("受け流した回数：通常 " + prefs.getInt(RecognizerIntent.ACTION_WEB_SEARCH, 0)
                + " ／ ロック中の経路 " + prefs.getInt(RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE, 0)
                + (last == 0 ? "" : "\n最終受信：" + DateFormat.getDateTimeInstance().format(new Date(last))));
    }

    private void choose(String action) {
        new AlertDialog.Builder(this).setTitle("受け口を選択")
                .setMessage("次の選択画面で「GT8D 音声キー抑止」を選び、「常時」を押してください。\n選択画面が出ない場合は既定の別アプリが開くことがあります。")
                .setNegativeButton("キャンセル", null)
                .setPositiveButton("選択画面を開く", (dialog, which) -> {
                    try { startActivity(new Intent(action)); }
                    catch (ActivityNotFoundException error) {
                        new AlertDialog.Builder(this).setMessage("受け口が見つかりませんでした。").setPositiveButton("閉じる", null).show();
                    }
                }).show();
    }

    private TextView text(LinearLayout parent, String value, int size) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(Color.rgb(232, 239, 246));
        view.setPadding(0, dp(7), 0, dp(7)); parent.addView(view); return view;
    }
    private void button(LinearLayout parent, String value, Runnable action) {
        Button view = new Button(this); view.setText(value); view.setTextSize(18); view.setAllCaps(false);
        view.setMinHeight(dp(56)); view.setOnClickListener(v -> action.run());
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
