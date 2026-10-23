package com.codemind.memory.infrastructure.llm;

import com.codemind.memory.domain.feedback.LlmClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/** {@link LlmClient} 的 Spring AI 实现（OpenAI 兼容协议，同 review 的 SpringAiLlmClient）。 */
@Component
public class SpringAiLlmClient implements LlmClient {

    private final ChatClient chatClient;

    public SpringAiLlmClient(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) throws LlmException {
        try {
            String content = chatClient.prompt().system(systemPrompt).user(userPrompt).call().content();
            if (content == null || content.isBlank()) {
                throw new LlmException("LLM 返回空内容");
            }
            return content;
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("LLM 调用失败: " + e.getClass().getSimpleName(), e);
        }
    }
}
