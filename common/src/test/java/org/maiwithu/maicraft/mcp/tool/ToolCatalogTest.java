// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import java.util.Base64;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 装进 MCP 的调用结果：先一段 JSON 文字，工具另带的图跟在后面成图片内容块，图的字节不进文字那一块。 */
class ToolCatalogTest {

    @Test
    void 没有图时只有一块文字() {
        JsonArray content = ToolCatalog.wrap(ToolReply.ok(new JsonObject())).getAsJsonArray("content");
        assertEquals(1, content.size());
        assertEquals("text", content.get(0).getAsJsonObject().get("type").getAsString());
    }

    @Test
    void 图跟在文字后面_base64写在data里() {
        byte[] bytes = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3};
        JsonObject result = ToolCatalog.wrap(ToolReply.ok(new JsonObject()),
                List.of(new ToolReply.Image("image/jpeg", bytes)));
        JsonArray content = result.getAsJsonArray("content");
        assertEquals(2, content.size());
        assertEquals("text", content.get(0).getAsJsonObject().get("type").getAsString());
        JsonObject image = content.get(1).getAsJsonObject();
        assertEquals("image", image.get("type").getAsString());
        assertEquals("image/jpeg", image.get("mimeType").getAsString());
        assertArrayEquals(bytes, Base64.getDecoder().decode(image.get("data").getAsString()));
        assertFalse(content.get(0).getAsJsonObject().get("text").getAsString()
                .contains(Base64.getEncoder().encodeToString(bytes)), "文字那一块不带图的字节");
        assertFalse(result.has("isError") && result.get("isError").getAsBoolean());
    }
}
