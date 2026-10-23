package com.codemind.integration.infrastructure.persistence;

import com.codemind.common.event.FindingFeedbackReceived;
import com.codemind.integration.domain.feedback.FindingFeedbackRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** 全部 SQL 用 schema 限定名；事件 JSON 用私有 mapper 副本，不带类型元数据（同 JdbcPublicationRepository）。 */
@Repository
public class JdbcFindingFeedbackRepository implements FindingFeedbackRepository {

    /** last_error 只存截断后的原因，避免把整段响应写进库。 */
    private static final int MAX_ERROR_LENGTH = 500;

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public JdbcFindingFeedbackRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper.copy().deactivateDefaultTyping();
    }

    @Override
    public boolean registerIfAbsent(FindingFeedbackReceived event, boolean superseded) {
        String json;
        try {
            json = mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize feedback event");
        }
        return jdbc.sql("""
                insert into integration.finding_feedback
                    (event_id, repo, pr_number, comment_id, source_type, source_id, event_payload, state)
                values (:id, :repo, :pr, :comment, :type, :source, cast(:payload as jsonb), :state)
                on conflict on constraint finding_feedback_source_unique do nothing
                """)
                .param("id", event.eventId()).param("repo", event.repo()).param("pr", event.prNumber())
                .param("comment", event.commentId()).param("type", event.feedbackType().name())
                .param("source", event.sourceId()).param("payload", json)
                .param("state", superseded ? "SUPERSEDED" : "PENDING")
                .update() == 1;
    }

    @Override
    public List<FindingFeedbackReceived> findPending(int limit) {
        record Row(String eventId, String payload) {
        }
        List<Row> rows = jdbc.sql("""
                select event_id, event_payload from integration.finding_feedback
                where state = 'PENDING' order by created_at, event_id limit :limit
                """)
                .param("limit", limit)
                .query((rs, n) -> new Row(rs.getString("event_id"), rs.getString("event_payload")))
                .list();
        List<FindingFeedbackReceived> events = new java.util.ArrayList<>();
        for (Row row : rows) {
            try {
                events.add(mapper.readValue(row.payload(), FindingFeedbackReceived.class));
            } catch (JsonProcessingException | IllegalArgumentException e) {
                jdbc.sql("""
                        update integration.finding_feedback set state = 'FAILED', last_error = :reason
                        where event_id = :id and state = 'PENDING'
                        """)
                        .param("id", row.eventId()).param("reason", "payload 无法反序列化").update();
            }
        }
        return events;
    }

    @Override
    public boolean markSent(String eventId, Instant now) {
        return jdbc.sql("""
                update integration.finding_feedback
                set state = 'SENT', sent_at = :now, attempts = attempts + 1, last_error = null
                where event_id = :id and state = 'PENDING'
                """)
                .param("id", eventId).param("now", Timestamp.from(now))
                .update() == 1;
    }

    @Override
    public void recordFailure(String eventId, String reason) {
        String trimmed = reason == null ? "" : reason.substring(0, Math.min(reason.length(), MAX_ERROR_LENGTH));
        jdbc.sql("""
                update integration.finding_feedback
                set attempts = attempts + 1, last_error = :reason,
                    state = case when attempts + 1 >= :max then 'FAILED' else 'PENDING' end
                where event_id = :id and state = 'PENDING'
                """)
                .param("id", eventId).param("reason", trimmed).param("max", MAX_ATTEMPTS)
                .update();
    }

    @Override
    public List<PublishedReview> findPublishedSince(Instant since) {
        return jdbc.sql("""
                select repo, pr_number, review_id from integration.review_publication
                where state = 'PUBLISHED' and updated_at >= :since
                order by updated_at, task_id
                """)
                .param("since", Timestamp.from(since))
                .query((rs, n) -> new PublishedReview(rs.getString("repo"), rs.getInt("pr_number"),
                        rs.getLong("review_id")))
                .list();
    }
}
