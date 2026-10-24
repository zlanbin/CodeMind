package com.codemind.review.domain.rule;

import java.util.List;

/**
 * 召回规范文档条款的出站端口（M11）。
 *
 * <p>review 不直连 rule-service：召回是跨服务调用，本端口的实现经 Feign 调 rule 的 internal 端点。
 *
 * <p>纯接口，domain 不依赖 Spring Cloud / Feign。
 */
public interface RuleRecallPort {

    /**
     * 召回某项目下与 query 最相关的规则条款候选。
     *
     * @throws RuleRecallException 调用失败（网络、rule-service 不可达）
     */
    List<RecalledRule> recall(String project, String query, int topK);
}
