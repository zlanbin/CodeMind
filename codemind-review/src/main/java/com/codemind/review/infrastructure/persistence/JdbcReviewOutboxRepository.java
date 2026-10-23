package com.codemind.review.infrastructure.persistence;

import com.codemind.common.event.ReviewCompleted;
import com.codemind.review.domain.publication.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Repository
public class JdbcReviewOutboxRepository implements ReviewOutboxRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public JdbcReviewOutboxRepository(JdbcClient jdbc, ObjectMapper mapper, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.transactions = transactions;
    }

    @Override
    public Optional<OutboxEntry> findByTaskId(String taskId) {
        return jdbc.sql("select * from review_publication_outbox where task_id=:id")
                .param("id", UUID.fromString(taskId)).query(this::mapRow).optional();
    }

    @Override
    public void insert(ReviewCompleted event) {
        try {
            jdbc.sql("""
                    insert into review_publication_outbox(task_id,event_id,payload)
                    values (:taskId,:eventId,cast(:payload as jsonb))
                    """).param("taskId", UUID.fromString(event.taskId()))
                    .param("eventId", UUID.fromString(event.eventId()))
                    .param("payload", mapper.writeValueAsString(event)).update();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("发布请求序列化失败", e);
        }
    }

    @Override
    public List<OutboxDelivery> claimDue(Instant now, int limit, Duration lease) {
        return claim(null, now, limit, lease);
    }

    @Override
    public Optional<OutboxDelivery> claimTask(String taskId, Instant now, Duration lease) {
        return claim(taskId, now, 1, lease).stream().findFirst();
    }

    private List<OutboxDelivery> claim(String taskId, Instant now, int limit, Duration lease) {
        if (limit <= 0 || lease.isNegative() || lease.isZero()) throw new IllegalArgumentException("非法领取参数");
        return transactions.execute(status -> {
            String scoped = taskId == null ? "" : " and task_id=:taskId";
            var query = jdbc.sql("""
                    select * from review_publication_outbox
                    where acknowledged_at is null and next_attempt_at <= :now
                      and (lease_until is null or lease_until <= :now)
                    """ + scoped + " order by next_attempt_at,task_id limit :limit for update skip locked")
                    .param("now", Timestamp.from(now)).param("limit", limit);
            if (taskId != null) query = query.param("taskId", UUID.fromString(taskId));
            List<OutboxEntry> entries = query.query(this::mapRow).list();
            List<OutboxDelivery> claimed = new ArrayList<>();
            for (OutboxEntry entry : entries) {
                String token = UUID.randomUUID().toString();
                jdbc.sql("""
                        update review_publication_outbox set lease_token=:token,lease_until=:until,
                          attempts=attempts+1,updated_at=:now where task_id=:id
                        """).param("token", UUID.fromString(token)).param("until", Timestamp.from(now.plus(lease)))
                        .param("now", Timestamp.from(now)).param("id", UUID.fromString(entry.event().taskId())).update();
                claimed.add(new OutboxDelivery(entry.event(), token));
            }
            return List.copyOf(claimed);
        });
    }

    @Override
    public boolean reschedule(String taskId, String leaseToken, Instant nextAt, String error) {
        return jdbc.sql("""
                update review_publication_outbox set next_attempt_at=:nextAt,lease_token=null,lease_until=null,
                  last_error=:error,updated_at=now()
                where task_id=:id and lease_token=:token and acknowledged_at is null
                """).param("nextAt", Timestamp.from(nextAt)).param("error", error)
                .param("id", UUID.fromString(taskId)).param("token", UUID.fromString(leaseToken)).update() > 0;
    }

    @Override
    public void acknowledge(String taskId, Instant now) {
        jdbc.sql("""
                update review_publication_outbox set acknowledged_at=coalesce(acknowledged_at,:now),
                  lease_token=null,lease_until=null,updated_at=:now
                where task_id=:id
                """).param("now", Timestamp.from(now)).param("id", UUID.fromString(taskId)).update();
    }

    private OutboxEntry mapRow(ResultSet rs, int row) throws SQLException {
        try {
            Timestamp ack = rs.getTimestamp("acknowledged_at");
            return new OutboxEntry(mapper.readValue(rs.getString("payload"), ReviewCompleted.class),
                    rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("next_attempt_at").toInstant(),
                    rs.getInt("attempts"), ack == null ? null : ack.toInstant());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("发布请求反序列化失败", e);
        }
    }
}
