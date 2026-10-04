package com.codemind.review.domain.chunk;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 用 JavaParser 把一个 Java 文件的变更 hunk 按方法边界归组成 {@link Unit}。
 *
 * <p>核心保证：覆盖同一方法的所有 hunk 归入同一个 unit——由于 unit 不会被 chunker 拆开，
 * 方法因此不会被截断。落在任何方法之外的 hunk（import、字段、类头变更）各自成一个 unit。
 */
final class JavaMethodIndex {

    /**
     * @return 按方法切好的 unit；源码解析失败返回 {@code null}（调用方据此降级为 hunk 切片）
     */
    List<Unit> splitByMethod(DiffFile file, String newSource) {
        List<MethodRange> methods = parseMethods(newSource);
        if (methods == null) {
            return null;
        }
        List<DiffHunk> hunks = file.hunks();
        int n = hunks.size();

        // 并查集：共享同一方法的 hunk 归并到一起
        DisjointSet dsu = new DisjointSet(n);
        List<Set<String>> hunkMethods = new ArrayList<>();
        for (DiffHunk hunk : hunks) {
            hunkMethods.add(methodsCovering(hunk, methods));
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (!disjoint(hunkMethods.get(i), hunkMethods.get(j))) {
                    dsu.union(i, j);
                }
            }
        }

        // 按根归组
        List<Unit> units = new ArrayList<>();
        int[] rootToUnit = new int[n];
        java.util.Arrays.fill(rootToUnit, -1);
        for (int i = 0; i < n; i++) {
            int root = dsu.find(i);
            if (rootToUnit[root] == -1) {
                rootToUnit[root] = units.size();
                units.add(new Unit(file, new ArrayList<>(), new LinkedHashSet<>()));
            }
            Unit u = units.get(rootToUnit[root]);
            u.hunks.add(hunks.get(i));
            u.methodNames.addAll(hunkMethods.get(i));
        }
        return units;
    }

    /** 解析源码，抽取所有方法/构造器的行号区间与限定名；解析失败返回 null。 */
    private List<MethodRange> parseMethods(String newSource) {
        try {
            ParseResult<CompilationUnit> result = new JavaParser().parse(newSource);
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                return null;
            }
            CompilationUnit cu = result.getResult().get();
            List<MethodRange> methods = new ArrayList<>();
            for (CallableDeclaration<?> callable : cu.findAll(CallableDeclaration.class)) {
                Optional<com.github.javaparser.Range> range = callable.getRange();
                if (range.isEmpty()) {
                    continue;
                }
                methods.add(new MethodRange(
                        qualifiedName(callable),
                        range.get().begin.line,
                        range.get().end.line));
            }
            return methods;
        } catch (RuntimeException parseFailure) {
            return null;
        }
    }

    /** hunk 的新行号区间与哪些方法区间相交。 */
    private Set<String> methodsCovering(DiffHunk hunk, List<MethodRange> methods) {
        Set<String> hit = new LinkedHashSet<>();
        if (hunk.newCount() == 0) {
            return hit; // 纯删除，无新行区间
        }
        int from = hunk.newStart();
        int to = hunk.newStart() + hunk.newCount() - 1;
        for (MethodRange m : methods) {
            if (from <= m.endLine() && m.beginLine() <= to) {
                hit.add(m.qualifiedName());
            }
        }
        return hit;
    }

    private boolean disjoint(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return true;
        }
        for (String s : a) {
            if (b.contains(s)) {
                return false;
            }
        }
        return true;
    }

    private String qualifiedName(CallableDeclaration<?> callable) {
        String method = callable.getNameAsString();
        Optional<TypeDeclaration> type = callable.findAncestor(TypeDeclaration.class);
        return type.map(t -> t.getNameAsString() + "#" + method).orElse(method);
    }

    /** 一个方法/构造器的行号区间。 */
    private record MethodRange(String qualifiedName, int beginLine, int endLine) {
    }
}
