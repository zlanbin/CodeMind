package com.codemind.memory.domain.memory;

/**
 * 文本向量化端口。实现必须确定（同文本同向量）、输出固定维度、非零向量，
 * 维度要与 {@code memory.memory.embedding} 列一致（列维度写在迁移里，换实现要新迁移）。
 */
public interface Embedder {

    float[] embed(String text);
}
