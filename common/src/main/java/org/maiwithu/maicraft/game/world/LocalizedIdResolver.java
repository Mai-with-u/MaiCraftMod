// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 入参本地化名称反查：当能力或查询参数收到非 {@code namespace:path} 格式的中文名等可读名称时，
 * 按当前游戏语言的注册名称反查标准英文 ID。
 *
 * <p>精确匹配唯一项或包含匹配唯一项时直接返回标准英文 ID；存在多个候选时返回候选清单让调用方点选，
 * 不替模型猜一个；没有任何候选时返回未命中，交由原有格式校验照常报错。
 */
public final class LocalizedIdResolver {

    /** 存在多候选歧义时，报错里最多列出多少个「id (名称)」供模型直接点选。 */
    public static final int MAX_CANDIDATES = 8;

    private LocalizedIdResolver() {}

    /** 一次名称反查的结论。 */
    public sealed interface Resolution {
        /** 唯一命中：自动换成标准英文注册 ID 继续执行。 */
        record Resolved(String id, String label) implements Resolution {}

        /** 多个候选命中：列出前若干个候选让调用方明确点选具体 ID。 */
        record Ambiguous(List<ReadsLocalizedNames.NamedId> candidates, int total) implements Resolution {
            public Ambiguous {
                candidates = List.copyOf(candidates);
            }
        }

        /** 未命中任何当前语言名称：沿用原有参数格式校验报错。 */
        record NotMatched() implements Resolution {}
    }

    /**
     * 按当前语言名称反查标准注册 ID：标准资源 ID（含冒号）或标签（# 开头）不走反查直接返回未命中。
     * 优先精确匹配显示名（忽略大小写），无精确命中时再按子串模糊匹配。
     */
    public static Resolution resolve(ReadsLocalizedNames names, IdKind kind, String rawInput) {
        if (names == null || kind == null || rawInput == null) {
            return new Resolution.NotMatched();
        }
        String query = rawInput.trim();
        if (query.isEmpty() || query.startsWith("#") || query.contains(":")) {
            return new Resolution.NotMatched();
        }
        List<ReadsLocalizedNames.NamedId> entries = names.entries(kind);
        if (entries.isEmpty()) {
            return new Resolution.NotMatched();
        }
        List<ReadsLocalizedNames.NamedId> exact = new ArrayList<>();
        for (ReadsLocalizedNames.NamedId entry : entries) {
            if (entry.label().trim().equalsIgnoreCase(query)) {
                exact.add(entry);
            }
        }
        if (exact.size() == 1) {
            ReadsLocalizedNames.NamedId only = exact.getFirst();
            return new Resolution.Resolved(only.id(), only.label());
        }
        if (exact.size() > 1) {
            return ambiguousOf(exact);
        }
        // 纯 ASCII 单字符（如误输 "a"）不做宽泛子串匹配，避免把无关错误参数误报成多候选；中文单字（如"床"）照常匹配。
        if (isPureAscii(query) && query.length() < 2) {
            return new Resolution.NotMatched();
        }
        String needle = query.toLowerCase(Locale.ROOT);
        List<ReadsLocalizedNames.NamedId> fuzzy = new ArrayList<>();
        for (ReadsLocalizedNames.NamedId entry : entries) {
            if (entry.label().toLowerCase(Locale.ROOT).contains(needle)) {
                fuzzy.add(entry);
            }
        }
        if (fuzzy.size() == 1) {
            ReadsLocalizedNames.NamedId only = fuzzy.getFirst();
            return new Resolution.Resolved(only.id(), only.label());
        }
        if (fuzzy.size() > 1) {
            return ambiguousOf(fuzzy);
        }
        return new Resolution.NotMatched();
    }

    /** 构造多候选报错文案：列出前若干个 {@code id (中文名)} 方便模型直接照抄标准 ID。 */
    public static String ambiguousMessage(IdKind kind, String rawInput, Resolution.Ambiguous ambiguous) {
        String listed = ambiguous.candidates().stream()
                .map(entry -> entry.id() + " (" + entry.label() + ")")
                .collect(Collectors.joining("、"));
        String more = ambiguous.total() > ambiguous.candidates().size()
                ? " 等共 " + ambiguous.total() + " 个" : "";
        return "名称 \"" + rawInput.trim() + "\" 匹配到多个" + kind.label()
                + "，请改用具体 ID：" + listed + more;
    }

    private static Resolution.Ambiguous ambiguousOf(List<ReadsLocalizedNames.NamedId> matches) {
        int limit = Math.min(MAX_CANDIDATES, matches.size());
        return new Resolution.Ambiguous(matches.subList(0, limit), matches.size());
    }

    private static boolean isPureAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }
}
