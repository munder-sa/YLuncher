import android.Manifest;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageEventsQuery;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Standalone app_process probe; never logs event payloads or exception messages. */
public final class UsageEventsProbe {
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final int PAIRS = 3;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final long endMs;
    private long windowMs;
    private long beginMs;
    private int pair;
    // Only fixed, safe literals may be assigned here or used as failure codes.
    private String phase = "initialization";

    private UsageEventsProbe(long endMs) {
        this.endMs = endMs;
    }

    public static void main(String[] args) {
        UsageEventsProbe probe = new UsageEventsProbe(System.currentTimeMillis() - 2000L);
        int exitCode;
        try {
            exitCode = probe.run();
        } catch (ProbeFailure failure) {
            probe.printFailure(failure.code);
            exitCode = 1;
        } catch (SecurityException failure) {
            probe.printFailure("access_denied");
            exitCode = 1;
        } catch (Throwable failure) {
            // Exception text/stack traces can contain package or event data.
            probe.printFailure("exception");
            exitCode = 1;
        }
        System.out.flush();
        System.exit(exitCode);
    }

    private int run() throws Exception {
        Looper.prepareMainLooper();
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Object activityThread = activityThreadClass.getMethod("systemMain").invoke(null);
        Context context = (Context) activityThreadClass.getMethod("getSystemContext")
                .invoke(activityThread);
        if (context == null) {
            throw new ProbeFailure("context_unavailable");
        }

        phase = "permission";
        checkAccess(context);
        UsageStatsManager manager = context.getSystemService(UsageStatsManager.class);
        if (manager == null) {
            throw new ProbeFailure("usage_service_unavailable");
        }

        boolean allMatched = true;
        for (long duration : new long[] {DAY_MS, 7L * DAY_MS}) {
            windowMs = duration;
            beginMs = endMs - windowMs;
            // Both APIs use this same manager/calling package and all target packages.
            UsageEventsQuery filteredQuery = new UsageEventsQuery.Builder(beginMs, endMs)
                    .setEventTypes(UsageEvents.Event.ACTIVITY_RESUMED)
                    .build();
            for (pair = 1; pair <= PAIRS; pair++) {
                Result legacy = query(manager, null);
                Result filtered = query(manager, filteredQuery);
                boolean matched = filtered.nonResumedCount == 0L
                        && legacy.resumedCount == filtered.totalCount
                        && legacy.sha256.equals(filtered.sha256);
                phase = "comparison_output";
                printComparison(legacy, filtered, matched);
                // Keep all three pairs, including the first, even after a mismatch.
                allMatched &= matched;
            }
        }
        return allMatched ? 0 : 1;
    }

    private void checkAccess(Context context) throws Exception {
        AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        if (appOps == null) {
            throw new ProbeFailure("app_ops_unavailable");
        }
        int uid = Process.myUid();
        int mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, uid, context.getOpPackageName());
        boolean permissionGranted = context.checkPermission(
                Manifest.permission.PACKAGE_USAGE_STATS, Process.myPid(), uid)
                == PackageManager.PERMISSION_GRANTED;
        boolean allowed = mode == AppOpsManager.MODE_ALLOWED
                || (mode == AppOpsManager.MODE_DEFAULT && permissionGranted);
        System.out.println(new JSONObject()
                .put("kind", "access")
                .put("uid", uid)
                .put("app_ops_mode", mode)
                .put("permission_granted", permissionGranted)
                .put("allowed", allowed).toString());
        if (!allowed) {
            throw new ProbeFailure("access_denied");
        }
    }

    private Result query(UsageStatsManager manager, UsageEventsQuery filteredQuery)
            throws Exception {
        boolean filtered = filteredQuery != null;
        phase = filtered ? "filtered_query" : "legacy_query";
        UsageEvents events;
        long queryStartNs;
        long queryNs;
        if (filtered) {
            queryStartNs = SystemClock.elapsedRealtimeNanos();
            events = manager.queryEvents(filteredQuery);
            queryNs = SystemClock.elapsedRealtimeNanos() - queryStartNs;
        } else {
            queryStartNs = SystemClock.elapsedRealtimeNanos();
            events = manager.queryEvents(beginMs, endMs);
            queryNs = SystemClock.elapsedRealtimeNanos() - queryStartNs;
        }
        if (events == null) {
            throw new ProbeFailure("null_events");
        }

        phase = filtered ? "filtered_parse" : "legacy_parse";
        long parseStartNs = SystemClock.elapsedRealtimeNanos();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        UsageEvents.Event event = new UsageEvents.Event();
        long totalCount = 0L;
        long resumedCount = 0L;
        while (events.hasNextEvent()) {
            if (!events.getNextEvent(event)) {
                throw new ProbeFailure("event_iteration_failed");
            }
            totalCount++;
            boolean resumed = event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED;
            if (resumed) {
                resumedCount++;
            }
            if (filtered || resumed) {
                // Preserve the returned order, duplicates, and nullable field values.
                updateEvent(digest, event);
            }
        }
        String sha256 = hex(digest.digest());
        long parseNs = SystemClock.elapsedRealtimeNanos() - parseStartNs;
        return new Result(totalCount, resumedCount, sha256, queryNs, parseNs);
    }

    private void printComparison(Result legacy, Result filtered, boolean matched)
            throws Exception {
        System.out.println(new JSONObject()
                .put("kind", "comparison")
                .put("window_ms", windowMs)
                .put("begin_ms", beginMs)
                .put("end_ms", endMs)
                .put("pair", pair)
                .put("legacy_total_count", legacy.totalCount)
                .put("legacy_resumed_count", legacy.resumedCount)
                .put("filtered_count", filtered.totalCount)
                .put("filtered_non_resumed_count", filtered.nonResumedCount)
                .put("legacy_resumed_sha256", legacy.sha256)
                .put("filtered_sha256", filtered.sha256)
                .put("legacy_query_ns", legacy.queryNs)
                .put("legacy_parse_ns", legacy.parseNs)
                .put("filtered_query_ns", filtered.queryNs)
                .put("filtered_parse_ns", filtered.parseNs)
                .put("matched", matched)
                .put("status", matched ? "match" : "mismatch").toString());
    }

    private void printFailure(String code) {
        // Fixed literals and numbers only; this path does not depend on org.json.
        System.out.println("{\"kind\":\"error\",\"error\":\"" + code
                + "\",\"phase\":\"" + phase + "\",\"window_ms\":" + windowMs
                + ",\"begin_ms\":" + beginMs + ",\"end_ms\":" + endMs
                + ",\"pair\":" + pair + "}");
    }

    private static void updateEvent(MessageDigest digest, UsageEvents.Event event) {
        // Each field has a signed 32-bit big-endian byte length. Null strings use -1.
        // Fields: int32 type, UTF-8 package, UTF-8 class, int64 wall-clock timestamp.
        updateInt(digest, 4);
        updateInt(digest, event.getEventType());
        updateString(digest, event.getPackageName());
        updateString(digest, event.getClassName());
        updateInt(digest, 8);
        long timestamp = event.getTimeStamp();
        for (int shift = 56; shift >= 0; shift -= 8) {
            digest.update((byte) (timestamp >>> shift));
        }
    }

    private static void updateString(MessageDigest digest, String value) {
        if (value == null) {
            updateInt(digest, -1);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        updateInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static String hex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            result[i * 2] = HEX[value >>> 4];
            result[i * 2 + 1] = HEX[value & 0xf];
        }
        return new String(result);
    }

    private static final class Result {
        final long totalCount;
        final long resumedCount;
        final long nonResumedCount;
        final String sha256;
        final long queryNs;
        final long parseNs;

        Result(long totalCount, long resumedCount, String sha256, long queryNs, long parseNs) {
            this.totalCount = totalCount;
            this.resumedCount = resumedCount;
            this.nonResumedCount = totalCount - resumedCount;
            this.sha256 = sha256;
            this.queryNs = queryNs;
            this.parseNs = parseNs;
        }
    }

    private static final class ProbeFailure extends Exception {
        final String code;

        ProbeFailure(String code) {
            this.code = code;
        }
    }
}
