package com.codemind.rule.application;

import com.codemind.rule.domain.rule.Embedder;
import com.codemind.rule.domain.rule.RuleChunk;
import com.codemind.rule.domain.rule.RuleChunkRepository;
import com.codemind.rule.domain.rule.RuleDocument;
import com.codemind.rule.domain.rule.RuleDocumentChunker;
import com.codemind.rule.domain.rule.RuleDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 规范文档上传用例（M11）：切块 → embedding → 版本替换（旧 ACTIVE 转 ARCHIVED，新文档 ACTIVE）。
 */
public class RuleIngestionService {

    private static final Logger log = LoggerFactory.getLogger(RuleIngestionService.class);

    public static final int MAX_CONTENT_LENGTH = 50_000;
    public static final int MAX_PROJECT_LENGTH = 255;
    public static final int MAX_TITLE_LENGTH = 255;

    private final RuleDocumentRepository documentRepository;
    private final RuleChunkRepository chunkRepository;
    private final Embedder embedder;
    private final Clock clock;
    private final RuleDocumentChunker chunker = new RuleDocumentChunker();

    public RuleIngestionService(RuleDocumentRepository documentRepository, RuleChunkRepository chunkRepository,
                                Embedder embedder, Clock clock) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.embedder = embedder;
        this.clock = clock;
    }

    public RuleDocument upload(String project, String title, String content) {
        requireNonBlank(project, "project", MAX_PROJECT_LENGTH);
        requireNonBlank(title, "title", MAX_TITLE_LENGTH);
        if (content == null) {
            throw new IllegalArgumentException("content 不能为空");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("content 不能超过 " + MAX_CONTENT_LENGTH + " 字符");
        }

        int oldVersion = documentRepository.archiveActiveByProjectAndTitle(project, title).orElse(0);
        int newVersion = oldVersion + 1;

        RuleDocument document = documentRepository.insert(project, title, newVersion, clock.instant());

        List<RuleChunk> chunks = chunker.chunk(project, document.id(), content);
        if (!chunks.isEmpty()) {
            List<float[]> embeddings = new ArrayList<>(chunks.size());
            for (RuleChunk chunk : chunks) {
                embeddings.add(embedder.embed(chunk.content()));
            }
            chunkRepository.insertAll(document.id(), project, chunks, embeddings);
        }

        log.info("规范文档上传 project={} title={} version={} chunks={}",
                project, title, newVersion, chunks.size());
        return document;
    }

    private static void requireNonBlank(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(name + " 不能超过 " + maxLength + " 字符");
        }
    }
}
