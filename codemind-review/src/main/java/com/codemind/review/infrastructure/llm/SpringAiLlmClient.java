package com.codemind.review.infrastructure.llm;

import com.codemind.review.domain.agent.LlmClient;
import com.codemind.review.domain.agent.LlmException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

/**
 * {@link LlmClient} 的 Spring AI 实现（ARCHITECTURE 3.2：infrastructure 适配器）。
 *
 * <p>走 OpenAI 兼容协议，端点由 {@code application.yml} 的 {@code spring.ai.openai.base-url} 配置
 * （M4 指向中转端点，模型为 Claude 系列）。API Key 只经环境变量注入。
 *
 * <p>M12：每次调用从 {@link ChatResponse} 的 usage 元数据读取真实 prompt/completion token 数，
 * 按 {@code callSite} 打到 {@code codemind_llm_tokens_total} 计数器（Task 4 Grafana dashboard 消费）。
 */
@Component
public class SpringAiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(SpringAiLlmClient.class);
    private static final String TOKENS_METRIC = "codemind_llm_tokens_total";

    private final ChatClient chatClient;
    private final MeterRegistry meterRegistry;

    public SpringAiLlmClient(ChatClient.Builder chatClientBuilder, MeterRegistry meterRegistry) {
        this.chatClient = chatClientBuilder.build();
        this.meterRegistry = meterRegistry;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt, String callSite) throws LlmException {
        try {
            ChatResponse response = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .chatResponse();

            recordTokenUsage(response, callSite);

            String content = response == null ? null : response.getResult().getOutput().getText();
            if (content == null || content.isBlank()) {
                throw new LlmException("LLM 返回空内容");
            }
            return content;
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("LLM 调用失败: " + e.getMessage(), e);
        }
    }

    /** 读取 usage 元数据并打点；usage 或其字段缺失时按 0 计数，仅告警不抛异常（Review Focus 第 1 条）。 */
    private void recordTokenUsage(ChatResponse response, String callSite) {
        Usage usage = response == null ? null : response.getMetadata().getUsage();
        Integer promptTokens = usage == null ? null : usage.getPromptTokens();
        Integer completionTokens = usage == null ? null : usage.getCompletionTokens();

        if (usage == null || promptTokens == null || completionTokens == null) {
            log.warn("LLM 调用 callSite={} 未返回完整 usage 元数据，token 指标按 0 计数", callSite);
        }

        meterRegistry.counter(TOKENS_METRIC, "call_site", callSite, "type", "prompt")
                .increment(promptTokens == null ? 0 : promptTokens);
        meterRegistry.counter(TOKENS_METRIC, "call_site", callSite, "type", "completion")
                .increment(completionTokens == null ? 0 : completionTokens);
    }
}
