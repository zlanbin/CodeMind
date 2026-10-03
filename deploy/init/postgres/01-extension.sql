-- 启用 pgvector，供 memory-service 和 rule-service 使用。
-- 建表语句在对应里程碑（M8 / M11）中补充。
CREATE EXTENSION IF NOT EXISTS vector;
