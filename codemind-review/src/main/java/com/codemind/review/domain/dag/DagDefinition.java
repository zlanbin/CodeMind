package com.codemind.review.domain.dag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * DAG 定义（拓扑结构 + 节点）。
 *
 * <p>用代码构建（builder 模式），不支持循环依赖。
 */
public final class DagDefinition {

    private final Map<String, Node> nodes;
    private final Map<String, List<String>> dependencies; // nodeId → 前置节点 ID 列表

    private DagDefinition(Map<String, Node> nodes, Map<String, List<String>> dependencies) {
        this.nodes = Map.copyOf(nodes);
        Map<String, List<String>> copiedDeps = new HashMap<>();
        dependencies.forEach((k, v) -> copiedDeps.put(k, List.copyOf(v)));
        this.dependencies = Map.copyOf(copiedDeps);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Map<String, Node> nodes() {
        return nodes;
    }

    public Map<String, List<String>> dependencies() {
        return dependencies;
    }

    /** 获取入度为 0 的节点（没有前置依赖）。 */
    public Set<String> getReadyNodes() {
        Set<String> ready = new HashSet<>(nodes.keySet());
        for (List<String> deps : dependencies.values()) {
            ready.removeAll(deps);
        }
        return ready;
    }

    /** 获取指定节点的直接后继（依赖该节点的节点）。 */
    public Set<String> getSuccessors(String nodeId) {
        Set<String> successors = new HashSet<>();
        dependencies.forEach((node, deps) -> {
            if (deps.contains(nodeId)) {
                successors.add(node);
            }
        });
        return successors;
    }

    public static final class Builder {
        private final Map<String, Node> nodes = new HashMap<>();
        private final Map<String, List<String>> dependencies = new HashMap<>();

        public Builder addNode(Node node) {
            Objects.requireNonNull(node, "node");
            if (nodes.containsKey(node.id())) {
                throw new IllegalArgumentException("节点 ID 重复: " + node.id());
            }
            nodes.put(node.id(), node);
            dependencies.putIfAbsent(node.id(), List.of());
            return this;
        }

        /**
         * 添加节点并声明其依赖（依赖列表中的节点必须在该节点之前完成）。
         *
         * @param node 节点
         * @param dependsOn 依赖的节点 ID 列表
         */
        public Builder addNode(Node node, List<String> dependsOn) {
            addNode(node);
            for (String depId : dependsOn) {
                addDependency(node.id(), depId);
            }
            return this;
        }

        /**
         * 声明依赖：node 依赖 dependency（dependency 必须在 node 之前完成）。
         */
        public Builder addDependency(String nodeId, String dependencyId) {
            if (!nodes.containsKey(nodeId)) {
                throw new IllegalArgumentException("节点不存在: " + nodeId);
            }
            if (!nodes.containsKey(dependencyId)) {
                throw new IllegalArgumentException("依赖节点不存在: " + dependencyId);
            }
            List<String> deps = dependencies.get(nodeId);
            if (deps == null || deps.isEmpty()) {
                dependencies.put(nodeId, new ArrayList<>(List.of(dependencyId)));
            } else {
                // 已有依赖，需要创建新列表（避免 List.of() 返回的不可变列表）
                if (!(deps instanceof ArrayList)) {
                    deps = new ArrayList<>(deps);
                    dependencies.put(nodeId, deps);
                }
                deps.add(dependencyId);
            }
            return this;
        }

        public DagDefinition build() {
            if (nodes.isEmpty()) {
                throw new IllegalStateException("DAG 至少需要一个节点");
            }
            detectCycle();
            return new DagDefinition(nodes, dependencies);
        }

        private void detectCycle() {
            Set<String> visited = new HashSet<>();
            Set<String> recStack = new HashSet<>();
            for (String nodeId : nodes.keySet()) {
                if (hasCycle(nodeId, visited, recStack)) {
                    throw new IllegalStateException("DAG 存在循环依赖，无法构建");
                }
            }
        }

        private boolean hasCycle(String nodeId, Set<String> visited, Set<String> recStack) {
            if (recStack.contains(nodeId)) {
                return true;
            }
            if (visited.contains(nodeId)) {
                return false;
            }
            visited.add(nodeId);
            recStack.add(nodeId);
            for (String dep : dependencies.getOrDefault(nodeId, List.of())) {
                if (hasCycle(dep, visited, recStack)) {
                    return true;
                }
            }
            recStack.remove(nodeId);
            return false;
        }
    }
}
