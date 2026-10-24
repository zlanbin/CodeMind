package com.codemind.rule.interfaces.web;

import com.codemind.rule.interfaces.web.dto.RuleDtos.RecallRequest;
import com.codemind.rule.interfaces.web.dto.RuleDtos.RecallResponse;
import com.codemind.rule.interfaces.web.dto.RuleDtos.UploadDocumentRequest;
import com.codemind.rule.interfaces.web.dto.RuleDtos.UploadDocumentResponse;
import com.codemind.common.api.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RuleControllerIT {

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcClient jdbc;

    private final String project = "codemind/rule-controller-it-" + UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        jdbc.sql("delete from rule.chunk where project = :p").param("p", project).update();
        jdbc.sql("delete from rule.document where project = :p").param("p", project).update();
    }

    @Test
    void uploadThenRecallFindsMatchingSectionPath() {
        String markdown = """
                ## 3.2 接口设计规范
                ### 3.2.1 方法命名
                公开方法命名必须用名词加动词结构，禁止使用单字母或缩写作为方法名。
                """;
        ResponseEntity<Result<UploadDocumentResponse>> uploadResponse = rest.exchange(
                "/internal/rules/documents", org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(
                        new UploadDocumentRequest(project, "接口设计规范", markdown)),
                new org.springframework.core.ParameterizedTypeReference<Result<UploadDocumentResponse>>() {});

        assertThat(uploadResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(uploadResponse.getBody().data().document().chunkCount()).isEqualTo(1);

        ResponseEntity<Result<RecallResponse>> recallResponse = rest.exchange(
                "/internal/rules/recall", org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(
                        new RecallRequest(project, "公开方法命名必须用名词加动词结构", null)),
                new org.springframework.core.ParameterizedTypeReference<Result<RecallResponse>>() {});

        assertThat(recallResponse.getBody().data().chunks()).isNotEmpty();
        assertThat(recallResponse.getBody().data().chunks().get(0).sectionPath())
                .isEqualTo("3.2 接口设计规范 > 3.2.1 方法命名");
    }

    @Test
    void reuploadingSameTitleOnlyRecallsNewContent() {
        rest.postForEntity("/internal/rules/documents",
                new UploadDocumentRequest(project, "文档", "# 标题\n旧版本内容。"), Result.class);
        rest.postForEntity("/internal/rules/documents",
                new UploadDocumentRequest(project, "文档", "# 标题\n新版本内容。"), Result.class);

        ResponseEntity<Result<RecallResponse>> recallResponse = rest.exchange(
                "/internal/rules/recall", org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(new RecallRequest(project, "版本内容", 20)),
                new org.springframework.core.ParameterizedTypeReference<Result<RecallResponse>>() {});

        assertThat(recallResponse.getBody().data().chunks())
                .extracting(c -> c.content())
                .containsExactly("新版本内容。");
    }

    @Test
    void blankProjectIsRejectedWith400() {
        ResponseEntity<Result> response = rest.postForEntity("/internal/rules/documents",
                new UploadDocumentRequest("", "标题", "# 标题\n正文。"), Result.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
