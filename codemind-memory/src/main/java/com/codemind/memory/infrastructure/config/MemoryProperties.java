package com.codemind.memory.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** {@code codemind.memory.*}，默认值见 application.yml。 */
@ConfigurationProperties("codemind.memory")
public record MemoryProperties(Duration halfLife, double archiveThreshold, int candidateLimit, int defaultTopK) {
}
