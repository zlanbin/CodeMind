package com.codemind.rule.application;

import com.codemind.rule.domain.rule.RuleChunk;
import com.codemind.rule.domain.rule.RuleChunkCandidate;
import com.codemind.rule.domain.rule.RuleChunkRepository;
import com.codemind.rule.domain.rule.RuleDocumentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.convention.TestBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 版本替换的事务边界（spec D7「整个过程在一个事务内完成」，S6 规格轴/标准轴共同发现：
 * archiveActiveByProjectAndTitle → insert → insertAll 三条独立写无事务保护，中途失败会留下
 * 「一个 ACTIVE 文档都不剩」或「ACTIVE 文档存在但零 chunk」的脏状态，召回静默返回空，
 * 规范条款整份消失且无人可知）。
 *
 * <p>用 {@link TestBean} 把容器里的 {@link RuleChunkRepository} 替换成「insertAll 必然抛异常」
 * 的测试替身，驱动「archive 旧版本成功后、写新版本失败」这个真实场景，验证
 * {@code RuleIngestionService.upload} 加了 {@code @Transactional} 后确实整体回滚。
 */
@SpringBootTest
class RuleIngestionServiceIT {

    @TestBean(name = "jdbcRuleChunkRepository", methodName = "failingChunkRepository")
    private RuleChunkRepository chunkRepositoryOverride;

    static RuleChunkRepository failingChunkRepository() {
        return new RuleChunkRepository() {
            @Override
            public void insertAll(long documentId, String project, List<RuleChunk> chunks,
                                  List<float[]> embeddings) {
                throw new IllegalStateException("模拟写入失败（S6 评审：验证事务回滚）");
            }

            @Override
            public List<RuleChunkCandidate> findCandidates(String project, String queryText,
                                                            float[] queryEmbedding, int limit) {
                return List.of();
            }
        };
    }

    @Autowired
    private RuleIngestionService ruleIngestionService;
    @Autowired
    private RuleDocumentRepository documentRepository;
    @Autowired
    private JdbcClient jdbc;

    private final String project = "codemind/rule-ingestion-it-" + UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        jdbc.sql("delete from rule.chunk where project = :p").param("p", project).update();
        jdbc.sql("delete from rule.document where project = :p").param("p", project).update();
    }

    @Test
    void reuploadFailureDuringChunkInsertDoesNotLeaveZeroActiveVersions() {
        // 第一版用真实仓储直接插入文档行（绕开 chunkRepository，避免被测试替身干扰）
        documentRepository.insert(project, "文档", 1, java.time.Instant.now());

        assertThatThrownBy(() -> ruleIngestionService.upload(project, "文档", "# 标题\n第二版内容。"))
                .isInstanceOf(IllegalStateException.class);

        Integer activeCount = jdbc.sql(
                "select count(*) from rule.document where project = :p and title = :t and status = 'ACTIVE'")
                .param("p", project).param("t", "文档").query(Integer.class).single();
        assertThat(activeCount)
                .as("事务回滚后，旧版本应仍是 ACTIVE——不能因为新版本写入失败就一个 ACTIVE 版本都不剩")
                .isEqualTo(1);

        Integer archivedCount = jdbc.sql(
                "select count(*) from rule.document where project = :p and title = :t and status = 'ARCHIVED'")
                .param("p", project).param("t", "文档").query(Integer.class).single();
        assertThat(archivedCount)
                .as("失败的新版本不应残留在数据库里（无论 ACTIVE 还是 ARCHIVED）")
                .isZero();
    }
}
