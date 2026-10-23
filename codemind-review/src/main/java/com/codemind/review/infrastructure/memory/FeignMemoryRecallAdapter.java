package com.codemind.review.infrastructure.memory;

import com.codemind.review.domain.memory.MemoryRecallException;
import com.codemind.review.domain.memory.MemoryRecallPort;
import com.codemind.review.domain.memory.RecalledMemory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * {@link MemoryRecallPort} 的 Feign 实现（M9b）：把 domain 端口调用转成对 memory 的 HTTP 调用，
 * 并把 Feign 的异常类型挡在 domain 之外。
 */
@Component
public class FeignMemoryRecallAdapter implements MemoryRecallPort {

    private static final String REJECTED_PATTERN = "REJECTED_PATTERN";

    private final MemoryFeignClient feignClient;

    public FeignMemoryRecallAdapter(MemoryFeignClient feignClient) {
        this.feignClient = Objects.requireNonNull(feignClient, "feignClient");
    }

    @Override
    public List<RecalledMemory> recallRejectedPatterns(String project, String query, int topK, double minScore) {
        try {
            var result = feignClient.recall(new RecallRequestDto(project, query, topK, REJECTED_PATTERN, minScore));
            RecallResponseDto response = result == null ? null : result.data();
            if (response == null || response.memories() == null) {
                return List.of();
            }
            return response.memories().stream()
                    .map(item -> new RecalledMemory(item.memory().content(), item.score()))
                    .toList();
        } catch (RuntimeException e) {
            // 脏数据（如缺字段的候选项）在映射阶段抛的 NPE 也要包成 MemoryRecallException——
            // 召回失败必须放行，不能因为过滤器出错而让整个评审任务失败（S6 评审发现）。
            throw new MemoryRecallException(
                    "召回否决记忆失败 project=%s query=%s".formatted(project, query), e);
        }
    }
}
