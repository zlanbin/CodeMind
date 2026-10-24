package com.codemind.review.infrastructure.rule;

import com.codemind.common.api.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 调 rule 的 internal 规则召回端点（M11）。按服务名经 Nacos 发现实例（ARCHITECTURE 3.3）。
 *
 * <p>rule 的 internal 端点统一走 {@link Result} 信封，返回类型必须声明为
 * {@code Result<RecallResponseDto>}，不能是裸 DTO（同 M9b 对 memory 学到的教训）。
 */
@FeignClient(
        name = "codemind-rule",
        path = "/internal/rules",
        // 空串表示按服务名经 Nacos 发现（生产路径）；设了就直连该地址，
        // 供关掉 discovery 的 IT 指向本机已启动的 rule 实例
        url = "${codemind.rule.url:}")
public interface RuleFeignClient {

    @PostMapping("/recall")
    Result<RecallResponseDto> recall(@RequestBody RecallRequestDto request);
}
