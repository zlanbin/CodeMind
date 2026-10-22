-- review_chunk 主键改为 (task_id, chunk_id)（M7a）。
-- chunkId 是任务内 ID（ARCHITECTURE 4：挂在 ReviewTask 聚合内），SemanticChunker 每个任务都从 chunk-0 编号。
-- V2 把 chunk_id 设成全局主键，库里有过一个任务的切片后，第二个任务的 plan-chunks 必撞主键。
alter table review_chunk drop constraint review_chunk_pkey;

alter table review_chunk add constraint review_chunk_pkey primary key (task_id, chunk_id);
