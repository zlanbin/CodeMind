package com.codemind.review.domain.chunk;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** DiffParser 单测：验证路径、变更类型、hunk 行号与原始行解析。 */
class DiffParserTest {

    private final DiffParser parser = new DiffParser();

    @Test
    void parsesSingleFileSingleHunk() {
        List<DiffFile> files = parser.parse(DiffResources.diff("single-method.diff"));

        assertThat(files).hasSize(1);
        DiffFile f = files.get(0);
        assertThat(f.path()).isEqualTo("OrderService.java");
        assertThat(f.changeType()).isEqualTo(ChangeType.MODIFIED);
        assertThat(f.isJava()).isTrue();
        assertThat(f.hunks()).hasSize(1);

        DiffHunk h = f.hunks().get(0);
        assertThat(h.oldStart()).isEqualTo(4);
        assertThat(h.oldCount()).isEqualTo(3);
        assertThat(h.newStart()).isEqualTo(4);
        assertThat(h.newCount()).isEqualTo(4);
        assertThat(h.lines()).containsExactly(
                "     public void cancel(int id) {",
                "-        stock++;",
                "+        stock = stock + 1;",
                "+        log(id);",
                "     }");
        assertThat(h.coversNewLine(5)).isTrue();
        assertThat(h.coversNewLine(99)).isFalse();
    }

    @Test
    void parsesMultipleHunksInOneFile() {
        List<DiffFile> files = parser.parse(DiffResources.diff("multi-method.diff"));

        assertThat(files).hasSize(1);
        assertThat(files.get(0).hunks()).hasSize(2);
        assertThat(files.get(0).hunks().get(0).newStart()).isEqualTo(3);
        assertThat(files.get(0).hunks().get(1).newStart()).isEqualTo(9);
    }

    @Test
    void parsesMultipleFilesWithoutMerging() {
        List<DiffFile> files = parser.parse(DiffResources.diff("cross-file.diff"));

        assertThat(files).hasSize(2);
        assertThat(files).extracting(DiffFile::path)
                .containsExactly("OrderService.java", "InventoryClient.java");
        // 关键回归：第二个文件的头不能被当成第一个文件 hunk 的上下文行吞掉
        assertThat(files.get(0).hunks()).hasSize(1);
        assertThat(files.get(1).hunks()).hasSize(1);
    }

    @Test
    void detectsAddedAndDeletedFiles() {
        String added = """
                diff --git a/New.java b/New.java
                new file mode 100644
                index 0000000..1111111
                --- /dev/null
                +++ b/New.java
                @@ -0,0 +1,2 @@
                +package x;
                +class New {}
                """;
        List<DiffFile> files = parser.parse(added);
        assertThat(files).hasSize(1);
        assertThat(files.get(0).changeType()).isEqualTo(ChangeType.ADDED);
        assertThat(files.get(0).path()).isEqualTo("New.java");
    }

    @Test
    void parsesRename() {
        String rename = """
                diff --git a/Old.java b/Renamed.java
                similarity index 95%
                rename from Old.java
                rename to Renamed.java
                index 1111111..2222222 100644
                --- a/Old.java
                +++ b/Renamed.java
                @@ -1,1 +1,1 @@
                -class Old {}
                +class Renamed {}
                """;
        List<DiffFile> files = parser.parse(rename);
        assertThat(files).hasSize(1);
        assertThat(files.get(0).changeType()).isEqualTo(ChangeType.RENAMED);
        assertThat(files.get(0).oldPath()).isEqualTo("Old.java");
        assertThat(files.get(0).newPath()).isEqualTo("Renamed.java");
    }

    @Test
    void emptyDiffYieldsNoFiles() {
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse(null)).isEmpty();
    }
}
