package com.codemind.review.domain.dag;

import java.util.List;
import java.util.Optional;

/**
 * 节点运行记录仓储（checkpoint 持久化）。
 */
public interface NodeRunRepository {

    void save(NodeRun nodeRun);

    Optional<NodeRun> findByTaskAndNode(String taskId, String nodeId);

    /** 查询任务下所有已完成的节点运行记录。 */
    List<NodeRun> findCompletedByTask(String taskId);
}
