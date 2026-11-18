package local.gt8d.ipv6guard;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.provider.Settings;
import java.util.concurrent.TimeUnit;

/** GT8D-specific bootstrap: permitted by this firmware's current SELinux policy. */
final class Bootstrap {
    private boolean startedByUs;
    private boolean wifiDebugWasOn;

    static boolean listening() {
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress(LocalAdb.SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT));
            return true;
        } catch (IOException ignored) { return false; }
    }

    static Bootstrap begin(Context context) throws Exception {
        Bootstrap bootstrap = new Bootstrap();
        if (listening()) {
            Guard.log(context, "bootstrap: adbd was already listening; left untouched");
            return bootstrap;
        }
        bootstrap.wifiDebugWasOn =
                Settings.Global.getInt(context.getContentResolver(), "adb_wifi_enabled", 0) == 1;
        bootstrap.startedByUs = true;
        try {
            // Both properties are volatile. The abstract Unix socket has no LAN listener.
            property("service.adb.listen_addrs", "localabstract:" + LocalAdb.SOCKET_NAME);
            Guard.log(context, "bootstrap: configured device-local Unix ADB socket");
            property("ctl.restart", "adbd");
            Guard.log(context, "bootstrap: adbd restarted for device-local Unix ADB socket");
            for (int attempt = 0; attempt < 15; attempt++) {
                Thread.sleep(200);
                if (listening()) {
                    Guard.log(context, "bootstrap: device-local Unix ADB socket ready");
                    return bootstrap;
                }
            }
            throw new IOException("端末内のADB待受を起動できません");
        } catch (Exception e) {
            bootstrap.end(context);
            throw e;
        }
    }

    void end(Context context) {
        if (!startedByUs) return;
        try {
            property("service.adb.listen_addrs", "");
            Guard.log(context, "bootstrap: listen_addrs cleared");
        } catch (Exception e) {
            Guard.log(context, "bootstrap: could not clear listen_addrs: " + e);
        }
        // Attempt the service cleanup even if clearing the socket property failed.
        try {
            if (wifiDebugWasOn) {
                property("ctl.restart", "adbd");
                Guard.log(context, "bootstrap: adbd restarted for wireless debugging");
            } else {
                property("ctl.stop", "adbd");
                Guard.log(context, "bootstrap: adbd stopped");
            }
        } catch (Exception e) {
            Guard.log(context, "bootstrap: could not " + (wifiDebugWasOn ? "restart" : "stop") + " adbd: " + e);
        }
    }

    private static void property(String key, String value) throws Exception {
        Process process = new ProcessBuilder("/system/bin/setprop", key, value)
                .redirectErrorStream(true).start();
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroy();
            throw new IOException("起動設定の変更がタイムアウトしました: " + key);
        }
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[512];
        int length;
        while ((length = process.getInputStream().read(buffer)) >= 0) result.write(buffer, 0, length);
        if (process.exitValue() != 0) throw new IOException(key + ": " + result.toString("UTF-8").trim());
    }
}
