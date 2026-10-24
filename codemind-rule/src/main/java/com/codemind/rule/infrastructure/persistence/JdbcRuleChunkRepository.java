package com.codemind.rule.infrastructure.persistence;

import com.codemind.rule.domain.rule.RuleChunk;
import com.codemind.rule.domain.rule.RuleChunkCandidate;
import com.codemind.rule.domain.rule.RuleChunkRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 规则切块表全部用 schema 限定名；向量以 pgvector 文本字面量 {@code [x,y,...]} 传参再 cast，
 * 不引入 pgvector-java 依赖（同 memory JdbcMemoryRepository 做法）。
 */
@Repository
public class JdbcRuleChunkRepository implements RuleChunkRepository {

    private final JdbcClient jdbc;

    public JdbcRuleChunkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertAll(long documentId, String project, List<RuleChunk> chunks, List<float[]> embeddings) {
        for (int i = 0; i < chunks.size(); i++) {
            RuleChunk c = chunks.get(i);
            jdbc.sql("""
                    insert into rule.chunk (document_id, project, section_path, heading, content, embedding)
                    values (:documentId, :project, :sectionPath, :heading, :content, cast(:embedding as public.vector))
                    """)
                    .param("documentId", documentId).param("project", project)
                    .param("sectionPath", c.sectionPath()).param("heading", c.heading())
                    .param("content", c.content()).param("embedding", vectorLiteral(embeddings.get(i)))
                    .update();
        }
    }

    @Override
    public List<RuleChunkCandidate> findCandidates(String project, String queryText, float[] queryEmbedding,
                                                    int limit) {
        // 两路各自排序取 limit，再 union 去重后统一算单一 similarity（max），同 memory M8 结论：
        // 两路都是按 project 过滤后的精确扫描，不建近似索引（HNSW/GIN 带过滤会拿不满 limit）
        return jdbc.sql("""
                with q as (select cast(:embedding as public.vector) as v),
                hits as (
                    (select c.id from rule.chunk c, q
                     where c.project = :project
                     order by c.embedding operator(public.<=>) q.v
                     limit :limit)
                    union
                    (select c.id from rule.chunk c
                     where c.project = :project
                       and public.similarity(c.content, :query) > 0
                     order by public.similarity(c.content, :query) desc
                     limit :limit)
                )
                select c.id, c.document_id, c.project, c.section_path, c.heading, c.content, d.title as document_title,
                       greatest(1 - (c.embedding operator(public.<=>) q.v), public.similarity(c.content, :query)) as similarity
                from rule.chunk c join hits h on h.id = c.id join rule.document d on d.id = c.document_id, q
                """)
                .param("embedding", vectorLiteral(queryEmbedding))
                .param("project", project)
                .param("query", queryText)
                .param("limit", limit)
                .query(JdbcRuleChunkRepository::mapCandidate)
                .list();
    }

    private static RuleChunkCandidate mapCandidate(ResultSet rs, int rowNum) throws SQLException {
        RuleChunk chunk = new RuleChunk(rs.getLong("id"), rs.getLong("document_id"), rs.getString("project"),
                rs.getString("section_path"), rs.getString("heading"), rs.getString("content"));
        return new RuleChunkCandidate(chunk, rs.getString("document_title"), rs.getDouble("similarity"));
    }

    static String vectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 12).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
