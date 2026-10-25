// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.maiwithu.maicraft.mcp.knowledge.web.PageReplay.COMPARATOR;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 联网查询怎么搜、怎么读：按查询选站、搜到的几条读开头一句、读过的进缓存与过期、关着时不联网。网页用回放替身。 */
class WebKnowledgeServiceTest {
    @Test
    void searchPicksTheChineseSiteAndReadsTheOpeningSentences() throws Exception {
        WebKnowledgeService.SearchAnswer answer = service(PageReplay.chineseWiki(), new MovingClock()).search("比较器");
        assertEquals("Minecraft Wiki（中文）", answer.site());
        assertTrue(answer.chineseSite(), "查询里有汉字搜中文站");
        assertEquals(2, answer.total());
        WebKnowledgeService.Found first = answer.results().getFirst();
        assertEquals("红石比较器", first.title());
        assertEquals(COMPARATOR, first.url());
        assertEquals("红石比较器是一种红石元件。", first.summary());
        assertEquals(List.of("红石比较器/DV：站点上没有这一页（HTTP 404）"), answer.unreadSummaries(), "子页读不到，原因写明是哪一条");
    }

    @Test
    void summariesThatCannotBeReadKeepTheResultsAndShareOneReason() throws Exception {
        PageReplay replay = PageReplay.chineseWiki();
        replay.pages.remove(COMPARATOR.toString());
        WebKnowledgeService.SearchAnswer answer = service(replay, new MovingClock()).search("红石比较器");
        assertEquals("红石比较器", answer.results().getFirst().title(), "读不到正文的那一条照样列出来");
        assertEquals("", answer.results().getFirst().summary());
        assertEquals(List.of("红石比较器、红石比较器/DV：站点上没有这一页（HTTP 404）"), answer.unreadSummaries(),
                "同样的原因合在一起说一次");
    }

    @Test
    void latinQueriesSearchTheEnglishSite() throws Exception {
        PageReplay replay = new PageReplay();
        replay.pages.put("https://minecraft.wiki/images/sitemaps/index.xml",
                "<sitemapindex><sitemap><loc>https://minecraft.wiki/images/sitemaps/NS_0-0.xml.gz</loc></sitemap></sitemapindex>");
        replay.pages.put("https://minecraft.wiki/images/sitemaps/NS_0-0.xml.gz",
                "<urlset><url><loc>https://minecraft.wiki/w/Redstone_Comparator</loc></url></urlset>");
        WebKnowledgeService.SearchAnswer answer = service(replay, new MovingClock()).search("comparator");
        assertFalse(answer.chineseSite());
        assertEquals("Minecraft Wiki（英文）", answer.site());
        assertEquals("Redstone Comparator", answer.results().getFirst().title());
    }

    @Test
    void readingASearchedArticleDoesNotGoOnlineAgainUntilTheCacheExpires() throws Exception {
        PageReplay replay = PageReplay.chineseWiki();
        MovingClock clock = new MovingClock();
        WebKnowledgeService service = service(replay, clock);
        service.search("红石比较器");
        int afterSearch = replay.calls(COMPARATOR);
        assertEquals(1, afterSearch, "搜索时读开头一句读过一次");
        service.read(COMPARATOR);
        assertEquals(1, replay.calls(COMPARATOR), "搜索时读过的那篇接着读不用再联网");
        clock.advance(Duration.ofMinutes(61));
        service.read(COMPARATOR);
        assertEquals(2, replay.calls(COMPARATOR), "一小时以后重读");
    }

    @Test
    void theSwitchedOffServiceNeverGoesOnline() {
        assertFalse(WebKnowledgeService.off().enabled());
    }

    private static WebKnowledgeService service(WebFetch fetch, Clock clock) {
        return new WebKnowledgeService(fetch, true, "1.21.1", clock);
    }

    /** 能往前拨的时钟，从替身记下的取页时刻起走：验证缓存过期不用真的等。 */
    private static final class MovingClock extends Clock {
        private Instant now = PageReplay.FETCHED_AT;

        void advance(Duration duration) { now = now.plus(duration); }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
