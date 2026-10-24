package com.codemind.rule.domain.rule;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleDocumentChunkerTest {

    private final RuleDocumentChunker chunker = new RuleDocumentChunker();

    @Test
    void singleHeadingWithBodyProducesOneChunk() {
        String markdown = """
                # 方法命名
                公开方法命名必须用名词+动词结构。
                """;

        List<RuleChunk> chunks = chunker.chunk("proj", 1L, markdown);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).sectionPath()).isEqualTo("方法命名");
        assertThat(chunks.get(0).heading()).isEqualTo("方法命名");
        assertThat(chunks.get(0).content()).isEqualTo("公开方法命名必须用名词+动词结构。");
        assertThat(chunks.get(0).project()).isEqualTo("proj");
        assertThat(chunks.get(0).documentId()).isEqualTo(1L);
        assertThat(chunks.get(0).id()).isZero();
    }

    @Test
    void multiLevelHeadingsProduceDottedSectionPath() {
        String markdown = """
                ## 3.2 接口设计规范
                总述文字。
                ### 3.2.1 方法命名
                公开方法命名必须用名词+动词结构。
                """;

        List<RuleChunk> chunks = chunker.chunk("proj", 1L, markdown);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).sectionPath()).isEqualTo("3.2 接口设计规范");
        assertThat(chunks.get(1).sectionPath()).isEqualTo("3.2 接口设计规范 > 3.2.1 方法命名");
        assertThat(chunks.get(1).content()).isEqualTo("公开方法命名必须用名词+动词结构。");
    }

    @Test
    void emptyBlockWithNoBodyIsSkipped() {
        String markdown = """
                # 空标题
                ## 真正有内容的标题
                这里有正文。
                """;

        List<RuleChunk> chunks = chunker.chunk("proj", 1L, markdown);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).heading()).isEqualTo("真正有内容的标题");
    }

    @Test
    void plainTextWithNoHeadingIsSkippedEntirely() {
        String markdown = """
                这是一段没有标题的正文，不应该被切成块。
                """;

        List<RuleChunk> chunks = chunker.chunk("proj", 1L, markdown);

        assertThat(chunks).isEmpty();
    }

    @Test
    void jumpingHeadingLevelHandledByStack() {
        String markdown = """
                # 顶层标题
                顶层正文。
                ### 跳级子标题
                子标题正文。
                """;

        List<RuleChunk> chunks = chunker.chunk("proj", 1L, markdown);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).sectionPath()).isEqualTo("顶层标题");
        assertThat(chunks.get(1).sectionPath()).isEqualTo("顶层标题 > 跳级子标题");
    }

    @Test
    void blankHeadingTextIsNotRecognizedAsHeadingAndBodyIsSkipped() {
        // "# " 去掉行尾空格后退化成 "#"，没有标题文本——不构成有意义的条款引用单位，
        // 不当标题识别（整行连同后续正文一起落入「无标题」分支被跳过），不会切出空 sectionPath
        String markdown = """
                #
                正文内容。
                """;

        List<RuleChunk> chunks = chunker.chunk("proj", 1L, markdown);

        assertThat(chunks).isEmpty();
    }

    @Test
    void documentWithoutTrailingNewlineStillFlushesLastChunk() {
        String markdown = "# 标题\n最后一行没有换行符";

        List<RuleChunk> chunks = chunker.chunk("proj", 1L, markdown);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo("最后一行没有换行符");
    }

    @Test
    void siblingHeadingAfterDeeperLevelPopsStackCorrectly() {
        String markdown = """
                # A
                ## A.1
                内容一。
                # B
                内容二。
                """;

        List<RuleChunk> chunks = chunker.chunk("proj", 1L, markdown);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).sectionPath()).isEqualTo("A > A.1");
        assertThat(chunks.get(1).sectionPath()).isEqualTo("B");
    }
}
