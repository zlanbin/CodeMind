-- M9：已处理的反馈事件。integration 重投同一 eventId，memory 据此只学一次。
create table memory.feedback_event (
    event_id     varchar(64)  primary key,
    project      text         not null,
    outcome      text         not null,
    processed_at timestamptz  not null default now()
);
