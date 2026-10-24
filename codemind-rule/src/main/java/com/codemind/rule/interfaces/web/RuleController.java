package com.codemind.rule.interfaces.web;

import com.codemind.common.api.Result;
import com.codemind.rule.application.RuleIngestionService;
import com.codemind.rule.application.RuleRecallService;
import com.codemind.rule.interfaces.web.dto.RuleDtos.DocumentView;
import com.codemind.rule.interfaces.web.dto.RuleDtos.RecallRequest;
import com.codemind.rule.interfaces.web.dto.RuleDtos.RecallResponse;
import com.codemind.rule.interfaces.web.dto.RuleDtos.RecalledRuleChunk;
import com.codemind.rule.interfaces.web.dto.RuleDtos.UploadDocumentRequest;
import com.codemind.rule.interfaces.web.dto.RuleDtos.UploadDocumentResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 规范文档的 internal 端点（M11）。供 review 经服务发现调用，**无鉴权**，
 * 与 M7a/M8/M9a 的 internal 端点同一缺口：网关暴露到本机之外前必须封掉 {@code /internal/**}。
 */
@RestController
@RequestMapping("/internal/rules")
public class RuleController {

    private final RuleIngestionService ingestionService;
    private final RuleRecallService recallService;

    public RuleController(RuleIngestionService ingestionService, RuleRecallService recallService) {
        this.ingestionService = ingestionService;
        this.recallService = recallService;
    }

    @PostMapping("/documents")
    public ResponseEntity<Result<UploadDocumentResponse>> upload(@RequestBody UploadDocumentRequest request) {
        var outcome = ingestionService.upload(request.project(), request.title(), request.content());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Result.ok(new UploadDocumentResponse(DocumentView.of(outcome))));
    }

    @PostMapping("/recall")
    public Result<RecallResponse> recall(@RequestBody RecallRequest request) {
        var scored = recallService.recall(request.project(), request.query(), request.topK());
        return Result.ok(new RecallResponse(scored.stream().map(RecalledRuleChunk::of).toList()));
    }
}
