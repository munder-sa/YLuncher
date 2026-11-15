package com.gt8d.carprobe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses only SdAAReverse payloads from the latest discovery exchange. */
public final class ServiceSummary {
    private static final String TAG = "SdAAReverse: ";
    private static final Pattern DISPLAY = Pattern.compile("\\bDISPLAY_TYPE_[A-Z0-9_]+\\b");
    private static final Pattern CLUSTER = Pattern.compile(
            "\\bdisplay_type\\s*:\\s*DISPLAY_TYPE_CLUSTER\\b");
    private static final Pattern RESOLUTION = Pattern.compile(
            "\\bcodec_resolution\\s*:\\s*([A-Za-z0-9_]+)");
    private static final Pattern SERVICES = Pattern.compile("\\bservices\\s*\\{");

    public final boolean hasCluster;
    public final boolean hasNavStatus;
    public final List<String> displayTypes;
    public final List<String> resolutions;
    public final boolean navFocusSeen;
    public final boolean found;

    private ServiceSummary(boolean hasCluster, boolean hasNavStatus,
            Set<String> displayTypes, Set<String> resolutions, boolean navFocusSeen,
            boolean found) {
        this.hasCluster = hasCluster;
        this.hasNavStatus = hasNavStatus;
        this.displayTypes = Collections.unmodifiableList(new ArrayList<>(displayTypes));
        this.resolutions = Collections.unmodifiableList(new ArrayList<>(resolutions));
        this.navFocusSeen = navFocusSeen;
        this.found = found;
    }

    public static ServiceSummary parse(String log) {
        Set<String> displays = new LinkedHashSet<>();
        Set<String> sizes = new LinkedHashSet<>();
        boolean afterRequest = false;
        boolean cluster = false;
        boolean navStatus = false;
        boolean focus = false;
        boolean services = false;
        for (String line : log.split("\\r?\\n")) {
            int tag = line.indexOf(TAG);
            if (tag < 0) {
                continue;
            }
            String payload = line.substring(tag + TAG.length());
            if (payload.contains("MESSAGE_SERVICE_DISCOVERY_REQUEST")) {
                afterRequest = true;
                cluster = false;
                navStatus = false;
                focus = false;
                services = false;
                displays.clear();
                sizes.clear();
                continue;
            }
            if (!afterRequest) {
                continue;
            }
            services |= SERVICES.matcher(payload).find();
            cluster |= CLUSTER.matcher(payload).find();
            navStatus |= payload.contains("navigation_status_service");
            focus |= payload.contains("NAV_FOCUS");
            Matcher display = DISPLAY.matcher(payload);
            while (display.find()) {
                displays.add(display.group());
            }
            Matcher resolution = RESOLUTION.matcher(payload);
            while (resolution.find()) {
                sizes.add(resolution.group(1));
            }
        }
        return new ServiceSummary(cluster, navStatus, displays, sizes, focus, services);
    }

    public String toText() {
        String missing = found ? "" :
                "サービス一覧が見つかりません(ログが入れ替わった可能性。USB を抜き挿ししてやり直し)\n";
        return missing
                + "Cluster sink: " + yesNo(hasCluster) + "\n"
                + "Nav status service: " + yesNo(hasNavStatus) + "\n"
                + "Display types: " + displayTypes + "\n"
                + "Resolutions: " + resolutions + "\n"
                + "NAV_FOCUS: " + yesNo(navFocusSeen);
    }

    private static String yesNo(boolean value) {
        return value ? "YES" : "NO";
    }
}
