package com.codemind.review.domain.dag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * DAG 调度器：拓扑排序调度、虚拟线程并行、超时重试、checkpoint 续跑（ARCHITECTURE 5.1）。
 *
 * <p>调度策略：
 * <ul>
 *   <li>入度为 0 的节点并行执行（虚拟线程）；</li>
 *   <li>节点完成后更新上下文、写 checkpoint，并触发后继节点检查入度；</li>
 *   <li>已完成节点（checkpoint COMPLETED）不重复执行；</li>
 *   <li>节点失败按重试策略重试，最终失败整个 DAG 转 FAILED。</li>
 * </ul>
 */
public final class DagScheduler {

    private static final Logger log = LoggerFactory.getLogger(DagScheduler.class);

    private final NodeRunRepository nodeRunRepository;
    private final Duration defaultTimeout;
    private final int defaultRetries;

    public DagScheduler(NodeRunRepository nodeRunRepository, Duration defaultTimeout, int defaultRetries) {
        this.nodeRunRepository = Objects.requireNonNull(nodeRunRepository, "nodeRunRepository");
        this.defaultTimeout = Objects.requireNonNull(defaultTimeout, "defaultTimeout");
        this.defaultRetries = defaultRetries;
    }

    /**
     * 执行 DAG，从 checkpoint 续跑。
     *
     * @return 最终上下文（包含所有节点结果）
     * @throws DagExecutionException DAG 执行失败
     */
    public NodeContext execute(DagDefinition dag, NodeContext initialContext) throws DagExecutionException {
        Objects.requireNonNull(dag, "dag");
        Objects.requireNonNull(initialContext, "initialContext");

        // 加载 checkpoint：已完成节点不重复执行
        NodeContext context = loadCheckpoint(initialContext);
        Set<String> completed = context.completedResults().keySet();
        Set<String> remaining = new HashSet<>(dag.nodes().keySet());
        remaining.removeAll(completed);

        if (remaining.isEmpty()) {
            log.info("任务 {} 所有节点已完成，无需重复执行", initialContext.taskId());
            return context;
        }

        log.info("任务 {} 开始执行 DAG，剩余节点: {}", initialContext.taskId(), remaining);

        // 拓扑调度
        Map<String, Integer> inDegree = calculateInDegree(dag, completed);
        NodeContext currentContext = context;

        while (!remaining.isEmpty()) {
            Set<String> ready = getReadyNodes(remaining, inDegree);
            if (ready.isEmpty()) {
                throw new DagExecutionException("DAG 死锁：无可执行节点，剩余 " + remaining);
            }

            currentContext = executeParallel(dag, ready, currentContext);

            for (String nodeId : ready) {
                remaining.remove(nodeId);
                for (String successor : dag.getSuccessors(nodeId)) {
                    inDegree.merge(successor, -1, Integer::sum);
                }
            }
        }

        log.info("任务 {} DAG 执行完成", initialContext.taskId());
        return currentContext;
    }

    private NodeContext loadCheckpoint(NodeContext initialContext) {
        var completed = nodeRunRepository.findCompletedByTask(initialContext.taskId());
        NodeContext context = initialContext;
        for (NodeRun run : completed) {
            context = context.withCompletedResult(run.nodeId(), run.result());
            log.info("从 checkpoint 恢复节点 {}: {}", run.nodeId(), run.status());
        }
        return context;
    }

    private Map<String, Integer> calculateInDegree(DagDefinition dag, Set<String> completed) {
        Map<String, Integer> inDegree = new HashMap<>();
        dag.dependencies().forEach((nodeId, deps) -> {
            if (completed.contains(nodeId)) {
                return; // 已完成节点不参与调度
            }
            long remainingDeps = deps.stream().filter(d -> !completed.contains(d)).count();
            inDegree.put(nodeId, (int) remainingDeps);
        });
        return inDegree;
    }

    private Set<String> getReadyNodes(Set<String> remaining, Map<String, Integer> inDegree) {
        Set<String> ready = new HashSet<>();
        for (String nodeId : remaining) {
            if (inDegree.getOrDefault(nodeId, 0) == 0) {
                ready.add(nodeId);
            }
        }
        return ready;
    }

    /** 并行执行入度为 0 的节点，用虚拟线程。 */
    private NodeContext executeParallel(DagDefinition dag, Set<String> readyNodes, NodeContext context)
            throws DagExecutionException {
        if (readyNodes.size() == 1) {
            // 单节点优化：直接执行，不创建线程池
            String nodeId = readyNodes.iterator().next();
            Object result = executeNode(dag.nodes().get(nodeId), context);
            return context.withCompletedResult(nodeId, result);
        }

        // 多节点并行：用虚拟线程池
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Map<String, Future<Object>> futures = new HashMap<>();
            for (String nodeId : readyNodes) {
                Node node = dag.nodes().get(nodeId);
                Future<Object> future = executor.submit(() -> executeNode(node, context));
                futures.put(nodeId, future);
            }

            // 等待所有任务完成
            NodeContext updated = context;
            for (Map.Entry<String, Future<Object>> entry : futures.entrySet()) {
                try {
                    Object result = entry.getValue().get();
                    updated = updated.withCompletedResult(entry.getKey(), result);
                } catch (ExecutionException e) {
                    throw new DagExecutionException("节点 " + entry.getKey() + " 执行失败", e.getCause());
                }
            }
            return updated;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DagExecutionException("DAG 执行被中断", e);
        } finally {
            executor.shutdown();
        }
    }

    private Object executeNode(Node node, NodeContext context) throws DagExecutionException {
        String nodeId = node.id();
        String taskId = context.taskId();

        // 检查 checkpoint：已完成不重复执行
        var existingRun = nodeRunRepository.findByTaskAndNode(taskId, nodeId);
        if (existingRun.isPresent() && existingRun.get().isCompleted()) {
            log.info("节点 {} 已完成，跳过执行", nodeId);
            return existingRun.get().result();
        }

        Duration timeout = node.timeout() != null ? node.timeout() : defaultTimeout;
        int retries = node.retries() != null ? node.retries() : defaultRetries;

        Exception lastException = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            NodeRun run = NodeRun.started(taskId, nodeId);
            nodeRunRepository.save(run);

            try {
                log.info("执行节点 {} (尝试 {}/{})", nodeId, attempt + 1, retries + 1);
                Object result = executeWithTimeout(node, context, timeout);
                NodeRun completed = run.complete(result);
                nodeRunRepository.save(completed);
                log.info("节点 {} 完成", nodeId);
                return result;
            } catch (Exception e) {
                lastException = e;
                log.warn("节点 {} 执行失败 (尝试 {}/{}): {}", nodeId, attempt + 1, retries + 1, e.getMessage());
                NodeRun failed = run.fail(e.getMessage());
                nodeRunRepository.save(failed);

                if (attempt < retries) {
                    try {
                        Thread.sleep(Duration.ofSeconds(1).toMillis() * (attempt + 1)); // 指数退避
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new DagExecutionException("节点 " + nodeId + " 重试被中断", ie);
                    }
                }
            }
        }

        throw new DagExecutionException("节点 " + nodeId + " 执行失败，已重试 " + retries + " 次", lastException);
    }

    private Object executeWithTimeout(Node node, NodeContext context, Duration timeout) throws Exception {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<Object> future = executor.submit(() -> node.execute(context));
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new DagExecutionException("节点 " + node.id() + " 超时 (" + timeout + ")", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            } else {
                throw new DagExecutionException("节点 " + node.id() + " 执行失败", cause);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            executor.shutdown();
        }
    }
}
