package com.codemind.review.infrastructure.memory;

import com.codemind.common.api.Result;
import com.codemind.review.domain.finding.TaskFingerprintException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FeignTaskFingerprintAdapterTest {

    @Test
    void unwrapsResultEnvelope() {
        TaskFingerprintFeignClient stub = new TaskFingerprintFeignClient() {
            @Override
            public Result<PublishedFingerprintsResponseDto> published(String repo, int prNumber) {
                return Result.ok(new PublishedFingerprintsResponseDto(List.of("fp1", "fp2")));
            }

            @Override
            public Result<RecordFingerprintsResponseDto> record(RecordFingerprintsRequestDto request) {
                throw new UnsupportedOperationException();
            }
        };
        var adapter = new FeignTaskFingerprintAdapter(stub);

        assertEquals(Set.of("fp1", "fp2"), adapter.published("org/repo", 1));
    }

    @Test
    void wrapsFeignFailureAsTaskFingerprintException() {
        TaskFingerprintFeignClient failing = new TaskFingerprintFeignClient() {
            @Override
            public Result<PublishedFingerprintsResponseDto> published(String repo, int prNumber) {
                throw new RuntimeException("boom");
            }

            @Override
            public Result<RecordFingerprintsResponseDto> record(RecordFingerprintsRequestDto request) {
                throw new RuntimeException("boom");
            }
        };
        var adapter = new FeignTaskFingerprintAdapter(failing);

        assertThrows(TaskFingerprintException.class, () -> adapter.published("org/repo", 1));
        assertThrows(TaskFingerprintException.class, () -> adapter.record("org/repo", 1, List.of("fp1")));
    }

    @Test
    void recordUnwrapsResultEnvelopeWithoutThrowing() {
        TaskFingerprintFeignClient stub = new TaskFingerprintFeignClient() {
            @Override
            public Result<PublishedFingerprintsResponseDto> published(String repo, int prNumber) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Result<RecordFingerprintsResponseDto> record(RecordFingerprintsRequestDto request) {
                return Result.ok(new RecordFingerprintsResponseDto(2));
            }
        };
        var adapter = new FeignTaskFingerprintAdapter(stub);

        adapter.record("org/repo", 1, List.of("fp1", "fp2"));
    }
}
