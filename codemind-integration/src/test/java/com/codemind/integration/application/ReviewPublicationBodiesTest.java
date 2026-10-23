package com.codemind.integration.application;

import com.codemind.common.event.ReviewCompleted;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReviewPublicationBodiesTest {
    @Test void exactMarkerMatchesOnlyCompleteTask() {
        assertTrue(ReviewPublicationBodies.matches("body\n<!-- codemind:task=abc -->", "abc"));
        assertFalse(ReviewPublicationBodies.matches("<!-- codemind:task=abcdef -->", "abc"));
        assertFalse(ReviewPublicationBodies.matches(null, "abc"));
    }
    @Test void fallbackRetainsSummaryAndAllInlineDetails() {
        var event=ReviewCompleted.of("abc","owner/repo",7,"head","original summary",List.of(
                new ReviewCompleted.LineComment("A.java",11,"first"),new ReviewCompleted.LineComment("B.java",7,"second")));
        String fallback=ReviewPublicationBodies.summaryOnly(event);
        assertTrue(fallback.contains("original summary"));
        assertTrue(fallback.contains("A.java:11")); assertTrue(fallback.contains("first"));
        assertTrue(fallback.contains("B.java:7")); assertTrue(fallback.contains("second"));
        assertTrue(fallback.contains("降级"));
        assertTrue(ReviewPublicationBodies.matches(fallback,"abc"));
    }
}
