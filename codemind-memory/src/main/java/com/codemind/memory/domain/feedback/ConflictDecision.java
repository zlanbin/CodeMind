package com.codemind.memory.domain.feedback;

import java.util.Objects;

/**
 * 新否决规则与已有记忆的冲突消解结果（ARCHITECTURE 7.2 第 4 步，M9 D11）。
 *
 * @param targetId ADD 时为 0；其余为被改写 / 删除 / 强化的已有记忆 id
 * @param content  ADD / UPDATE / DELETE 后要写入的规则正文；NOOP 时为 null（只强化 target）
 */
public record ConflictDecision(Action action, long targetId, String content) {

    public enum Action {
        /** 新增，无相关已有记忆。 */
        ADD,
        /** 与已有记忆讲的是同一件事，合并改写它。 */
        UPDATE,
        /** 已有记忆与新否决矛盾（如「要求 X」vs「允许不 X」），删掉它再新增。 */
        DELETE,
        /** 已有记忆已经覆盖，只强化它。 */
        NOOP
    }

    public ConflictDecision {
        Objects.requireNonNull(action, "action");
        if (action == Action.ADD && targetId != 0) {
            throw new IllegalArgumentException("ADD 不应带 targetId");
        }
        if (action != Action.ADD && targetId <= 0) {
            throw new IllegalArgumentException(action + " 需要 targetId");
        }
        if (action != Action.NOOP && (content == null || content.isBlank())) {
            throw new IllegalArgumentException(action + " 需要 content");
        }
    }

    public static ConflictDecision add(String content) {
        return new ConflictDecision(Action.ADD, 0, content);
    }
}
