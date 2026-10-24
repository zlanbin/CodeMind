package com.codemind.review.infrastructure.rule;

import com.codemind.review.domain.rule.RecalledRule;
import com.codemind.review.domain.rule.RuleRecallException;
import com.codemind.review.domain.rule.RuleRecallPort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * {@link RuleRecallPort} 的 Feign 实现（M11）：把 domain 端口调用转成对 rule 的 HTTP 调用，
 * 并把 Feign 的异常类型挡在 domain 之外。
 */
@Component
public class FeignRuleRecallAdapter implements RuleRecallPort {

    private final RuleFeignClient feignClient;

    public FeignRuleRecallAdapter(RuleFeignClient feignClient) {
        this.feignClient = Objects.requireNonNull(feignClient, "feignClient");
    }

    @Override
    public List<RecalledRule> recall(String project, String query, int topK) {
        try {
            var result = feignClient.recall(new RecallRequestDto(project, query, topK));
            RecallResponseDto response = result == null ? null : result.data();
            if (response == null || response.chunks() == null) {
                return List.of();
            }
            return response.chunks().stream()
                    .map(c -> new RecalledRule(c.documentTitle(), c.sectionPath(), c.heading(), c.content(),
                            c.similarity()))
                    .toList();
        } catch (RuntimeException e) {
            // 脏数据（如缺字段的候选项）在映射阶段抛的 NPE 也要包成 RuleRecallException——
            // 召回失败必须放行（M11 D10），不能让规则服务不可用拖垮整个评审任务
            throw new RuleRecallException("召回规则条款失败 project=%s query=%s".formatted(project, query), e);
        }
    }
}
