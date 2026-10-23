package com.codemind.review.infrastructure.memory;

import com.codemind.common.api.Result;
import com.codemind.review.domain.memory.MemoryRecallException;
import com.codemind.review.domain.memory.RecalledMemory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FeignMemoryRecallAdapterTest {

    @Test
    void wrapsFeignFailureAsMemoryRecallException() {
        MemoryFeignClient failing = req -> {
            throw new RuntimeException("connection refused");
        };
        var adapter = new FeignMemoryRecallAdapter(failing);

        assertThrows(MemoryRecallException.class,
                () -> adapter.recallRejectedPatterns("org/repo", "query", 5, 0.05));
    }

    @Test
    void mapsResponseToRecalledMemories() {
        MemoryFeignClient stub = req -> Result.ok(new RecallResponseDto(List.of(
                new RecallResponseDto.Item(
                        new RecallResponseDto.Memory(1L, "org/repo", "REJECTED_PATTERN", "本项目不要求...",
                                0.9, "ACTIVE"),
                        0.8, 0.95, 0.76))));
        var adapter = new FeignMemoryRecallAdapter(stub);

        List<RecalledMemory> result = adapter.recallRejectedPatterns("org/repo", "query", 5, 0.05);

        assertEquals(1, result.size());
        assertEquals("本项目不要求...", result.get(0).content());
        assertEquals(0.76, result.get(0).score());
    }

    /**
     * memory 的 internal 端点统一走 {@code Result} 信封（{@code MemoryController.recall} 返回
     * {@code Result<RecallResponse>}），真实 HTTP 响应是 {@code {code,message,data:{memories:[...]}}}，
     * 不是裸的 {@code {memories:[...]}}。本用例对真实线上格式做 Jackson 反序列化，
     * 证明 {@link MemoryFeignClient#recall} 的声明返回类型必须是 {@code Result<RecallResponseDto>}，
     * 不能是裸 {@code RecallResponseDto}（后者会让 {@code memories} 恒为 null，过滤器形同空操作）。
     */
    @Test
    void deserializesRealMemoryEnvelopeFormat() throws Exception {
        String wireJson = """
                {"code":0,"message":"ok","data":{"memories":[
                    {"memory":{"id":1,"project":"org/repo","kind":"REJECTED_PATTERN",
                     "content":"本项目不要求...","importance":0.9,"status":"ACTIVE"},
                     "similarity":0.8,"decay":0.95,"score":0.76}
                ]}}""";
        ObjectMapper mapper = new ObjectMapper();
        Result<RecallResponseDto> decoded = mapper.readValue(wireJson,
                mapper.getTypeFactory().constructParametricType(Result.class, RecallResponseDto.class));

        assertEquals(1, decoded.data().memories().size());
        assertEquals("本项目不要求...", decoded.data().memories().get(0).memory().content());
    }
}
