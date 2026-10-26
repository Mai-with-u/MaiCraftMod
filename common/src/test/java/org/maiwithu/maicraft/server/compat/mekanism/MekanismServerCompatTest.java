// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.compat.mekanism;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.server.ServerOperationRegistry;
import org.maiwithu.maicraft.server.compat.ServerCompatRegistry;

/** 通用机械的服务端联动入口：登记只读操作 mekanism.network，管网汇总写成回答的字段名与客户端读的一致。 */
class MekanismServerCompatTest {

    @Test
    void 登记只读操作mekanism点network() {
        ServerOperationRegistry operations = new ServerOperationRegistry();
        new ServerCompatRegistry(operations).add(new MekanismServerCompat((level, at) -> Optional.empty()));
        assertTrue(operations.registered(MekanismServerCompat.OPERATION));
    }

    @Test
    void 能量网写存量上限与单位_没有流量与装的是什么() {
        JsonObject answer = MekanismServerCompat.json(new MekanismNetworkReads.PipeNetwork("5f1c", "energy", 14, 3,
                Optional.of(new MekanismNetworkReads.Stock("", 6_400, 24_000, -1, "FE"))));
        assertTrue(answer.get("found").getAsBoolean());
        assertEquals("5f1c", answer.get("network").getAsString());
        assertEquals("energy", answer.get("medium").getAsString());
        assertEquals(14, answer.get("pipes").getAsInt());
        assertEquals(3, answer.get("machines").getAsInt());
        assertEquals(6_400L, answer.get("energy_stored").getAsLong());
        assertEquals(24_000L, answer.get("energy_max").getAsLong());
        assertEquals("FE", answer.get("energy_unit").getAsString());
        assertFalse(answer.has("energy_flow"));
        assertFalse(answer.has("energy"));
    }

    @Test
    void 流体网写装的是什么与上一刻送出多少() {
        JsonObject answer = MekanismServerCompat.json(new MekanismNetworkReads.PipeNetwork("9a0b", "fluid", 6, 2,
                Optional.of(new MekanismNetworkReads.Stock("minecraft:water", 1_500, 6_000, 250, "mB"))));
        assertEquals("minecraft:water", answer.get("fluid").getAsString());
        assertEquals(1_500L, answer.get("fluid_stored").getAsLong());
        assertEquals(6_000L, answer.get("fluid_max").getAsLong());
        assertEquals(250L, answer.get("fluid_flow").getAsLong());
        assertEquals("mB", answer.get("fluid_unit").getAsString());
    }

    @Test
    void 空的化学品网不写装的是什么_存量照写零() {
        JsonObject answer = MekanismServerCompat.json(new MekanismNetworkReads.PipeNetwork("77cc", "chemical", 4, 1,
                Optional.of(new MekanismNetworkReads.Stock("", 0, 2_000, 0, "mB"))));
        assertFalse(answer.has("chemical"));
        assertEquals(0L, answer.get("chemical_stored").getAsLong());
        assertEquals(2_000L, answer.get("chemical_max").getAsLong());
    }

    @Test
    void 物品网不存东西_只写管道与机器() {
        JsonObject answer = MekanismServerCompat.json(new MekanismNetworkReads.PipeNetwork("13de", "item", 9, 4,
                Optional.empty()));
        assertEquals(9, answer.get("pipes").getAsInt());
        assertFalse(answer.has("item_stored"));
        assertFalse(answer.has("item_max"));
    }
}
