package com.codemind.review.domain.dag;

import java.time.Duration;

/**
 * DAG 节点接口（ARCHITECTURE 5.1）。
 *
 * <p>节点执行纯逻辑：拿上下文、返回结果、不管调度。超时、重试、checkpoint 由调度器处理。
 */
public interface Node {

    /**
     * 节点唯一标识（同一 DAG 内不重复）。
     */
    String id();

    /**
     * 执行节点逻辑。
     *
     * @param context 执行上下文（任务标识、已完成节点结果、共享状态）
     * @return 本节点产出（结果对象或动态扇出的子图），由调度器写入 checkpoint
     * @throws Exception 节点执行失败（调度器根据重试策略决定重试或转 FAILED）
     */
    Object execute(NodeContext context) throws Exception;

    /**
     * 节点超时配置，null 表示使用调度器默认超时。
     */
    default Duration timeout() {
        return null;
    }

    /**
     * 重试次数，0 表示不重试，null 表示使用调度器默认重试策略。
     */
    default Integer retries() {
        return null;
    }
}
