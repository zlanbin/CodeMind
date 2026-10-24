package com.codemind.rule;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

/** M11 起 rule 依赖 PG：上下文加载即验证 rule schema 的迁移能跑通。 */
@SpringBootTest
class RuleApplicationIT {

    @Autowired
    JdbcClient jdbc;

    @Test
    void contextLoadsAndRuleSchemaIsMigrated() {
        Integer tables = jdbc.sql("""
                select count(*) from information_schema.tables
                where table_schema = 'rule' and table_name in ('document', 'chunk')
                """).query(Integer.class).single();
        assertThat(tables).isEqualTo(2);
    }
}
