-- 语义切片表（M2）。PlanChunks 节点把 diff 切成 chunk，每片对应一个 LLM 子会话。
-- M2 只建表，持久化接线（JdbcChunkRepository）在 M3 DAG 引擎落 checkpoint 时补。
create table if not exists review_chunk
(
    chunk_id        varchar(64)  primary key,
    task_id         uuid         not null references review_task (task_id),
    seq             integer      not null,
    files           text         not null,
    changed_symbols text         not null,
    diff_content    text         not null,
    token_estimate  integer      not null,
    oversized       boolean      not null default false,
    created_at      timestamptz  not null default now(),
    constraint review_chunk_token_estimate_nonneg check (token_estimate >= 0)
);

-- 一个任务下的 chunk 按 seq 有序；按 task_id 查全部切片。
create unique index if not exists uk_review_chunk_task_seq
    on review_chunk (task_id, seq);

comment on table review_chunk is '评审语义切片，一片对应一个 LLM 子会话';
comment on column review_chunk.files is '本片覆盖的文件路径，JSON 数组';
comment on column review_chunk.changed_symbols is '本片涉及的变更符号（Java 方法限定名），JSON 数组';
comment on column review_chunk.oversized is '单片即超 diff 预算的退化场景标记';
