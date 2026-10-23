package com.codemind.memory.application;

import com.codemind.memory.domain.fingerprint.TaskFingerprintRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskFingerprintServiceTest {

    private final List<Collection<String>> calls = new ArrayList<>();
    private final TaskFingerprintService service = new TaskFingerprintService(new TaskFingerprintRepository() {
        @Override
        public int addAll(String repo, int prNumber, Collection<String> fingerprints) {
            calls.add(fingerprints);
            return fingerprints.size();
        }

        @Override
        public Set<String> findAll(String repo, int prNumber) {
            return Set.of();
        }
    });

    @Test
    void emptyBatchSkipsRepository() {
        assertThat(service.record("o/r", 1, List.of())).isZero();
        assertThat(service.record("o/r", 1, null)).isZero();
        assertThat(calls).isEmpty();
    }

    @Test
    void acceptsBoundaryValues() {
        String longest = "f".repeat(TaskFingerprintService.MAX_FINGERPRINT_LENGTH);
        List<String> fullBatch = Collections.nCopies(TaskFingerprintService.MAX_BATCH, longest);

        assertThat(service.record("o/r", 1, fullBatch)).isEqualTo(TaskFingerprintService.MAX_BATCH);
    }

    @Test
    void rejectsInvalidInputBeforeTouchingRepository() {
        assertThatThrownBy(() -> service.record(" ", 1, List.of("a"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.record("o/r", 0, List.of("a"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.record("o/r", 1, List.of(" "))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.record("o/r", 1,
                List.of("f".repeat(TaskFingerprintService.MAX_FINGERPRINT_LENGTH + 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.record("o/r", 1,
                Collections.nCopies(TaskFingerprintService.MAX_BATCH + 1, "a")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.published("o/r", -1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).isEmpty();
    }
}
