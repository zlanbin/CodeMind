package com.codemind.integration.domain.publication;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicationResultTest {

    @Test
    void negativeInlineCountIsRejectedBeforeItCanBecomeAPublishedReceipt() {
        assertThatThrownBy(() -> new PublicationResult(123L, -1, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroInlineCommentsRemainsAValidSummaryOnlyResult() {
        PublicationResult result = new PublicationResult(123L, 0, true);
        assertThat(result.inlineCount()).isZero();
        assertThat(result.degraded()).isTrue();
    }
}
