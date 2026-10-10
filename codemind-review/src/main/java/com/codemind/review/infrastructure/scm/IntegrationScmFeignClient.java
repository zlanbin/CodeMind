package com.codemind.review.infrastructure.scm;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 调 integration 的 internal PR 内容端点（M7a）。按服务名经 Nacos 发现实例（ARCHITECTURE 3.3）。
 */
@FeignClient(
        name = "codemind-integration",
        path = "/internal/pulls",
        // 空串表示按服务名经 Nacos 发现（生产路径）；设了就直连该地址，
        // 供关掉 discovery 的 IT 指向本机已启动的 integration 实例
        url = "${codemind.integration.url:}")
public interface IntegrationScmFeignClient {

    @GetMapping("/diff")
    String diff(@RequestParam("repo") String repo, @RequestParam("prNumber") int prNumber);

    @PostMapping("/contents")
    FileContentsResponseDto contents(@RequestBody FileContentsRequestDto request);
}
