create table review_publication_outbox (
    task_id uuid primary key references review_task(task_id),
    event_id uuid not null unique,
    payload jsonb not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    next_attempt_at timestamptz not null default now(),
    lease_token uuid,
    lease_until timestamptz,
    attempts integer not null default 0 check (attempts >= 0),
    acknowledged_at timestamptz,
    last_error varchar(256),
    check ((lease_token is null) = (lease_until is null))
);

create index idx_review_outbox_due on review_publication_outbox(next_attempt_at)
    where acknowledged_at is null;
