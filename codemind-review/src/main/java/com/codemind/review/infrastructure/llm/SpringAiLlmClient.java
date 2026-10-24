package com.codemind.review.infrastructure.llm;

import com.codemind.review.domain.agent.LlmClient;
import com.codemind.review.domain.agent.LlmException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * {@link LlmClient} 的 Spring AI 实现（ARCHITECTURE 3.2：infrastructure 适配器）。
 *
 * <p>走 OpenAI 兼容协议，端点由 {@code application.yml} 的 {@code spring.ai.openai.base-url} 配置
 * （M4 指向中转端点，模型为 Claude 系列）。API Key 只经环境变量注入。
 */
@Component
public class SpringAiLlmClient implements LlmClient {

    private final ChatClient chatClient;

    public SpringAiLlmClient(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public String complete(String systemPrompt, String userPrompt, String callSite) throws LlmException {
        try {
            String content = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
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
}
