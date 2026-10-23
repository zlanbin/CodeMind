create table integration.review_publication (
    task_id       varchar(255) primary key,
    event_payload jsonb        not null,
    content_digest char(64)    not null,
    repo          text         not null,
    pr_number     integer      not null check (pr_number > 0),
    head_sha      text         not null,
    state         varchar(20)  not null default 'READY'
        check (state in ('READY', 'IN_FLIGHT', 'UNCERTAIN', 'PUBLISHED')),
    mode          varchar(20)  not null default 'INLINE'
        check (mode in ('INLINE', 'SUMMARY_ONLY')),
    attempt_token text,
    review_id     bigint,
    inline_count  integer,
    degraded      boolean,
    created_at    timestamptz  not null default now(),
    updated_at    timestamptz  not null default now(),
    last_error    text,
    constraint review_publication_result_consistency check (
        (state <> 'PUBLISHED' and review_id is null and inline_count is null and degraded is null)
        or
        (state = 'PUBLISHED' and review_id is not null and inline_count is not null and degraded is not null)
    )
);

create index review_publication_state_idx
    on integration.review_publication (state);
