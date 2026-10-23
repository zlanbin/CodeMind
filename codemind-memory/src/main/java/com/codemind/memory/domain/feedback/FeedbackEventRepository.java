package com.codemind.memory.domain.feedback;

/** 已处理反馈事件的登记（M9 D10）：LLM 不确定，同一事件重放不能再学一遍。 */
public interface FeedbackEventRepository {

    boolean isProcessed(String eventId);

    /**
     * 登记事件，与记忆写入在同一事务里、且作为事务第一步调用。已存在返回 false（并发重投时后到的一方据此回滚）。
     *
     * @param outcome 简短的处理摘要；写记忆前先占位，写完用 {@link #recordOutcome} 覆盖
     */
    boolean markProcessed(String eventId, String project, String outcome);

    /** 覆盖处理摘要，如 {@code ADD#12} 或 {@code DEGRADED:ADD#12}。 */
    void recordOutcome(String eventId, String outcome);
}
