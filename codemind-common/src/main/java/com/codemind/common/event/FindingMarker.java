package com.codemind.common.event;

import com.codemind.common.event.FindingFeedbackReceived.FindingRef;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 行级评论里每条 Finding 的隐藏标记（M9 D2）：{@code <!-- codemind:finding fp=<sha256> cat=<CATEGORY> -->}。
 *
 * <p>review 渲染时写入，integration 读反馈时解析，两侧共用这一份格式契约。
 * category 只按「大写字母与下划线」校验，不引用 review 的枚举。
 */
public final class FindingMarker {

    private static final Pattern MARKER = Pattern.compile(
            "<!-- codemind:finding fp=([0-9a-f]{64}) cat=([A-Z_]{1,32}) -->");
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern CATEGORY = Pattern.compile("[A-Z_]{1,32}");

    private FindingMarker() {
    }

    public static String format(String fingerprint, String category) {
        if (!isFormattable(fingerprint, category)) {
            throw new IllegalArgumentException("fingerprint 须为 64 位小写十六进制、category 须为大写字母与下划线："
                    + category);
        }
        return "<!-- codemind:finding fp=" + fingerprint + " cat=" + category + " -->";
    }

    /** 写方据此决定是否加标记：不合法时宁可不加，也不让渲染失败。 */
    public static boolean isFormattable(String fingerprint, String category) {
        return fingerprint != null && FINGERPRINT.matcher(fingerprint).matches()
                && category != null && CATEGORY.matcher(category).matches();
    }

    /** 按出现顺序返回正文里全部合法标记；无标记或 null 返回空列表。 */
    public static List<FindingRef> parseAll(String body) {
        List<FindingRef> refs = new ArrayList<>();
        if (body == null) {
            return refs;
        }
        Matcher m = MARKER.matcher(body);
        while (m.find()) {
            refs.add(new FindingRef(m.group(1), m.group(2)));
        }
        return refs;
    }

    /** 去掉 Finding 标记（不动其它 HTML 注释），用于把评论正文交给 LLM。 */
    public static String strip(String body) {
        return body == null ? "" : MARKER.matcher(body).replaceAll("");
    }
}
