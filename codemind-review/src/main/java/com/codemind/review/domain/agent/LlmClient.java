package com.codemind.review.domain.agent;

/**
 * LLM 调用端口（ARCHITECTURE 3.2：domain 定义接口，infrastructure 用 Spring AI 实现）。
 *
 * <p>domain 层只认这个纯接口，不依赖 Spring AI / OpenAI SDK，保持分层纯度。
 */
public interface LlmClient {

    /**
     * 单轮补全：给系统提示和用户提示，返回模型文本输出。
     *
     * @param systemPrompt 系统提示（角色设定、输出格式约束）
     * @param userPrompt   用户提示（diff、上下文）
     * @param callSite     固定的调用来源标识（{@code STYLE}/{@code SECURITY}/{@code PERFORMANCE}/
     *                     {@code TEST}/{@code CHANGE_MAP}/{@code GLOBAL_REDUCE} 之一），供 infrastructure
     *                     层做指标打点用，domain 层本身不解释这个值的含义
     * @return 模型原始文本输出（可能含 markdown 代码围栏，由调用方解析）
     * @throws LlmException 调用失败（网络、鉴权、限流、服务端错误）
     */
    String complete(String systemPrompt, String userPrompt, String callSite) throws LlmException;
}
