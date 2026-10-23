package com.codemind.memory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

/** M8 起 memory 依赖 PG：上下文加载即验证 memory schema 的迁移能跑通。 */
@SpringBootTest
class MemoryApplicationIT {

    @Autowired
    JdbcClient jdbc;

    @Test
    void contextLoadsAndMemorySchemaIsMigrated() {
        Integer tables = jdbc.sql("""
                select count(*) from information_schema.tables
                where table_schema = 'memory' and table_name in ('memory', 'task_fingerprint')
                """).query(Integer.class).single();
        assertThat(tables).isEqualTo(2);
    }
}
