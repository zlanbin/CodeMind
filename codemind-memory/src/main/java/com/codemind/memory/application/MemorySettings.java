package com.codemind.memory.application;

/**
 * 召回与归档的运行参数，由 infrastructure 从配置装配（application 不依赖配置类）。
 *
 * @param archiveThreshold {@code importance × decay} 低于它即归档
 * @param candidateLimit   向量与三元组两路各取的候选数
 * @param defaultTopK      调用方不给 topK 时的默认值
 */
public record MemorySettings(double archiveThreshold, int candidateLimit, int defaultTopK) {

    public MemorySettings {
        if (!(archiveThreshold >= 0 && archiveThreshold < 1)) {
            throw new IllegalArgumentException("archiveThreshold 必须在 [0, 1) 内：" + archiveThreshold);
        }
        if (candidateLimit < 1) {
            throw new IllegalArgumentException("candidateLimit 必须 >= 1：" + candidateLimit);
        }
        if (defaultTopK < 1 || defaultTopK > MemoryService.MAX_TOP_K) {
            throw new IllegalArgumentException("defaultTopK 必须在 [1, " + MemoryService.MAX_TOP_K + "] 内：" + defaultTopK);
        }
    }
}
