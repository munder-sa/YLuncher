package com.gt8d.carprobe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
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
    private static final Pattern SERVICE_ID = Pattern.compile("  id: (\\d+)");
    private static final Pattern DISPLAY_ID = Pattern.compile("    display_id: (-?\\d+)");
    private static final Pattern DISPLAY_TYPE = Pattern.compile("    display_type: (\\S+)");

    public final boolean hasCluster;
    public final boolean hasNavStatus;
    public final List<String> displayTypes;
    public final List<String> resolutions;
    public final boolean navFocusSeen;
    public final boolean found;
    public final List<VideoSink> videoSinks;
    public final boolean hasSecondaryVideo;
    public final String navStatusEndpoint;
    public final int navStatusEvents;
    public final List<String> navStatusSamples;

    public static final class VideoSink {
        public final Integer serviceId;
        public final Integer displayId;
        public final String displayType;
        public final List<String> resolutions;

        private VideoSink(VideoService service) {
            serviceId = service.serviceId;
            displayId = service.displayId;
            displayType = service.displayType;
            resolutions = Collections.unmodifiableList(new ArrayList<>(service.resolutions));
        }
    }

    /** Indentation keeps nested video configs and other services' display IDs separate. */
    private static final class VideoService {
        private Integer serviceId;
        private Integer displayId;
        private String displayType;
        private final Set<String> resolutions = new LinkedHashSet<>();
        private boolean inMediaSink;
        private boolean video;

        private void accept(String payload) {
            Matcher id = SERVICE_ID.matcher(payload);
            if (serviceId == null && id.matches()) {
                serviceId = Integer.valueOf(id.group(1));
            }
            if (payload.equals("  media_sink_service {")) {
                inMediaSink = true;
                return;
            }
            if (payload.equals("  }")) {
                inMediaSink = false;
                return;
            }
            if (!inMediaSink) {
                return;
            }
            video |= payload.contains("codec_resolution:")
                    || payload.contains("video_configs {") || payload.contains("display_type:");
            Matcher displayIdMatch = DISPLAY_ID.matcher(payload);
            if (displayIdMatch.matches()) {
                displayId = Integer.valueOf(displayIdMatch.group(1));
            }
            Matcher displayTypeMatch = DISPLAY_TYPE.matcher(payload);
            if (displayTypeMatch.matches()) {
                displayType = displayTypeMatch.group(1);
            }
            Matcher resolution = RESOLUTION.matcher(payload);
            while (resolution.find()) {
                resolutions.add(resolution.group(1));
            }
        }
    }

    private ServiceSummary(boolean hasCluster, boolean hasNavStatus,
            Set<String> displayTypes, Set<String> resolutions, boolean navFocusSeen,
            boolean found, List<VideoSink> videoSinks, String navStatusEndpoint,
            int navStatusEvents, List<String> navStatusSamples) {
        this.hasCluster = hasCluster;
        this.hasNavStatus = hasNavStatus;
        this.displayTypes = Collections.unmodifiableList(new ArrayList<>(displayTypes));
        this.resolutions = Collections.unmodifiableList(new ArrayList<>(resolutions));
        this.navFocusSeen = navFocusSeen;
        this.found = found;
        this.videoSinks = Collections.unmodifiableList(new ArrayList<>(videoSinks));
        boolean secondary = videoSinks.size() >= 2;
        for (VideoSink sink : videoSinks) {
            secondary |= (sink.displayId != null && sink.displayId != 0)
                    || (sink.displayType != null && !sink.displayType.equals("DISPLAY_TYPE_MAIN"));
        }
        this.hasSecondaryVideo = secondary;
        this.navStatusEndpoint = navStatusEndpoint;
        this.navStatusEvents = navStatusEvents;
        this.navStatusSamples = Collections.unmodifiableList(new ArrayList<>(navStatusSamples));
    }

    public static ServiceSummary parse(String log) {
        Set<String> displays = new LinkedHashSet<>();
        Set<String> sizes = new LinkedHashSet<>();
        boolean afterRequest = false;
        boolean cluster = false;
        boolean navStatus = false;
        boolean focus = false;
        boolean services = false;
        List<VideoSink> videoSinks = new ArrayList<>();
        VideoService currentService = null;
        String navStatusEndpoint = "?";
        int navStatusEvents = 0;
        List<String> navStatusSamples = new ArrayList<>();
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
                videoSinks.clear();
                currentService = null;
                navStatusEndpoint = "?";
                navStatusEvents = 0;
                navStatusSamples.clear();
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
            if (payload.equals("services {")) {
                addVideoSink(videoSinks, currentService);
                currentService = new VideoService();
            } else if (!payload.startsWith(" ")) {
                addVideoSink(videoSinks, currentService);
                currentService = null;
            } else if (currentService != null) {
                currentService.accept(payload);
            }
            if (payload.contains("NavigationStatusEndpoint")) {
                if (payload.contains("notifyDiscoveryInfo")) {
                    navStatusEndpoint = payload.contains("service is exist") ? "exist"
                            : payload.contains("service is not found") ? "not found" : "?";
                } else if (!payload.contains("addDiscoveryInfo")
                        && (payload.contains("routeMessage") || payload.contains("handleNavigation"))) {
                    navStatusEvents++;
                    if (navStatusSamples.size() < 3) {
                        navStatusSamples.add(payload);
                    }
                }
            }
        }
        addVideoSink(videoSinks, currentService);
        return new ServiceSummary(cluster, navStatus, displays, sizes, focus, services,
                videoSinks, navStatusEndpoint, navStatusEvents, navStatusSamples);
    }

    private static void addVideoSink(List<VideoSink> sinks, VideoService service) {
        if (service == null || !service.video) {
            return;
        }
        VideoSink sink = new VideoSink(service);
        for (int i = 0; i < sinks.size(); i++) {
            VideoSink previous = sinks.get(i);
            boolean same = sink.serviceId != null ? sink.serviceId.equals(previous.serviceId)
                    : previous.serviceId == null
                    && Objects.equals(sink.displayId, previous.displayId)
                    && Objects.equals(sink.displayType, previous.displayType)
                    && sink.resolutions.equals(previous.resolutions);
            if (same) {
                // Replace the partial dump without moving its first-seen position.
                sinks.set(i, sink);
                return;
            }
        }
        sinks.add(sink);
    }

    public String toText() {
        String missing = found ? "" :
                "サービス一覧が見つかりません(ログが入れ替わった可能性。USB を抜き挿ししてやり直し)\n";
        String original = missing
                + "Cluster sink: " + yesNo(hasCluster) + "\n"
                + "Nav status service: " + yesNo(hasNavStatus) + "\n"
                + "Display types: " + displayTypes + "\n"
                + "Resolutions: " + resolutions + "\n"
                + "NAV_FOCUS: " + yesNo(navFocusSeen);
        StringBuilder text = new StringBuilder(original);
        text.append("\nVideo sinks: ").append(videoSinks.size());
        for (VideoSink sink : videoSinks) {
            text.append("\n  - id=").append(sink.serviceId == null ? "?" : sink.serviceId)
                    .append(" display_id=").append(sink.displayId == null ? "?" : sink.displayId)
                    .append(" type=").append(sink.displayType == null ? "?" : sink.displayType)
                    .append(' ').append(sink.resolutions);
        }
        text.append("\nSecondary video: ").append(yesNo(hasSecondaryVideo))
                .append("\nNav status endpoint: ").append(navStatusEndpoint)
                .append("\nNav status events: ").append(navStatusEvents);
        for (String sample : navStatusSamples) {
            text.append("\n  > ").append(sample);
        }
        return text.toString();
    }

    private static String yesNo(boolean value) {
        return value ? "YES" : "NO";
    }
}
