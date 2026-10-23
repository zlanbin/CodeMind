-- M8：L2 项目长期记忆 + L1 已发布 Finding 指纹（ARCHITECTURE 7）。
-- 扩展装在 public（vector 由 deploy/init 建好），本 schema 的对象一律限定名引用，不依赖 search_path。
create extension if not exists vector with schema public;
create extension if not exists pg_trgm with schema public;

create table memory.memory (
    id            bigserial    primary key,
    project       text         not null,
    kind          varchar(32)  not null
        check (kind in ('CONVENTION', 'REJECTED_PATTERN', 'COMMON_DEFECT')),
    content       text         not null,
    content_hash  char(64)     not null,
    -- 维度与 HashingEmbedder.DIMENSIONS 一致；换真实 embedding 模型要新迁移改列并重算
    embedding     public.vector(256) not null,
    importance    double precision not null check (importance > 0 and importance <= 1),
    status        varchar(16)  not null default 'ACTIVE'
        check (status in ('ACTIVE', 'ARCHIVED')),
    created_at    timestamptz  not null,
    reinforced_at timestamptz  not null,
    archived_at   timestamptz,
    constraint memory_unique_content unique (project, kind, content_hash)
);

create index memory_project_status_idx on memory.memory (project, status);
-- 两路召回都不建近似索引，按 (project, status) 过滤后精确扫描（M8 S6 评审）：
-- 向量一路的 HNSW 是全表索引，带 project 过滤时先取全局 ef_search(默认 40) 个近邻再过滤，
-- 小项目会拿不满候选甚至为空；三元组一路的 % 运算符默认阈值 0.3 会滤掉中文近义句（实测约 0.19）。
-- 单项目记忆量到数万条再考虑 hnsw.iterative_scan / 分区索引、GIN + set local pg_trgm.similarity_threshold

create table memory.task_fingerprint (
    repo         text        not null,
    pr_number    integer     not null check (pr_number > 0),
    fingerprint  varchar(128) not null,
    created_at   timestamptz not null default now(),
    primary key (repo, pr_number, fingerprint)
);
