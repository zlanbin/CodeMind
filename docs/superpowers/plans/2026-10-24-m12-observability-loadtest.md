# M12 可观测与压测 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 review-service 接上 Token 成本与 DAG 节点耗时的 Micrometer 指标，Prometheus 采集、Grafana 可见；用 stub LLM 压测 webhook→评审全链路，报告存入 `docs/reports/`。

**Architecture:** Token 成本和节点耗时都走「domain 端口 + infrastructure 适配器」模式——domain 层（`LlmClient` 签名、`Node` 接口、新增 `MetricsRecorder` 端口）不依赖 Micrometer，infrastructure 层（`SpringAiLlmClient`、新增 `MicrometerMetricsRecorder`）持有 `MeterRegistry` 做实际打点。压测用新增的 `loadtest` Spring profile 把真实 LLM 换成确定性延迟的 `StubLlmClient`，隔离 LLM 网络抖动对压测数据的干扰。

**Tech Stack:** Micrometer（`micrometer-registry-prometheus`，由 `spring-boot-starter-parent:3.5.16` 的 BOM 管理版本）、Prometheus、Grafana（均经 `deploy/docker-compose.yml` 新增容器）、Java 虚拟线程（压测工具并发发请求）。

**Spec:** `docs/superpowers/specs/2026-10-24-m12-observability-loadtest-design.md`

## Global Constraints

- domain 层不依赖 Spring 和任何基础设施（包括 Micrometer）——`LlmClient`、`Node`、`MetricsRecorder` 三个接口本身不出现任何 Micrometer 类型
- 密钥只通过环境变量注入，不写入仓库——本计划不涉及新密钥
- 测试命令 `mvn verify`，运行前必须 `cd deploy && docker compose up -d`
- 测试分两类：`*Test`（surefire，纯逻辑）、`*IT`（failsafe，依赖真实中间件）
- Token 成本标签 `call_site` 固定 6 个值：`STYLE`/`SECURITY`/`PERFORMANCE`/`TEST`/`CHANGE_MAP`/`GLOBAL_REDUCE`（来自 spec 1.1，不得用动态字符串，防止 Prometheus 标签基数爆炸）
- 节点耗时标签 `node_type` 固定 6 个值：`REVIEW_CHUNK`/`BUILD_CHANGE_MAP`/`CHUNK_REDUCE`/`GLOBAL_REDUCE`/`RECALL_RULES`/`PLAN_CHUNKS`（来自 spec 1.2，同样禁止用 `node.id()` 做标签）
- `StubLlmClient` 与 `SpringAiLlmClient` 必须用 `@Profile` 互斥（`loadtest` vs `!loadtest`），不能同时存在导致 bean 装配歧义

## Review Focus

- usage 字段缺失或为 null（中转端点并非所有模型都稳定返回 usage）：按 0 记录并 `log.warn` 一次，**不抛异常、不中断调用链**——遗漏会让某次真实 LLM 响应缺 usage 时让整个评审链路失败
- `DagScheduler` 节点重试中间失败（不是最终失败）：**也要记一次 failure**，不能只在最终抛异常时才记——遗漏会让 Grafana 看到的失败率比真实重试率低，掩盖不稳定节点
- 压测工具并发创建的 webhook 若 `(repo, pr_number, head_sha)` 重复：命中 M1 幂等唯一索引，任务数会比预期少且压测工具会一直轮询一个不存在或别人的任务——遗漏会让压测报告的任务数与实际发出的请求数不一致
- `loadtest` profile 启动失败或 `StubLlmClient` 未真正替换 `SpringAiLlmClient`：压测会悄悄打到真实 LLM 中转端点，产生意外费用且结果被网络延迟污染——需要一个独立验证步骤确认生效的是哪个 bean
- Grafana 容器首次启动 provisioning 目录为空或 JSON 格式错误：dashboard 不出现但容器本身正常启动（不会报错阻塞 `docker compose up`），容易被误判为"已完成"——需要显式打开 Grafana UI 核实面板真的渲染出数据

---

## Task 1: Token 成本指标 —— domain 端口加 callSite，三处调用方传参

**Files:**
- Modify: `codemind-review/src/main/java/com/codemind/review/domain/agent/LlmClient.java`
- Modify: `codemind-review/src/main/java/com/codemind/review/domain/agent/ReviewAgent.java:115`
- Modify: `codemind-review/src/main/java/com/codemind/review/domain/changemap/ChangeMapAgent.java:75`
- Modify: `codemind-review/src/main/java/com/codemind/review/domain/agent/GlobalReduceAgent.java:87`
- Modify: 所有现有 `implements LlmClient` 的测试替身（`grep -rl "implements LlmClient" codemind-review/src/test` 先列出清单，逐个改签名）
- Test: 现有 `ReviewAgentTest`、`BuildChangeMapNodeTest`、`DagOrchestratorTest`、`CrossSessionMapReduceTest`、`GlobalReduceNodeTest`、`ReviewExecutionServiceTest` 里对 `LlmClient` 的调用断言需要跟着新签名改（不是新建测试文件，是修已有测试里的 stub 实现）

**Interfaces:**
- Consumes: 无（本任务是最底层改动）
- Produces: `LlmClient.complete(String systemPrompt, String userPrompt, String callSite)`——Task 2 的 `SpringAiLlmClient` 实现这个新签名

- [ ] **Step 1: 改 `LlmClient` 接口签名**

```java
String complete(String systemPrompt, String userPrompt, String callSite) throws LlmException;
```

在方法 Javadoc 补一行：`callSite` 是固定的调用来源标识（`STYLE`/`SECURITY`/`PERFORMANCE`/`TEST`/`CHANGE_MAP`/`GLOBAL_REDUCE` 之一），供 infrastructure 层做指标打点用，domain 层本身不解释这个值的含义。

- [ ] **Step 2: 改三处生产调用方**

`ReviewAgent.java:115`：`llmClient.complete(systemPrompt, userPrompt, dimension.name())`（`ReviewDimension` 枚举值恰好是 `STYLE`/`SECURITY`/`PERFORMANCE`/`TEST`，直接用 `.name()`）。

`ChangeMapAgent.java:75`：`llmClient.complete(SYSTEM_PROMPT, userPrompt, "CHANGE_MAP")`。

`GlobalReduceAgent.java:87`：`llmClient.complete(SYSTEM_PROMPT, userPrompt, "GLOBAL_REDUCE")`。

- [ ] **Step 3: 改全部测试替身签名**

先运行 `grep -rl "implements LlmClient" codemind-review/src/test` 列出文件清单。每个实现类的 `complete` 方法加 `String callSite` 参数（多数测试替身忽略这个参数即可，除非该测试本身要断言 callSite 传值正确）。

- [ ] **Step 4: 编译确认改动完整**

Run: `mvn -q compile test-compile -pl codemind-review -am`
Expected: BUILD SUCCESS（纯签名改动，不涉及新逻辑，不需要新测试断言；现有测试逻辑不变只是签名跟进）

- [ ] **Step 5: 跑现有单测确认未破坏行为**

Run: `mvn test -pl codemind-review`
Expected: 现有 surefire 测试全部通过（本步不新增测试，只验证签名改动没引入回归）

- [ ] **Step 6: Commit**

```bash
git add codemind-review/src/main/java/com/codemind/review/domain/agent/LlmClient.java \
        codemind-review/src/main/java/com/codemind/review/domain/agent/ReviewAgent.java \
        codemind-review/src/main/java/com/codemind/review/domain/changemap/ChangeMapAgent.java \
        codemind-review/src/main/java/com/codemind/review/domain/agent/GlobalReduceAgent.java \
        codemind-review/src/test
git commit -m "M12 T1: LlmClient.complete 加 callSite 参数，三处调用方与测试替身跟进"
```

---

## Task 2: Token 成本指标 —— SpringAiLlmClient 读 usage 打点

**Files:**
- Modify: `codemind-review/src/main/java/com/codemind/review/infrastructure/llm/SpringAiLlmClient.java`
- Modify: `codemind-review/pom.xml`（加 `micrometer-registry-prometheus`，无需写 `<version>`，由 `spring-boot-starter-parent` BOM 管理）
- Test: `codemind-review/src/test/java/com/codemind/review/infrastructure/llm/SpringAiLlmClientTest.java`（新建）

**Interfaces:**
- Consumes: `LlmClient.complete(systemPrompt, userPrompt, callSite)`（Task 1 产出）；Spring AI `ChatClient`、`io.micrometer.core.instrument.MeterRegistry`（均为 Spring 容器已有 bean，`MeterRegistry` 由 `micrometer-registry-prometheus` 自动配置提供）
- Produces: `SpringAiLlmClient` 对每次调用记录 `Counter("codemind_llm_tokens_total").tag("call_site", callSite).tag("type", "prompt"|"completion")` —— Task 4 的 Grafana dashboard 查询这个指标名

- [ ] **Step 1: 写失败测试**

```java
@Test
void recordsPromptAndCompletionTokensTaggedByCallSite() {
    // 用 mock ChatClient.Builder/ChatClient 链路返回一个带 Usage 的 ChatResponse
    // (promptTokens=100, completionTokens=50)，调用 complete(sys, user, "STYLE")
    // 断言 MeterRegistry 里 codemind_llm_tokens_total{call_site=STYLE,type=prompt} 累加了 100
    // 断言 codemind_llm_tokens_total{call_site=STYLE,type=completion} 累加了 50
}

@Test
void missingUsageRecordsZeroAndDoesNotThrow() {
    // ChatResponse.getMetadata().getUsage() 返回 null 或 usage 字段为 0
    // 断言 complete() 正常返回 content，不抛异常（Review Focus 第 1 条）
}
```

Mock `ChatClient` 的方式：Spring AI `ChatClient` 的 fluent API（`prompt().system().user().call()`）不易直接 mock 链路，用 `Mockito.mock(ChatClient.class, Answers.RETURNS_DEEP_STUBS)` 或等价的深度 stub 手段让 `.call().chatResponse()` 返回构造好的 `ChatResponse`。两个测试断言的具体 mock 搭建方式由实现时决定，只要能控制 `ChatResponse` 的 usage 返回值即可。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl codemind-review -Dtest=SpringAiLlmClientTest`
Expected: FAIL（`complete` 方法签名还是两参，或 `MeterRegistry` 未注入）

- [ ] **Step 3: 改 `pom.xml` 加依赖**

`codemind-review/pom.xml` 加：

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

- [ ] **Step 4: 实现 `SpringAiLlmClient`**

构造器新增 `MeterRegistry meterRegistry` 参数。`complete(systemPrompt, userPrompt, callSite)` 内部改用：

```java
ChatResponse response = chatClient.prompt().system(systemPrompt).user(userPrompt).call().chatResponse();
```

从 `response.getMetadata().getUsage()` 取 `getPromptTokens()` / `getCompletionTokens()`（`Usage` 接口返回 `Integer`，可能为 null——null 时按 0 处理并 `log.warn` 一次，不抛异常，符合 Review Focus 第 1 条）。用：

```java
meterRegistry.counter("codemind_llm_tokens_total", "call_site", callSite, "type", "prompt").increment(promptTokens);
meterRegistry.counter("codemind_llm_tokens_total", "call_site", callSite, "type", "completion").increment(completionTokens);
```

最终从 `response` 取出文本内容（`response.getResult().getOutput().getText()`，具体取法以 Spring AI 1.1.8 API 为准）返回给调用方，空内容仍抛 `LlmException`（保持原有行为）。

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn test -pl codemind-review -Dtest=SpringAiLlmClientTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add codemind-review/pom.xml codemind-review/src/main/java/com/codemind/review/infrastructure/llm/SpringAiLlmClient.java codemind-review/src/test/java/com/codemind/review/infrastructure/llm/SpringAiLlmClientTest.java
git commit -m "M12 T2: SpringAiLlmClient 读真实 usage 打点 Token 成本指标"
```

---

## Task 3: 节点耗时指标 —— Node.nodeType() + MetricsRecorder 端口 + DagScheduler 打点

**Files:**
- Modify: `codemind-review/src/main/java/com/codemind/review/domain/dag/Node.java`
- Modify: `codemind-review/src/main/java/com/codemind/review/domain/dag/ReviewChunkNode.java`
- Modify: `codemind-review/src/main/java/com/codemind/review/domain/dag/BuildChangeMapNode.java`
- Modify: `codemind-review/src/main/java/com/codemind/review/domain/dag/ChunkReduceNode.java`
- Modify: `codemind-review/src/main/java/com/codemind/review/domain/dag/GlobalReduceNode.java`
- Modify: `codemind-review/src/main/java/com/codemind/review/domain/dag/RecallRulesNode.java`
- Modify: `codemind-review/src/main/java/com/codemind/review/domain/dag/PlanChunksNode.java`
- Create: `codemind-review/src/main/java/com/codemind/review/domain/dag/MetricsRecorder.java`
- Modify: `codemind-review/src/main/java/com/codemind/review/domain/dag/DagScheduler.java`
- Create: `codemind-review/src/main/java/com/codemind/review/infrastructure/metrics/MicrometerMetricsRecorder.java`
- Modify: `codemind-review/src/main/java/com/codemind/review/infrastructure/config/ReviewDagConfig.java`
- Test: `codemind-review/src/test/java/com/codemind/review/domain/dag/DagSchedulerTest.java`（已存在，加新用例）
- Test: `codemind-review/src/test/java/com/codemind/review/infrastructure/metrics/MicrometerMetricsRecorderTest.java`（新建）

**Interfaces:**
- Consumes: 无新端口依赖
- Produces: `MetricsRecorder.recordNodeExecution(String nodeType, long durationMillis, boolean success)` 接口；`Node.nodeType()` 默认方法；`MicrometerMetricsRecorder` 记录 `Timer("codemind_dag_node_duration").tag("node_type", nodeType).tag("success", String.valueOf(success))` —— Task 4 的 Grafana dashboard 查询这个指标名

- [ ] **Step 1: 写 `MetricsRecorder` 端口的失败测试**

先创建接口本身（接口没有行为需要先测），直接进 Step 2 写 `DagScheduler` 的失败测试：

```java
@Test
void recordsNodeExecutionMetricOnSuccess() {
    // 用一个 fake MetricsRecorder（记录收到的调用参数到一个列表）注入 DagScheduler
    // 构造一个单节点 DAG，节点返回成功结果
    // 执行后断言 fake recorder 收到恰好一次 recordNodeExecution(nodeType, durationMillis>=0, success=true)
    // nodeType 断言等于该节点 Node.nodeType() 的返回值
}

@Test
void recordsFailureMetricOnEachRetryAttemptNotOnlyFinal() {
    // 节点配置 retries=2，每次 execute() 都抛异常
    // 断言 fake recorder 收到 3 次 recordNodeExecution(nodeType, _, success=false)
    // （初次 + 2 次重试，对应 Review Focus 第 2 条：中间失败也要记）
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl codemind-review -Dtest=DagSchedulerTest`
Expected: FAIL（`DagScheduler` 构造器还没有 `MetricsRecorder` 参数）

- [ ] **Step 3: 定义 `Node.nodeType()` 默认方法**

`Node.java` 加：

```java
default String nodeType() {
    return getClass().getSimpleName();
}
```

六个实现类各自覆写返回固定常量：`ReviewChunkNode→"REVIEW_CHUNK"`、`BuildChangeMapNode→"BUILD_CHANGE_MAP"`、`ChunkReduceNode→"CHUNK_REDUCE"`、`GlobalReduceNode→"GLOBAL_REDUCE"`、`RecallRulesNode→"RECALL_RULES"`、`PlanChunksNode→"PLAN_CHUNKS"`。

- [ ] **Step 4: 定义 `MetricsRecorder` 端口**

```java
package com.codemind.review.domain.dag;

public interface MetricsRecorder {
    void recordNodeExecution(String nodeType, long durationMillis, boolean success);
}
```

- [ ] **Step 5: `DagScheduler` 注入并打点**

构造器新增 `MetricsRecorder metricsRecorder` 参数（与现有 `NodeRunRepository` 同等地位）。`executeNode()` 的 `for` 循环内：成功分支（`return result` 之前）记一次 `success=true`；catch 分支（`log.warn` 之后）记一次 `success=false`——两处都要算出 `durationMillis`（用 `System.currentTimeMillis()` 在 `executeWithTimeout` 调用前后各取一次，或 `System.nanoTime()` 换算，具体计时方式不影响接口契约）。

- [ ] **Step 6: 跑测试确认通过**

Run: `mvn test -pl codemind-review -Dtest=DagSchedulerTest`
Expected: PASS

- [ ] **Step 7: 写 `MicrometerMetricsRecorderTest` 失败测试**

```java
@Test
void recordsTimerTaggedByNodeTypeAndSuccess() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MicrometerMetricsRecorder recorder = new MicrometerMetricsRecorder(registry);
    recorder.recordNodeExecution("REVIEW_CHUNK", 1234L, true);
    Timer timer = registry.find("codemind_dag_node_duration")
            .tag("node_type", "REVIEW_CHUNK").tag("success", "true").timer();
    assertThat(timer).isNotNull();
    assertThat(timer.count()).isEqualTo(1);
}
```

- [ ] **Step 8: 跑测试确认失败，再实现 `MicrometerMetricsRecorder`**

Run: `mvn test -pl codemind-review -Dtest=MicrometerMetricsRecorderTest` → FAIL（类不存在）

实现：

```java
package com.codemind.review.infrastructure.metrics;

public class MicrometerMetricsRecorder implements MetricsRecorder {
    private final MeterRegistry meterRegistry;
    public MicrometerMetricsRecorder(MeterRegistry meterRegistry) { this.meterRegistry = meterRegistry; }
    @Override
    public void recordNodeExecution(String nodeType, long durationMillis, boolean success) {
        meterRegistry.timer("codemind_dag_node_duration", "node_type", nodeType, "success", String.valueOf(success))
                .record(durationMillis, TimeUnit.MILLISECONDS);
    }
}
```

- [ ] **Step 9: 跑测试确认通过**

Run: `mvn test -pl codemind-review -Dtest=MicrometerMetricsRecorderTest`
Expected: PASS

- [ ] **Step 10: 接入 `ReviewDagConfig`**

加一个 `@Bean public MetricsRecorder metricsRecorder(MeterRegistry meterRegistry) { return new MicrometerMetricsRecorder(meterRegistry); }`，`dagScheduler(...)` 方法签名加 `MetricsRecorder metricsRecorder` 参数并传给 `new DagScheduler(...)`。

- [ ] **Step 11: 全量编译确认装配正确**

Run: `mvn -q compile -pl codemind-review -am`
Expected: BUILD SUCCESS

- [ ] **Step 12: Commit**

```bash
git add codemind-review/src/main/java/com/codemind/review/domain/dag \
        codemind-review/src/main/java/com/codemind/review/infrastructure/metrics \
        codemind-review/src/main/java/com/codemind/review/infrastructure/config/ReviewDagConfig.java \
        codemind-review/src/test
git commit -m "M12 T3: Node.nodeType() + MetricsRecorder 端口，DagScheduler 记录节点耗时指标"
```

---

## Task 4: Prometheus + Grafana 接入

**Files:**
- Modify: `deploy/docker-compose.yml`
- Create: `deploy/init/prometheus/prometheus.yml`
- Create: `deploy/init/grafana/provisioning/datasources/prometheus.yml`
- Create: `deploy/init/grafana/provisioning/dashboards/dashboards.yml`
- Create: `deploy/init/grafana/dashboards/codemind.json`
- Modify: `codemind-gateway/src/main/resources/application.yml`
- Modify: `codemind-integration/src/main/resources/application.yml`
- Modify: `codemind-review/src/main/resources/application.yml`
- Modify: `codemind-memory/src/main/resources/application.yml`
- Modify: `codemind-rule/src/main/resources/application.yml`
- Modify: `codemind-admin/src/main/resources/application.yml`
- Modify: `codemind-gateway/pom.xml`、`codemind-integration/pom.xml`、`codemind-memory/pom.xml`、`codemind-rule/pom.xml`、`codemind-admin/pom.xml`（各加 `micrometer-registry-prometheus`，`codemind-review` 已在 Task 2 加过）

**Interfaces:**
- Consumes: Task 2/3 产出的指标名 `codemind_llm_tokens_total`、`codemind_dag_node_duration`
- Produces: Grafana 可访问的 dashboard，Task 6 压测报告引用其截图/快照

- [ ] **Step 1: 其余 5 个服务加 micrometer 依赖**

每个模块 `pom.xml` 加（内容同 Task 2 Step 3）：

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

- [ ] **Step 2: 6 个服务 `application.yml` 补 `management.endpoints.web.exposure.include`**

把现有 `include: health,info`（或类似配置）改为 `include: health,info,prometheus`。6 个模块逐一确认现状后补齐（先 `grep -n "exposure" codemind-*/src/main/resources/application.yml` 列出现状，review-service 现在是 `health,info`，照此模式改其余 5 个）。

- [ ] **Step 3: 写 `deploy/init/prometheus/prometheus.yml`**

```yaml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'codemind-services'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets:
          - 'host.docker.internal:8080'  # gateway
          - 'host.docker.internal:8081'  # integration
          - 'host.docker.internal:8082'  # review
          - 'host.docker.internal:8083'  # memory
          - 'host.docker.internal:8084'  # rule
          - 'host.docker.internal:8085'  # admin
        labels:
          group: 'codemind'
```

服务跑在宿主机（非容器内，项目既有做法是 `java -jar` 直接在宿主机跑），Prometheus 容器用 `host.docker.internal` 访问宿主机端口（Docker Desktop for Windows 原生支持这个域名，不需要额外配置 `extra_hosts`）。

- [ ] **Step 4: 写 Grafana provisioning 文件**

`deploy/init/grafana/provisioning/datasources/prometheus.yml`：

```yaml
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
```

`deploy/init/grafana/provisioning/dashboards/dashboards.yml`：

```yaml
apiVersion: 1
providers:
  - name: codemind
    folder: CodeMind
    type: file
    options:
      path: /etc/grafana/provisioning/dashboards/files
```

- [ ] **Step 5: 写 dashboard JSON**

`deploy/init/grafana/dashboards/codemind.json`：至少两个面板，Panel 1 用 PromQL `sum by (call_site) (codemind_llm_tokens_total)` 画 Token 用量曲线；Panel 2 用 `histogram_quantile(0.95, sum by (le, node_type) (rate(codemind_dag_node_duration_seconds_bucket[5m])))` 画节点耗时 P95（Micrometer `Timer` 默认导出为 Prometheus histogram，指标名加 `_seconds_bucket` 后缀）。具体 JSON 结构用 Grafana 标准 dashboard schema（`"panels": [...]`，每个 panel 的 `"targets"` 里放上述 PromQL）。

- [ ] **Step 6: 改 `deploy/docker-compose.yml` 加两个服务**

```yaml
  prometheus:
    image: prom/prometheus:v2.55.1
    container_name: codemind-prometheus
    ports:
      - "9090:9090"
    volumes:
      - ./init/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
    extra_hosts:
      - "host.docker.internal:host-gateway"

  grafana:
    image: grafana/grafana:11.3.1
    container_name: codemind-grafana
    ports:
      - "3000:3000"
    environment:
      GF_SECURITY_ADMIN_PASSWORD: codemind
    volumes:
      - grafanadata:/var/lib/grafana
      - ./init/grafana/provisioning:/etc/grafana/provisioning:ro
      - ./init/grafana/dashboards:/etc/grafana/provisioning/dashboards/files:ro
    depends_on:
      - prometheus
```

`volumes:` 顶层段加 `grafanadata:`（和现有 `pgdata`/`redisdata`/`nacosdata` 同级）。

- [ ] **Step 7: 验证——起容器，确认两个服务被 Prometheus 正确抓取**

```bash
cd deploy && docker compose up -d prometheus grafana
# 确认 integration/review 等服务已用正常方式起着（宿主机 java -jar）
curl http://127.0.0.1:9090/api/v1/targets
```

Expected: `targets` 响应里 `codemind-services` job 的每个 target `health` 字段为 `up`（至少已启动的服务，如 review-service）。

- [ ] **Step 8: 验证——Grafana dashboard 渲染出数据**

```bash
curl -u admin:codemind http://127.0.0.1:3000/api/search?query=codemind
```

Expected: 返回里能看到 `codemind.json` 对应的 dashboard（确认 provisioning 生效，这是 Review Focus 第 5 条要求的显式核实，不能只看容器 `docker compose up` 无报错就判定完成）。再用浏览器打开 `http://127.0.0.1:3000` 用 `admin`/`codemind` 登录，进 CodeMind 文件夹，确认两个面板存在（即使当时没有真实流量、面板也应该渲染出来，只是没有数据点，不报错）。

- [ ] **Step 9: Commit**

```bash
git add deploy/docker-compose.yml deploy/init/prometheus deploy/init/grafana \
        codemind-gateway/pom.xml codemind-integration/pom.xml codemind-memory/pom.xml codemind-rule/pom.xml codemind-admin/pom.xml \
        codemind-*/src/main/resources/application.yml
git commit -m "M12 T4: compose 接入 Prometheus + Grafana，6 服务暴露 /actuator/prometheus"
```

---

## Task 5: `loadtest` profile 与 `StubLlmClient`

**Files:**
- Create: `codemind-review/src/main/java/com/codemind/review/infrastructure/llm/StubLlmClient.java`
- Modify: `codemind-review/src/main/java/com/codemind/review/infrastructure/llm/SpringAiLlmClient.java`（加 `@Profile("!loadtest")`）
- Create: `codemind-review/src/main/resources/application-loadtest.yml`
- Test: `codemind-review/src/test/java/com/codemind/review/infrastructure/llm/StubLlmClientTest.java`（新建）

**Interfaces:**
- Consumes: `LlmClient.complete(systemPrompt, userPrompt, callSite)`（Task 1 签名）
- Produces: `@Profile("loadtest")` 下唯一的 `LlmClient` bean。Task 6 的压测工具依赖 `loadtest` profile 启动的 review-service 实例，不直接调用 `StubLlmClient` 的 Java API

- [ ] **Step 1: 写 `StubLlmClientTest` 失败测试**

```java
@Test
void returnsLegalFindingJsonForReviewDimensionCallSites() {
    StubLlmClient client = new StubLlmClient();
    for (String callSite : List.of("STYLE", "SECURITY", "PERFORMANCE", "TEST")) {
        String raw = client.complete("sys", "user", callSite);
        // 断言能被现有 FindingJsonParser 解析成功（复用 Task 1 前既有的 FindingJsonParser）
        List<Finding> findings = new FindingJsonParser(new ObjectMapper()).parse(raw);
        assertThat(findings).isNotNull();
    }
}

@Test
void returnsLegalChangeMapJsonForChangeMapCallSite() {
    // 断言 complete(..., "CHANGE_MAP") 返回的内容能被 ChangeMapJsonParser 解析
}

@Test
void returnsLegalGlobalReduceJsonForGlobalReduceCallSite() {
    // 断言 complete(..., "GLOBAL_REDUCE") 返回的内容能被 FindingJsonParser 解析（GlobalReduceAgent 复用同一解析器）
}

@Test
void sleepsBetweenTwoAndEightSeconds() {
    long start = System.currentTimeMillis();
    new StubLlmClient().complete("sys", "user", "STYLE");
    long elapsed = System.currentTimeMillis() - start;
    assertThat(elapsed).isBetween(2000L, 8100L); // 留 100ms 余量给方法本身的开销
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl codemind-review -Dtest=StubLlmClientTest`
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现 `StubLlmClient`**

```java
package com.codemind.review.infrastructure.llm;

@Component
@Profile("loadtest")
public class StubLlmClient implements LlmClient {
    @Override
    public String complete(String systemPrompt, String userPrompt, String callSite) {
        sleepRandomly();
        return switch (callSite) {
            case "CHANGE_MAP" -> SAMPLE_CHANGE_MAP_JSON;
            case "GLOBAL_REDUCE" -> SAMPLE_GLOBAL_REDUCE_FINDING_JSON;
            default -> SAMPLE_FINDING_JSON; // STYLE/SECURITY/PERFORMANCE/TEST 共用一份评审样例
        };
    }
    private void sleepRandomly() {
        try {
            Thread.sleep(2000 + ThreadLocalRandom.current().nextLong(6001));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

三个样例 JSON 常量按 `ReviewAgent`/`ChangeMapAgent`/`GlobalReduceAgent` 各自要求的结构手写（`ReviewAgent` 要求的结构见 `ReviewAgent.java` 里的 `BASE_SYSTEM_PROMPT` 常量，含 `findings` + `summary` 两块；`ChangeMap`/`GlobalReduce` 的结构参照 `ChangeMapJsonParser`/`FindingJsonParser` 的现有解析逆推），各给 1~2 条样例 Finding/摘要，字段全部合法（不要留空摘要触发降级分支，压测要测的是正常路径吞吐）。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -pl codemind-review -Dtest=StubLlmClientTest`
Expected: PASS

- [ ] **Step 5: `SpringAiLlmClient` 加 `@Profile("!loadtest")`**

类注解从 `@Component` 改为 `@Component @Profile("!loadtest")`（Global Constraints 要求的互斥）。

- [ ] **Step 6: 写 `application-loadtest.yml`**

```yaml
# loadtest profile：用 StubLlmClient 替换真实 LLM，隔离网络抖动对压测数据的干扰（见 spec 3.1）
codemind:
  llm:
    max-retries: 0  # stub 从不失败，不需要重试预算
```

- [ ] **Step 7: 验证——两个 profile 下恰好各有一个 `LlmClient` bean**

写一个 `@SpringBootTest(properties = "spring.profiles.active=loadtest")` 的上下文加载测试（或在现有 `ReviewApplicationIT` 基础上加一个 `-loadtest` 变体），断言容器里 `LlmClient` bean 的具体类型是 `StubLlmClient`；默认 profile（不带 `loadtest`）下断言是 `SpringAiLlmClient`。这一步直接对应 Review Focus 第 4 条——不能只靠肉眼看 `@Profile` 注解就认为生效，要有测试实锤。

Run: `mvn test -pl codemind-review -Dtest=<新增的profile验证测试类名>`
Expected: PASS，两个 profile 各自解析出正确的 `LlmClient` 实现类

- [ ] **Step 8: Commit**

```bash
git add codemind-review/src/main/java/com/codemind/review/infrastructure/llm \
        codemind-review/src/main/resources/application-loadtest.yml \
        codemind-review/src/test
git commit -m "M12 T5: loadtest profile 用 StubLlmClient 替换真实 LLM"
```

---

## Task 6: 压测工具、跑一轮、写报告

**Files:**
- Create: `codemind-review/src/test/java/com/codemind/review/loadtest/LoadTestRunner.java`
- Create: `docs/reports/M12-loadtest.md`

**Interfaces:**
- Consumes: Task 5 的 `loadtest` profile 启动的 review-service；integration-service 的 `POST /webhook/github`（现有端点，签名算法照抄 `GithubWebhookIT.sign()`）；`review_task` 表的 `status` 列（V1 迁移，枚举值见 `ReviewTaskStatus`）
- Produces: `docs/reports/M12-loadtest.md`（本计划终产物，不被其他任务消费）

- [ ] **Step 1: 写 `LoadTestRunner`（不是单测，是手动运行的压测主程序）**

放在 `src/test/java` 下但不是 `*Test`/`*IT`（不参与 `mvn verify` 自动执行，和项目里 `support/` 下 sandbox 工具的既有做法一致——参照 `codemind-review` 或 `codemind-integration` 下已有的 `support/` 包手动工具的放置模式）。

```java
public final class LoadTestRunner {
    public static void main(String[] args) throws Exception {
        int concurrency = 10;
        int totalTasks = 30;
        String webhookSecret = System.getenv("GITHUB_WEBHOOK_SECRET");
        String integrationBaseUrl = "http://127.0.0.1:8081";
        // 1. 用虚拟线程池并发发 webhook，每个任务用独立 UUID 做 repo 名后缀避免幂等冲突
        //    （同 PROGRESS 里 M4 ChunkRepositoryIT 修复用的手法：独立 repo 名 + UUID）
        //    记录每个任务发出时用的 (repo, pr_number, head_sha) 三元组，供第 3 步核对
        // 2. 记录每个任务的发送时间戳
        // 3. 轮询 PG review_task 表（JDBC 直连，复用 application.yml 里的连接串环境变量）
        //    直到 status 变成 AGGREGATING / FAILED，或超时（如 180s）
        // 4. 汇总打印：发出数 vs 按 (repo, pr_number, head_sha) 在 review_task 实际查到的任务数
        //    （两者不一致说明被幂等索引拦掉或 UUID 生成冲突，Review Focus 第 3 条——必须先核对这一步，
        //    数字不一致时先查原因，不能把它当成「失败数」直接混进延迟统计）；
        //    核对一致后再汇总：成功数/失败数/超时数，P50/P95/P99 延迟，吞吐（任务数/分钟）
    }
}
```

HMAC 签名逻辑直接照抄 `codemind-integration/src/test/java/com/codemind/integration/GithubWebhookIT.java` 的 `sign()` 方法（`Mac.getInstance("HmacSHA256")` + `HexFormat`）。PG 查询用 `java.sql.DriverManager` 直连或复用 `spring-boot-starter-jdbc` 已有的 `DataSource`（具体选择由实现时定，`LoadTestRunner` 本身不是 Spring 容器管理的 bean，用最简单能跑的方式）。

- [ ] **Step 2: 手动验证——`loadtest` profile 启动 review-service**

```bash
cd deploy && docker compose up -d
mvn -q package -DskipTests -pl codemind-review -am
cp codemind-review/target/codemind-review-0.1.0-SNAPSHOT.jar /tmp/codemind-review-loadtest.jar
GITHUB_WEBHOOK_SECRET=<任意> java -jar /tmp/codemind-review-loadtest.jar --spring.profiles.active=loadtest &
```

同时按 README 现有方式起 `codemind-integration`（真实流程需要它接收 webhook、发 MQ 事件给 review-service）。

- [ ] **Step 3: 跑一轮压测**

```bash
mvn -q compile test-compile -pl codemind-review -am
# 用 maven-exec-plugin 或直接 java -cp 跑 LoadTestRunner.main，具体调用方式实现时定
```

Expected: 控制台打印出 30 个任务的汇总统计，脱离无限等待（有超时兜底）。

- [ ] **Step 4: 核实指标在压测时确实在工作**

```bash
curl http://127.0.0.1:8082/actuator/prometheus | grep codemind_llm_tokens_total
curl http://127.0.0.1:8082/actuator/prometheus | grep codemind_dag_node_duration
```

Expected: 两个指标名都出现且数值随压测增长（`codemind_llm_tokens_total` 用的是 Task 5 里 `loadtest` profile 下的假 token 数，详见 spec 3.1 最后一段——`loadtest` 下仍要打点验证埋点链路本身工作，不是只在生产装配下才生效）。

- [ ] **Step 5: 在 Grafana 上核实两个面板有数据**

打开 `http://127.0.0.1:3000`，CodeMind 文件夹下的 dashboard，确认 Token 用量曲线和节点耗时 P95 面板在压测时间窗口内有数据点（不是空白）。

- [ ] **Step 6: 写 `docs/reports/M12-loadtest.md`**

包含：
- 测试条件：并发数（10）、任务数（30）、`loadtest` profile 下 stub 延迟分布（2~8s 均匀分布）、运行环境（本机配置、是否容器化）
- 结果表格：成功率、延迟分布（P50/P95/P99，单位秒）、吞吐（任务数/分钟）—— 真实跑出来的数字，不是占位符
- 观察现象：哪个 `node_type` 最慢（从 Grafana P95 面板读出）、DAG 并行节点是否真的并行（同一任务内多个 `REVIEW_CHUNK` 节点的总耗时是否接近单节点耗时而非相加，参照 M3 `DagSchedulerTest.parallelNodesExecuteConcurrently` 的验证逻辑类比到真实压测数据上）、`call_site` 维度的 token 用量分布

- [ ] **Step 7: Commit**

```bash
git add codemind-review/src/test/java/com/codemind/review/loadtest docs/reports/M12-loadtest.md
git commit -m "M12 T6: 压测工具 + 10并发x30任务摸底报告"
```

---

## 收尾（S6~S8，不在本计划的任务清单内，执行完 Task 1~6 后按 WORKFLOW.md 继续）

本计划只覆盖 S4（任务拆分）～S5（代码编写）范围。完成 Task 1~6 并全部测试通过后，回到 `docs/WORKFLOW.md` 的 S6（代码评审，固定点用 M11 完成时的 commit）→ S7（`mvn verify` 全量验证门，检查有没有新增 skipped）→ S8（更新 `docs/PROGRESS.md` / `docs/ARCHITECTURE.md`）。
