package com.codemind.memory.infrastructure.embedding;

import com.codemind.memory.domain.memory.Embedder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 本地确定性 embedding：特征哈希（M8 D3）。
 *
 * <p>中转端点没有 embedding 模型，先用它保证召回可跑、可测。特征是英文/数字按词、
 * 其它文字（中文等）按字符 bigram，FNV-1a 哈希到 {@link #DIMENSIONS} 个桶，只加不减
 * （余弦不为负），再 L2 归一化。只捕获字面重叠，不懂同义改写；换真实模型只需替换本适配器
 * 并新迁移改向量列维度。
 */
public final class HashingEmbedder implements Embedder {

    public static final int DIMENSIONS = 256;

    @Override
    public float[] embed(String text) {
        String normalized = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        List<String> features = features(normalized);
        if (features.isEmpty()) {
            // 纯标点等：整串作为一个特征，保证非零向量（零向量的余弦是 NaN）
            features = List.of("\u0000" + normalized);
        }
        float[] vector = new float[DIMENSIONS];
        for (String feature : features) {
            vector[Math.floorMod(fnv1a(feature), DIMENSIONS)] += 1f;
        }
        double norm = 0;
        for (float v : vector) {
            norm += (double) v * v;
        }
        float scale = (float) (1 / Math.sqrt(norm));
        for (int i = 0; i < vector.length; i++) {
            vector[i] *= scale;
        }
        return vector;
    }

    private static List<String> features(String text) {
        List<String> features = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            if (isAsciiWordChar(cp)) {
                int start = i;
                while (i < text.length() && isAsciiWordChar(text.codePointAt(i))) {
                    i++;
                }
                features.add("w:" + text.substring(start, i));
            } else if (Character.isLetterOrDigit(cp)) {
                List<Integer> run = new ArrayList<>();
                while (i < text.length()) {
                    int c = text.codePointAt(i);
                    if (isAsciiWordChar(c) || !Character.isLetterOrDigit(c)) {
                        break;
                    }
                    run.add(c);
                    i += Character.charCount(c);
                }
                if (run.size() == 1) {
                    features.add("c:" + Character.toString(run.get(0)));
                }
                for (int k = 0; k + 1 < run.size(); k++) {
                    features.add("c:" + Character.toString(run.get(k)) + Character.toString(run.get(k + 1)));
                }
            } else {
                i += Character.charCount(cp);
            }
        }
        return features;
    }

    private static boolean isAsciiWordChar(int cp) {
        return cp < 128 && (Character.isLetterOrDigit(cp) || cp == '_');
    }

    private static int fnv1a(String feature) {
        int hash = 0x811c9dc5;
        for (byte b : feature.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xff;
            hash *= 0x01000193;
        }
        return hash;
    }
}
