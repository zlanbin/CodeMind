package com.codemind.review.infrastructure.memory;

import com.codemind.common.api.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 调 memory 的 internal 已发布指纹端点（M8 建好，M10 起有调用方）。按服务名经 Nacos 发现实例。
 *
 * <p>memory 的 internal 端点统一走 {@link Result} 信封，返回类型必须声明为 {@code Result<T>}，
 * 不能是裸 DTO（同 {@link MemoryFeignClient} 的既有约束）。
 */
@FeignClient(
        name = "codemind-memory",
        path = "/internal/task-fingerprints",
        url = "${codemind.memory.url:}")
public interface TaskFingerprintFeignClient {

    @GetMapping
    Result<PublishedFingerprintsResponseDto> published(@RequestParam("repo") String repo,
                                                        @RequestParam("prNumber") int prNumber);

    @PostMapping
    Result<RecordFingerprintsResponseDto> record(@RequestBody RecordFingerprintsRequestDto request);
}
