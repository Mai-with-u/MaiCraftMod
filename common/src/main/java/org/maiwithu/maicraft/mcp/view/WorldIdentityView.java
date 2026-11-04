// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.view;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.maiwithu.maicraft.game.world.SaveIdentity;

import java.util.Locale;

/**
 * 角色眼下在哪个世界：{@code events} 每一页与 {@code observe(self)} 都带同一个 {@code world}。
 *
 * <p>宿主按 {@code id} 把不同存档、服务器里记下的事分开；{@code name} 只给人看，不当键用；
 * {@code source} 说编号从哪来，按地址或目录算的编号认不出删档重建。不在世界里、握手还没认出世界时整个是 null。
 */
public final class WorldIdentityView {
    private WorldIdentityView() {}

    /** 世界的样子；不在世界里或还没认出世界时给 JSON null，字段照样在，宿主不用分"没带"和"没认出"。 */
    public static JsonElement of(SaveIdentity world) {
        if (world == null) return JsonNull.INSTANCE;
        JsonObject json = new JsonObject();
        json.addProperty("id", world.key());
        json.addProperty("name", world.name());
        json.addProperty("source", world.source().name().toLowerCase(Locale.ROOT));
        return json;
    }
}
