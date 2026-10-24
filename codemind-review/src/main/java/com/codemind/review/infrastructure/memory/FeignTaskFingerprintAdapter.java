package com.codemind.review.infrastructure.memory;

import com.codemind.review.domain.finding.TaskFingerprintException;
import com.codemind.review.domain.finding.TaskFingerprintPort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * {@link TaskFingerprintPort} 的 Feign 实现（M10）：把 domain 端口调用转成对 memory 的 HTTP 调用，
 * 并把 Feign 的异常类型挡在 domain 之外。
 */
@Component
public class FeignTaskFingerprintAdapter implements TaskFingerprintPort {

    private final TaskFingerprintFeignClient feignClient;

    public FeignTaskFingerprintAdapter(TaskFingerprintFeignClient feignClient) {
        this.feignClient = Objects.requireNonNull(feignClient, "feignClient");
    }

    @Override
    public Set<String> published(String repo, int prNumber) {
        try {
            var result = feignClient.published(repo, prNumber);
            var response = result == null ? null : result.data();
            if (response == null || response.fingerprints() == null) {
                return Set.of();
            }
            return Set.copyOf(response.fingerprints());
        } catch (RuntimeException e) {
            throw new TaskFingerprintException(
                    "查询已发布指纹失败 repo=%s prNumber=%d".formatted(repo, prNumber), e);
        }
    }

    @Override
    public void record(String repo, int prNumber, List<String> fingerprints) {
        try {
            feignClient.record(new RecordFingerprintsRequestDto(repo, prNumber, fingerprints));
        } catch (RuntimeException e) {
            throw new TaskFingerprintException(
                    "登记已发布指纹失败 repo=%s prNumber=%d".formatted(repo, prNumber), e);
        }
    }
}
