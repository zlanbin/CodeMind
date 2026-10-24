create table last_reviewed_head (
    repo        varchar(255) not null,
    pr_number   integer      not null,
    base_sha    varchar(64)  not null,
    head_sha    varchar(64)  not null,
    updated_at  timestamptz  not null default now(),
    primary key (repo, pr_number)
);
