package com.codemind.memory.domain.feedback;

import java.util.List;
import java.util.Objects;

/**
 * memory 自己的反馈视图，由 application 从 {@code FindingFeedbackReceived} 映射而来，domain 不依赖事件类型。
 *
 * @param thumbsDown 👎 一律算否决；false 表示回复，是否否决由 LLM 判断
 * @param replyText  回复正文，👎 时为 null
 * @param commentBody 被反馈的 CodeMind 评论正文（已去标记）
 * @param categories 评论里各条 Finding 的维度，至少一个
 */
public record FeedbackInput(String project, boolean thumbsDown, String replyText, String commentBody,
                            List<String> categories) {

    public FeedbackInput {
        Objects.requireNonNull(project, "project");
        commentBody = commentBody == null ? "" : commentBody;
        categories = List.copyOf(categories);
        if (categories.isEmpty()) {
            throw new IllegalArgumentException("categories 不能为空");
        }
    }
}
