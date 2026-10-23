package com.codemind.integration.interfaces.mq;

import com.codemind.common.event.ReviewCompleted;
import com.codemind.integration.application.PublishOutcome;
import com.codemind.integration.application.ReviewPublishService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** listener 只做反序列化与转调；发布状态机全在 {@link ReviewPublishService}（已有单测覆盖）。 */
class ReviewCompletedListenerTest {

    private final ReviewPublishService service = mock(ReviewPublishService.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ReviewCompletedListener listener = new ReviewCompletedListener(service, mapper);

    @Test
    void parsesPayloadAndDelegatesToPublishService() throws Exception {
        ReviewCompleted event = ReviewCompleted.of("task-1", "owner/repo", 7, "head", "summary",
                List.of(new ReviewCompleted.LineComment("A.java", 11, "issue")));
        String payload = mapper.writeValueAsString(event);

        listener.onMessage(payload);

        verify(service).publish(event);
    }

    @Test
    void malformedPayloadThrowsSoMqRetries() {
        assertThrows(IllegalStateException.class, () -> listener.onMessage("not json"));
        verify(service, never()).publish(any());
    }

    @Test
    void awaitingConfirmationOutcomeDoesNotThrow() throws Exception {
        when(service.publish(any())).thenReturn(PublishOutcome.AWAITING_CONFIRMATION);
        String payload = mapper.writeValueAsString(ReviewCompleted.of("task-1", "owner/repo", 7, "head", "summary", List.of()));

        assertDoesNotThrow(() -> listener.onMessage(payload));
    }
}
