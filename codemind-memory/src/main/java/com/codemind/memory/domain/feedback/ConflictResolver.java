package com.codemind.memory.domain.feedback;

import com.codemind.memory.domain.memory.Memory;
import com.codemind.memory.domain.memory.MemoryKind;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 新否决规则与已有相关记忆的 ADD / UPDATE / DELETE / NOOP 判定（ARCHITECTURE 7.2 第 4 步）。
 *
 * <p>没有候选不调 LLM，直接 ADD。LLM 失败、输出非法、指向候选以外的 id，一律退回 ADD：
 * 多一条重复记忆的代价远小于误删一条有效记忆。
 */
public final class ConflictResolver {

    private static final String SYSTEM_PROMPT = """
            你维护一个代码评审项目的长期记忆。现在要写入一条新规则，下面给出已有的相关记忆。
            选择一个动作：
            - ADD：已有记忆与新规则无关
            - UPDATE：某条已有记忆和新规则说的是同一件事，给出合并后的一句话（保留新规则开头的 [维度] 前缀）
            - DELETE：某条 [COMMON_DEFECT] 记忆把新规则允许的写法当成缺陷，删掉它，content 为新规则。
              只能删 COMMON_DEFECT；两条 REJECTED_PATTERN 之间只能 UPDATE 或 NOOP
            - NOOP：某条已有记忆已经完整表达了新规则
            只输出 JSON：{"action": "ADD|UPDATE|DELETE|NOOP", "targetId": <已有记忆 id，ADD 时为 0>, "content": "<要写入的规则，NOOP 时为空>"}""";

    private final LlmClient llm;

    public ConflictResolver(LlmClient llm) {
        this.llm = Objects.requireNonNull(llm, "llm");
    }

    public ConflictDecision resolve(String newContent, List<Memory> candidates) {
        if (candidates.isEmpty()) {
            return ConflictDecision.add(newContent);
        }
        StringBuilder user = new StringBuilder("新规则：").append(newContent).append("\n\n已有记忆：\n");
        for (Memory m : candidates) {
            user.append("- id=").append(m.id()).append(" [").append(m.kind()).append("] ")
                    .append(m.content()).append('\n');
        }
        try {
            return parse(llm.complete(SYSTEM_PROMPT, user.toString()), newContent, candidates);
        } catch (LlmClient.LlmException | IllegalArgumentException e) {
            return ConflictDecision.add(newContent);
        }
    }

    static ConflictDecision parse(String raw, String newContent, List<Memory> candidates) {
        JsonNode root = LlmJson.readObject(raw);
        JsonNode action = root.get("action");
        if (action == null || !action.isTextual()) {
            throw new IllegalArgumentException("缺少 action");
        }
        ConflictDecision.Action a = ConflictDecision.Action.valueOf(action.textValue().strip().toUpperCase(Locale.ROOT));
        if (a == ConflictDecision.Action.ADD) {
            return ConflictDecision.add(newContent);
        }
        JsonNode target = root.get("targetId");
        if (target == null || !target.canConvertToLong()) {
            throw new IllegalArgumentException("缺少 targetId");
        }
        long id = target.asLong();
        Memory hit = candidates.stream().filter(m -> m.id() == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("targetId 不在候选内：" + id));
        // 结果里必须留下一条 REJECTED_PATTERN（M9b 只按它过滤）：
        // NOOP / UPDATE 只能落在已有的否决记忆上，落在缺陷或规范上等于「强化 / 改写被否决的东西」；
        // DELETE 只能删缺陷：团队规范只能人工删；两条否决记忆不互删——T6 实测里 👎 概括出的低可信规则
        // 以「矛盾」为由删掉了回复学到的高可信规则，否决之间的分歧只能 UPDATE 合并
        if ((a == ConflictDecision.Action.NOOP || a == ConflictDecision.Action.UPDATE)
                && hit.kind() != MemoryKind.REJECTED_PATTERN) {
            throw new IllegalArgumentException(a + " 只能作用于否决记忆，目标是 " + hit.kind());
        }
        if (a == ConflictDecision.Action.DELETE && hit.kind() != MemoryKind.COMMON_DEFECT) {
            throw new IllegalArgumentException("DELETE 只能删常见缺陷，目标是 " + hit.kind());
        }
        return switch (a) {
            case NOOP -> new ConflictDecision(a, id, null);
            case DELETE -> new ConflictDecision(a, id, newContent);
            case UPDATE -> {
                JsonNode content = root.get("content");
                if (content == null || !content.isTextual() || content.textValue().isBlank()
                        || content.textValue().length() > FeedbackExtractor.MAX_RULE_LENGTH + 40) {
                    throw new IllegalArgumentException("UPDATE 缺少合法 content");
                }
                yield new ConflictDecision(a, id, withPrefixOf(newContent, content.textValue().strip()));
            }
            case ADD -> throw new IllegalStateException("unreachable");
        };
    }

    private static final java.util.regex.Pattern PREFIX = java.util.regex.Pattern.compile("^\\[[A-Z_]{1,32}]\\s*");

    /** 合并后的正文统一用新规则的 {@code [CATEGORY]} 前缀：模型常把前缀丢掉或改掉，M9b 靠它看维度。 */
    static String withPrefixOf(String newContent, String merged) {
        var m = PREFIX.matcher(newContent);
        if (!m.find()) {
            return merged;
        }
        return newContent.substring(0, m.end()).strip() + " " + PREFIX.matcher(merged).replaceFirst("");
    }
}
