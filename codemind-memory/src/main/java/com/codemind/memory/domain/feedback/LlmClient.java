package com.codemind.memory.domain.feedback;

/**
 * LLM 调用端口（M9 D12）。与 review 的同名端口各自一份，不跨模块共享：两个服务的 domain 互不依赖。
 */
public interface LlmClient {

    /**
     * @return 模型原始文本（可能带 markdown 围栏，由调用方解析）
     * @throws LlmException 网络、鉴权、限流、空输出
     */
    String complete(String systemPrompt, String userPrompt) throws LlmException;

    class LlmException extends Exception {
        public LlmException(String message) {
            super(message);
        }

        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
