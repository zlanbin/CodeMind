package com.codemind.review.infrastructure.rule;

import com.codemind.common.api.Result;
import com.codemind.review.domain.rule.RecalledRule;
import com.codemind.review.domain.rule.RuleRecallException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeignRuleRecallAdapterTest {

    @Test
    void wrapsFeignFailureAsRuleRecallException() {
        RuleFeignClient failing = req -> {
            throw new RuntimeException("connection refused");
        };
        var adapter = new FeignRuleRecallAdapter(failing);

        assertThrows(RuleRecallException.class, () -> adapter.recall("org/repo", "query", 5));
    }

    @Test
    void mapsResponseToRecalledRules() {
        RuleFeignClient stub = req -> Result.ok(new RecallResponseDto(List.of(
                new RecallResponseDto.Chunk("接口设计规范", "3.2.1 方法命名", "方法命名",
                        "公开方法命名必须用名词加动词结构", 0.8))));
        var adapter = new FeignRuleRecallAdapter(stub);

        List<RecalledRule> result = adapter.recall("org/repo", "query", 5);

        assertEquals(1, result.size());
        assertEquals("接口设计规范", result.get(0).documentTitle());
        assertEquals("3.2.1 方法命名", result.get(0).sectionPath());
        assertEquals(0.8, result.get(0).similarity());
    }

    @Test
    void emptyDataReturnsEmptyList() {
        RuleFeignClient stub = req -> new Result<>(0, "ok", null);
        var adapter = new FeignRuleRecallAdapter(stub);

        assertTrue(adapter.recall("org/repo", "query", 5).isEmpty());
    }

    /**
     * rule 的 internal 端点统一走 {@code Result} 信封（同 M9b 对 memory 学到的教训：
     * 真实响应是 {@code {code,message,data:{chunks:[...]}}}，不是裸 {@code {chunks:[...]}}）。
     * 本用例对真实线上格式做 Jackson 反序列化，证明 {@link RuleFeignClient#recall} 的声明
     * 返回类型必须是 {@code Result<RecallResponseDto>}，不能是裸 DTO。
     */
    @Test
    void deserializesRealRuleEnvelopeFormat() throws Exception {
        String wireJson = """
                {"code":0,"message":"ok","data":{"chunks":[
                    {"documentTitle":"接口设计规范","sectionPath":"3.2.1 方法命名","heading":"方法命名",
                     "content":"公开方法命名必须用名词加动词结构","similarity":0.8}
                ]}}""";
        ObjectMapper mapper = new ObjectMapper();
        Result<RecallResponseDto> decoded = mapper.readValue(wireJson,
                mapper.getTypeFactory().constructParametricType(Result.class, RecallResponseDto.class));

        assertEquals(1, decoded.data().chunks().size());
        assertEquals("接口设计规范", decoded.data().chunks().get(0).documentTitle());
    }
}
