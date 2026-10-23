-- M9：开发者对 CodeMind 行级评论的反馈（👎 / 回复）。登记与投递分离：轮询只登记，后台把 PENDING 发 MQ。
-- (repo, source_type, source_id) 唯一：同一 reaction / 回复反复被轮询到只登记一次，事件 ID 固定，重投同一 eventId。
create table integration.finding_feedback (
    event_id      varchar(64)  primary key,
    repo          text         not null,
    pr_number     integer      not null check (pr_number > 0),
    comment_id    bigint       not null check (comment_id > 0),
    source_type   varchar(16)  not null check (source_type in ('THUMBS_DOWN', 'REPLY')),
    source_id     bigint       not null check (source_id > 0),
    event_payload jsonb        not null,
    -- FAILED：payload 坏了或投递失败次数到上限，不再重投，避免一条坏数据卡住整批（S6 评审）
    -- SUPERSEDED：同一人对同一条评论既 👎 又回复，只学回复；👎 留行去重、永不投递（T6 实测后加）
    state         varchar(16)  not null default 'PENDING'
        check (state in ('PENDING', 'SENT', 'FAILED', 'SUPERSEDED')),
    attempts      integer      not null default 0 check (attempts >= 0),
    created_at    timestamptz  not null default now(),
    sent_at       timestamptz,
    last_error    text,
    constraint finding_feedback_source_unique unique (repo, source_type, source_id),
    constraint finding_feedback_sent_consistency check ((state = 'SENT') = (sent_at is not null))
);

create index finding_feedback_pending_idx on integration.finding_feedback (created_at) where state = 'PENDING';
