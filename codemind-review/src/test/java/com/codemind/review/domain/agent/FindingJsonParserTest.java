package com.codemind.review.domain.agent;

import com.codemind.review.domain.finding.Category;
import com.codemind.review.domain.finding.Finding;
import com.codemind.review.domain.finding.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FindingJsonParser} 纯单测：合法/带围栏/非法 JSON 的解析行为。
 */
class FindingJsonParserTest {

    private final FindingJsonParser parser = new FindingJsonParser();

    @Test
    void parsesValidFindings() throws Exception {
        String json = """
                {"findings":[
                  {"file":"OrderService.java","line":42,"severity":"MAJOR","category":"SECURITY",
                   "message":"SQL 拼接存在注入风险","suggestion":"用参数化查询","codeSnippet":"stmt.execute(sql)"}
                ]}
                """;
        List<Finding> findings = parser.parse(json);

        assertEquals(1, findings.size());
        Finding f = findings.get(0);
        assertEquals("OrderService.java", f.file());
        assertEquals(42, f.line());
        assertEquals(Severity.MAJOR, f.severity());
        assertEquals(Category.SECURITY, f.category());
        assertFalse(f.fingerprint().isBlank());
    }

    @Test
    void stripsMarkdownFence() throws Exception {
        String fenced = """
                这是模型多嘴的解释。
                ```json
                {"findings":[{"file":"A.java","line":1,"severity":"MINOR","category":"STYLE","message":"命名不规范"}]}
                ```
                """;
        List<Finding> findings = parser.parse(fenced);

        assertEquals(1, findings.size());
        assertEquals(Category.STYLE, findings.get(0).category());
        // suggestion / codeSnippet 缺省：suggestion 为空串，snippet 回退到 message
        assertEquals("", findings.get(0).suggestion());
    }

    @Test
    void parsesEmptyFindings() throws Exception {
        assertTrue(parser.parse("{\"findings\":[]}").isEmpty());
    }

    @Test
    void rejectsNonJson() {
        assertThrows(FindingParseException.class, () -> parser.parse("这根本不是 JSON"));
    }

    @Test
    void rejectsMissingFindingsArray() {
        assertThrows(FindingParseException.class, () -> parser.parse("{\"result\":\"ok\"}"));
    }

    @Test
    void rejectsIllegalEnum() {
        String json = """
                {"findings":[{"file":"A.java","line":1,"severity":"SUPER_BAD","category":"STYLE","message":"x"}]}
                """;
        assertThrows(FindingParseException.class, () -> parser.parse(json));
    }

    @Test
    void rejectsMissingRequiredField() {
        // 缺 message
        String json = """
                {"findings":[{"file":"A.java","line":1,"severity":"MINOR","category":"STYLE"}]}
                """;
        assertThrows(FindingParseException.class, () -> parser.parse(json));
    }

    @Test
    void rejectsBlankInput() {
        assertThrows(FindingParseException.class, () -> parser.parse("   "));
        assertThrows(FindingParseException.class, () -> parser.parse(null));
    }
}
