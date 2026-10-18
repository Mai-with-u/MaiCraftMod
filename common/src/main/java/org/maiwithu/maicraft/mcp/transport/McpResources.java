// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.transport;

import com.google.gson.JsonObject;

/**
 * MCP 资源层的通用出口：列资源、按地址读。
 *
 * <p>传输层只管 JSON-RPC 与能力声明，不认识技能、知识这些具体种类；谁实现它，
 * 谁决定本服务器此刻有哪些资源、正文长什么样。地址不存在时抛
 * {@link IllegalArgumentException}，由传输层统一换成 Invalid params。
 */
public interface McpResources {

    /** {@code resources/list} 的结果：一个带 {@code resources} 数组的对象。 */
    JsonObject list();

    /** {@code resources/read} 的结果：一个带 {@code contents} 数组的对象。 */
    JsonObject read(String uri);

    /** 此刻一项资源都没有时返回 true，握手就不声明 resources。 */
    boolean isEmpty();
}
