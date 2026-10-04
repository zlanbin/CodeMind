package com.codemind.review.domain.chunk;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** 测试辅助：从 classpath 的 diff/ 目录读样例 diff 与源码。 */
final class DiffResources {

    private DiffResources() {
    }

    static String load(String path) {
        try (InputStream in = DiffResources.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("找不到测试资源: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String diff(String name) {
        return load("diff/" + name);
    }

    static String source(String name) {
        return load("diff/src/" + name);
    }
}
