package com.codemind.common.event;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewPublicationEventsTest {

    @Test
    void eventsImplementDomainContract() {
        assertThat(DomainEvent.class).isAssignableFrom(ReviewCompleted.class);
        assertThat(DomainEvent.class).isAssignableFrom(ReviewPublished.class);
    }

    @Test
    void commentsAreDefensivelyCopied() {
        List<ReviewCompleted.LineComment> source = new ArrayList<>(List.of(
                new ReviewCompleted.LineComment("src/App.java", 12, "handle null input")));

        ReviewCompleted event = ReviewCompleted.of(
                "task-1", "owner/repo", 7, "head-sha", "summary", source);

        source.clear();

        assertThat(event.comments()).hasSize(1);
        assertThatThrownBy(() -> event.comments().add(
                new ReviewCompleted.LineComment("src/Other.java", 3, "another comment")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNonPositiveInlineLine() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReviewCompleted.LineComment("src/App.java", 0, "invalid"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReviewCompleted.LineComment("src/App.java", -1, "invalid"));
    }

    @Test
    void rejectsNegativePublishedCount() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReviewPublished("event-1", java.time.Instant.now(), "task-1", -1, false));
    }
}
