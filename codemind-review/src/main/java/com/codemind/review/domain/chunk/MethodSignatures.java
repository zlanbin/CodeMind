package com.codemind.review.domain.chunk;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.Parameter;

import java.util.Optional;
import java.util.StringJoiner;

/**
 * 方法限定名的统一格式（M6 升级）：{@code 外层类名#方法名(参数类型, ...)}，例如
 * {@code OrderService#cancel(int)}、{@code InventoryClient#rollback(Long, String)}。
 *
 * <p>M2 原格式只到 {@code 类名#方法名}，无法区分重载，也无法支撑 M6 验证点
 * 「跨文件签名不一致」（{@code foo(int)} vs {@code foo(String)}）。参数类型按源码字面量取
 * （不做类型解析，{@code List<Long>} 原样保留），对评审与跨 chunk 比对足够。
 *
 * <p>限制未变：类名取最近一层外层类型声明，不含包名、不消解嵌套类同名歧义。
 */
final class MethodSignatures {

    private MethodSignatures() {
    }

    /** {@code 类名#方法名(参数类型, ...)}；无外层类型时退化为 {@code 方法名(...)}。 */
    static String qualifiedName(CallableDeclaration<?> callable) {
        String nameWithParams = callable.getNameAsString() + parameterList(callable);
        Optional<TypeDeclaration> type = callable.findAncestor(TypeDeclaration.class);
        return type.map(t -> t.getNameAsString() + "#" + nameWithParams).orElse(nameWithParams);
    }

    /** {@code (int, String)}；无参为 {@code ()}。可变参数渲染为 {@code String...}。 */
    static String parameterList(CallableDeclaration<?> callable) {
        StringJoiner joiner = new StringJoiner(", ", "(", ")");
        for (Parameter p : callable.getParameters()) {
            joiner.add(p.getType().asString() + (p.isVarArgs() ? "..." : ""));
        }
        return joiner.toString();
    }

    /**
     * 从限定名里取出不带类名、不带参数的裸方法名：{@code OrderService#cancel(int)} → {@code cancel}。
     * 用于「diff 文本里出现 {@code 方法名(}」这类按调用形态的匹配。
     */
    static String simpleMethodName(String qualifiedName) {
        String s = qualifiedName;
        int hash = s.indexOf('#');
        if (hash >= 0) {
            s = s.substring(hash + 1);
        }
        int paren = s.indexOf('(');
        if (paren >= 0) {
            s = s.substring(0, paren);
        }
        return s;
    }
}
