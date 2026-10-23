package com.codemind.review.infrastructure.persistence;

import com.codemind.review.domain.publication.FingerprintRegistration;
import com.codemind.review.domain.publication.FingerprintRegistrationDelivery;
import com.codemind.review.domain.publication.FingerprintRegistrationOutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcFingerprintRegistrationOutboxRepository implements FingerprintRegistrationOutboxRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public JdbcFingerprintRegistrationOutboxRepository(JdbcClient jdbc, ObjectMapper mapper,
                                                        TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.transactions = transactions;
    }

    @Override
    public void insert(String taskId, String repo, int prNumber, List<String> fingerprints) {
        try {
            jdbc.sql("""
                    insert into fingerprint_registration_outbox (task_id, repo, pr_number, fingerprints)
                    values (:taskId, :repo, :prNumber, cast(:fingerprints as jsonb))
                    on conflict (task_id) do nothing
                    """)
                    .param("taskId", UUID.fromString(taskId))
                    .param("repo", repo)
                    .param("prNumber", prNumber)
                    .param("fingerprints", mapper.writeValueAsString(fingerprints))
                    .update();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("指纹登记请求序列化失败", e);
        }
    }

    @Override
    public List<FingerprintRegistrationDelivery> claimDue(Instant now, int limit, Duration lease) {
        if (limit <= 0 || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("非法领取参数");
        }
        return transactions.execute(status -> {
            List<Row> rows = jdbc.sql("""
                    select * from fingerprint_registration_outbox
                    where acknowledged_at is null and next_attempt_at <= :now
                      and (lease_until is null or lease_until <= :now)
                    order by next_attempt_at, task_id limit :limit for update skip locked
                    """)
                    .param("now", Timestamp.from(now)).param("limit", limit)
                    .query(this::mapRow).list();
            List<FingerprintRegistrationDelivery> claimed = new ArrayList<>();
            for (Row row : rows) {
                String token = UUID.randomUUID().toString();
                jdbc.sql("""
                        update fingerprint_registration_outbox set lease_token=:token, lease_until=:until,
                          attempts=attempts+1 where task_id=:id
                        """)
                        .param("token", UUID.fromString(token))
                        .param("until", Timestamp.from(now.plus(lease)))
                        .param("id", UUID.fromString(row.registration.taskId())).update();
                claimed.add(new FingerprintRegistrationDelivery(row.registration, token));
            }
            return List.copyOf(claimed);
        });
    }

    @Override
    public boolean reschedule(String taskId, String leaseToken, Instant nextAt, String error) {
        return jdbc.sql("""
                update fingerprint_registration_outbox set next_attempt_at=:nextAt, lease_token=null,
                  lease_until=null, last_error=:error
                where task_id=:id and lease_token=:token and acknowledged_at is null
                """)
                .param("nextAt", Timestamp.from(nextAt)).param("error", error)
                .param("id", UUID.fromString(taskId)).param("token", UUID.fromString(leaseToken))
                .update() > 0;
    }

    @Override
    public void acknowledge(String taskId, Instant now) {
        jdbc.sql("""
                update fingerprint_registration_outbox set acknowledged_at=coalesce(acknowledged_at,:now),
                  lease_token=null, lease_until=null
                where task_id=:id
                """)
                .param("now", Timestamp.from(now)).param("id", UUID.fromString(taskId)).update();
    }

    private Row mapRow(ResultSet rs, int rowNum) throws SQLException {
        try {
            List<String> fingerprints = mapper.readValue(rs.getString("fingerprints"),
                    new TypeReference<List<String>>() { });
            var registration = new FingerprintRegistration(rs.getString("task_id"), rs.getString("repo"),
                    rs.getInt("pr_number"), fingerprints);
            return new Row(registration);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("指纹登记请求反序列化失败", e);
        }
    }

    private record Row(FingerprintRegistration registration) { }
}
