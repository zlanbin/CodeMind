package com.codemind.review.infrastructure.memory;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 调 memory 的 internal 记忆召回端点（M9b）。按服务名经 Nacos 发现实例（ARCHITECTURE 3.3）。
 */
@FeignClient(
        name = "codemind-memory",
        path = "/internal/memories",
        // 空串表示按服务名经 Nacos 发现（生产路径）；设了就直连该地址，
        // 供关掉 discovery 的 IT 指向本机已启动的 memory 实例
        url = "${codemind.memory.url:}")
public interface MemoryFeignClient {

    @PostMapping("/recall")
    RecallResponseDto recall(@RequestBody RecallRequestDto request);
}
