package com.codemind.integration.interfaces.web;

import com.codemind.integration.application.PullRequestContentService;
import com.codemind.integration.interfaces.web.dto.CompareDiffResponse;
import com.codemind.integration.interfaces.web.dto.FileContentsRequest;
import com.codemind.integration.interfaces.web.dto.FileContentsResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/**
 * 对内暴露 PR 内容（M7a）。供 review 服务经服务发现调用，不对外网开放。
 *
 * <p>只做参数转发与 DTO 包装，业务判断在 {@link PullRequestContentService}。
 */
@RestController
@RequestMapping("/internal/pulls")
public class PullRequestContentController {

    private final PullRequestContentService contentService;

    public PullRequestContentController(PullRequestContentService contentService) {
        this.contentService = contentService;
    }

    /** 整份 unified diff，纯文本返回（调用方直接喂 DiffParser）。 */
    @GetMapping(value = "/diff", produces = MediaType.TEXT_PLAIN_VALUE)
    public String diff(@RequestParam String repo, @RequestParam int prNumber) {
        return contentService.diff(repo, prNumber);
    }

    /** 批量拉文件新侧源码；拉不到的文件不在响应的 key 里。 */
    @PostMapping("/contents")
    public FileContentsResponse contents(@RequestBody FileContentsRequest request) {
        return new FileContentsResponse(
                contentService.fileContents(request.repo(), request.ref(), request.paths()));
    }

    /** 两次 commit 之间的增量 diff（M10）；JSON 返回（不是纯文本），因为要带 status 字段。 */
    @GetMapping("/compare-diff")
    public CompareDiffResponse compareDiff(@RequestParam String repo,
                                           @RequestParam String oldHead,
                                           @RequestParam String newHead) {
        var result = contentService.compareDiff(repo, oldHead, newHead);
        return new CompareDiffResponse(result.status(), result.diffText());
    }
}
