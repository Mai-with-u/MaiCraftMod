// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge.web;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;

/** 按地址取一页文字：检索和读条目共用同一个期限；测试用已知网页回放，不碰真实站点。 */
@FunctionalInterface
public interface WebFetch {
    Page get(URI uri, long deadlineNanos) throws IOException, InterruptedException;

    /** 取回的一页：最后落在哪个地址（跟过重定向）、解压后的文字、什么时候取的。 */
    record Page(URI uri, String body, Instant fetchedAt) {}
}
