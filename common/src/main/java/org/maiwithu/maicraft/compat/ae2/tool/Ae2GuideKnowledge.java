// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeDocument;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeSource;

/**
 * AE2 内置 GuideME 手册（assets/ae2/ae2guide）的按需只读知识来源：
 * 启动时扫描全部 Markdown 文件的 YAML frontmatter（item_ids、title、categories）建轻量索引；
 * 目录仅放一条总索引避免刷屏，搜索与 entriesAbout(registryId) 覆盖全部手册页面；
 * read(uri) 按需读取原文并剥离 GameScene、BlockImage、RecipeFor 等 GuideME JSX 标签，保留说明正文与 ItemLink 文本。
 */
public final class Ae2GuideKnowledge implements KnowledgeSource {

    public static final String INDEX_URI = "maicraft://knowledge/ae2/guide/index";
    public static final String PAGE_URI_PREFIX = "maicraft://knowledge/ae2/guide/";

    private static final Pattern FRONTMATTER = Pattern.compile("\\A---\\r?\\n(.*?)\\r?\\n---\\r?\\n?", Pattern.DOTALL);
    private static final Pattern GAME_SCENE_BLOCK = Pattern.compile("<GameScene\\b[^>]*>.*?</GameScene>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern ITEM_LINK_SELF = Pattern.compile(
            "<ItemLink\\b[^>]*\\bid\\s*=\\s*\"([^\"]+)\"[^>]*/>");
    private static final Pattern ITEM_LINK_PAIRED = Pattern.compile(
            "<ItemLink\\b[^>]*\\bid\\s*=\\s*\"([^\"]+)\"[^>]*(?<!/)>(.*?)</ItemLink>", Pattern.DOTALL);
    private static final Pattern KEY_BIND_SELF = Pattern.compile(
            "<KeyBind\\b[^>]*\\bid\\s*=\\s*\"([^\"]+)\"[^>]*/>");
    private static final Pattern SELF_CLOSING_JSX = Pattern.compile("<[A-Z][A-Za-z0-9]*\\b[^>]*/>");
    private static final Pattern OPEN_OR_CLOSE_JSX = Pattern.compile("</?[A-Z][A-Za-z0-9]*\\b[^>]*>");
    private static final Pattern MULTI_BLANK_LINES = Pattern.compile("\\n{3,}");

    /** 解析后的单篇手册元信息索引。 */
    public record PageIndex(String relativePath, String uri, String title, List<String> categories,
                            Set<String> itemIds) {
        public PageIndex {
            Objects.requireNonNull(relativePath, "relativePath");
            Objects.requireNonNull(uri, "uri");
            Objects.requireNonNull(title, "title");
            categories = List.copyOf(categories);
            itemIds = Set.copyOf(itemIds);
        }
    }

    private final Ae2Compat compat;
    private final Ae2ToolReads reads;
    private volatile Map<String, PageIndex> pagesByUri;
    private volatile Map<String, List<PageIndex>> pagesByItemId;

    public Ae2GuideKnowledge(Ae2Compat compat, Ae2ToolReads reads) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.reads = Objects.requireNonNull(reads, "reads");
    }

    @Override public List<KnowledgeDocument.Entry> entries() {
        ensureIndexed();
        if (pagesByUri.isEmpty()) return List.of();
        return List.of(new KnowledgeDocument.Entry(
                INDEX_URI,
                "ae2.guide.index",
                "应用能源2内置指南（GuideME）",
                "已索引 " + pagesByUri.size() + " 篇 AE2 内置英文手册条目，按物品 ID 或搜索关键词按需查阅",
                "ae2 guidebook guideme 手册 指南 机制 原理"));
    }

    @Override public List<KnowledgeDocument.Entry> searchCandidates(String query) {
        ensureIndexed();
        List<KnowledgeDocument.Entry> all = new ArrayList<>(pagesByUri.size() + 1);
        all.addAll(entries());
        for (PageIndex page : pagesByUri.values()) {
            all.add(toEntry(page));
        }
        return List.copyOf(all);
    }

    @Override public List<KnowledgeDocument.Entry> entriesAbout(String registryId) {
        if (registryId == null || registryId.isBlank()) return List.of();
        ensureIndexed();
        List<PageIndex> matched = pagesByItemId.get(registryId.toLowerCase(Locale.ROOT));
        if (matched == null || matched.isEmpty()) return List.of();
        return matched.stream().map(Ae2GuideKnowledge::toEntry).toList();
    }

    @Override public KnowledgeDocument read(String uri) {
        if (uri == null) return null;
        ensureIndexed();
        if (INDEX_URI.equals(uri)) {
            return buildIndexDocument();
        }
        PageIndex page = pagesByUri.get(uri);
        if (page == null) return null;
        Optional<String> raw = compat.call("读取 AE2 指南正文", () -> reads.readGuidePage(page.relativePath()));
        if (raw.isEmpty()) return null;
        String cleaned = stripGuideJsx(raw.get());
        return new KnowledgeDocument(page.uri(), "ae2.guide." + page.relativePath(), page.title(),
                "AE2 内置指南", cleaned);
    }

    @Override public String status() {
        ensureIndexed();
        return pagesByUri.isEmpty()
                ? "应用能源2内置指南：未扫描到手册资源"
                : "应用能源2内置指南：可用，" + pagesByUri.size() + " 篇条目";
    }

    /**
     * 解析一篇 GuideME Markdown 的 YAML frontmatter（纯函数）：
     * 提取 title（支持顶层 title 或 navigation.title，缺省取首行 # 标题或文件名）、categories 与 item_ids。
     */
    public static PageIndex parseFrontmatter(String relativePath, String rawMarkdown) {
        String normalizedPath = relativePath.replace('\\', '/');
        String slug = normalizedPath.endsWith(".md")
                ? normalizedPath.substring(0, normalizedPath.length() - 3)
                : normalizedPath;
        String uri = PAGE_URI_PREFIX + slug;
        String title = "";
        List<String> categories = new ArrayList<>();
        Set<String> itemIds = new LinkedHashSet<>();

        Matcher fm = FRONTMATTER.matcher(rawMarkdown == null ? "" : rawMarkdown);
        if (fm.find()) {
            String yaml = fm.group(1);
            String section = "";
            for (String rawLine : yaml.split("\\r?\\n")) {
                String trimmed = rawLine.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int indent = leadingSpaces(rawLine);
                if (indent == 0 && !trimmed.startsWith("-")) {
                    section = "";
                }
                if (trimmed.startsWith("-")) {
                    String value = unquote(trimmed.substring(1).trim());
                    if ("categories".equals(section) && !value.isEmpty()) {
                        categories.add(value);
                    } else if ("item_ids".equals(section) && !value.isEmpty()) {
                        itemIds.add(normalizeItemId(value));
                    }
                    continue;
                }
                int colon = trimmed.indexOf(':');
                if (colon <= 0) continue;
                String key = trimmed.substring(0, colon).trim();
                String rest = trimmed.substring(colon + 1).trim();
                if ("categories".equals(key)) {
                    section = "categories";
                    parseInlineList(rest, categories, false);
                } else if ("item_ids".equals(key)) {
                    section = "item_ids";
                    parseInlineList(rest, itemIds, true);
                } else if ("navigation".equals(key)) {
                    section = "navigation";
                } else if ("title".equals(key) && !rest.isEmpty() && (indent == 0 || "navigation".equals(section))) {
                    if (title.isEmpty() || indent == 0) {
                        title = unquote(rest);
                    }
                }
            }
        }
        if (title.isEmpty()) {
            title = extractFirstHeading(rawMarkdown).orElse(fallbackTitle(slug));
        }
        return new PageIndex(normalizedPath, uri, title, categories, itemIds);
    }

    /**
     * 剥离 GuideME Markdown 中的 frontmatter 与可视化 JSX 标签（纯函数）：
     * 移除 GameScene 整块及 BlockImage/ItemImage/RecipeFor 等自闭合标签，
     * 保留 ItemLink 文本与可读物品 ID、解包排版标签内文字并整理空行。
     */
    public static String stripGuideJsx(String rawMarkdown) {
        if (rawMarkdown == null || rawMarkdown.isBlank()) return "";
        String text = rawMarkdown.replace("\r\n", "\n");
        text = FRONTMATTER.matcher(text).replaceFirst("");
        text = GAME_SCENE_BLOCK.matcher(text).replaceAll("");
        text = replaceItemLinksSelf(text);
        text = replaceItemLinksPaired(text);
        text = replaceKeyBinds(text);
        text = SELF_CLOSING_JSX.matcher(text).replaceAll("");
        text = OPEN_OR_CLOSE_JSX.matcher(text).replaceAll("");
        text = MULTI_BLANK_LINES.matcher(text).replaceAll("\n\n");
        return text.trim();
    }

    private void ensureIndexed() {
        if (pagesByUri != null) return;
        synchronized (this) {
            if (pagesByUri != null) return;
            Map<String, PageIndex> byUri = new LinkedHashMap<>();
            Map<String, List<PageIndex>> byItem = new LinkedHashMap<>();
            List<String> paths = compat.call("扫描 AE2 指南目录", reads::listGuidePages);
            for (String path : paths) {
                if (path == null || !path.endsWith(".md")) continue;
                Optional<String> raw = compat.call("读取 AE2 指南元信息", () -> reads.readGuidePage(path));
                if (raw.isEmpty()) continue;
                PageIndex page = parseFrontmatter(path, raw.get());
                byUri.put(page.uri(), page);
                for (String itemId : page.itemIds()) {
                    byItem.computeIfAbsent(itemId, unused -> new ArrayList<>()).add(page);
                }
            }
            Map<String, List<PageIndex>> frozenByItem = new LinkedHashMap<>();
            byItem.forEach((k, v) -> frozenByItem.put(k, List.copyOf(v)));
            this.pagesByItemId = Map.copyOf(frozenByItem);
            this.pagesByUri = Map.copyOf(byUri);
        }
    }

    private KnowledgeDocument buildIndexDocument() {
        StringBuilder sb = new StringBuilder("# 应用能源2内置指南（GuideME）\n\n");
        sb.append("共索引 ").append(pagesByUri.size()).append(" 篇条目；可通过物品资料页的「相关资料」或关键词搜索按 URI 读取正文。\n\n");
        for (PageIndex page : pagesByUri.values()) {
            sb.append("- ").append(page.title()).append("（`").append(page.uri()).append("`）");
            if (!page.itemIds().isEmpty()) {
                sb.append("：").append(String.join(", ", page.itemIds()));
            }
            sb.append("\n");
        }
        return new KnowledgeDocument(INDEX_URI, "ae2.guide.index", "应用能源2内置指南（GuideME）",
                "AE2 内置指南", sb.toString());
    }

    private static KnowledgeDocument.Entry toEntry(PageIndex page) {
        String keywords = page.title() + " " + String.join(" ", page.categories()) + " "
                + String.join(" ", page.itemIds());
        String summary = page.itemIds().isEmpty()
                ? "AE2 内置指南条目：" + page.title()
                : "AE2 内置指南条目，关联：" + String.join(", ", page.itemIds());
        return new KnowledgeDocument.Entry(page.uri(), "ae2.guide." + page.relativePath(), page.title(),
                summary, keywords);
    }

    private static String replaceItemLinksPaired(String text) {
        Matcher matcher = ITEM_LINK_PAIRED.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String id = normalizeItemId(matcher.group(1));
            String label = matcher.group(2).trim();
            String replacement = label.isEmpty() ? "`" + id + "`" : label + "（`" + id + "`）";
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String replaceItemLinksSelf(String text) {
        Matcher matcher = ITEM_LINK_SELF.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String id = normalizeItemId(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement("`" + id + "`"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String replaceKeyBinds(String text) {
        Matcher matcher = KEY_BIND_SELF.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement("`" + matcher.group(1) + "`"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static void parseInlineList(String rest, Collection<String> sink, boolean asItemId) {
        if (rest.isEmpty() || !rest.startsWith("[") || !rest.endsWith("]")) return;
        String inside = rest.substring(1, rest.length() - 1).trim();
        if (inside.isEmpty()) return;
        for (String piece : inside.split(",")) {
            String value = unquote(piece.trim());
            if (!value.isEmpty()) {
                sink.add(asItemId ? normalizeItemId(value) : value);
            }
        }
    }

    private static String normalizeItemId(String raw) {
        String clean = raw.trim().toLowerCase(Locale.ROOT);
        return clean.contains(":") ? clean : "ae2:" + clean;
    }

    private static Optional<String> extractFirstHeading(String markdown) {
        if (markdown == null) return Optional.empty();
        String body = FRONTMATTER.matcher(markdown).replaceFirst("");
        for (String line : body.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                return Optional.of(trimmed.substring(2).trim());
            }
        }
        return Optional.empty();
    }

    private static String fallbackTitle(String slug) {
        int slash = slug.lastIndexOf('/');
        return slash >= 0 ? slug.substring(slash + 1) : slug;
    }

    private static int leadingSpaces(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') i++;
        return i;
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }
}
