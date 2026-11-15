package local.gt8d.ipv6guard;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.util.concurrent.TimeUnit;

/** GT8D-specific bootstrap: permitted by this firmware's current SELinux policy. */
final class Bootstrap {
    private static boolean listening() {
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress(LocalAdb.SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT));
            return true;
        } catch (IOException ignored) { return false; }
    }

    static void ensure(Context context) throws Exception {
        if (listening()) return;
        // Both properties are volatile. The abstract Unix socket has no LAN listener.
        property("service.adb.listen_addrs", "localabstract:" + LocalAdb.SOCKET_NAME);
        Guard.log(context, "bootstrap: configured device-local Unix ADB socket");
        property("ctl.restart", "adbd");
        for (int attempt = 0; attempt < 8; attempt++) {
            if (listening()) {
                Guard.log(context, "bootstrap: device-local Unix ADB socket ready");
                return;
            }
            Thread.sleep(200);
        }
        throw new IOException("端末内のADB待受を起動できません");
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
