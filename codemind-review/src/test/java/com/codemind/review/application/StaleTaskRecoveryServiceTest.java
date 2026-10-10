package com.codemind.review.application;

import com.codemind.review.domain.task.ReviewTask;
import com.codemind.review.domain.task.ReviewTaskRepository;
import com.codemind.review.domain.task.ReviewTaskStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link StaleTaskRecoveryService} 纯单测。
 *
 * <p>SQL 判定逻辑（哪些任务算卡死）由 {@code StaleTaskRecoveryIT} 在真实 PG 上验，
 * 这里只验编排：抢占成功才提交、抢不到就跳过、一个坏任务不卡住整轮。
 */
class StaleTaskRecoveryServiceTest {

    @Test
    void resubmitsEachClaimedStaleTask() {
        StubRepository repo = new StubRepository(List.of("t1", "t2"));
        RecordingSubmitter submitter = new RecordingSubmitter();
        StaleTaskRecoveryService service =
                new StaleTaskRecoveryService(repo, submitter, Duration.ofMinutes(15));

        int recovered = service.recoverStaleTasks();

        assertEquals(2, recovered);
        assertEquals(List.of("t1", "t2"), submitter.submitted);
    }

    @Test
    void skipsTaskLostToAnotherInstance() {
        StubRepository repo = new StubRepository(List.of("t1"));
        repo.claimable = false;
        RecordingSubmitter submitter = new RecordingSubmitter();
        StaleTaskRecoveryService service =
                new StaleTaskRecoveryService(repo, submitter, Duration.ofMinutes(15));

        int recovered = service.recoverStaleTasks();

        assertEquals(0, recovered);
        assertEquals(List.of(), submitter.submitted, "抢不到的任务归别的实例跑，不能重复提交");
    }

    @Test
    void returnsZeroWhenNothingStale() {
        StubRepository repo = new StubRepository(List.of());
        RecordingSubmitter submitter = new RecordingSubmitter();
        StaleTaskRecoveryService service =
                new StaleTaskRecoveryService(repo, submitter, Duration.ofMinutes(15));

        assertEquals(0, service.recoverStaleTasks());
        assertEquals(List.of(), submitter.submitted);
    }

    @Test
    void oneTaskFailureDoesNotStopTheRest() {
        StubRepository repo = new StubRepository(List.of("bad", "good"));
        RecordingSubmitter submitter = new RecordingSubmitter();
        submitter.failOn = "bad";
        StaleTaskRecoveryService service =
                new StaleTaskRecoveryService(repo, submitter, Duration.ofMinutes(15));

        int recovered = service.recoverStaleTasks();

        assertEquals(1, recovered, "坏任务不计入，但好任务要跑");
        assertTrue(submitter.submitted.contains("good"), "一个坏任务不能卡住整轮扫描");
    }

    /** 记录提交的 taskId，可指定某个 id 抛异常。 */
    private static final class RecordingSubmitter implements StaleTaskRecoveryService.ReviewSubmitter {
        private final List<String> submitted = new ArrayList<>();
        private String failOn;

        @Override
        public void submit(String taskId) {
            if (taskId.equals(failOn)) {
                throw new IllegalStateException("模拟提交失败: " + taskId);
            }
            submitted.add(taskId);
        }
    }

    private static final class StubRepository implements ReviewTaskRepository {
        private final List<String> stale;
        private boolean claimable = true;

        private StubRepository(List<String> stale) {
            this.stale = stale;
        }

        @Override
        public List<String> findStaleInProgress(Duration idleFor) {
            return stale;
        }

        @Override
        public boolean claimForRecovery(String taskId) {
            return claimable;
        }

        @Override
        public boolean insertIfAbsent(ReviewTask task) {
            return true;
        }

        @Override
        public Optional<ReviewTask> findByPrHead(String repo, int prNumber, String headSha) {
            return Optional.empty();
        }

        @Override
        public Optional<ReviewTask> findById(String taskId) {
            return Optional.empty();
        }

        @Override
        public void updateStatus(String taskId, ReviewTaskStatus status) {
        }
    }
}
