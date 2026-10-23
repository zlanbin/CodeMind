package com.codemind.memory.interfaces.web;

import com.codemind.memory.domain.memory.Embedder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M8 验证点端到端：真实 PG 上写入 → 混合召回，返回的顺序与每条分数都等于测试里
 * 独立重算的 {@code max(向量余弦, pg_trgm) × importance × 0.5^(Δt/30d)}。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MemoryControllerIT {

    private static final Instant T0 = Instant.parse("2026-07-01T00:00:00Z");

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    Embedder embedder;
    @Autowired
    MutableClock clock;

    private final String project = "codemind/memory-api-it-" + UUID.randomUUID();
    private final String repo = "codemind/fp-api-it-" + UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        jdbc.sql("delete from memory.memory where project = :p").param("p", project).update();
        jdbc.sql("delete from memory.task_fingerprint where repo = :r").param("r", repo).update();
        clock.set(T0);
    }

    @Test
    void recallOrderAndScoresFollowTheFormula() throws Exception {
        String stale = "本项目允许 Controller 直接返回实体";
        String fresh = "Controller 返回实体前不强制转换 DTO";
        String unrelated = "SQL 字符串拼接会导致注入风险";
        write(stale, "REJECTED_PATTERN", 0.9, status().isCreated());
        clock.set(T0.plus(Duration.ofDays(60)));
        write(fresh, "REJECTED_PATTERN", 0.6, status().isCreated());
        write(unrelated, "COMMON_DEFECT", 1.0, status().isCreated());

        String query = "Controller 直接返回实体";
        JsonNode memories = recall(Map.of("project", project, "query", query, "topK", 10)).path("memories");

        Map<String, Double> expected = Map.of(
                stale, expectedScore(stale, query, 0.9, Duration.ofDays(60)),
                fresh, expectedScore(fresh, query, 0.6, Duration.ZERO),
                unrelated, expectedScore(unrelated, query, 1.0, Duration.ZERO));
        // 向量一路按距离取 top-N，无关记忆也会作为低分候选回来（minScore 默认 0），所以三条都在
        List<String> expectedOrder = expected.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey).toList();

        List<String> actualOrder = new ArrayList<>();
        for (JsonNode m : memories) {
            String content = m.path("memory").path("content").asText();
            actualOrder.add(content);
            double importance = m.path("memory").path("importance").asDouble();
            assertThat(m.path("score").asDouble()).isCloseTo(expected.get(content), within(1e-5));
            assertThat(m.path("score").asDouble())
                    .isCloseTo(m.path("similarity").asDouble() * importance * m.path("decay").asDouble(), within(1e-12));
        }
        // 两条与查询相关的记忆都在，旧的那条衰减到 1/4 后排到新的后面
        assertThat(actualOrder).containsAll(List.of(stale, fresh)).isEqualTo(expectedOrder);
        assertThat(actualOrder.indexOf(fresh)).isLessThan(actualOrder.indexOf(stale));
        assertThat(memories.get(actualOrder.indexOf(stale)).path("decay").asDouble()).isCloseTo(0.25, within(1e-12));
    }

    @Test
    void rewriteReinforcesAndKindFilterApplies() throws Exception {
        String text = "Service 层禁止直接依赖 HttpServletRequest";
        JsonNode first = write(text, "CONVENTION", 0.3, status().isCreated());
        clock.set(T0.plus(Duration.ofDays(30)));
        JsonNode again = write(text, "CONVENTION", 0.7, status().isOk());

        assertThat(again.path("created").asBoolean()).isFalse();
        assertThat(again.path("memory").path("id").asLong()).isEqualTo(first.path("memory").path("id").asLong());
        assertThat(again.path("memory").path("importance").asDouble()).isEqualTo(0.7);
        // 强化刷新了衰减起点：此刻 decay = 1
        JsonNode hit = recall(Map.of("project", project, "query", text, "kind", "CONVENTION")).path("memories").get(0);
        assertThat(hit.path("decay").asDouble()).isEqualTo(1.0);
        assertThat(recall(Map.of("project", project, "query", text, "kind", "COMMON_DEFECT")).path("memories"))
                .isEmpty();
    }

    @Test
    void archiveForgetsStaleMemoryUntilRewritten() throws Exception {
        String text = "DTO 字段命名用小驼峰";
        write(text, "CONVENTION", 0.5, status().isCreated());
        clock.set(T0.plus(Duration.ofDays(150)));  // retention = 0.5 × 0.5^5 ≈ 0.016 < 0.05

        JsonNode archived = json.readTree(mvc.perform(post("/internal/memories/archive").param("project", project))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(archived.path("data").path("archived").asInt()).isEqualTo(1);
        assertThat(recall(Map.of("project", project, "query", text)).path("memories")).isEmpty();

        write(text, "CONVENTION", 0.5, status().isOk());
        assertThat(recall(Map.of("project", project, "query", text)).path("memories")).hasSize(1);
    }

    @Test
    void deleteRemovesMemory() throws Exception {
        long id = write("错误记忆", "CONVENTION", 0.5, status().isCreated()).path("memory").path("id").asLong();

        mvc.perform(delete("/internal/memories/{id}", id)).andExpect(status().isNoContent());
        mvc.perform(delete("/internal/memories/{id}", id)).andExpect(status().isNotFound());
        assertThat(recall(Map.of("project", project, "query", "错误记忆")).path("memories")).isEmpty();
    }

    @Test
    void invalidRequestsAreRejectedWith400() throws Exception {
        postJson("/internal/memories", Map.of("project", project, "kind", "CONVENTION", "content", "x",
                "importance", 1.5)).andExpect(status().isBadRequest());
        postJson("/internal/memories", Map.of("project", project, "kind", "NOT_A_KIND", "content", "x"))
                .andExpect(status().isBadRequest());
        postJson("/internal/memories", Map.of("project", project, "content", "x"))
                .andExpect(status().isBadRequest());
        postJson("/internal/memories/recall", Map.of("project", project, "query", "x", "topK", 0))
                .andExpect(status().isBadRequest());
        postJson("/internal/memories/recall", Map.of("project", project, "query", "x", "minScore", 2))
                .andExpect(status().isBadRequest());
        postJson("/internal/task-fingerprints", Map.of("repo", repo, "prNumber", 0, "fingerprints", List.of("a")))
                .andExpect(status().isBadRequest());
        // 查询参数缺失 / 类型不对也走 Result 格式
        String missing = mvc.perform(post("/internal/memories/archive"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(missing).path("code").asInt()).isEqualTo(MemoryExceptionHandler.CODE_BAD_REQUEST);
        String mismatch = mvc.perform(get("/internal/task-fingerprints").param("repo", repo).param("prNumber", "x"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(mismatch).path("code").asInt()).isEqualTo(MemoryExceptionHandler.CODE_BAD_REQUEST);
    }

    @Test
    void fingerprintsRoundTrip() throws Exception {
        JsonNode added = json.readTree(postJson("/internal/task-fingerprints",
                Map.of("repo", repo, "prNumber", 7, "fingerprints", List.of("f1", "f2", "f1")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(added.path("data").path("added").asInt()).isEqualTo(2);

        JsonNode listed = json.readTree(mvc.perform(get("/internal/task-fingerprints")
                        .param("repo", repo).param("prNumber", "7"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(listed.path("data").path("fingerprints")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("f1", "f2");
    }

    private double expectedScore(String content, String query, double importance, Duration age) {
        float[] a = embedder.embed(content);
        float[] b = embedder.embed(query);
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        double vector = dot / Math.sqrt(na * nb);
        double text = jdbc.sql("select public.similarity(:c, :q)")
                .param("c", content).param("q", query).query(Double.class).single();
        double similarity = Math.min(1, Math.max(0, Math.max(vector, text)));
        return similarity * importance * Math.pow(0.5, age.toMillis() / (double) Duration.ofDays(30).toMillis());
    }

    private JsonNode write(String content, String kind, double importance, ResultMatcher expectedStatus)
            throws Exception {
        String body = postJson("/internal/memories",
                Map.of("project", project, "kind", kind, "content", content, "importance", importance))
                .andExpect(expectedStatus).andReturn().getResponse().getContentAsString();
        JsonNode data = json.readTree(body).path("data");
        return data;
    }

    private JsonNode recall(Map<String, Object> request) throws Exception {
        String body = postJson("/internal/memories/recall", request)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data");
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String path, Object body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
