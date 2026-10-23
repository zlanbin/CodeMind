package com.codemind.integration.application;

import com.codemind.common.event.ReviewCompleted;

/** 平台幂等标记与 422 后的纯总结降级，不重写评审意见。 */
public final class ReviewPublicationBodies {
    private ReviewPublicationBodies() { }
    public static String marker(String taskId) { return "<!-- codemind:task=" + taskId + " -->"; }
    public static boolean matches(String body, String taskId) { return body != null && body.contains(marker(taskId)); }
    public static String normal(ReviewCompleted event) { return event.summaryBody() + "\n\n" + marker(event.taskId()); }
    public static String summaryOnly(ReviewCompleted event) {
        StringBuilder body = new StringBuilder(event.summaryBody()).append("\n\n## 行级发布降级\n\n")
                .append("GitHub 拒绝行级位置，以下意见转入总结：\n\n");
        for (var comment : event.comments()) {
            body.append("### ").append(comment.path()).append(':').append(comment.line()).append("\n\n")
                    .append(comment.body()).append("\n\n");
        }
        return body.append(marker(event.taskId())).toString();
    }
}
