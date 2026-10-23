package com.codemind.integration.interfaces.web;

import com.codemind.common.api.Result;
import com.codemind.integration.application.FeedbackCollectionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 手动触发反馈轮询（M9 D1），用于验收与排查，不必等定时器。会读 GitHub、发 MQ，**不写 GitHub**。
 * 无鉴权，与其它 internal 端点同一缺口。
 */
@RestController
@RequestMapping("/internal/feedback")
public class FeedbackController {

    private final FeedbackCollectionService service;
    private final String lookback;

    public FeedbackController(FeedbackCollectionService service,
                              @Value("${codemind.feedback.lookback:14d}") String lookback) {
        this.service = service;
        this.lookback = lookback;
    }

    public record PollResult(int registered, int dispatched) {
    }

    @PostMapping("/poll")
    public Result<PollResult> poll(@RequestParam String repo, @RequestParam int prNumber) {
        if (repo.isBlank() || prNumber < 1) {
            throw new IllegalArgumentException("repo 不能为空且 prNumber 必须为正");
        }
        int registered = service.collect(repo, prNumber, DurationStyle.detectAndParse(lookback));
        return Result.ok(new PollResult(registered, service.dispatchPending()));
    }
}
