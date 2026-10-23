package com.codemind.review.infrastructure.memory;

import com.codemind.review.domain.memory.MemoryRecallException;
import com.codemind.review.domain.memory.RecalledMemory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FeignMemoryRecallAdapterTest {

    @Test
    void wrapsFeignFailureAsMemoryRecallException() {
        MemoryFeignClient failing = req -> {
            throw new RuntimeException("connection refused");
        };
        var adapter = new FeignMemoryRecallAdapter(failing);

        assertThrows(MemoryRecallException.class,
                () -> adapter.recallRejectedPatterns("org/repo", "query", 5, 0.05));
    }

    @Test
    void mapsResponseToRecalledMemories() {
        MemoryFeignClient stub = req -> new RecallResponseDto(List.of(
                new RecallResponseDto.Item(
                        new RecallResponseDto.Memory(1L, "org/repo", "REJECTED_PATTERN", "本项目不要求...",
                                0.9, "ACTIVE"),
                        0.8, 0.95, 0.76)));
        var adapter = new FeignMemoryRecallAdapter(stub);

        List<RecalledMemory> result = adapter.recallRejectedPatterns("org/repo", "query", 5, 0.05);

        assertEquals(1, result.size());
        assertEquals("本项目不要求...", result.get(0).content());
        assertEquals(0.76, result.get(0).score());
    }
}
