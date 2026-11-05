// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonObject;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.world.KeepInventoryRule;
import org.maiwithu.maicraft.game.world.RespawnPoint;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * observe(self) 的死一次会怎样：本机读到真实的就照写并标确认；还在读的不写；
 * 别人的服务器死亡不掉落写 unknown，重生点按最近睡过的床推断，没睡过是世界出生点，都标没确认。
 */
class AfterDeathViewTest {

    private static final RespawnPoint.Spot SPAWN = new RespawnPoint.Spot(0, 70, 0, "minecraft:overworld",
            RespawnPoint.Source.WORLD_SPAWN);

    @Test
    void 本机读到_照写并标确认() {
        RespawnPoint.Spot anchor = new RespawnPoint.Spot(10, 40, -3, "minecraft:the_nether", RespawnPoint.Source.RESPAWN_ANCHOR);
        JsonObject view = write(SelfView.afterDeath(KeepInventoryRule.Reading.ON,
                new RespawnPoint.Reading(RespawnPoint.Status.READ, anchor), Optional.empty(), Optional.of(SPAWN)));
        assertEquals("on", view.get("keep_inventory").getAsString());
        JsonObject respawn = view.getAsJsonObject("respawn");
        assertEquals("respawn_anchor", respawn.get("source").getAsString());
        assertEquals("minecraft:the_nether", respawn.get("dimension").getAsString());
        assertEquals(40, respawn.get("y").getAsInt());
        assertEquals(true, respawn.get("confirmed").getAsBoolean());
    }

    @Test
    void 还在读的两项都不写() {
        JsonObject view = write(SelfView.afterDeath(KeepInventoryRule.Reading.READING,
                new RespawnPoint.Reading(RespawnPoint.Status.READING, null), Optional.empty(), Optional.of(SPAWN)));
        assertFalse(view.has("keep_inventory"));
        assertFalse(view.has("respawn"));
    }

    @Test
    void 别人的服务器_按最近睡过的床推断() {
        WorldPosition bed = new WorldPosition(120, 65, 40, "minecraft:overworld");
        JsonObject view = write(SelfView.afterDeath(KeepInventoryRule.Reading.UNREADABLE,
                new RespawnPoint.Reading(RespawnPoint.Status.UNREADABLE, null), Optional.of(bed), Optional.of(SPAWN)));
        assertEquals("unknown", view.get("keep_inventory").getAsString());
        JsonObject respawn = view.getAsJsonObject("respawn");
        assertEquals("bed", respawn.get("source").getAsString());
        assertEquals(120, respawn.get("x").getAsInt());
        assertEquals(false, respawn.get("confirmed").getAsBoolean());
    }

    @Test
    void 别人的服务器没睡过床_是世界出生点() {
        JsonObject view = write(SelfView.afterDeath(KeepInventoryRule.Reading.UNREADABLE,
                new RespawnPoint.Reading(RespawnPoint.Status.UNREADABLE, null), Optional.empty(), Optional.of(SPAWN)));
        JsonObject respawn = view.getAsJsonObject("respawn");
        assertEquals("world_spawn", respawn.get("source").getAsString());
        assertEquals(false, respawn.get("confirmed").getAsBoolean());
    }

    private static JsonObject write(SelfView.AfterDeath afterDeath) {
        JsonObject view = new JsonObject();
        SelfView.addAfterDeath(view, afterDeath);
        return view;
    }
}
