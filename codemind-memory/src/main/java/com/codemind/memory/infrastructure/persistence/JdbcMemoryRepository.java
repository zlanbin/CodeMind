package com.codemind.memory.infrastructure.persistence;

import com.codemind.memory.domain.memory.Memory;
import com.codemind.memory.domain.memory.MemoryContent;
import com.codemind.memory.domain.memory.MemoryKind;
import com.codemind.memory.domain.memory.MemoryRepository;
import com.codemind.memory.domain.memory.MemoryStatus;
import com.codemind.memory.domain.memory.MemoryCandidate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 记忆表全部用 schema 限定名；向量以 pgvector 文本字面量 {@code [x,y,...]} 传参再 cast，
 * 不引入 pgvector-java 依赖。
 */
@Repository
public class JdbcMemoryRepository implements MemoryRepository {

    private static final String COLUMNS =
            "id, project, kind, content, importance, status, created_at, reinforced_at";

    private final JdbcClient jdbc;

    public JdbcMemoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UpsertResult upsert(String project, MemoryKind kind, MemoryContent content, double importance,
                               float[] embedding, Instant now) {
        // xmax = 0 只在本语句新插入的行上成立，用来区分「新建」与「强化」
        return jdbc.sql("""
                insert into memory.memory
                    (project, kind, content, content_hash, embedding, importance, status, created_at, reinforced_at)
                values (:project, :kind, :content, :hash, cast(:embedding as public.vector), :importance,
                        'ACTIVE', :now, :now)
                on conflict (project, kind, content_hash) do update
                    set importance = greatest(memory.memory.importance, excluded.importance),
                        reinforced_at = greatest(memory.memory.reinforced_at, excluded.reinforced_at),
                        status = 'ACTIVE',
                        archived_at = null
                returning %s, (xmax = 0) as created
                """.formatted(COLUMNS))
                .param("project", project).param("kind", kind.name())
                .param("content", content.text()).param("hash", content.hash())
                .param("embedding", vectorLiteral(embedding)).param("importance", importance)
                .param("now", Timestamp.from(now))
                .query((rs, n) -> new UpsertResult(mapMemory(rs), rs.getBoolean("created")))
                .single();
    }

    @Override
    public List<MemoryCandidate> findCandidates(String project, MemoryKind kind, String queryText,
                                                float[] queryEmbedding, int limit) {
        // 两路各自排序取 limit，再 union 去重后统一算两路相似度。两路都是按 project 过滤后的精确扫描：
        // 向量一路不走 HNSW（近似索引先取全局 ef_search 个近邻再过滤，小项目会拿不满 limit）；
        // 三元组一路用 similarity() > 0 而非 %：默认阈值 0.3 会滤掉中文近义句。见 V1 注释
        return jdbc.sql("""
                with q as (select cast(:embedding as public.vector) as v),
                hits as (
                    (select m.id from memory.memory m, q
                     where m.project = :project and m.status = 'ACTIVE'
                       and (cast(:kind as varchar) is null or m.kind = :kind)
                     order by m.embedding operator(public.<=>) q.v
                     limit :limit)
                    union
                    (select m.id from memory.memory m
                     where m.project = :project and m.status = 'ACTIVE'
                       and (cast(:kind as varchar) is null or m.kind = :kind)
                       and public.similarity(m.content, :query) > 0
                     order by public.similarity(m.content, :query) desc
                     limit :limit)
                )
                select %s,
                       1 - (m.embedding operator(public.<=>) q.v) as vector_similarity,
                       public.similarity(m.content, :query) as text_similarity
                from memory.memory m join hits h on h.id = m.id, q
                """.formatted(prefixed()))
                .param("embedding", vectorLiteral(queryEmbedding))
                .param("project", project)
                .param("kind", kind == null ? null : kind.name())
                .param("query", queryText)
                .param("limit", limit)
                .query((rs, n) -> new MemoryCandidate(mapMemory(rs),
                        rs.getDouble("vector_similarity"), rs.getDouble("text_similarity")))
                .list();
    }

    @Override
    public List<Memory> findActiveReinforcedBefore(String project, Instant cutoff) {
        return jdbc.sql("select " + COLUMNS + " from memory.memory"
                        + " where project = :project and status = 'ACTIVE' and reinforced_at < :cutoff")
                .param("project", project).param("cutoff", Timestamp.from(cutoff))
                .query((rs, n) -> mapMemory(rs))
                .list();
    }

    @Override
    public boolean archiveIfUnchanged(long id, Instant expectedReinforcedAt, Instant now) {
        return jdbc.sql("""
                update memory.memory set status = 'ARCHIVED', archived_at = :now
                where id = :id and status = 'ACTIVE' and reinforced_at = :expected
                """)
                .param("id", id).param("expected", Timestamp.from(expectedReinforcedAt))
                .param("now", Timestamp.from(now))
                .update() == 1;
    }

    @Override
    public boolean delete(long id) {
        return jdbc.sql("delete from memory.memory where id = :id").param("id", id).update() == 1;
    }

    @Override
    public Optional<Memory> findById(long id) {
        return jdbc.sql("select " + COLUMNS + " from memory.memory where id = :id")
                .param("id", id)
                .query((rs, n) -> mapMemory(rs))
                .optional();
    }

    private static String prefixed() {
        return "m." + COLUMNS.replace(", ", ", m.");
    }

    private static Memory mapMemory(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        try {
            return new Memory(id, rs.getString("project"), MemoryKind.valueOf(rs.getString("kind")),
                    rs.getString("content"), rs.getDouble("importance"), MemoryStatus.valueOf(rs.getString("status")),
                    rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("reinforced_at").toInstant());
        } catch (IllegalArgumentException e) {
            // 库里的脏数据是服务端问题，不能经 IAE → 400 伪装成调用方错误
            throw new IllegalStateException("记忆行数据非法 id=" + id, e);
        }
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
