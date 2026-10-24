package com.codemind.rule.interfaces.web.dto;

import com.codemind.rule.application.RuleIngestionService;
import com.codemind.rule.domain.rule.RuleDocument;
import com.codemind.rule.domain.rule.ScoredRuleChunk;

import java.time.Instant;
import java.util.List;

/** internal 规则端点的请求 / 响应体。不跨模块共享，调用方（review）自行定义对应 DTO。 */
public final class RuleDtos {

    private RuleDtos() {
    }

    public record UploadDocumentRequest(String project, String title, String content) {
    }

    public record DocumentView(long id, String project, String title, String status, int version,
                               Instant uploadedAt, int chunkCount) {

        public static DocumentView of(RuleIngestionService.UploadOutcome outcome) {
            RuleDocument doc = outcome.document();
            return new DocumentView(doc.id(), doc.project(), doc.title(), doc.status().name(), doc.version(),
                    doc.uploadedAt(), outcome.chunkCount());
        }
    }

    public record UploadDocumentResponse(DocumentView document) {
    }

    /** topK 可空。 */
    public record RecallRequest(String project, String query, Integer topK) {
    }

    public record RecalledRuleChunk(String documentTitle, String sectionPath, String heading, String content,
                                    double similarity) {

        public static RecalledRuleChunk of(ScoredRuleChunk s) {
            return new RecalledRuleChunk(s.documentTitle(), s.chunk().sectionPath(), s.chunk().heading(),
                    s.chunk().content(), s.score());
        }
    }

    public record RecallResponse(List<RecalledRuleChunk> chunks) {
    }
}
