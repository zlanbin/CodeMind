-- 评审任务表（M1）。ChangeMap / chunk / finding 等表在 M2、M3、M5 追加迁移。
create table if not exists review_task
(
    task_id         uuid         primary key,
    repo            varchar(255) not null,
    pr_number       integer      not null,
    head_sha        varchar(64)  not null,
    base_sha        varchar(64)  not null,
    source_event_id uuid         not null,
    status          varchar(32)  not null,
    created_at      timestamptz  not null default now(),
    updated_at      timestamptz  not null default now(),
    constraint review_task_pr_number_positive check (pr_number > 0)
);

-- 幂等兜底：同一 PR 的同一 head commit 只有一个任务。
-- Redis 幂等键是第一道闸（挡 webhook 重投），TTL 过期后由本索引拦住。
create unique index if not exists uk_review_task_repo_pr_head
    on review_task (repo, pr_number, head_sha);

comment on table review_task is '评审任务聚合根';
comment on column review_task.source_event_id is '触发本任务的 PullRequestReceived 事件 ID';
