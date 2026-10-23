package com.codemind.memory.interfaces.web.dto;

import com.codemind.memory.domain.memory.Memory;
import com.codemind.memory.domain.memory.MemoryKind;
import com.codemind.memory.domain.recall.ScoredMemory;

import java.time.Instant;
import java.util.List;

/** internal 记忆端点的请求 / 响应体。不跨模块共享，调用方（M9 起的 review）自行定义对应 DTO。 */
public final class MemoryDtos {

    private MemoryDtos() {
    }

    /** @param importance 可空，缺省 0.5 */
    public record WriteMemoryRequest(String project, MemoryKind kind, String content, Double importance) {
    }

    /** topK / kind / minScore 可空。 */
    public record RecallRequest(String project, String query, Integer topK, MemoryKind kind, Double minScore) {
    }

    public record MemoryView(long id, String project, MemoryKind kind, String content, double importance,
                             String status, Instant createdAt, Instant reinforcedAt) {

        public static MemoryView of(Memory m) {
            return new MemoryView(m.id(), m.project(), m.kind(), m.content(), m.importance(), m.status().name(),
                    m.createdAt(), m.reinforcedAt());
        }
    }

    public record WriteMemoryResponse(MemoryView memory, boolean created) {
    }

    /** 每条带打分因子，调用方可按 score 自行截断或解释。 */
    public record RecalledMemory(MemoryView memory, double similarity, double decay, double score) {

        public static RecalledMemory of(ScoredMemory s) {
            return new RecalledMemory(MemoryView.of(s.memory()), s.similarity(), s.decay(), s.score());
        }
    }

    public record RecallResponse(List<RecalledMemory> memories) {
    }

    public record ArchiveResponse(int archived) {
    }

    public record RecordFingerprintsRequest(String repo, Integer prNumber, List<String> fingerprints) {
    }

    public record RecordFingerprintsResponse(int added) {
    }

    public record FingerprintsResponse(List<String> fingerprints) {
    }
}
