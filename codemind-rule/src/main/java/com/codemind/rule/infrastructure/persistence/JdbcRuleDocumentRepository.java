package com.codemind.rule.infrastructure.persistence;

import com.codemind.rule.domain.rule.RuleDocument;
import com.codemind.rule.domain.rule.RuleDocumentRepository;
import com.codemind.rule.domain.rule.RuleDocumentStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class JdbcRuleDocumentRepository implements RuleDocumentRepository {

    private static final String COLUMNS = "id, project, title, status, version, uploaded_at";

    private final JdbcClient jdbc;

    public JdbcRuleDocumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public RuleDocument insert(String project, String title, int version, Instant uploadedAt) {
        return jdbc.sql("""
                insert into rule.document (project, title, status, version, uploaded_at)
                values (:project, :title, 'ACTIVE', :version, :uploadedAt)
                returning %s
                """.formatted(COLUMNS))
                .param("project", project).param("title", title).param("version", version)
                .param("uploadedAt", Timestamp.from(uploadedAt))
                .query(JdbcRuleDocumentRepository::mapDocument)
                .single();
    }

    @Override
    public Optional<Integer> archiveActiveByProjectAndTitle(String project, String title) {
        return jdbc.sql("""
                update rule.document set status = 'ARCHIVED'
                where project = :project and title = :title and status = 'ACTIVE'
                returning version
                """)
                .param("project", project).param("title", title)
                .query(Integer.class)
                .optional();
    }

    private static RuleDocument mapDocument(ResultSet rs, int rowNum) throws SQLException {
        return new RuleDocument(rs.getLong("id"), rs.getString("project"), rs.getString("title"),
                RuleDocumentStatus.valueOf(rs.getString("status")), rs.getInt("version"),
                rs.getTimestamp("uploaded_at").toInstant());
    }
}
