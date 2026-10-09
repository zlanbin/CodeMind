package com.codemind.review.infrastructure.persistence;

import com.codemind.review.domain.chunk.ChunkRepository;
import com.codemind.review.domain.chunk.ReviewChunk;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 语义切片 JDBC 实现（M2 建表，M3 补接线）。
 */
@Repository
public class JdbcChunkRepository implements ChunkRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public JdbcChunkRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void saveAll(String taskId, List<ReviewChunk> chunks) {
        // 先清空旧切片（续跑重新切片场景）
        jdbcClient.sql("delete from review_chunk where task_id = ?")
                .param(UUID.fromString(taskId))
                .update();

        // 批量插入
        for (int i = 0; i < chunks.size(); i++) {
            ReviewChunk chunk = chunks.get(i);
            jdbcClient.sql("""
                    insert into review_chunk (chunk_id, task_id, seq, files, changed_symbols, diff_content, token_estimate, oversized)
                    values (?, ?, ?, ?::json, ?::json, ?, ?, ?)
                    """)
                    .params(chunk.chunkId(), UUID.fromString(taskId), i,
                            toJson(chunk.files()), toJson(chunk.changedSymbols()),
                            chunk.diffContent(), chunk.tokenEstimate(), chunk.oversized())
                    .update();
        }
    }

    @Override
    public List<ReviewChunk> findByTask(String taskId) {
        return jdbcClient.sql("""
                select chunk_id, files, changed_symbols, diff_content, token_estimate, oversized
                from review_chunk
                where task_id = ?
                order by seq
                """)
                .param(UUID.fromString(taskId))
                .query((rs, rowNum) -> new ReviewChunk(
                        rs.getString("chunk_id"),
                        fromJson(rs.getString("files"), new TypeReference<>() {}),
                        fromJson(rs.getString("changed_symbols"), new TypeReference<>() {}),
                        rs.getString("diff_content"),
                        rs.getInt("token_estimate"),
                        rs.getBoolean("oversized")
                ))
                .list();
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 序列化失败", e);
        }
    }

    private <T> T fromJson(String json, TypeReference<T> typeRef) {
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 反序列化失败: " + json, e);
        }
    }
}
