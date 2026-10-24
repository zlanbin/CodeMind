create table fingerprint_registration_outbox (
    task_id          uuid primary key references review_task(task_id),
    repo             varchar(255) not null,
    pr_number        integer      not null,
    fingerprints     jsonb        not null,
    created_at       timestamptz  not null default now(),
    next_attempt_at  timestamptz  not null default now(),
    lease_token      uuid,
    lease_until      timestamptz,
    attempts         integer      not null default 0 check (attempts >= 0),
    acknowledged_at  timestamptz,
    last_error       varchar(256),
    check ((lease_token is null) = (lease_until is null))
);

create index idx_fingerprint_outbox_due on fingerprint_registration_outbox(next_attempt_at)
    where acknowledged_at is null;
