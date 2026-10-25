// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge.web;

import java.io.IOException;

/**
 * 联网查资料没查成：只说明这一次外部站点用不了，不能据此断言机器、物品或配方不存在。
 * message 是给 LLM 的一句中文原因，种类决定 lookup 回哪个错误码。
 */
public final class WebKnowledgeException extends IOException {
    /** 没查成的种类：调用方据此决定是稍后再试、换地址，还是先用随包资料。 */
    public enum Kind {
        /** 同时进行的联网查询已经满了，这一次没开始。 */
        BUSY,
        /** 期限内没查完：站点太慢或连不上。 */
        TIMEOUT,
        /** 连不上站点：没联网、域名解析不了、连接被断开。 */
        UNREACHABLE,
        /** 站点说没有这一页（HTTP 404）。 */
        NOT_FOUND,
        /** 站点不让读：robots 规则禁止、要求放慢、返回错误，或者跳去了白名单外。 */
        SITE_REFUSED,
        /** 页面拿到了但读不出：找不到正文（改版或验证页）、太大、不是网页。 */
        UNREADABLE
    }

    private final Kind kind;

    public WebKnowledgeException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() { return kind; }
}
