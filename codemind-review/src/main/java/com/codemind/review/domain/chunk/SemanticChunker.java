package com.codemind.review.domain.chunk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 按 AST 方法边界把 diff 切成 {@link ReviewChunk}（ARCHITECTURE 6.2）。
 *
 * <p>切片不变量：
 * <ul>
 *   <li>Java 文件按方法边界切——同一方法的所有 hunk 落在同一片，<b>方法不被截断</b>；
 *       无法解析的 Java / 非 Java 文件按 hunk 降级（ARCHITECTURE 1.2）。</li>
 *   <li>有调用关系的变更合进同一片（跨文件也合）——这是软约束，整体超预算时让位，
 *       沿合并缝拆回原子单元（ARCHITECTURE 6.3 降级链最后一步）。</li>
 *   <li>按 diff token 预算装箱，每片不超 {@link ChunkBudget#diffBudget()}；
 *       <b>单个原子单元</b>（一个完整方法）本身超预算时整片输出并置 {@code oversized}，
 *       不截断、不丢弃。</li>
 * </ul>
 *
 * <p>Java 方法边界需要文件完整源码（new 侧）。源码由调用方提供（拉文件内容是 integration 的职责，
 * ARCHITECTURE 3.3），domain 层只做纯算法。未提供源码或解析失败则对该文件降级为 hunk 切片。
 */
public final class SemanticChunker {

    private static final Logger log = LoggerFactory.getLogger(SemanticChunker.class);

    private final TokenEstimator tokenEstimator;
    private final JavaMethodIndex methodIndex = new JavaMethodIndex();

    public SemanticChunker(TokenEstimator tokenEstimator) {
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "tokenEstimator");
    }

    /**
     * 切片。
     *
     * @param files      已解析的变更文件
     * @param newSources 文件新路径 → 完整新源码；只对 Java 文件有意义，缺失则该文件降级
     * @param budget     token 预算
     */
    public List<ReviewChunk> chunk(List<DiffFile> files, Map<String, String> newSources, ChunkBudget budget) {
        Objects.requireNonNull(files, "files");
        Objects.requireNonNull(budget, "budget");
        Map<String, String> sources = newSources == null ? Map.of() : newSources;

        List<Unit> units = new ArrayList<>();
        for (DiffFile file : files) {
            if (file.hunks().isEmpty()) {
                continue; // 二进制 / 纯模式变更，无可评审 diff
            }
            units.addAll(buildUnits(file, sources.get(file.path())));
        }
        if (units.isEmpty()) {
            return List.of();
        }

        mergeByCallRelationship(units);
        return packIntoChunks(splitOversizedUnits(units, budget), budget);
    }

    /** 把一个文件切成若干 unit（方法级或 hunk 级降级）。 */
    private List<Unit> buildUnits(DiffFile file, String newSource) {
        if (file.isJava() && newSource != null) {
            List<Unit> byMethod = methodIndex.splitByMethod(file, newSource);
            if (byMethod != null) {
                return byMethod;
            }
        }
        // 降级：每个 hunk 一个 unit
        List<Unit> fallback = new ArrayList<>();
        for (DiffHunk hunk : file.hunks()) {
            fallback.add(new Unit(file, new ArrayList<>(List.of(hunk)), new LinkedHashSet<>()));
        }
        return fallback;
    }

    /**
     * 合并有调用关系的 unit：若 unit B 的 diff 文本里出现了 unit A 的某个变更方法名（跨文件时），
     * 认为二者相关，合入同一片。用并查集按发现顺序合并。
     */
    private void mergeByCallRelationship(List<Unit> units) {
        int n = units.size();
        DisjointSet dsu = new DisjointSet(n);
        for (int a = 0; a < n; a++) {
            Set<String> symbolsA = units.get(a).methodNames();
            if (symbolsA.isEmpty()) {
                continue;
            }
            for (int b = 0; b < n; b++) {
                if (a == b || units.get(a).sameFile(units.get(b))) {
                    continue; // 同文件内已按方法切，这里只连跨文件调用关系
                }
                String diffB = units.get(b).addedAndContextText();
                if (referencesAny(diffB, symbolsA)) {
                    dsu.union(a, b);
                }
            }
        }
        // 按并查集根归组，合并 unit（保持首次出现顺序）
        Map<Integer, Unit> merged = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int root = dsu.find(i);
            Unit u = units.get(i);
            if (merged.containsKey(root)) {
                merged.get(root).absorb(u);
            } else {
                merged.put(root, u);
            }
        }
        units.clear();
        units.addAll(merged.values());
    }

    private boolean referencesAny(String text, Set<String> methodNames) {
        for (String qualified : methodNames) {
            // 限定名自 M6 起带参数类型（OrderService#cancel(int)），按调用形态匹配只取裸方法名
            String simple = MethodSignatures.simpleMethodName(qualified);
            if (simple.isBlank()) {
                continue;
            }
            // 以调用形态匹配：methodName( ，避免子串误伤
            if (text.contains(simple + "(")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 超预算降级的最后一步「拆分 chunk」（ARCHITECTURE 6.3）：聚合出来的 unit 若整体超 diff 预算，
     * 沿合并缝拆回原子单元，让装箱有机会把它们分到不同片。
     *
     * <p>只拆聚合关系，不拆方法——单个原子单元自己超预算时原样留下，由装箱标 {@code oversized}。
     * 代价是牺牲「有调用关系的变更在同一片」这条软约束；跨片关联改由 M6 的 GlobalReduce 兜
     * （它只读各片摘要，正是为这种情况准备的）。
     */
    private List<Unit> splitOversizedUnits(List<Unit> units, ChunkBudget budget) {
        int diffBudget = budget.diffBudget();
        List<Unit> result = new ArrayList<>();
        for (Unit unit : units) {
            int tokens = tokenEstimator.estimate(unit.render());
            if (tokens <= diffBudget || !unit.isSplittable()) {
                result.add(unit);
                continue;
            }
            List<Unit> parts = unit.constituents();
            log.info("聚合单元超 diff 预算（{} > {}），沿合并缝拆回 {} 个原子单元：{}",
                    tokens, diffBudget, parts.size(), unit.filePaths());
            // 拆出来的原子单元可能仍需再拆（递归展开已在 constituents 完成，此处直接摊平）
            result.addAll(parts);
        }
        return result;
    }

    /** 贪心装箱：按 diff token 预算把 unit 填进 chunk，超预算的 unit 独占一片并标记 oversized。 */
    private List<ReviewChunk> packIntoChunks(List<Unit> units, ChunkBudget budget) {
        int diffBudget = budget.diffBudget();
        List<ReviewChunk> chunks = new ArrayList<>();
        List<Unit> current = new ArrayList<>();
        int currentTokens = 0;
        int seq = 0;

        for (Unit unit : units) {
            String unitText = unit.render();
            int unitTokens = tokenEstimator.estimate(unitText);

            if (unitTokens > diffBudget) {
                // 单 unit 超预算：先封存已累积的片，再让它独占一片（退化场景，不截断）
                if (!current.isEmpty()) {
                    chunks.add(toChunk(current, seq++, diffBudget));
                    current = new ArrayList<>();
                    currentTokens = 0;
                }
                chunks.add(toChunk(List.of(unit), seq++, diffBudget));
                continue;
            }
            if (!current.isEmpty() && currentTokens + unitTokens > diffBudget) {
                chunks.add(toChunk(current, seq++, diffBudget));
                current = new ArrayList<>();
                currentTokens = 0;
            }
            current.add(unit);
            currentTokens += unitTokens;
        }
        if (!current.isEmpty()) {
            chunks.add(toChunk(current, seq, diffBudget));
        }
        return List.copyOf(chunks);
    }

    private ReviewChunk toChunk(List<Unit> units, int seq, int diffBudget) {
        List<String> files = new ArrayList<>();
        List<String> symbols = new ArrayList<>();
        StringBuilder diff = new StringBuilder();
        for (Unit u : units) {
            for (String path : u.filePaths()) {
                if (!files.contains(path)) {
                    files.add(path);
                }
            }
            symbols.addAll(u.methodNames());
            if (diff.length() > 0) {
                diff.append('\n');
            }
            diff.append(u.render());
        }
        String diffContent = diff.toString();
        int tokens = tokenEstimator.estimate(diffContent);
        return new ReviewChunk("chunk-" + seq, files, symbols, diffContent, tokens, tokens > diffBudget);
    }
}
