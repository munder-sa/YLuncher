package local.gt8d.ipv6guard;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private TextView status;
    private Button toggle, apply, refresh;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 24, 32, 24);
        layout.setBackgroundColor(Color.rgb(24, 27, 32));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);
        TextView title = text("GT8D IPv6補助", 28);
        layout.addView(title);
        layout.addView(text("Wi-Fi通信がSIM側のIPv6経路へ流れ込むのを防ぐ設定を、自動適用します。", 18));
        status = text("状態を確認しています…", 19);
        status.setPadding(0, 28, 0, 20);
        layout.addView(status);
        toggle = button(layout, "", view -> runTask(() -> {
            boolean next = !Guard.enabled(this);
            Guard.setEnabled(this, next);
            return next ? Guard.apply(this, "user enabled") : "自動適用を停止しました。\n現在のルールは再起動まで残ります。";
        }));
        apply = button(layout, "今すぐ適用", view -> runTask(() -> Guard.apply(this, "manual apply")));
        refresh = button(layout, "状態を更新", view -> runTask(() -> Guard.status(this)));
        layout.addView(text("起動時に一度だけ設定し、常駐しません。必要なときだけ端末内のADBを一瞬使い、すぐ停止します。PCへの接続は不要です。", 15));
        updateButtons();
        runTask(() -> {
            if (!Guard.preferences(this).getBoolean("initialized", false)) {
                Guard.setEnabled(this, true);
                Guard.preferences(this).edit().putBoolean("initialized", true).commit();
            }
            return Guard.enabled(this) ? Guard.apply(this, "app opened") : Guard.status(this);
        });
    }
    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(Color.rgb(235, 239, 245));
        view.setTextSize(size);
        view.setPadding(0, 8, 0, 8);
        return view;
    }
    private Button button(LinearLayout layout, String label, View.OnClickListener listener) {
        Button view = new Button(this);
        view.setText(label);
        view.setTextSize(18);
        view.setOnClickListener(listener);
        layout.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }
    private void updateButtons() {
        toggle.setText(Guard.enabled(this) ? "自動適用を停止" : "自動適用を有効にする");
        apply.setEnabled(Guard.enabled(this));
    }
    private interface Task { String run() throws Exception; }
    private void runTask(Task task) {
        toggle.setEnabled(false);
        apply.setEnabled(false);
        refresh.setEnabled(false);
        new Thread(() -> {
            String result;
            try { result = task.run(); }
            catch (Exception e) {
                Guard.log(this, "UI attempt: " + e);
                Guard.scheduleRetry(this);
                result = "接続待ち：" + e.getMessage() + "\n自動適用が有効なら、あとで再試行します。";
            }
            final String display = result;
            runOnUiThread(() -> {
                status.setText(display);
                toggle.setEnabled(true);
                refresh.setEnabled(true);
                updateButtons();
            });
        }, "guard-ui").start();
    }
}
