package com.codemind.integration.infrastructure.persistence;

import com.codemind.common.event.ReviewCompleted;
import com.codemind.integration.domain.publication.PublicationMode;
import com.codemind.integration.domain.publication.PublicationRecord;
import com.codemind.integration.domain.publication.PublicationRepository;
import com.codemind.integration.domain.publication.PublicationResult;
import com.codemind.integration.domain.publication.PublicationState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/** All SQL is schema-qualified; conditional updates are the cross-instance attempt fence. */
@Repository
public class JdbcPublicationRepository implements PublicationRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public JdbcPublicationRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        // A private copy, not a global mapper bean; the stored event is ordinary JSON without type metadata.
        this.mapper = mapper.copy().deactivateDefaultTyping();
    }

    @Override
    @Transactional
    public PublicationRecord register(ReviewCompleted event) {
        Objects.requireNonNull(event, "event");
        String digest = contentDigest(event);
        String json;
        try {
            json = mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            // Do not include the request body in exceptions or logs.
            throw new IllegalArgumentException("Cannot serialize publication event");
        }
        jdbc.sql("""
                insert into integration.review_publication
                    (task_id, event_payload, content_digest, repo, pr_number, head_sha, state, mode)
                values (:id, cast(:payload as jsonb), :digest, :repo, :pr, :head, 'READY', 'INLINE')
                on conflict (task_id) do nothing
                """)
                .param("id", event.taskId()).param("payload", json).param("digest", digest)
                .param("repo", event.repo()).param("pr", event.prNumber()).param("head", event.headSha())
                .update();
        PublicationRecord saved = findByTaskId(event.taskId()).orElseThrow();
        if (!saved.contentDigest().equals(digest)) {
            throw new IllegalStateException("Conflicting publication payload for registered task");
        }
        return saved;
    }

    @Override
    public Optional<PublicationRecord> findByTaskId(String taskId) {
        return jdbc.sql("select * from integration.review_publication where task_id = :id")
                .param("id", taskId).query(this::mapRow).optional();
    }

    @Override
    public boolean beginAttempt(String taskId, String token, PublicationMode expectedMode) {
        Objects.requireNonNull(token, "token");
        return jdbc.sql("""
                update integration.review_publication
                set state = 'IN_FLIGHT', attempt_token = :token, updated_at = now()
                where task_id = :id and state = 'READY' and mode = :mode
                """)
                .param("id", taskId).param("token", token).param("mode", expectedMode.name())
                .update() == 1;
    }

    @Override
    public boolean markUncertain(String taskId, String token, String reason) {
        return jdbc.sql("""
                update integration.review_publication
                set state = 'UNCERTAIN', last_error = :reason, updated_at = now()
                where task_id = :id and state = 'IN_FLIGHT' and attempt_token = :token
                """)
                .param("id", taskId).param("token", token).param("reason", reason).update() == 1;
    }

    @Override
    public boolean recordRejection(String taskId, String token, PublicationMode nextMode, String reason) {
        return jdbc.sql("""
                update integration.review_publication
                set state = 'READY', mode = :mode, attempt_token = null, last_error = :reason, updated_at = now()
                where task_id = :id and state = 'IN_FLIGHT' and attempt_token = :token
                """)
                .param("id", taskId).param("token", token).param("mode", nextMode.name())
                .param("reason", reason).update() == 1;
    }

    @Override
    public boolean recordSuccess(String taskId, String token, PublicationResult result) {
        return successUpdate("and state in ('IN_FLIGHT', 'UNCERTAIN') and attempt_token = :token", taskId, result)
                .param("token", token).update() == 1;
    }

    @Override
    public boolean reconcileSuccess(String taskId, PublicationResult result) {
        // Only confirms the caller's complete marker lookup. There is no reset-to-READY here.
        return successUpdate("and state in ('READY', 'IN_FLIGHT', 'UNCERTAIN')", taskId, result).update() == 1;
    }

    private JdbcClient.StatementSpec successUpdate(String condition, String taskId, PublicationResult result) {
        return jdbc.sql("""
                update integration.review_publication
                set state = 'PUBLISHED', review_id = :review, inline_count = :count, degraded = :degraded,
                    last_error = null, updated_at = now()
                where task_id = :id
                """ + condition)
                .param("id", taskId).param("review", result.reviewId())
                .param("count", result.inlineCount()).param("degraded", result.degraded());
    }

    private PublicationRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        ReviewCompleted event;
        try {
            event = mapper.readValue(rs.getString("event_payload"), ReviewCompleted.class);
        } catch (JsonProcessingException e) {
            throw new SQLException("Cannot deserialize stored publication event");
        }
        Long reviewId = rs.getObject("review_id", Long.class);
        PublicationResult result = reviewId == null ? null : new PublicationResult(reviewId,
                rs.getInt("inline_count"), rs.getBoolean("degraded"));
        return new PublicationRecord(rs.getString("task_id"), event, rs.getString("content_digest"),
                PublicationState.valueOf(rs.getString("state")), PublicationMode.valueOf(rs.getString("mode")),
                rs.getString("attempt_token"), result, rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getString("last_error"));
    }

    /** Length-prefixed UTF-8 fields avoid delimiter ambiguity and mapper configuration dependence. */
    private static String contentDigest(ReviewCompleted event) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            addField(digest, event.taskId());
            addField(digest, event.repo());
            addField(digest, Integer.toString(event.prNumber()));
            addField(digest, event.headSha());
            addField(digest, event.summaryBody());
            addField(digest, Integer.toString(event.comments().size()));
            for (ReviewCompleted.LineComment comment : event.comments()) {
                addField(digest, comment.path());
                addField(digest, Integer.toString(comment.line()));
                addField(digest, comment.body());
            }
            // eventId / occurredAt are transport metadata, never part of the business digest.
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void addField(MessageDigest digest, String value) {
        byte[] bytes = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes == null ? -1 : bytes.length).array());
        if (bytes != null) {
            digest.update(bytes);
        }
    }
}
