package com.gt8d.carprobe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class ServiceSummaryTest {
    private static String log(String... payloads) {
        StringBuilder result = new StringBuilder();
        for (String payload : payloads) {
            result.append("11-15 15:15:09.127  1843  3543 D SdAAReverse: ")
                    .append(payload).append('\n');
        }
        return result.toString();
    }

    @Test
    public void mainOnlyList() {
        ServiceSummary summary = ServiceSummary.parse(log(
                "number=5 name=MESSAGE_SERVICE_DISCOVERY_REQUEST",
                "services {",
                "  media_sink_service {",
                "    display_type: DISPLAY_TYPE_MAIN",
                "    codec_resolution: VIDEO_1920x1080",
                "  }", "}",
                "services {", "  navigation_status_service {", "  }", "}",
                // The repeated full dump must not duplicate values.
                "services {",
                "    display_type: DISPLAY_TYPE_MAIN",
                "    codec_resolution: VIDEO_1920x1080", "}"));
        assertTrue(summary.found);
        assertFalse(summary.hasCluster);
        assertTrue(summary.hasNavStatus);
        assertEquals(Collections.singletonList("DISPLAY_TYPE_MAIN"), summary.displayTypes);
        assertEquals(Collections.singletonList("VIDEO_1920x1080"), summary.resolutions);
        assertFalse(summary.navFocusSeen);
        assertTrue(summary.toText().contains("Cluster sink: NO"));
        assertTrue(summary.toText().contains("Nav status service: YES"));
    }

    @Test
    public void clusterList() {
        ServiceSummary summary = ServiceSummary.parse(log(
                "number=5 name=MESSAGE_SERVICE_DISCOVERY_REQUEST",
                "services {",
                "    display_type: DISPLAY_TYPE_MAIN",
                "    codec_resolution: VIDEO_1920x1080", "}",
                "services {",
                "    display_type: DISPLAY_TYPE_CLUSTER",
                "    codec_resolution: VIDEO_800x480", "}",
                "    display_type: DISPLAY_TYPE_CLUSTER",
                "    codec_resolution: VIDEO_800x480",
                "number=13 name=MESSAGE_NAV_FOCUS_REQUEST",
                "    focus_type: NAV_FOCUS_NATIVE"));
        assertTrue(summary.found);
        assertTrue(summary.hasCluster);
        assertFalse(summary.hasNavStatus);
        assertEquals(Arrays.asList("DISPLAY_TYPE_MAIN", "DISPLAY_TYPE_CLUSTER"),
                summary.displayTypes);
        assertEquals(Arrays.asList("VIDEO_1920x1080", "VIDEO_800x480"), summary.resolutions);
        assertTrue(summary.navFocusSeen);
        assertTrue(summary.toText().contains("Cluster sink: YES"));
        assertTrue(summary.toText().contains("NAV_FOCUS: YES"));
    }

    @Test
    public void onlyLastDiscoveryList() {
        ServiceSummary summary = ServiceSummary.parse(log(
                "number=5 name=MESSAGE_SERVICE_DISCOVERY_REQUEST",
                "services {",
                "    display_type: DISPLAY_TYPE_CLUSTER",
                "    codec_resolution: VIDEO_800x480",
                "  navigation_status_service {", "  }", "}",
                "    focus_type: NAV_FOCUS_NATIVE",
                "number=5 name=MESSAGE_SERVICE_DISCOVERY_REQUEST",
                "services {",
                "    display_type: DISPLAY_TYPE_MAIN",
                "    codec_resolution: VIDEO_1920x1080", "}")
                + "11-15 15:15:09.128  1843  3543 D OtherTag: "
                + "MESSAGE_SERVICE_DISCOVERY_REQUEST display_type: DISPLAY_TYPE_CLUSTER\n"
                + "    display_type: DISPLAY_TYPE_CLUSTER\n");
        assertTrue(summary.found);
        assertFalse(summary.hasCluster);
        assertFalse(summary.hasNavStatus);
        assertFalse(summary.navFocusSeen);
        assertEquals(Collections.singletonList("DISPLAY_TYPE_MAIN"), summary.displayTypes);
        assertEquals(Collections.singletonList("VIDEO_1920x1080"), summary.resolutions);
    }

    @Test
    public void missingDiscoveryRequest() {
        ServiceSummary summary = ServiceSummary.parse(log(
                "services {",
                "    display_type: DISPLAY_TYPE_CLUSTER",
                "    codec_resolution: VIDEO_800x480",
                "  navigation_status_service {", "  }", "}",
                "    focus_type: NAV_FOCUS_NATIVE"));
        assertFalse(summary.found);
        assertFalse(summary.hasCluster);
        assertFalse(summary.hasNavStatus);
        assertFalse(summary.navFocusSeen);
        assertEquals(Collections.emptyList(), summary.displayTypes);
        assertEquals(Collections.emptyList(), summary.resolutions);
        assertTrue(summary.toText().contains(
                "サービス一覧が見つかりません(ログが入れ替わった可能性。USB を抜き挿ししてやり直し)"));
        assertFalse(ServiceSummary.parse("").found);
        // A new request without its response must not reuse an older list.
        assertFalse(ServiceSummary.parse(log(
                "MESSAGE_SERVICE_DISCOVERY_REQUEST", "services {", "}",
                "MESSAGE_SERVICE_DISCOVERY_REQUEST")).found);
    }

    private String dhuLog() throws IOException {
        InputStream stream = getClass().getResourceAsStream("/sdr_dhu_20261115.log");
        assertNotNull("DHU log resource", stream);
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            StringBuilder result = new StringBuilder();
            char[] buffer = new char[1024];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                result.append(buffer, 0, count);
            }
            return result.toString();
        }
    }

    @Test
    public void dhuRealLogCountsOneVideoSink() throws IOException {
        ServiceSummary summary = ServiceSummary.parse(dhuLog());
        assertTrue(summary.found);
        assertEquals(1, summary.videoSinks.size());
        ServiceSummary.VideoSink sink = summary.videoSinks.get(0);
        assertEquals(Integer.valueOf(2), sink.serviceId);
        assertEquals(Integer.valueOf(0), sink.displayId);
        assertEquals("DISPLAY_TYPE_MAIN", sink.displayType);
        assertEquals(Collections.singletonList("VIDEO_1920x1080"), sink.resolutions);
        assertFalse(summary.hasSecondaryVideo);
        assertFalse(summary.hasCluster);
        assertTrue(summary.hasNavStatus);
        assertEquals("exist", summary.navStatusEndpoint);
        assertEquals(0, summary.navStatusEvents);
        assertEquals(Collections.emptyList(), summary.navStatusSamples);
        assertEquals("Cluster sink: NO\n"
                + "Nav status service: YES\n"
                + "Display types: [DISPLAY_TYPE_MAIN]\n"
                + "Resolutions: [VIDEO_1920x1080]\n"
                + "NAV_FOCUS: NO\n"
                + "Video sinks: 1\n"
                + "  - id=2 display_id=0 type=DISPLAY_TYPE_MAIN [VIDEO_1920x1080]\n"
                + "Secondary video: NO\n"
                + "Nav status endpoint: exist\n"
                + "Nav status events: 0", summary.toText());
    }

    @Test
    public void secondaryVideoWithoutDisplayType() {
        ServiceSummary summary = ServiceSummary.parse(log(
                "MESSAGE_SERVICE_DISCOVERY_REQUEST",
                "services {", "  id: 2", "  media_sink_service {",
                "    video_configs {", "      codec_resolution: VIDEO_1920x1080", "    }",
                "    display_id: 0", "    display_type: DISPLAY_TYPE_MAIN", "  }", "}",
                "services {", "  id: 10", "  media_sink_service {",
                "    video_configs {", "      codec_resolution: VIDEO_800x480", "    }",
                "    display_id: 1", "  }", "}"));
        assertEquals(2, summary.videoSinks.size());
        ServiceSummary.VideoSink sink = summary.videoSinks.get(1);
        assertEquals(Integer.valueOf(10), sink.serviceId);
        assertEquals(Integer.valueOf(1), sink.displayId);
        assertNull(sink.displayType);
        assertEquals(Collections.singletonList("VIDEO_800x480"), sink.resolutions);
        assertTrue(summary.hasSecondaryVideo);
        assertFalse(summary.hasCluster);
        assertTrue(summary.toText().contains("Secondary video: YES"));
        assertTrue(summary.toText().contains("type=?"));
    }

    @Test
    public void clusterDisplayTypeIsSecondary() {
        ServiceSummary summary = ServiceSummary.parse(log(
                "MESSAGE_SERVICE_DISCOVERY_REQUEST",
                "services {", "  id: 11", "  media_sink_service {",
                "    display_id: 0", "    display_type: DISPLAY_TYPE_CLUSTER", "  }", "}"));
        assertEquals(1, summary.videoSinks.size());
        assertTrue(summary.hasSecondaryVideo);
        assertTrue(summary.hasCluster);
    }

    @Test
    public void navStatusEventsIgnoreInputType32769() throws IOException {
        StringBuilder input = new StringBuilder(dhuLog());
        for (int i = 0; i < 5; i++) {
            input.append(log("virtual int InputSource::routeMessage(uint8_t, uint16_t, "
                    + "const shared_ptr<IoBuffer> &):237 ret:0 channelId:3 type:32769"));
        }
        List<String> events = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String event = "NavigationStatusEndpoint::virtual int NavigationStatusEndpoint::"
                    + "routeMessage(uint8_t, uint16_t, const shared_ptr<IoBuffer> &):100 "
                    + "channelId:6 type:32769 event=" + i;
            events.add(event);
            input.append(log(event));
        }
        ServiceSummary summary = ServiceSummary.parse(input.toString());
        assertEquals("exist", summary.navStatusEndpoint);
        assertEquals(4, summary.navStatusEvents);
        assertEquals(3, summary.navStatusSamples.size());
        assertEquals(events.subList(0, 3), summary.navStatusSamples);
        assertTrue(summary.toText().contains("Nav status events: 4"));
        assertTrue(summary.toText().endsWith("\n  > " + events.get(0)
                + "\n  > " + events.get(1) + "\n  > " + events.get(2)));
    }

    @Test
    public void newDiscoveryResetsVideoSinks() {
        String first = log(
                "MESSAGE_SERVICE_DISCOVERY_REQUEST",
                "services {", "  id: 10", "  media_sink_service {",
                "    codec_resolution: VIDEO_800x480", "    display_id: 1", "  }", "}",
                "NavigationStatusEndpoint::notifyDiscoveryInfo service is exist",
                "NavigationStatusEndpoint::routeMessage channelId:6 type:32769");
        assertTrue(ServiceSummary.parse(first).hasSecondaryVideo);
        ServiceSummary summary = ServiceSummary.parse(first + log(
                "MESSAGE_SERVICE_DISCOVERY_REQUEST",
                "services {", "  id: 2", "  media_sink_service {",
                "    codec_resolution: VIDEO_1920x1080",
                "    display_id: 0", "    display_type: DISPLAY_TYPE_MAIN", "  }", "}"));
        assertEquals(1, summary.videoSinks.size());
        assertEquals(Integer.valueOf(2), summary.videoSinks.get(0).serviceId);
        assertFalse(summary.hasSecondaryVideo);
        assertEquals("?", summary.navStatusEndpoint);
        assertEquals(0, summary.navStatusEvents);
        assertEquals(Collections.emptyList(), summary.navStatusSamples);
    }

    @Test
    public void laterVideoServiceReplacesEarlierWithoutReordering() {
        ServiceSummary summary = ServiceSummary.parse(log(
                "MESSAGE_SERVICE_DISCOVERY_REQUEST",
                "services {", "  id: 2", "  media_sink_service {",
                "    codec_resolution: VIDEO_640x480",
                // A new service closes this partial dump.
                "services {", "  id: 10", "  media_sink_service {",
                "    video_configs {", "    }", "  }",
                // An unindented daemon log also closes a service.
                "Controller::routeMessage channelId:0 type:6",
                "  media_sink_service {", "    display_id: 9", "  }",
                "services {", "  id: 2", "  id: 99", "  media_sink_service {",
                "    video_configs {",
                "      codec_resolution: VIDEO_1920x1080",
                "      ui_config {", "        display_id: 7",
                "        display_type: DISPLAY_TYPE_CLUSTER", "      }", "    }",
                "    video_configs {", "      codec_resolution: VIDEO_800x480", "    }",
                "    video_configs {", "      codec_resolution: VIDEO_1920x1080", "    }",
                "    display_id: 0", "    display_type: DISPLAY_TYPE_MAIN", "  }",
                "  input_source_service {", "    display_id: 8",
                "    codec_resolution: VIDEO_320x240", "  }"));
        assertEquals(2, summary.videoSinks.size());
        ServiceSummary.VideoSink first = summary.videoSinks.get(0);
        assertEquals(Integer.valueOf(2), first.serviceId);
        assertEquals(Integer.valueOf(0), first.displayId);
        assertEquals("DISPLAY_TYPE_MAIN", first.displayType);
        assertEquals(Arrays.asList("VIDEO_1920x1080", "VIDEO_800x480"), first.resolutions);
        ServiceSummary.VideoSink second = summary.videoSinks.get(1);
        assertEquals(Integer.valueOf(10), second.serviceId);
        assertNull(second.displayId);
        assertNull(second.displayType);
        assertEquals(Collections.emptyList(), second.resolutions);
        assertTrue(summary.hasSecondaryVideo);
    }

    @Test
    public void anonymousVideoSinksDeduplicateByValues() {
        String unknown = log("services {", "  media_sink_service {",
                "    video_configs {", "    }", "  }", "}");
        String request = log("MESSAGE_SERVICE_DISCOVERY_REQUEST");
        ServiceSummary single = ServiceSummary.parse(request + unknown + unknown);
        assertEquals(1, single.videoSinks.size());
        assertNull(single.videoSinks.get(0).serviceId);
        assertNull(single.videoSinks.get(0).displayId);
        assertNull(single.videoSinks.get(0).displayType);
        assertFalse(single.hasSecondaryVideo);
        assertTrue(single.toText().contains("  - id=? display_id=? type=? []"));

        String resolutions = log("services {", "  media_sink_service {",
                "    codec_resolution: VIDEO_1920x1080",
                "    codec_resolution: VIDEO_800x480", "  }", "}");
        ServiceSummary summary = ServiceSummary.parse(request + unknown + resolutions
                + resolutions + unknown + log("services {", "  media_sink_service {",
                "    codec_resolution: VIDEO_800x480",
                "    codec_resolution: VIDEO_1920x1080", "  }", "}"));
        assertEquals(3, summary.videoSinks.size());
        assertEquals(Collections.emptyList(), summary.videoSinks.get(0).resolutions);
        assertEquals(Arrays.asList("VIDEO_1920x1080", "VIDEO_800x480"),
                summary.videoSinks.get(1).resolutions);
        assertEquals(Arrays.asList("VIDEO_800x480", "VIDEO_1920x1080"),
                summary.videoSinks.get(2).resolutions);
        assertTrue(summary.hasSecondaryVideo);
    }

    @Test
    public void navStatusEndpointUsesLastNotificationAndCountsOnlyEvents() {
        String discovery = log("MESSAGE_SERVICE_DISCOVERY_REQUEST",
                "NavigationStatusEndpoint::notifyDiscoveryInfo start");
        assertEquals("?", ServiceSummary.parse(discovery).navStatusEndpoint);
        discovery += log("NavigationStatusEndpoint::notifyDiscoveryInfo service is exist");
        assertEquals("exist", ServiceSummary.parse(discovery).navStatusEndpoint);
        discovery += log("NavigationStatusEndpoint::notifyDiscoveryInfo service is not found",
                "InputSource::notifyDiscoveryInfo service is exist",
                "NavigationStatusEndpoint::addDiscoveryInfo routeMessage handleNavigation",
                "NavigationStatusEndpoint::notifyDiscoveryInfo service is not found routeMessage");
        String event = "NavigationStatusEndpoint::handleNavigation status=1";
        ServiceSummary summary = ServiceSummary.parse(discovery + log(event));
        assertEquals("not found", summary.navStatusEndpoint);
        assertEquals(1, summary.navStatusEvents);
        assertEquals(Collections.singletonList(event), summary.navStatusSamples);
        assertTrue(summary.toText().contains("Nav status endpoint: not found"));
    }

    @Test
    public void newDiscoveryDiscardsPartialServiceAndIgnoresOtherTags() {
        String records = log("services {", "  id: 10", "  media_sink_service {",
                "    codec_resolution: VIDEO_800x480", "    display_id: 1", "  }", "}",
                "NavigationStatusEndpoint::notifyDiscoveryInfo service is exist",
                "NavigationStatusEndpoint::handleNavigation status=1");
        String partial = log("services {", "  id: 11", "  media_sink_service {",
                "    display_type: DISPLAY_TYPE_CLUSTER");
        ServiceSummary summary = ServiceSummary.parse(records
                + log("MESSAGE_SERVICE_DISCOVERY_REQUEST") + records + partial
                + log("MESSAGE_SERVICE_DISCOVERY_REQUEST")
                + records.replace("SdAAReverse: ", "OtherTag: "));
        assertFalse(summary.found);
        assertEquals(Collections.emptyList(), summary.videoSinks);
        assertFalse(summary.hasSecondaryVideo);
        assertEquals("?", summary.navStatusEndpoint);
        assertEquals(0, summary.navStatusEvents);
        assertEquals(Collections.emptyList(), summary.navStatusSamples);
        ServiceSummary beforeRequest = ServiceSummary.parse(records);
        assertEquals(Collections.emptyList(), beforeRequest.videoSinks);
        assertEquals("?", beforeRequest.navStatusEndpoint);
        assertEquals(0, beforeRequest.navStatusEvents);
        assertEquals(Collections.emptyList(), beforeRequest.navStatusSamples);
    }

    @Test
    public void newListsAreUnmodifiable() throws IOException {
        ServiceSummary summary = ServiceSummary.parse(dhuLog()
                + log("NavigationStatusEndpoint::handleNavigation status=1"));
        assertThrows(UnsupportedOperationException.class,
                () -> summary.videoSinks.remove(0));
        assertThrows(UnsupportedOperationException.class,
                () -> summary.videoSinks.get(0).resolutions.add("VIDEO_800x480"));
        assertThrows(UnsupportedOperationException.class,
                () -> summary.navStatusSamples.set(0, "changed"));
    }
}
