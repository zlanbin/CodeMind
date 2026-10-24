package com.codemind.rule.infrastructure.persistence;

import com.codemind.rule.domain.rule.Embedder;
import com.codemind.rule.domain.rule.RuleChunk;
import com.codemind.rule.domain.rule.RuleChunkCandidate;
import com.codemind.rule.domain.rule.RuleChunkRepository;
import com.codemind.rule.domain.rule.RuleDocument;
import com.codemind.rule.domain.rule.RuleDocumentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@SpringBootTest
class JdbcRuleChunkRepositoryIT {

    private static final Instant T0 = Instant.parse("2026-10-24T00:00:00Z");

    @Autowired
    RuleChunkRepository chunkRepository;
    @Autowired
    RuleDocumentRepository documentRepository;
    @Autowired
    Embedder embedder;
    @Autowired
    JdbcClient jdbc;

    private final String project = "codemind/rule-chunk-it-" + UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        jdbc.sql("delete from rule.chunk where project = :p").param("p", project).update();
        jdbc.sql("delete from rule.document where project = :p").param("p", project).update();
    }

    @Test
    void insertedChunksAreFindableByVectorOrTextChannel() {
        RuleDocument doc = documentRepository.insert(project, "接口设计规范", 1, T0);
        RuleChunk chunk = new RuleChunk(0, doc.id(), project, "3.2.1 方法命名",
                "方法命名", "公开方法命名必须用名词加动词结构");
        chunkRepository.insertAll(doc.id(), project, List.of(chunk),
                List.of(embedder.embed(chunk.content())));

        String query = "公开方法命名必须用名词加动词结构";
        List<RuleChunkCandidate> candidates =
                chunkRepository.findCandidates(project, query, embedder.embed(query), 50);

        assertThat(candidates).hasSize(1);
        RuleChunkCandidate found = candidates.get(0);
        assertThat(found.chunk().sectionPath()).isEqualTo("3.2.1 方法命名");
        assertThat(found.documentTitle()).isEqualTo("接口设计规范");
        assertThat(found.similarity()).isCloseTo(1.0, within(1e-5));
    }

    @Test
    void textChannelReturnsRowsTheVectorLimitWouldCut() {
        RuleDocument doc = documentRepository.insert(project, "文档", 1, T0);
        // 两条干扰项含查询的全部 bigram（向量更近）但不含连续三字（三元组为 0）；
        // 目标向量更远、三元组 > 0。limit=1 时向量一路只会取干扰项，目标只能靠三元组一路进来
        RuleChunk decoy1 = new RuleChunk(0, doc.id(), project, "p1", "h1", "库存校验验库");
        RuleChunk decoy2 = new RuleChunk(0, doc.id(), project, "p2", "h2", "验库库存校验");
        RuleChunk target = new RuleChunk(0, doc.id(), project, "p3", "h3", "必须校验库存数量");
        chunkRepository.insertAll(doc.id(), project, List.of(decoy1, decoy2, target), List.of(
                embedder.embed(decoy1.content()), embedder.embed(decoy2.content()), embedder.embed(target.content())));

        String query = "校验库存";
        List<RuleChunkCandidate> candidates = chunkRepository.findCandidates(project, query, embedder.embed(query), 1);

        assertThat(candidates).hasSize(2);
        assertThat(candidates).anySatisfy(c -> assertThat(c.chunk().sectionPath()).isEqualTo("p3"));
    }

    @Test
    void candidatesOnlyFromRequestedProject() {
        RuleDocument docA = documentRepository.insert(project, "文档A", 1, T0);
        RuleChunk chunkA = new RuleChunk(0, docA.id(), project, "pA", "hA", "同一句话出现在两个项目");
        chunkRepository.insertAll(docA.id(), project, List.of(chunkA), List.of(embedder.embed(chunkA.content())));

        String otherProject = project + "-other";
        RuleDocument docB = documentRepository.insert(otherProject, "文档B", 1, T0);
        RuleChunk chunkB = new RuleChunk(0, docB.id(), otherProject, "pB", "hB", "同一句话出现在两个项目");
        chunkRepository.insertAll(docB.id(), otherProject, List.of(chunkB), List.of(embedder.embed(chunkB.content())));

        try {
            String query = "同一句话出现在两个项目";
            List<RuleChunkCandidate> candidates =
                    chunkRepository.findCandidates(project, query, embedder.embed(query), 50);

            assertThat(candidates).hasSize(1);
            assertThat(candidates.get(0).chunk().sectionPath()).isEqualTo("pA");
        } finally {
            jdbc.sql("delete from rule.chunk where project = :p").param("p", otherProject).update();
            jdbc.sql("delete from rule.document where project = :p").param("p", otherProject).update();
        }
    }

    @Test
    void candidatesFromArchivedDocumentAreExcluded() {
        // 版本替换只改 rule.document.status，chunk 本身没有 status 列——chunk 跟着文档的
        // ACTIVE/ARCHIVED 走，查询必须 join document 过滤，否则旧版本内容会和新版本一起被召回
        RuleDocument oldDoc = documentRepository.insert(project, "文档", 1, T0);
        RuleChunk oldChunk = new RuleChunk(0, oldDoc.id(), project, "p", "h", "旧版本内容");
        chunkRepository.insertAll(oldDoc.id(), project, List.of(oldChunk), List.of(embedder.embed(oldChunk.content())));
        documentRepository.archiveActiveByProjectAndTitle(project, "文档");

        RuleDocument newDoc = documentRepository.insert(project, "文档", 2, T0);
        RuleChunk newChunk = new RuleChunk(0, newDoc.id(), project, "p", "h", "新版本内容");
        chunkRepository.insertAll(newDoc.id(), project, List.of(newChunk), List.of(embedder.embed(newChunk.content())));

        String query = "版本内容";
        List<RuleChunkCandidate> candidates = chunkRepository.findCandidates(project, query, embedder.embed(query), 50);

        assertThat(candidates).extracting(c -> c.chunk().content()).containsExactly("新版本内容");
    }
}
