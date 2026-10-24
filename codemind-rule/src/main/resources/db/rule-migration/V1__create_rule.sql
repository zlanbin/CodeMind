-- M11：规范文档 RAG（ARCHITECTURE 7 留的 rule-service 范围）。
-- 扩展装在 public（vector / pg_trgm 由 deploy/init 建好，同 memory 做法），本 schema 对象一律限定名引用。
create extension if not exists vector with schema public;
create extension if not exists pg_trgm with schema public;

create table rule.document (
    id          bigserial    primary key,
    project     text         not null,
    title       text         not null,
    status      varchar(16)  not null default 'ACTIVE'
        check (status in ('ACTIVE', 'ARCHIVED')),
    version     integer      not null check (version > 0),
    uploaded_at timestamptz  not null
);

-- 同 project + title 只能有一个 ACTIVE 版本；重新上传先把旧版本转 ARCHIVED 再插入新版本
create unique index rule_document_active_unique
    on rule.document (project, title)
    where status = 'ACTIVE';

create table rule.chunk (
    id            bigserial    primary key,
    document_id   bigint       not null references rule.document (id),
    project       text         not null,
    section_path  text         not null,
    heading       text         not null,
    content       text         not null,
    -- 维度与 rule.infrastructure.embedding.HashingEmbedder 一致（算法从 memory 复制，各自独立维护）
    embedding     public.vector(256) not null
);

create index rule_chunk_project_document_idx on rule.chunk (project, document_id);
-- 两路召回都不建近似索引，按 project 过滤后精确扫描（同 memory M8 S6 结论：HNSW/GIN 带过滤会拿不满候选）
