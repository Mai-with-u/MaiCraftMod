// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge.web;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * 联网查资料只去白名单里的三个站：Minecraft Wiki 中文站与英文站、MC百科，而且只走 HTTPS 的条目路径；
 * 重定向也必须重新经过同一道检查。站外地址在发请求之前就拒掉。
 */
public final class WebAccess {
    static final String WIKI_ZH = "zh.minecraft.wiki";
    static final String WIKI_EN = "minecraft.wiki";
    static final String MCMOD = "www.mcmod.cn";
    private static final Set<String> WIKIS = Set.of(WIKI_EN, WIKI_ZH);

    /** 写错地址时说清能读哪几种：照着改一次就对。 */
    public static final String ACCEPTED = "只能读 Minecraft Wiki 的条目（https://zh.minecraft.wiki/w/条目名 或 https://minecraft.wiki/w/条目名，"
            + "不带 ? 参数，不读 File:、Special: 这类带冒号的页面）或 MC百科的页面（https://www.mcmod.cn/item/数字.html，或 /class/、/post/ 下的）";

    private WebAccess() {}

    public static boolean wiki(URI uri) { return WIKIS.contains(host(uri)); }

    /** 回答里写给 LLM 的站名：一眼看出是哪个站、哪种语言。 */
    static String siteName(URI uri) {
        return switch (host(uri)) {
            case WIKI_ZH -> "Minecraft Wiki（中文）";
            case WIKI_EN -> "Minecraft Wiki（英文）";
            default -> "MC百科";
        };
    }

    // Wiki 主动公布的主命名空间站点地图只含条目地址，标题在本机比，不访问站点禁止自动抓取的搜索页和 API。
    static boolean sitemap(URI uri) {
        return wiki(uri) && uri.getRawQuery() == null && uri.getPath() != null
                && uri.getPath().matches("/images/sitemaps/(index\\.xml|NS_0-[0-9]+\\.xml(\\.gz)?)");
    }

    /**
     * 把 LLM 给的条目地址整理成唯一的写法：只认 Minecraft Wiki 的 /w/条目 与 MC百科的 /item/、/class/、/post/ 页面，
     * 不带查询参数。章节锚点去掉（读的是整篇），Wiki 条目名里的空格换成下划线，百分号编码与直接写中文读的是同一篇。
     */
    public static URI article(String value) {
        URI uri;
        try {
            // Wiki 的空格就是下划线；直接写空格时 URI 读不了，先换掉。
            uri = URI.create(value.strip().replace(' ', '_'));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(ACCEPTED);
        }
        if (!baseAllowed(uri) || uri.getRawQuery() != null || !articlePath(uri)) throw new IllegalArgumentException(ACCEPTED);
        try {
            return new URI("https", host(uri), uri.getPath(), null);
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException(ACCEPTED);
        }
    }

    public static void requireFetch(URI uri) throws WebKnowledgeException {
        if (!baseAllowed(uri) || uri.getRawFragment() != null
                || !(uri.getPath().equals("/robots.txt") && uri.getRawQuery() == null
                || sitemap(uri)
                || uri.getRawQuery() == null && articlePath(uri)))
            throw new WebKnowledgeException(WebKnowledgeException.Kind.SITE_REFUSED,
                    "地址 " + uri + " 不在能查的站点与条目路径里，没有去读");
    }

    private static boolean baseAllowed(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) && uri.getRawUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && (wiki(uri) || MCMOD.equals(host(uri)));
    }

    private static boolean articlePath(URI uri) {
        String path = uri.getPath();
        if (path == null || path.contains("..") || path.contains("\\") || path.chars().anyMatch(Character::isISOControl)) return false;
        if (wiki(uri)) return path.startsWith("/w/") && path.length() > 3 && !path.substring(3).contains(":");
        return path.matches("/(item|class|post)/[0-9]+\\.html");
    }

    static String host(URI uri) { return uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT); }
}
