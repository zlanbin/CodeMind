package com.codemind.review.domain.chunk;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 diff + 新侧源码确定性提取方法签名变更（M6，ARCHITECTURE 6.2 的「方法签名变更」输入）。
 *
 * <p>做法：新侧用 JavaParser 取被 hunk 命中的方法（含参数类型），旧侧没有完整源码，
 * 只能从 diff 的删除行里按正则识别方法声明；两侧按裸方法名配对：
 * <ul>
 *   <li>配对上且参数列表不同 → {@link SignatureChange.Kind#SIGNATURE_CHANGED}（M6 验证点的关键信号）</li>
 *   <li>删除行有声明、新侧已无同名方法 → {@link SignatureChange.Kind#REMOVED}</li>
 *   <li>新侧方法的声明行出现在新增行里且无对应删除声明 → {@link SignatureChange.Kind#ADDED}</li>
 *   <li>其余被命中的方法 → {@link SignatureChange.Kind#BODY_CHANGED}</li>
 * </ul>
 *
 * <p>纯算法，不依赖 Spring / LLM。非 Java 文件与无源码的文件直接跳过（不猜）。
 * 旧侧靠正则是因为 unified diff 不含旧文件全文；正则只认「修饰符 返回类型 方法名(参数)」形态的行，
 * 并用关键字黑名单排除 {@code return foo(x);} 这类调用行。
 */
public final class ChangedSignatureExtractor {

    /** 方法声明行：可选修饰符 + 可选泛型 + 返回类型 + 方法名 + 参数表。 */
    private static final Pattern METHOD_DECL = Pattern.compile(
            "^\\s*(?:(?:public|protected|private|static|final|abstract|synchronized|native|default|strictfp)\\s+)*"
                    + "(?:<[^>]*>\\s*)?"
                    + "([A-Za-z_$][\\w$.\\[\\]<>,?\\s]*?)\\s+"
                    + "([A-Za-z_$][\\w$]*)\\s*\\(([^)]*)\\)\\s*(?:throws[^{;]*)?[{;]");

    /** 构造器声明行：无返回类型。 */
    private static final Pattern CTOR_DECL = Pattern.compile(
            "^\\s*(?:(?:public|protected|private)\\s+)?"
                    + "([A-Z][\\w$]*)\\s*\\(([^)]*)\\)\\s*(?:throws[^{]*)?\\{");

    /** 排除控制流 / 语句行被误判为声明。 */
    private static final Set<String> KEYWORDS = Set.of(
            "return", "new", "throw", "throws", "if", "else", "while", "for", "switch", "case",
            "do", "catch", "try", "finally", "assert", "yield", "break", "continue", "instanceof",
            "super", "this", "import", "package");

    /**
     * @param files      已解析的变更文件
     * @param newSources 文件新路径 → 完整新源码；缺失的文件跳过
     * @return 按文件出现顺序的签名变更列表
     */
    public List<SignatureChange> extract(List<DiffFile> files, Map<String, String> newSources) {
        Objects.requireNonNull(files, "files");
        Map<String, String> sources = newSources == null ? Map.of() : newSources;

        List<SignatureChange> changes = new ArrayList<>();
        for (DiffFile file : files) {
            if (!file.isJava() || file.hunks().isEmpty()) {
                continue;
            }
            String newSource = sources.get(file.path());
            if (newSource == null) {
                continue;
            }
            changes.addAll(extractForFile(file, newSource));
        }
        return List.copyOf(changes);
    }

    private List<SignatureChange> extractForFile(DiffFile file, String newSource) {
        List<NewMethod> newMethods = parseNewMethods(newSource);
        if (newMethods == null) {
            return List.of(); // 解析失败不猜
        }
        List<NewMethod> touched = newMethods.stream().filter(m -> intersectsAnyHunk(m, file)).toList();
        List<Decl> deleted = declarationsOn(file, '-');
        Set<String> addedLines = new LinkedHashSet<>(rawLines(file, '+'));

        List<SignatureChange> changes = new ArrayList<>();
        Set<String> pairedDeletions = new LinkedHashSet<>();

        for (NewMethod m : touched) {
            Decl match = deleted.stream().filter(d -> d.name().equals(m.simpleName())).findFirst().orElse(null);
            if (match != null) {
                pairedDeletions.add(match.key());
                if (!normalizeParams(match.params()).equals(m.params())) {
                    changes.add(new SignatureChange(file.path(), m.qualifiedName(),
                            m.owner() + "#" + m.simpleName() + normalizeParams(match.params()),
                            SignatureChange.Kind.SIGNATURE_CHANGED));
                } else {
                    changes.add(new SignatureChange(file.path(), m.qualifiedName(), null,
                            SignatureChange.Kind.BODY_CHANGED));
                }
                continue;
            }
            boolean declaredInAddedLines = addedLines.stream().anyMatch(l -> declaresMethod(l, m.simpleName()));
            changes.add(new SignatureChange(file.path(), m.qualifiedName(), null,
                    declaredInAddedLines ? SignatureChange.Kind.ADDED : SignatureChange.Kind.BODY_CHANGED));
        }

        // 删除行里有声明，但新侧已无同名方法 → 整个方法被删
        Set<String> newNames = new LinkedHashSet<>();
        newMethods.forEach(m -> newNames.add(m.simpleName()));
        String owner = newMethods.isEmpty() ? stripExtension(file.path()) : newMethods.get(0).owner();
        for (Decl d : deleted) {
            if (pairedDeletions.contains(d.key()) || newNames.contains(d.name())) {
                continue;
            }
            changes.add(new SignatureChange(file.path(),
                    owner + "#" + d.name() + normalizeParams(d.params()), null,
                    SignatureChange.Kind.REMOVED));
        }
        return changes;
    }

    /** 新侧 AST 的全部方法/构造器；解析失败返回 null。 */
    private List<NewMethod> parseNewMethods(String newSource) {
        try {
            ParseResult<CompilationUnit> result = new JavaParser().parse(newSource);
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                return null;
            }
            List<NewMethod> methods = new ArrayList<>();
            for (CallableDeclaration<?> callable : result.getResult().get().findAll(CallableDeclaration.class)) {
                var range = callable.getRange();
                if (range.isEmpty()) {
                    continue;
                }
                String qualified = MethodSignatures.qualifiedName(callable);
                String owner = qualified.contains("#") ? qualified.substring(0, qualified.indexOf('#')) : "";
                methods.add(new NewMethod(qualified, owner, callable.getNameAsString(),
                        MethodSignatures.parameterList(callable),
                        range.get().begin.line, range.get().end.line));
            }
            return methods;
        } catch (RuntimeException parseFailure) {
            return null;
        }
    }

    private boolean intersectsAnyHunk(NewMethod method, DiffFile file) {
        for (DiffHunk hunk : file.hunks()) {
            if (hunk.newCount() == 0) {
                continue;
            }
            int from = hunk.newStart();
            int to = hunk.newStart() + hunk.newCount() - 1;
            if (from <= method.endLine() && method.beginLine() <= to) {
                return true;
            }
        }
        return false;
    }

    private List<String> rawLines(DiffFile file, char prefix) {
        List<String> lines = new ArrayList<>();
        for (DiffHunk hunk : file.hunks()) {
            for (String line : hunk.lines()) {
                if (!line.isEmpty() && line.charAt(0) == prefix) {
                    lines.add(line.substring(1));
                }
            }
        }
        return lines;
    }

    /** 指定前缀的 diff 行里识别出的方法/构造器声明。 */
    private List<Decl> declarationsOn(DiffFile file, char prefix) {
        List<Decl> decls = new ArrayList<>();
        for (String line : rawLines(file, prefix)) {
            Decl d = parseDeclaration(line);
            if (d != null) {
                decls.add(d);
            }
        }
        return decls;
    }

    private Decl parseDeclaration(String line) {
        Matcher m = METHOD_DECL.matcher(line);
        if (m.find()) {
            String type = m.group(1).trim();
            String name = m.group(2);
            if (!KEYWORDS.contains(type) && !KEYWORDS.contains(name)) {
                return new Decl(name, m.group(3));
            }
        }
        Matcher c = CTOR_DECL.matcher(line);
        if (c.find() && !KEYWORDS.contains(c.group(1))) {
            return new Decl(c.group(1), c.group(2));
        }
        return null;
    }

    private boolean declaresMethod(String line, String simpleName) {
        Decl d = parseDeclaration(line);
        return d != null && d.name().equals(simpleName);
    }

    /** 把正则抓到的原始参数表归一成与 AST 一致的 {@code (类型, 类型)} 形态。 */
    static String normalizeParams(String rawParams) {
        String inner = rawParams == null ? "" : rawParams.trim();
        if (inner.isEmpty()) {
            return "()";
        }
        List<String> types = new ArrayList<>();
        for (String param : splitTopLevel(inner)) {
            String p = param.trim()
                    .replaceAll("^(?:final|@\\w+)\\s+", "")
                    .replaceAll("\\s*,\\s*", ", ");
            if (p.isEmpty()) {
                continue;
            }
            int lastSpace = lastTopLevelSpace(p);
            types.add(lastSpace < 0 ? p : p.substring(0, lastSpace).trim());
        }
        return "(" + String.join(", ", types) + ")";
    }

    /** 按顶层逗号切分（忽略泛型内部的逗号）。 */
    private static List<String> splitTopLevel(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (ch == '<') {
                depth++;
            } else if (ch == '>') {
                depth--;
            }
            if (ch == ',' && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    /** 参数「类型 名字」的分界空格位置（泛型内部的空格不算）。 */
    private static int lastTopLevelSpace(String param) {
        int depth = 0;
        int pos = -1;
        for (int i = 0; i < param.length(); i++) {
            char ch = param.charAt(i);
            if (ch == '<') {
                depth++;
            } else if (ch == '>') {
                depth--;
            } else if (Character.isWhitespace(ch) && depth == 0) {
                pos = i;
            }
        }
        return pos;
    }

    private static String stripExtension(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.endsWith(".java") ? name.substring(0, name.length() - 5) : name;
    }

    /** 新侧 AST 的一个方法。 */
    private record NewMethod(String qualifiedName, String owner, String simpleName, String params,
                             int beginLine, int endLine) {
    }

    /** 从 diff 行正则识别出的声明（旧侧只有这点信息）。 */
    private record Decl(String name, String params) {
        String key() {
            return name + "|" + params;
        }
    }
}
