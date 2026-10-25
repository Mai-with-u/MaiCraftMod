// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge.web;

import java.io.IOException;
import java.net.URI;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.parser.Parser;

/**
 * Wiki 的条目标题索引：从站点公布的主命名空间站点地图读全部条目地址，标题在本机比。
 * Wiki 不让程序用它的搜索页和 API，所以按标题找；每个站一份，12 小时后重读。
 */
final class WikiTitleIndex {
    private static final Duration TTL = Duration.ofHours(12);
    private static final Pattern LOC = Pattern.compile("<loc>\\s*([^<\\s]+)\\s*</loc>");
    private static final Pattern MAIN_NAMESPACE = Pattern.compile("/images/sitemaps/NS_0-[0-9]+\\.xml(\\.gz)?");

    /** 标题对得上的一条：条目地址与标题。 */
    record Hit(URI uri, String title) {}
    /** 一次按标题找的结果：排好序的前几条与对得上的总数。 */
    record Search(List<Hit> hits, int total) {}

    private record Index(List<Hit> articles, Instant fetchedAt) {}
    private record Match(Hit hit, int score) {}

    private final WebFetch fetch;
    private final Clock clock;
    private final Map<String, Index> indexes = new ConcurrentHashMap<>();

    WikiTitleIndex(WebFetch fetch, Clock clock) {
        this.fetch = fetch;
        this.clock = clock;
    }

    /**
     * 在一个站的标题里找：标题一样排最前，再是以它开头、包含它、包含问句里每个词，最后是问句里含着整个标题
     * （"红石比较器怎么用"找到"红石比较器"）。同一档里短的标题排前，问句含着的标题长的排前。
     */
    Search search(String host, String query, int limit, long deadline) throws IOException, InterruptedException {
        Index index = indexes.get(host);
        if (index == null || index.fetchedAt().plus(TTL).isBefore(clock.instant())) {
            index = load(host, deadline);
            indexes.put(host, index);
        }
        String wanted = normalize(query);
        String[] words = wanted.split("\\s+");
        List<Match> matches = new ArrayList<>();
        for (Hit hit : index.articles()) {
            int score = score(normalize(hit.title()), wanted, words);
            if (score > 0) matches.add(new Match(hit, score));
        }
        matches.sort(Comparator.comparingInt(Match::score).reversed()
                .thenComparingInt(match -> match.hit().title().length())
                .thenComparing(match -> match.hit().title()));
        return new Search(matches.stream().limit(limit).map(Match::hit).toList(), matches.size());
    }

    private static int score(String title, String wanted, String[] words) {
        if (title.equals(wanted)) return 1000;
        if (title.startsWith(wanted)) return 800;
        if (title.contains(wanted)) return 700;
        if (words.length > 1 && List.of(words).stream().allMatch(title::contains)) return 600;
        // 问句里含着整个标题：标题越长越像要找的那篇；一个字的标题太容易碰上，不算。
        if (title.length() >= 2 && wanted.contains(title)) return 400 + Math.min(title.length(), 199);
        return 0;
    }

    // 只连续读主命名空间的全部分片；哪一片读不到都不把残缺的索引当成"已经找全"。
    private Index load(String host, long deadline) throws IOException, InterruptedException {
        String origin = "https://" + host;
        WebFetch.Page root = fetch.get(URI.create(origin + "/images/sitemaps/index.xml"), deadline);
        if (!root.body().contains("<sitemapindex")) throw invalid();
        LinkedHashSet<URI> seen = new LinkedHashSet<>();
        List<Hit> articles = new ArrayList<>();
        boolean mainNamespace = false;
        for (String location : locations(root.body())) {
            URI part = URI.create(location);
            if (!MAIN_NAMESPACE.matcher(part.getPath()).matches()) continue;
            if (!host.equals(part.getHost())) throw invalid();
            mainNamespace = true;
            WebFetch.Page page = fetch.get(part, deadline);
            if (!page.body().contains("<urlset")) throw invalid();
            for (String entry : locations(page.body())) {
                URI article;
                try { article = WebAccess.article(entry); }
                catch (IllegalArgumentException unsupported) { continue; }
                // 站点地图里同一条目会重复出现好几次（每种语言变体一次），只留一条。
                if (host.equals(article.getHost()) && seen.add(article)) articles.add(new Hit(article, title(article)));
            }
        }
        if (!mainNamespace) throw invalid();
        return new Index(List.copyOf(articles), root.fetchedAt());
    }

    // 站点地图有好几 MB：按 <loc> 逐个取地址，不建整棵 XML 树。
    private static List<String> locations(String xml) {
        List<String> found = new ArrayList<>();
        Matcher matcher = LOC.matcher(xml);
        while (matcher.find()) found.add(Parser.unescapeEntities(matcher.group(1), false));
        return found;
    }

    /** 条目地址里的名字就是标题：去掉 /w/，下划线换回空格。 */
    static String title(URI article) {
        return article.getPath().substring(3).replace('_', ' ');
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value.replace('_', ' '), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).strip();
    }

    private static WebKnowledgeException invalid() {
        return new WebKnowledgeException(WebKnowledgeException.Kind.UNREADABLE, "Wiki 的条目索引（站点地图）读不全，可能改版了");
    }
}
