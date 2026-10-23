package com.codemind.review.infrastructure.memory;

import com.codemind.common.api.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 调 memory 的 internal 记忆召回端点（M9b）。按服务名经 Nacos 发现实例（ARCHITECTURE 3.3）。
 *
 * <p>memory 的 internal 端点统一走 {@link Result} 信封（{@code MemoryController.recall} 返回
 * {@code Result<RecallResponse>}），返回类型必须声明为 {@code Result<RecallResponseDto>}，
 * 不能是裸 DTO——否则 Feign 解码后业务字段恒为 null（S6 评审发现：曾照抄 integration 的
 * {@code IntegrationScmFeignClient} 裸返回写法，但 integration 的 internal 端点不套信封，两者不同）。
 */
@FeignClient(
        name = "codemind-memory",
        path = "/internal/memories",
        // 空串表示按服务名经 Nacos 发现（生产路径）；设了就直连该地址，
        // 供关掉 discovery 的 IT 指向本机已启动的 memory 实例
        url = "${codemind.memory.url:}")
public interface MemoryFeignClient {

    @PostMapping("/recall")
    Result<RecallResponseDto> recall(@RequestBody RecallRequestDto request);
}
