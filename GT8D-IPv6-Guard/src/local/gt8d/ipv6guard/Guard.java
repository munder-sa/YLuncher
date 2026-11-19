package local.gt8d.ipv6guard;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

final class Guard {
    private static final String ROOT = "/data/local/gt8d-ipv6-guard";
    private static final int RETRY = 8011, PERIODIC = 8012;
    private static final Object APPLY_LOCK = new Object();
    private static final Pattern RULE_PRESENT =
            Pattern.compile("^32000:\\s+from all unreachable$");

    /**
     * Read IPv6 policy rules without starting adbd. Any failure requires a one-shot retry.
     */
    static boolean rulePresentNoRoot() {
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/ip", "-6", "rule", "show").start();
            process.getOutputStream().close();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroy();
                return false;
            }
            if (process.exitValue() != 0) return false;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (RULE_PRESENT.matcher(line.trim()).matches()) return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            if (process != null) process.destroy();
        }
    }

    static Context storage(Context context) { return context.createDeviceProtectedStorageContext(); }
    static SharedPreferences preferences(Context context) {
        return storage(context).getSharedPreferences("settings", Context.MODE_PRIVATE);
    }
    static boolean enabled(Context context) { return preferences(context).getBoolean("enabled", false); }
    private static File flag(Context context) { return new File(storage(context).getFilesDir(), "enabled"); }

    static void setEnabled(Context context, boolean enabled) throws Exception {
        synchronized (APPLY_LOCK) {
            if (enabled) {
                preferences(context).edit().putBoolean("enabled", true).commit();
                schedulePeriodic(context);
            } else {
                preferences(context).edit().putBoolean("enabled", false).commit();
                if (flag(context).exists() && !flag(context).delete()) throw new Exception("停止用の設定を保存できません");
                JobScheduler jobs = context.getSystemService(JobScheduler.class);
                jobs.cancel(RETRY);
                jobs.cancel(PERIODIC);
                log(context, "automatic application disabled; current IPv6 rule retained");
            }
        }
    }

    static String apply(Context context, String reason) throws Exception {
        synchronized (APPLY_LOCK) {
            if (!enabled(context)) return "自動適用は停止中です。";
            // Stop a legacy monitor even when the rule is already present after an update.
            if (flag(context).exists() && !flag(context).delete()) {
                log(context, reason + ": could not delete legacy enabled flag");
            }
            if (rulePresentNoRoot()) {
                preferences(context).edit().putString("last_success", now()).apply();
                log(context, reason + ": rule present (adbd untouched)");
                return "設定済み：SIM側へのIPv6経路の流れ込みを防止しています。\n動作方式：ワンショット（常駐なし）\n最終確認：" + now();
            }
            Bootstrap bootstrap = Bootstrap.begin(context);
            String result;
            try {
                String command = "[ \"$(id -u)\" = 0 ] || { echo GUARD_NOT_ROOT; exit 1; }; "
                        + stopCommand() + "; rm -rf " + ROOT + "; "
                        + "/system/bin/ip -6 rule show | /system/bin/grep -Eq '^32000:[[:space:]]+from all unreachable([[:space:]]|$)' "
                        + "|| /system/bin/ip -6 rule add priority 32000 unreachable; "
                        // 有線 AA へ曲情報を送る aameta-watch(YLuncher Task 273)。置いてあり、まだ動いていなければ起動する
                        // watch が起動する logcat で判定する。[M] はこのコマンド自身(sh -c の引数にパスを含む)に一致させないため
                        + "if [ -x /data/local/tmp/aameta ] && [ -r /data/local/tmp/aameta-watch.sh ] "
                        + "&& ! /system/bin/pgrep -f 'logcat -v raw -s [M]ediaInfo:D' >/dev/null; then "
                        // 背景に回すのは単純なコマンドだけ。{ } を背景に回すと外側の sh が adb の出力を握り、LocalAdb.shell が終わらない
                        // adbd の cgroup に残ると ctl.stop adbd で init に一緒に終了させられるので、子を作る前にルートの cgroup へ移る
                        + "/system/bin/nohup /system/bin/sh -c 'echo $$ >/sys/fs/cgroup/cgroup.procs; exec /system/bin/sh /data/local/tmp/aameta-watch.sh' "
                        + "</dev/null >/dev/null 2>&1 & fi; "
                        + "echo GUARD_ONESHOT_OK";
                result = LocalAdb.shell(command);
                if (!result.contains("GUARD_ONESHOT_OK")) {
                    log(context, reason + " failed: " + result);
                    throw new Exception("自動適用を確認できません: " + result.trim());
                }
            } finally {
                bootstrap.end(context);
            }
            if (!rulePresentNoRoot()) {
                log(context, reason + ": rule missing after one-shot application");
                throw new Exception("IPv6ルールの設定を確認できません");
            }
            preferences(context).edit().putString("last_success", now()).apply();
            log(context, reason + " success: " + result.replace('\n', ' ').trim());
            return "設定済み：SIM側へのIPv6経路の流れ込みを防止しています。\n動作方式：ワンショット（常駐なし）\n最終確認：" + now();
        }
    }

    static String status(Context context) throws Exception {
        boolean ruleOn = rulePresentNoRoot();
        return "自動適用：" + (enabled(context) ? "有効" : "停止中")
                + "\nルール：" + (ruleOn ? "設定済み" : "未設定")
                + "\n動作方式：ワンショット（常駐なし）"
                + "\n最終確認：" + preferences(context).getString("last_success", "未実行");
    }

    private static String stopCommand() {
        return "if [ -r " + ROOT + "/pid ]; then p=$(cat " + ROOT + "/pid); "
                + "case $p in ''|*[!0-9]*) ;; *) "
                + "if [ -r /proc/$p/cmdline ] && tr '\\000' ' ' </proc/$p/cmdline | grep -Fq '" + ROOT + "/guard.sh'; "
                + "then kill $p; fi ;; esac; fi";
    }

    static void scheduleRetry(Context context) {
        if (!enabled(context)) return;
        JobInfo job = new JobInfo.Builder(RETRY, new ComponentName(context, RetryJob.class))
                .setMinimumLatency(15000).setOverrideDeadline(45000)
                .setBackoffCriteria(30000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build();
        int result = context.getSystemService(JobScheduler.class).schedule(job);
        log(context, "retry scheduled: " + result);
    }
    static void schedulePeriodic(Context context) {
        if (!enabled(context)) return;
        JobInfo job = new JobInfo.Builder(PERIODIC, new ComponentName(context, RetryJob.class))
                .setPeriodic(15 * 60 * 1000L).setPersisted(true)
                .setBackoffCriteria(30000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build();
        context.getSystemService(JobScheduler.class).schedule(job);
    }
    static synchronized void log(Context context, String message) {
        String line = now() + " " + message + "\n";
        Log.i("GT8DIPv6Guard", message);
        try {
            File file = new File(storage(context).getFilesDir(), "events.log");
            if (file.length() > 65536) {
                File previous = new File(file.getParentFile(), "events.previous.log");
                if (previous.exists()) previous.delete();
                file.renameTo(previous);
            }
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) { Log.e("GT8DIPv6Guard", "Cannot save log", e); }
    }
    private static String now() { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()); }
}
