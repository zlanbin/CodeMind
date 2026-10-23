package com.codemind.memory.domain.memory;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryContentTest {

    @Test
    void normalizesWhitespaceSoEquivalentTextSharesHash() {
        MemoryContent a = MemoryContent.of("  本项目允许   Controller\n直接返回实体 ");
        MemoryContent b = MemoryContent.of("本项目允许 Controller 直接返回实体");

        assertThat(a.text()).isEqualTo("本项目允许 Controller 直接返回实体");
        assertThat(a.hash()).isEqualTo(b.hash()).hasSize(64);
    }

    @Test
    void caseIsSignificant() {
        assertThat(MemoryContent.of("Use Optional").hash()).isNotEqualTo(MemoryContent.of("use optional").hash());
    }

    @Test
    void rejectsBlank() {
        assertThatThrownBy(() -> MemoryContent.of(" \n ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MemoryContent.of(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lengthLimitAppliesAfterNormalization() {
        String padded = "  " + "x".repeat(MemoryContent.MAX_LENGTH) + "  ";
        assertThat(MemoryContent.of(padded).text()).hasSize(MemoryContent.MAX_LENGTH);
        assertThatThrownBy(() -> MemoryContent.of("x".repeat(MemoryContent.MAX_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Memory.requireProject("p".repeat(Memory.MAX_PROJECT_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void memoryRejectsImportanceOutsideHalfOpenUnitInterval() {
        Instant now = Instant.EPOCH;
        assertThatThrownBy(() -> new Memory(1, "p", MemoryKind.CONVENTION, "c", 0.0, MemoryStatus.ACTIVE, now, now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Memory(1, "p", MemoryKind.CONVENTION, "c", 1.01, MemoryStatus.ACTIVE, now, now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new Memory(1, "p", MemoryKind.CONVENTION, "c", 1.0, MemoryStatus.ACTIVE, now, now).importance())
                .isEqualTo(1.0);
    }
}
