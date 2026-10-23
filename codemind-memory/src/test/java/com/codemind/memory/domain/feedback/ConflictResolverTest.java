package com.codemind.memory.domain.feedback;

import com.codemind.memory.domain.feedback.ConflictDecision.Action;
import com.codemind.memory.domain.memory.Memory;
import com.codemind.memory.domain.memory.MemoryKind;
import com.codemind.memory.domain.memory.MemoryStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConflictResolverTest {

    private static final String NEW = "[DESIGN] 本项目允许 Controller 直接返回实体";
    private final FeedbackExtractorTest.ScriptedLlm llm = new FeedbackExtractorTest.ScriptedLlm();
    private final ConflictResolver resolver = new ConflictResolver(llm);

    private final Memory defect = memory(7, MemoryKind.COMMON_DEFECT, "Controller 必须返回 DTO");
    private final Memory pattern = memory(8, MemoryKind.REJECTED_PATTERN, "[DESIGN] 允许返回实体");
    private final Memory convention = memory(9, MemoryKind.CONVENTION, "接口统一返回 Result");

    @Test
    void noCandidatesMeansAddWithoutCallingLlm() {
        assertThat(resolver.resolve(NEW, List.of())).isEqualTo(ConflictDecision.add(NEW));
        assertThat(llm.calls).isZero();
    }

    @Test
    void eachActionIsParsedAgainstCandidates() {
        llm.replies.add("{\"action\": \"UPDATE\", \"targetId\": 8, \"content\": \"[DESIGN] 允许 Controller 直接返回实体\"}");
        assertThat(resolver.resolve(NEW, List.of(defect, pattern)))
                .isEqualTo(new ConflictDecision(Action.UPDATE, 8, "[DESIGN] 允许 Controller 直接返回实体"));

        llm.replies.add("```json\n{\"action\": \"delete\", \"targetId\": 7}\n```");
        assertThat(resolver.resolve(NEW, List.of(defect, pattern)))
                .isEqualTo(new ConflictDecision(Action.DELETE, 7, NEW));

        llm.replies.add("{\"action\": \"NOOP\", \"targetId\": 8, \"content\": \"\"}");
        assertThat(resolver.resolve(NEW, List.of(defect, pattern))).isEqualTo(new ConflictDecision(Action.NOOP, 8, null));

        llm.replies.add("{\"action\": \"ADD\", \"targetId\": 0}");
        assertThat(resolver.resolve(NEW, List.of(defect))).isEqualTo(ConflictDecision.add(NEW));

        assertThat(llm.userPrompts.get(0)).contains("id=7 [COMMON_DEFECT] Controller 必须返回 DTO", "id=8", NEW);
    }

    @Test
    void anythingInvalidFallsBackToAdd() {
        llm.replies.add("{\"action\": \"DELETE\", \"targetId\": 999}");      // 不在候选内
        llm.replies.add("{\"action\": \"UPDATE\", \"targetId\": 8}");        // 缺 content
        llm.replies.add("{\"action\": \"MERGE\", \"targetId\": 8}");         // 未知动作
        llm.replies.add("胡言乱语");

        for (int i = 0; i < 4; i++) {
            assertThat(resolver.resolve(NEW, List.of(defect, pattern))).isEqualTo(ConflictDecision.add(NEW));
        }
        llm.failAlways = true;
        assertThat(resolver.resolve(NEW, List.of(defect))).isEqualTo(ConflictDecision.add(NEW));
    }

    @Test
    void teamConventionsAreNeverRewrittenDeletedOrReinforcedByFeedback() {
        llm.replies.add("{\"action\": \"DELETE\", \"targetId\": 9}");
        llm.replies.add("{\"action\": \"UPDATE\", \"targetId\": 9, \"content\": \"改写规范\"}");
        llm.replies.add("{\"action\": \"NOOP\", \"targetId\": 9}");

        for (int i = 0; i < 3; i++) {
            assertThat(resolver.resolve(NEW, List.of(convention))).isEqualTo(ConflictDecision.add(NEW));
        }
    }

    @Test
    void noopOrUpdateOnADefectWouldStrengthenTheRejectedThingSoFallsBackToAdd() {
        // S6 规格轴严重项：NOOP 落在 COMMON_DEFECT 上会抬高被否决缺陷的 importance，且一条否决记忆都不留
        llm.replies.add("{\"action\": \"NOOP\", \"targetId\": 7}");
        llm.replies.add("{\"action\": \"UPDATE\", \"targetId\": 7, \"content\": \"改写缺陷\"}");

        assertThat(resolver.resolve(NEW, List.of(defect))).isEqualTo(ConflictDecision.add(NEW));
        assertThat(resolver.resolve(NEW, List.of(defect))).isEqualTo(ConflictDecision.add(NEW));
    }

    @Test
    void oneRejectionNeverDeletesAnother() {
        // T6 实测：👎 学到的规则把回复学到的规则当矛盾删了
        llm.replies.add("{\"action\": \"DELETE\", \"targetId\": 8}");

        assertThat(resolver.resolve(NEW, List.of(pattern))).isEqualTo(ConflictDecision.add(NEW));
    }

    @Test
    void updateAlwaysKeepsTheNewRulesCategoryPrefix() {
        llm.replies.add("{\"action\": \"UPDATE\", \"targetId\": 8, \"content\": \"本项目可以直接返回实体\"}");
        llm.replies.add("{\"action\": \"UPDATE\", \"targetId\": 8, \"content\": \"[STYLE] 本项目可以直接返回实体\"}");

        assertThat(resolver.resolve(NEW, List.of(pattern)).content()).isEqualTo("[DESIGN] 本项目可以直接返回实体");
        assertThat(resolver.resolve(NEW, List.of(pattern)).content()).isEqualTo("[DESIGN] 本项目可以直接返回实体");
    }

    private static Memory memory(long id, MemoryKind kind, String content) {
        Instant t = Instant.parse("2026-10-01T00:00:00Z");
        return new Memory(id, "o/r", kind, content, 0.5, MemoryStatus.ACTIVE, t, t);
    }
}
