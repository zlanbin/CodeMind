package com.codemind.review.loadtest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * M12 压测主程序（手动运行，不是 {@code *Test}/{@code *IT}，不参与 {@code mvn verify}）。
 *
 * <p>前置条件：{@code loadtest} profile 下的 codemind-review（{@code StubLlmClient}，无真实 LLM
 * 调用）+ 默认 profile 的 codemind-integration（真实接收 webhook、发 MQ 事件）都已启动，PostgreSQL /
 * Redis / RocketMQ / Nacos 均在跑。
 *
 * <p>运行方式（在仓库根目录）：
 * <pre>
 * mvn -q compile test-compile -pl codemind-review -am
 * GITHUB_WEBHOOK_SECRET=&lt;同起服务时用的值&gt; java -cp "codemind-review/target/classes;codemind-review/target/test-classes;$(cat classpath.txt)" com.codemind.review.loadtest.LoadTestRunner
 * </pre>
 *
 * <p>review 服务拿到 webhook 后会经 integration-service 的 {@code /internal/pulls/diff} 回头拉
 * <b>真实</b> GitHub diff（repo/PR 号对应真实仓库，{@code fetchPullRequestDiff} 按 repo+prNumber 查，
 * 不看 webhook payload 里的 head/base sha）——{@code loadtest} profile 只替身了 LLM 调用
 * （{@code StubLlmClient}），没有替身 GitHub 内容拉取这条路径。所以 30 个任务必须指向同一个真实
 * 存在的仓库 + PR（{@code CODEMIND_TEST_REPO} / {@code CODEMIND_TEST_PR} 环境变量，与现有
 * {@code RealPullRequestReviewIT} 用的 sandbox 仓库一致），每个任务只在 webhook payload 的
 * {@code head_sha} 上用独立 UUID，使 {@code review_task} 的 {@code (repo, pr_number, head_sha)}
 * 唯一索引把 30 个任务当作同一 PR 的 30 次不同 head 提交，而不会相互冲突或被幂等拦掉。
 */
public final class LoadTestRunner {

    private static final int CONCURRENCY = 10;
    private static final int TOTAL_TASKS = 30;
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(180);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final String INTEGRATION_BASE_URL = "http://127.0.0.1:8081";
    private static final String JDBC_URL = "jdbc:postgresql://127.0.0.1:5432/codemind";
    private static final String JDBC_USER = "codemind";
    private static final String JDBC_PASSWORD = "codemind";

    private LoadTestRunner() {
    }

    public static void main(String[] args) throws Exception {
        String webhookSecret = System.getenv("GITHUB_WEBHOOK_SECRET");
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalStateException("环境变量 GITHUB_WEBHOOK_SECRET 未设置——必须与起 integration-service 时用的值一致");
        }
        String repo = System.getenv("CODEMIND_TEST_REPO");
        String prNumberRaw = System.getenv("CODEMIND_TEST_PR");
        if (repo == null || repo.isBlank() || prNumberRaw == null || prNumberRaw.isBlank()) {
            throw new IllegalStateException("环境变量 CODEMIND_TEST_REPO / CODEMIND_TEST_PR 未设置——"
                    + "review 服务会回头向真实 GitHub 拉 diff，必须指向一个真实存在的仓库+PR");
        }
        int prNumber = Integer.parseInt(prNumberRaw);

        List<TaskSpec> specs = new ArrayList<>();
        for (int i = 0; i < TOTAL_TASKS; i++) {
            String suffix = UUID.randomUUID().toString();
            specs.add(new TaskSpec(
                    repo,
                    prNumber,
                    "head-" + suffix,
                    "base-" + suffix));
        }

        HttpClient httpClient = HttpClient.newHttpClient();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        System.out.println("=== M12 压测：并发=" + CONCURRENCY + " 任务数=" + TOTAL_TASKS
                + " repo=" + repo + " pr=" + prNumber + " ===");

        // 1+2. 并发发送 webhook，记录发送时间戳
        List<TaskResult> results = new ArrayList<>();
        List<Future<TaskResult>> futures = new ArrayList<>();
        // 用固定大小的并发闸门限制同时在飞的发送数（虚拟线程池本身不限流）
        java.util.concurrent.Semaphore gate = new java.util.concurrent.Semaphore(CONCURRENCY);
        for (TaskSpec spec : specs) {
            futures.add(executor.submit(() -> {
                gate.acquire();
                try {
                    return sendWebhook(httpClient, webhookSecret, spec);
                } finally {
                    gate.release();
                }
            }));
        }
        for (Future<TaskResult> f : futures) {
            results.add(f.get());
        }

        long sent = results.stream().filter(r -> r.httpStatus == 200).count();
        long sendFailed = results.size() - sent;
        System.out.println("发送完成：成功提交 " + sent + "/" + results.size() + "，HTTP 提交失败 " + sendFailed);

        // 3. 轮询 review_task 表直到 AGGREGATING / FAILED 或超时
        Class.forName("org.postgresql.Driver");
        try (Connection conn = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD)) {
            for (TaskResult r : results) {
                if (r.httpStatus != 200) {
                    r.outcome = Outcome.SEND_FAILED;
                    continue;
                }
                pollUntilDone(conn, r);
            }
        }

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // 4. 核对发出数 vs review_task 实际查到的任务数
        long foundInDb = results.stream().filter(r -> r.outcome != Outcome.SEND_FAILED && r.outcome != Outcome.NOT_FOUND).count();
        System.out.println();
        System.out.println("=== 核对：发出 " + sent + " 个 webhook（HTTP 200），review_task 表里按 (repo,pr_number,head_sha) 查到 " + foundInDb + " 个对应行 ===");
        if (foundInDb != sent) {
            System.out.println("!!! 数字不一致：" + (sent - foundInDb) + " 个任务发出但未在 review_task 查到对应行。"
                    + "先查原因（幂等索引拦截 / UUID 生成冲突 / webhook 处理异常），不要把这部分直接混入下面的延迟统计。");
        }

        printSummary(results);
    }

    private static TaskResult sendWebhook(HttpClient httpClient, String secret, TaskSpec spec) {
        String payload = body(spec);
        String signature = sign(secret, payload);
        String deliveryId = "loadtest-" + UUID.randomUUID();
        Instant sentAt = Instant.now();
        TaskResult result = new TaskResult(spec, sentAt);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(INTEGRATION_BASE_URL + "/webhook/github"))
                    .header("Content-Type", "application/json")
                    .header("X-GitHub-Delivery", deliveryId)
                    .header("X-GitHub-Event", "pull_request")
                    .header("X-Hub-Signature-256", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            result.httpStatus = response.statusCode();
        } catch (Exception e) {
            result.httpStatus = -1;
            result.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return result;
    }

    private static void pollUntilDone(Connection conn, TaskResult result) throws Exception {
        Instant deadline = result.sentAt.plus(POLL_TIMEOUT);
        String sql = "select status, updated_at from review_task where repo = ? and pr_number = ? and head_sha = ?";
        // 先查一次当前状态，不管 deadline 是否已经过期（串行轮询下，排在队尾的任务轮到它查询时
        // deadline 可能早就过了，但它本身可能早就到达终态——必须先查一次才知道，不能让下面的
        // while 循环因为 deadline 已过而直接跳过、把它误判成 NOT_FOUND）。
        QueryOutcome initial = queryOnce(conn, sql, result);
        if (initial.done) {
            result.finishedAt = initial.updatedAt;
            result.finalStatus = initial.status;
            result.outcome = "FAILED".equals(initial.status) ? Outcome.FAILED : Outcome.SUCCESS;
            return;
        }
        result.lastSeenStatus = initial.status;
        while (Instant.now().isBefore(deadline)) {
            Thread.sleep(POLL_INTERVAL.toMillis());
            QueryOutcome outcome = queryOnce(conn, sql, result);
            if (outcome.done) {
                // finishedAt 用数据库 updated_at（任务真正到达终态的时刻），不是「轮到它查询时」
                // 的 Instant.now()——串行轮询下后者会被排队顺序污染，变成「真实完成时刻的滚动
                // 最大值」，抹掉真实的延迟离散度。
                result.finishedAt = outcome.updatedAt;
                result.finalStatus = outcome.status;
                result.outcome = "FAILED".equals(outcome.status) ? Outcome.FAILED : Outcome.SUCCESS;
                return;
            }
            result.lastSeenStatus = outcome.status;
        }
        // 超时：区分「从未在表里出现过」和「出现过但卡在中间状态」
        result.finishedAt = Instant.now();
        result.outcome = (result.lastSeenStatus == null) ? Outcome.NOT_FOUND : Outcome.TIMEOUT;
    }

    /** 查一次任务当前状态；到达终态（AGGREGATING/FAILED）时附带数据库里的 updated_at。 */
    private static QueryOutcome queryOnce(Connection conn, String sql, TaskResult result) throws Exception {
        try (var ps = conn.prepareStatement(sql)) {
            ps.setString(1, result.spec.repo());
            ps.setInt(2, result.spec.prNumber());
            ps.setString(3, result.spec.headSha());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new QueryOutcome(false, null, null);
                }
                String status = rs.getString("status");
                if ("AGGREGATING".equals(status) || "FAILED".equals(status)) {
                    return new QueryOutcome(true, status, rs.getTimestamp("updated_at").toInstant());
                }
                return new QueryOutcome(false, status, null);
            }
        }
    }

    private record QueryOutcome(boolean done, String status, Instant updatedAt) {
    }

    private static void printSummary(List<TaskResult> results) {
        List<Double> latenciesSeconds = new ArrayList<>();
        int success = 0;
        int failed = 0;
        int timeout = 0;
        int notFound = 0;
        int sendFailed = 0;
        for (TaskResult r : results) {
            switch (r.outcome) {
                case SUCCESS -> {
                    success++;
                    latenciesSeconds.add(Duration.between(r.sentAt, r.finishedAt).toMillis() / 1000.0);
                }
                case FAILED -> failed++;
                case TIMEOUT -> timeout++;
                case NOT_FOUND -> notFound++;
                case SEND_FAILED -> sendFailed++;
            }
        }
        latenciesSeconds.sort(Double::compareTo);

        System.out.println();
        System.out.println("=== 汇总 ===");
        System.out.println("发送失败（HTTP 层）: " + sendFailed);
        System.out.println("发送成功但 review_task 未查到对应行（超时未出现）: " + notFound);
        System.out.println("成功（AGGREGATING）: " + success);
        System.out.println("失败（FAILED）: " + failed);
        System.out.println("超时（卡在中间状态未到终态）: " + timeout);
        if (!latenciesSeconds.isEmpty()) {
            System.out.printf(Locale.ROOT, "延迟 P50=%.1fs P95=%.1fs P99=%.1fs min=%.1fs max=%.1fs%n",
                    percentile(latenciesSeconds, 50), percentile(latenciesSeconds, 95),
                    percentile(latenciesSeconds, 99), latenciesSeconds.get(0),
                    latenciesSeconds.get(latenciesSeconds.size() - 1));
            Instant firstSent = results.stream().map(r -> r.sentAt).min(Instant::compareTo).orElseThrow();
            Instant lastFinished = results.stream()
                    .filter(r -> r.outcome == Outcome.SUCCESS)
                    .map(r -> r.finishedAt)
                    .max(Instant::compareTo).orElse(firstSent);
            double windowMinutes = Duration.between(firstSent, lastFinished).toMillis() / 60000.0;
            double throughput = windowMinutes > 0 ? success / windowMinutes : 0;
            System.out.printf(Locale.ROOT, "吞吐（成功任务数/分钟，按首发到末次成功完成的窗口计）: %.2f%n", throughput);
        }
        for (TaskResult r : results) {
            if (r.outcome == Outcome.FAILED || r.outcome == Outcome.TIMEOUT
                    || r.outcome == Outcome.NOT_FOUND || r.outcome == Outcome.SEND_FAILED) {
                System.out.println("  异常任务 repo=" + r.spec.repo() + " outcome=" + r.outcome
                        + " lastSeenStatus=" + r.lastSeenStatus
                        + (r.error != null ? " error=" + r.error : "")
                        + " httpStatus=" + r.httpStatus);
            }
        }
    }

    private static double percentile(List<Double> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        index = Math.max(0, Math.min(sorted.size() - 1, index));
        return sorted.get(index);
    }

    private static String body(TaskSpec spec) {
        return """
                {"action":"opened","repository":{"full_name":"%s"},
                 "pull_request":{"number":%d,"head":{"sha":"%s"},"base":{"sha":"%s"}}}
                """.formatted(spec.repo(), spec.prNumber(), spec.headSha(), spec.baseSha());
    }

    private static String sign(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record TaskSpec(String repo, int prNumber, String headSha, String baseSha) {
    }

    private enum Outcome {
        SUCCESS, FAILED, TIMEOUT, NOT_FOUND, SEND_FAILED
    }

    private static final class TaskResult {
        final TaskSpec spec;
        final Instant sentAt;
        int httpStatus;
        String error;
        Instant finishedAt;
        String finalStatus;
        String lastSeenStatus;
        Outcome outcome;

        TaskResult(TaskSpec spec, Instant sentAt) {
            this.spec = spec;
            this.sentAt = sentAt;
        }
    }
}
