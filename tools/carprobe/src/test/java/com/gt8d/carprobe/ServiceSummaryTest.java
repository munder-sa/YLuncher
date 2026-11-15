package com.gt8d.carprobe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
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
}
