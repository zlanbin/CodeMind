package com.codemind.review.domain.chunk;

import java.util.List;

/**
 * 语义切片仓储（M2 建表，M3 补接线）。
 */
public interface ChunkRepository {

    /**
     * 批量保存任务的所有切片。调用前清空该任务的旧切片（续跑重新切片场景）。
     */
    void saveAll(String taskId, List<ReviewChunk> chunks);

    /**
     * 查询任务的所有切片，按 seq 升序。
     */
    List<ReviewChunk> findByTask(String taskId);
}
