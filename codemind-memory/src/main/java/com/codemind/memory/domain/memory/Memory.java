package com.codemind.memory.domain.memory;

import java.time.Instant;
import java.util.Objects;

/**
 * L2 项目长期记忆。
 *
 * @param importance   (0, 1]，由写入方给出；同内容再次写入取较大者
 * @param reinforcedAt 最近一次写入（强化）时间，衰减从这里算起；召回不刷新它
 */
public record Memory(long id, String project, MemoryKind kind, String content, double importance,
                     MemoryStatus status, Instant createdAt, Instant reinforcedAt) {

    public static final double DEFAULT_IMPORTANCE = 0.5;
    /** project 是 owner/repo 形式的仓库名。 */
    public static final int MAX_PROJECT_LENGTH = 255;

    public Memory {
        requireProject(project);
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(content, "content");
        requireImportance(importance);
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(reinforcedAt, "reinforcedAt");
    }

    public static String requireProject(String project) {
        if (project == null || project.isBlank()) {
            throw new IllegalArgumentException("project 不能为空");
        }
        if (project.length() > MAX_PROJECT_LENGTH) {
            throw new IllegalArgumentException("project 不能超过 " + MAX_PROJECT_LENGTH + " 字符");
        }
        return project;
    }

    public static double requireImportance(double importance) {
        if (!(importance > 0 && importance <= 1)) {
            throw new IllegalArgumentException("importance 必须在 (0, 1] 内：" + importance);
        }
        return importance;
    }
}
