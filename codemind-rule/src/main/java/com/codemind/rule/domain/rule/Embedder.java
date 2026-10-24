package com.codemind.rule.domain.rule;

/**
 * 文本向量化端口（同 memory M8 的 Embedder 签名，各自模块独立定义不跨模块共享类）。
 * 实现必须确定（同文本同向量）、输出固定维度、非零向量，维度要与 {@code rule.chunk.embedding}
 * 列一致（列维度写在迁移里，换实现要新迁移）。
 */
public interface Embedder {

    float[] embed(String text);
}
