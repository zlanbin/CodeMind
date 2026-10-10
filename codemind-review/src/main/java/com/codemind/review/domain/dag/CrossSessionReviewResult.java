package com.codemind.review.domain.dag;

import com.codemind.review.domain.agent.GlobalReduceResult;
import com.codemind.review.domain.changemap.ChangeMap;
import com.codemind.review.domain.finding.Finding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨会话 Map-Reduce 的完整结果（M6，ARCHITECTURE 6.2）。
 *
 * @param changeMap    全局变更地图
 * @param chunkResults 各切片的归约结果（含摘要），按 chunkId 索引
 * @param globalReduce 全局归约结果（跨文件 Finding 或降级）
 */
public record CrossSessionReviewResult(
        ChangeMap changeMap,
        Map<String, ChunkReduceResult> chunkResults,
        GlobalReduceResult globalReduce) {

    public CrossSessionReviewResult {
        chunkResults = Map.copyOf(chunkResults);
    }

    /**
     * 任务级 Finding：各片去重后的意见 + 全局归约的跨文件意见（追加在后）。
     * 同 fingerprint 只留一条，片级优先（片级有具体行号，信息更全）。
     */
    public List<Finding> allFindings() {
        Map<String, Finding> byFingerprint = new LinkedHashMap<>();
        for (ChunkReduceResult result : chunkResults.values()) {
            for (Finding f : result.findings()) {
                byFingerprint.putIfAbsent(f.fingerprint(), f);
            }
        }
        for (Finding f : globalReduce.findings()) {
            byFingerprint.putIfAbsent(f.fingerprint(), f);
        }
        return List.copyOf(new ArrayList<>(byFingerprint.values()));
    }

    /** 全部降级信息：片级降级 + 全局归约降级。 */
    public List<String> allDegradations() {
        List<String> all = new ArrayList<>();
        chunkResults.values().forEach(r -> all.addAll(r.degradations()));
        if (globalReduce.degraded()) {
            all.add("全局归约降级: " + globalReduce.degradeReason());
        }
        if (changeMap != null && changeMap.degraded()) {
            all.add("变更地图降级: " + changeMap.degradeReason());
        }
        return List.copyOf(all);
    }
}
