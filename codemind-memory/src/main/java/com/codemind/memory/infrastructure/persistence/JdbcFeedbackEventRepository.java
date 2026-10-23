package com.codemind.memory.infrastructure.persistence;

import com.codemind.memory.domain.feedback.FeedbackEventRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcFeedbackEventRepository implements FeedbackEventRepository {

    private final JdbcClient jdbc;

    public JdbcFeedbackEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isProcessed(String eventId) {
        return jdbc.sql("select count(*) from memory.feedback_event where event_id = :id")
                .param("id", eventId).query(Integer.class).single() > 0;
    }

    @Override
    public boolean markProcessed(String eventId, String project, String outcome) {
        return jdbc.sql("""
                insert into memory.feedback_event (event_id, project, outcome) values (:id, :project, :outcome)
                on conflict (event_id) do nothing
                """)
                .param("id", eventId).param("project", project).param("outcome", outcome)
                .update() == 1;
    }

    @Override
    public void recordOutcome(String eventId, String outcome) {
        jdbc.sql("update memory.feedback_event set outcome = :outcome where event_id = :id")
                .param("id", eventId).param("outcome", outcome).update();
    }
}
