package local.gt8d.ipv6guard;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class Guard {
    private static final String ROOT = "/data/local/gt8d-ipv6-guard";
    private static final int RETRY = 8011, PERIODIC = 8012;
    private static final Object APPLY_LOCK = new Object();

    static Context storage(Context context) { return context.createDeviceProtectedStorageContext(); }
    static SharedPreferences preferences(Context context) {
        return storage(context).getSharedPreferences("settings", Context.MODE_PRIVATE);
    }
    static boolean enabled(Context context) { return preferences(context).getBoolean("enabled", false); }
    private static File flag(Context context) { return new File(storage(context).getFilesDir(), "enabled"); }

    static void setEnabled(Context context, boolean enabled) throws Exception {
        synchronized (APPLY_LOCK) {
            if (enabled) {
                try (FileOutputStream out = new FileOutputStream(flag(context))) { out.write(1); }
                preferences(context).edit().putBoolean("enabled", true).commit();
                schedulePeriodic(context);
            } else {
                preferences(context).edit().putBoolean("enabled", false).commit();
                if (flag(context).exists() && !flag(context).delete()) throw new Exception("停止用の設定を保存できません");
                JobScheduler jobs = context.getSystemService(JobScheduler.class);
                jobs.cancel(RETRY);
                jobs.cancel(PERIODIC);
                // The flag also stops the root process if this ADB request cannot run.
                try { LocalAdb.shell(stopCommand()); } catch (Exception e) { log(context, "stop: " + e); }
                log(context, "automatic application disabled; current IPv6 rule retained");
            }
        }
    }

    static String apply(Context context, String reason) throws Exception {
        synchronized (APPLY_LOCK) {
            if (!enabled(context)) return "自動適用は停止中です。";
            Bootstrap.ensure(context);
            String script = script(context);
            String encoded = Base64.encodeToString(script.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            String command = "[ \"$(id -u)\" = 0 ] || { echo GUARD_NOT_ROOT; exit 1; }; "
                    + "umask 077; mkdir -p " + quote(ROOT) + " || exit 1; "
                    + "printf %s " + quote(encoded) + " | /system/bin/base64 -d > " + ROOT + "/guard.sh.new || exit 1; "
                    + "chmod 700 " + ROOT + "/guard.sh.new && mv " + ROOT + "/guard.sh.new " + ROOT + "/guard.sh || exit 1; "
                    + "/system/bin/nohup /system/bin/sh " + ROOT + "/guard.sh </dev/null >>" + ROOT + "/guard.log 2>&1 & "
                    + "sleep 0.3; " + statusCommand();
            String result = LocalAdb.shell(command);
            if (!result.contains("GUARD_ROOT=0") || !result.contains("GUARD_RUNNING=yes")
                    || !result.contains("from all unreachable")) {
                log(context, reason + " failed: " + result);
                throw new Exception("自動適用を確認できません: " + result.trim());
            }
            log(context, reason + " success: " + result.replace('\n', ' ').trim());
            preferences(context).edit().putString("last_success", now()).apply();
            return "設定済み：SIM側へのIPv6経路の流れ込みを防止しています。\n監視：動作中\n最終確認：" + now();
        }
    }

    static String status(Context context) throws Exception {
        String result = LocalAdb.shell(statusCommand());
        return "自動適用：" + (enabled(context) ? "有効" : "停止中")
                + "\nルール：" + (result.contains("from all unreachable") ? "設定済み" : "未設定")
                + "\n監視：" + (result.contains("GUARD_RUNNING=yes") ? "動作中" : "停止中")
                + "\n最終適用：" + preferences(context).getString("last_success", "未実行");
    }

    private static String statusCommand() {
        return "echo GUARD_ROOT=$(id -u); "
                + "/system/bin/ip -6 rule show | /system/bin/grep -E '^32000:[[:space:]]+from all unreachable([[:space:]]|$)'; "
                + "if [ -r " + ROOT + "/pid ]; then p=$(cat " + ROOT + "/pid); "
                + "case $p in ''|*[!0-9]*) ;; *) "
                + "if [ -r /proc/$p/cmdline ] && tr '\\000' ' ' </proc/$p/cmdline | grep -Fq '" + ROOT + "/guard.sh'; "
                + "then echo GUARD_RUNNING=yes; fi ;; esac; fi";
    }
    private static String stopCommand() {
        return "if [ -r " + ROOT + "/pid ]; then p=$(cat " + ROOT + "/pid); "
                + "case $p in ''|*[!0-9]*) ;; *) "
                + "if [ -r /proc/$p/cmdline ] && tr '\\000' ' ' </proc/$p/cmdline | grep -Fq '" + ROOT + "/guard.sh'; "
                + "then kill $p; fi ;; esac; fi";
    }
    private static String script(Context context) {
        return "#!/system/bin/sh\n"
                + "ROOT=" + quote(ROOT) + "\n"
                + "FLAG=" + quote(flag(context).getAbsolutePath()) + "\n"
                + "exec 0<>\"$ROOT/run.lock\"\n"
                + "/system/bin/flock -n 0 || exit 0\n"
                + "echo $$ >\"$ROOT/pid\"\n"
                + "trap 'rm -f \"$ROOT/pid\"' EXIT\n"
                + "trap 'exit 0' HUP INT TERM\n"
                + "echo \"$(date -Iseconds) started pid=$$ boot=$(cat /proc/sys/kernel/random/boot_id)\"\n"
                + "while [ -f \"$FLAG\" ]; do\n"
                + "  if ! /system/bin/ip -6 rule show | /system/bin/grep -Eq '^32000:[[:space:]]+from all unreachable([[:space:]]|$)'; then\n"
                + "    if /system/bin/ip -6 rule add priority 32000 unreachable; then\n"
                + "      echo \"$(date -Iseconds) restored IPv6 rule 32000\"\n"
                + "    else\n"
                + "      echo \"$(date -Iseconds) failed to restore IPv6 rule\"\n"
                + "    fi\n"
                + "  fi\n"
                + "  sleep 10\n"
                + "done\n"
                + "echo \"$(date -Iseconds) stopped\"\n";
    }
    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

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
