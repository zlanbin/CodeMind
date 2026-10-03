package com.codemind.common.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResultTest {

    @Test
    void okCarriesDataAndIsSuccess() {
        Result<String> r = Result.ok("x");
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.data()).isEqualTo("x");
    }

    @Test
    void failIsNotSuccessAndHasNoData() {
        Result<Void> r = Result.fail(400, "bad");
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.code()).isEqualTo(400);
        assertThat(r.data()).isNull();
    }
}
