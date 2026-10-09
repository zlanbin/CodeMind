-- 节点运行记录表（M3），用于 DAG checkpoint。
-- 节点完成后结果写此表，失败后从断点续跑，已完成节点不重复调用 LLM。
create table if not exists node_run
(
    run_id       uuid         primary key,
    task_id      varchar(64)  not null,
    node_id      varchar(64)  not null,
    status       varchar(32)  not null,
    result       text,
    error_message text,
    started_at   timestamptz  not null default now(),
    completed_at timestamptz,
    constraint node_run_status_check check (status in ('RUNNING', 'COMPLETED', 'FAILED'))
);

-- 按任务查询已完成节点（续跑时加载 checkpoint）
create index if not exists idx_node_run_task_status
    on node_run (task_id, status);

-- 按任务 + 节点查询最新运行记录（检查是否已完成）
create unique index if not exists uk_node_run_task_node
    on node_run (task_id, node_id);

comment on table node_run is 'DAG 节点运行记录（checkpoint），用于失败续跑';
comment on column node_run.result is '节点产出（COMPLETED 时非空），JSON 序列化';
comment on column node_run.error_message is '失败原因（FAILED 时非空）';
