package com.codemind.review.infrastructure.persistence;

import com.codemind.review.domain.dag.NodeRun;
import com.codemind.review.domain.dag.NodeRunRepository;
import com.codemind.review.domain.dag.NodeRunStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 节点运行记录 JDBC 实现（M3 checkpoint 持久化）。
 */
@Repository
public class JdbcNodeRunRepository implements NodeRunRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public JdbcNodeRunRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(NodeRun nodeRun) {
        String resultJson = serializeResult(nodeRun.result());
        jdbcClient.sql("""
                insert into node_run (run_id, task_id, node_id, status, result, error_message, started_at, completed_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (task_id, node_id) do update set
                    status = excluded.status,
                    result = excluded.result,
                    error_message = excluded.error_message,
                    completed_at = excluded.completed_at
                """)
                .params(UUID.fromString(nodeRun.runId()), nodeRun.taskId(), nodeRun.nodeId(),
                        nodeRun.status().name(), resultJson, nodeRun.errorMessage(),
                        Timestamp.from(nodeRun.startedAt()),
                        nodeRun.completedAt() != null ? Timestamp.from(nodeRun.completedAt()) : null)
                .update();
    }

    @Override
    public Optional<NodeRun> findByTaskAndNode(String taskId, String nodeId) {
        return jdbcClient.sql("""
                select run_id, task_id, node_id, status, result, error_message, started_at, completed_at
                from node_run
                where task_id = ? and node_id = ?
                """)
                .param(taskId)
                .param(nodeId)
                .query((rs, rowNum) -> NodeRun.rehydrate(
                        rs.getString("run_id"),
                        rs.getString("task_id"),
                        rs.getString("node_id"),
                        NodeRunStatus.valueOf(rs.getString("status")),
                        deserializeResult(rs.getString("result")),
                        rs.getString("error_message"),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("completed_at") != null
                                ? rs.getTimestamp("completed_at").toInstant()
                                : null
                ))
                .optional();
    }

    @Override
    public List<NodeRun> findCompletedByTask(String taskId) {
        return jdbcClient.sql("""
                select run_id, task_id, node_id, status, result, error_message, started_at, completed_at
                from node_run
                where task_id = ? and status = 'COMPLETED'
                order by completed_at
                """)
                .param(taskId)
                .query((rs, rowNum) -> NodeRun.rehydrate(
                        rs.getString("run_id"),
                        rs.getString("task_id"),
                        rs.getString("node_id"),
                        NodeRunStatus.valueOf(rs.getString("status")),
                        deserializeResult(rs.getString("result")),
                        rs.getString("error_message"),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("completed_at").toInstant()
                ))
                .list();
    }

    private String serializeResult(Object result) {
        if (result == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("节点结果序列化失败: " + e.getMessage(), e);
        }
    }

    private Object deserializeResult(String json) {
        if (json == null) {
            return null;
        }
        try {
            // 反序列化为 Object（调用方需按具体类型转换）
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("节点结果反序列化失败: " + e.getMessage(), e);
        }
    }
}
