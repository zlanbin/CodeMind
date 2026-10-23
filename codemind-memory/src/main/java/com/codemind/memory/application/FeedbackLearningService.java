package com.codemind.memory.application;

import com.codemind.common.event.FindingFeedbackReceived;
import com.codemind.common.event.FindingFeedbackReceived.FeedbackType;
import com.codemind.memory.domain.feedback.ConflictDecision;
import com.codemind.memory.domain.feedback.ConflictResolver;
import com.codemind.memory.domain.feedback.FeedbackEventRepository;
import com.codemind.memory.domain.feedback.FeedbackExtractor;
import com.codemind.memory.domain.feedback.FeedbackExtractor.ExtractionUnavailableException;
import com.codemind.memory.domain.feedback.FeedbackInput;
import com.codemind.memory.domain.feedback.Lessons;
import com.codemind.memory.domain.memory.Memory;
import com.codemind.memory.domain.memory.MemoryKind;
import com.codemind.memory.domain.recall.ScoredMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 反馈学习（ARCHITECTURE 7.2 第 3~4 步）：抽取否决规则 → 召回相关记忆 → 冲突消解 → 写入。
 *
 * <p>LLM 调用都在事务外；登记事件与全部记忆写入在同一事务里，任何一步失败整体回滚、事件不算处理过，
 * MQ 重投时从头再来。已登记的事件直接跳过（D10）。
 */
@Service
public class FeedbackLearningService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackLearningService.class);

    /** 👎 只说明「不要」；回复通常带了理由，更可信。 */
    static final double THUMBS_DOWN_IMPORTANCE = 0.8;
    static final double REPLY_IMPORTANCE = 0.9;
    /** 冲突消解看的相关记忆条数与最低分（过滤掉向量一路带回的无关记忆）。 */
    static final int CONFLICT_CANDIDATES = 5;
    static final double CONFLICT_MIN_SCORE = 0.05;

    private final FeedbackEventRepository events;
    private final FeedbackExtractor extractor;
    private final ConflictResolver resolver;
    private final MemoryService memories;
    private final TransactionTemplate tx;

    public FeedbackLearningService(FeedbackEventRepository events, FeedbackExtractor extractor,
                                   ConflictResolver resolver, MemoryService memories,
                                   PlatformTransactionManager transactionManager) {
        this.events = events;
        this.extractor = extractor;
        this.resolver = resolver;
        this.memories = memories;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** AMBIGUOUS：合并了多条意见的评论只有 👎、没有说明否决哪条，不学（M9 D16）。 */
    public enum Status { LEARNED, NOT_REJECTED, AMBIGUOUS, ALREADY_PROCESSED }

    public record Outcome(Status status, List<String> actions, boolean degraded) {
    }

    /** @throws ExtractionUnavailableException 回复类反馈且 LLM 不可用，调用方应让 MQ 重投 */
    public Outcome learn(FindingFeedbackReceived event) throws ExtractionUnavailableException {
        if (events.isProcessed(event.eventId())) {
            return new Outcome(Status.ALREADY_PROCESSED, List.of(), false);
        }
        boolean thumbsDown = event.feedbackType() == FeedbackType.THUMBS_DOWN;
        long distinctFindings = event.findings().stream().map(FindingFeedbackReceived.FindingRef::fingerprint)
                .distinct().count();
        if (thumbsDown && distinctFindings > 1) {
            // T6 实测：对合并了 4 维度的评论 👎，LLM 把每条原意见都复述成「规则」，连真缺陷（NPE）一起学成否决
            boolean first = Boolean.TRUE.equals(tx.execute(s -> events.markProcessed(event.eventId(), event.repo(),
                    "AMBIGUOUS:" + distinctFindings + " findings")));
            if (first) {
                log.info("合并评论只有 👎，无法判断否决哪条，不学 project={} eventId={} findings={}",
                        event.repo(), event.eventId(), distinctFindings);
            }
            return new Outcome(first ? Status.AMBIGUOUS : Status.ALREADY_PROCESSED, List.of(), false);
        }
        List<String> categories = event.findings().stream()
                .map(FindingFeedbackReceived.FindingRef::category).distinct().toList();
        Lessons lessons = extractor.extract(new FeedbackInput(event.repo(), thumbsDown, event.replyText(),
                event.commentBody(), categories));
        if (!lessons.rejected()) {
            boolean first = Boolean.TRUE.equals(tx.execute(s -> events.markProcessed(event.eventId(), event.repo(),
                    "NOT_REJECTED")));
            return new Outcome(first ? Status.NOT_REJECTED : Status.ALREADY_PROCESSED, List.of(), false);
        }

        double importance = thumbsDown ? THUMBS_DOWN_IMPORTANCE : REPLY_IMPORTANCE;
        List<Planned> plan = new ArrayList<>();
        for (Lessons.Rule rule : lessons.rules()) {
            String content = rule.memoryContent();
            List<Memory> related = memories.recall(event.repo(), content, null, CONFLICT_CANDIDATES, CONFLICT_MIN_SCORE)
                    .stream().map(ScoredMemory::memory).toList();
            plan.add(new Planned(resolver.resolve(content, related), related));
        }

        List<String> actions = tx.execute(status -> {
            // 先占位再写记忆：并发重投时后到的一方卡在主键上，立刻回滚，不去抢记忆行锁
            if (!events.markProcessed(event.eventId(), event.repo(), "IN_PROGRESS")) {
                status.setRollbackOnly();
                return null;
            }
            List<String> applied = new ArrayList<>();
            for (Planned p : plan) {
                applied.add(apply(event.repo(), p, importance));
            }
            // 模板降级学到的打标，LLM 恢复后可按 outcome 前缀找出来人工补学（技术债：无自动补学）
            events.recordOutcome(event.eventId(), (lessons.degraded() ? "DEGRADED:" : "") + String.join(";", applied));
            return applied;
        });
        if (actions == null) {
            return new Outcome(Status.ALREADY_PROCESSED, List.of(), false);
        }
        log.info("反馈学习 project={} eventId={} 动作={} 降级={}", event.repo(), event.eventId(), actions,
                lessons.degraded());
        return new Outcome(Status.LEARNED, actions, lessons.degraded());
    }

    private String apply(String project, Planned p, double importance) {
        ConflictDecision d = p.decision();
        Memory target = p.related().stream().filter(m -> m.id() == d.targetId()).findFirst().orElse(null);
        return switch (d.action()) {
            case ADD -> "ADD#" + write(project, d.content(), importance);
            case UPDATE -> {
                memories.delete(d.targetId());
                yield "UPDATE#" + d.targetId() + "->" + write(project, d.content(),
                        Math.max(importance, target == null ? 0 : target.importance()));
            }
            case DELETE -> {
                memories.delete(d.targetId());
                yield "DELETE#" + d.targetId() + "+ADD#" + write(project, d.content(), importance);
            }
            case NOOP -> {
                // 同内容再写一次即强化：importance 取大、衰减起点刷新
                memories.write(project, target.kind(), target.content(), Math.max(importance, target.importance()));
                yield "NOOP#" + d.targetId();
            }
        };
    }

    private long write(String project, String content, double importance) {
        return memories.write(project, MemoryKind.REJECTED_PATTERN, content, importance).memory().id();
    }

    private record Planned(ConflictDecision decision, List<Memory> related) {
    }
}
