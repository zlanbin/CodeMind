package com.codemind.review.interfaces.mq;

import com.codemind.common.event.ReviewPublished;
import com.codemind.review.application.ReviewPublicationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** listener 只做反序列化与转调；业务判定全在 {@link ReviewPublicationService}（已有单测覆盖）。 */
class ReviewPublishedListenerTest {

    private final ReviewPublicationService service = mock(ReviewPublicationService.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ReviewPublishedListener listener = new ReviewPublishedListener(service, mapper);

    @Test
    void parsesPayloadAndDelegatesToApplicationService() throws Exception {
        ReviewPublished event = ReviewPublished.of("task-1", 3, false);
        String payload = mapper.writeValueAsString(event);

        listener.onMessage(payload);

        verify(service).markPublished(event);
    }

    @Test
    void malformedPayloadThrowsSoMqRetries() {
        assertThrows(IllegalStateException.class, () -> listener.onMessage("not json"));
        verify(service, never()).markPublished(any());
    }

    @Test
    void unmatchedReceiptStillReturnsNormally() throws Exception {
        when(service.markPublished(any())).thenReturn(false);
        String payload = mapper.writeValueAsString(ReviewPublished.of("unknown-task", 0, false));

        assertDoesNotThrow(() -> listener.onMessage(payload));
    }
}
