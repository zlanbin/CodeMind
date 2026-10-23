package com.codemind.common.event;

import com.codemind.common.event.FindingFeedbackReceived.FeedbackType;
import com.codemind.common.event.FindingFeedbackReceived.FindingRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FindingMarkerTest {

    private static final String FP1 = "a".repeat(64);
    private static final String FP2 = "0123456789abcdef".repeat(4);

    @Test
    void roundTripsEveryMarkerInBodyOrder() {
        String body = "### MAJOR\n\n描述一" + FindingMarker.format(FP1, "CORRECTNESS")
                + "\n\n描述二" + FindingMarker.format(FP2, "SECURITY") + "\n";

        assertThat(FindingMarker.parseAll(body)).containsExactly(
                new FindingRef(FP1, "CORRECTNESS"), new FindingRef(FP2, "SECURITY"));
    }

    @Test
    void stripRemovesOnlyFindingMarkers() {
        String body = "描述" + FindingMarker.format(FP1, "STYLE") + "\n<!-- codemind:task=t1 -->";

        assertThat(FindingMarker.strip(body)).isEqualTo("描述\n<!-- codemind:task=t1 -->");
    }

    @Test
    void ignoresMalformedOrForeignComments() {
        String body = "<!-- codemind:finding fp=xyz cat=STYLE --> <!-- codemind:finding fp=" + FP1
                + " cat=style --> <!-- other -->";

        assertThat(FindingMarker.parseAll(body)).isEmpty();
        assertThat(FindingMarker.parseAll(null)).isEmpty();
    }

    @Test
    void formatRejectsValuesThatWouldBreakTheMarker() {
        assertThatThrownBy(() -> FindingMarker.format("not-hex", "STYLE")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FindingMarker.format(FP1, "STYLE -->")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void feedbackEventRequiresAtLeastOneFindingAndCopiesList() {
        var findings = new java.util.ArrayList<>(List.of(new FindingRef(FP1, "STYLE")));
        var event = FindingFeedbackReceived.of("o/r", 2, 10L, FeedbackType.THUMBS_DOWN, 99L, "lan-bin", null,
                "正文", findings);
        findings.clear();

        assertThat(event.findings()).hasSize(1);
        assertThatThrownBy(() -> FindingFeedbackReceived.of("o/r", 2, 10L, FeedbackType.REPLY, 99L, "u", "x",
                "正文", List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
