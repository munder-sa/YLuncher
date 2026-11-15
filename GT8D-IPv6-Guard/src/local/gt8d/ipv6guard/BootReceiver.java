package local.gt8d.ipv6guard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        Guard.log(context, "receiver: " + intent.getAction());
        if (!Guard.enabled(context)) return;
        Guard.scheduleRetry(context);
        Guard.schedulePeriodic(context);
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try { Guard.apply(context, intent.getAction()); }
            catch (Exception e) { Guard.log(context, "boot attempt: " + e); }
            finally { pending.finish(); }
        }, "guard-boot").start();
    }
}
