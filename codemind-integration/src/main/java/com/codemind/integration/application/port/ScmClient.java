package com.codemind.integration.application.port;

/**
 * 代码托管平台读端口（M7a）。integration 是唯一和外部仓库说话的上下文（ARCHITECTURE 3.3），
 * review 经 internal 端点间接使用。
 *
 * <p>只读。回写 PR 评论属 M7b，届时在本端口或另立写端口上扩展。
 */
public interface ScmClient {

    /**
     * 拉整份 unified diff。
     *
     * @param repo     仓库全名，形如 owner/name
     * @param prNumber PR 序号
     * @return unified diff 全文
     * @throws ScmException 平台返回错误状态或网络失败
     */
    String fetchPullRequestDiff(String repo, int prNumber);

    /**
     * 拉单个文件在指定 ref 上的完整内容。
     *
     * <p>语义切片要按 AST 方法边界切，而 unified diff 只有 hunk 和上下文行，
     * 所以需要新侧完整源码（见 M2 决策日志）。
     *
     * @param repo 仓库全名，形如 owner/name
     * @param path 仓库内相对路径
     * @param ref  commit SHA 或分支名
     * @return 文件全文
     * @throws ScmException 平台返回错误状态（含文件不存在）或网络失败
     */
    String fetchFileContent(String repo, String path, String ref);
}
