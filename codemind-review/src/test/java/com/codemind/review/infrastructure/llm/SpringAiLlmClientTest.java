package com.codemind.review.infrastructure.llm;

import com.codemind.review.domain.agent.LlmException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link SpringAiLlmClient} 读取真实 usage 并按 callSite 打点的单测（M12 可观测性）。
 *
 * <p>Spring AI {@code ChatClient} 的 fluent 链不易直接 mock，用
 * {@code Mockito.mock(ChatClient.class, Answers.RETURNS_DEEP_STUBS)} 让
 * {@code .prompt().system().user().call().chatResponse()} 返回构造好的 {@link ChatResponse}。
 */
class SpringAiLlmClientTest {

    @Test
    void recordsPromptAndCompletionTokensTaggedByCallSite() throws LlmException {
        ChatResponse chatResponse = chatResponseWithUsage("mock 输出", 100, 50);
        ChatClient.Builder builder = deepStubBuilderReturning(chatResponse);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        SpringAiLlmClient client = new SpringAiLlmClient(builder, meterRegistry);

        String content = client.complete("system", "user", "STYLE");

        assertEquals("mock 输出", content);
        assertEquals(100.0,
                meterRegistry.counter("codemind_llm_tokens_total", "call_site", "STYLE", "type", "prompt").count());
        assertEquals(50.0,
                meterRegistry.counter("codemind_llm_tokens_total", "call_site", "STYLE", "type", "completion")
                        .count());
    }

    @Test
    void missingUsageRecordsZeroAndDoesNotThrow() {
        ChatResponse chatResponse = chatResponseWithUsage("mock 输出", null, null);
        ChatClient.Builder builder = deepStubBuilderReturning(chatResponse);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        SpringAiLlmClient client = new SpringAiLlmClient(builder, meterRegistry);

        String content = assertDoesNotThrow(() -> client.complete("system", "user", "SECURITY"));

        assertEquals("mock 输出", content);
        assertEquals(0.0,
                meterRegistry.counter("codemind_llm_tokens_total", "call_site", "SECURITY", "type", "prompt")
                        .count());
        assertEquals(0.0,
                meterRegistry.counter("codemind_llm_tokens_total", "call_site", "SECURITY", "type", "completion")
                        .count());
    }

    private static ChatClient.Builder deepStubBuilderReturning(ChatResponse chatResponse) {
        ChatClient chatClient = Mockito.mock(ChatClient.class, Answers.RETURNS_DEEP_STUBS);
        Mockito.when(chatClient.prompt().system(Mockito.anyString()).user(Mockito.anyString())
                        .call().chatResponse())
                .thenReturn(chatResponse);
        ChatClient.Builder builder = Mockito.mock(ChatClient.Builder.class);
        Mockito.when(builder.build()).thenReturn(chatClient);
        return builder;
    }

    private static ChatResponse chatResponseWithUsage(String text, Integer promptTokens, Integer completionTokens) {
        Usage usage = Mockito.mock(Usage.class);
        Mockito.when(usage.getPromptTokens()).thenReturn(promptTokens);
        Mockito.when(usage.getCompletionTokens()).thenReturn(completionTokens);

        ChatResponseMetadata metadata = ChatResponseMetadata.builder().usage(usage).build();
        Generation generation = new Generation(new AssistantMessage(text));
        return new ChatResponse(java.util.List.of(generation), metadata);
    }
}
