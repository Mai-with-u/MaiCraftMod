// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.server.ServerOperationRegistry;
import org.maiwithu.maicraft.server.compat.ServerCompatRegistry;

/** AE2 的服务端联动入口：登记只读操作 ae2.network 与 ae2.device，汇总写成回答的字段名与客户端读的一致。 */
class Ae2ServerCompatTest {

    @Test
    void 登记只读操作ae2点network与ae2点device() {
        ServerOperationRegistry operations = new ServerOperationRegistry();
        new ServerCompatRegistry(operations).add(new Ae2ServerCompat((level, at) -> Optional.empty()));
        assertTrue(operations.registered(Ae2ServerCompat.OPERATION));
        assertTrue(operations.registered(Ae2ServerCompat.DEVICE_OPERATION));
    }

    @Test
    void 汇总写成回答_没控制器频道超了也照写() {
        JsonObject answer = Ae2ServerCompat.json(new Ae2GridReads.GridSummary("1a2b", 12, "none", false, "default",
                0, 8, "too_many_channels", true, 1200.5, 8000, 37, 4096, 1, 0));
        assertEquals(true, answer.get("found").getAsBoolean());
        assertEquals("none", answer.get("controller").getAsString());
        assertEquals(8, answer.get("ad_hoc_limit").getAsInt());
        assertEquals("too_many_channels", answer.get("ad_hoc_problem").getAsString());
        assertEquals(37, answer.get("stored_types").getAsInt());
        assertEquals(4096L, answer.get("stored_amount").getAsLong());
    }

    @Test
    void 汇总附带扩展明细时保留旧字段并附加供电CPU与样板容器() {
        Ae2GridReads.GridSummary base = new Ae2GridReads.GridSummary("1a2b", 12, "online", false, "default",
                6, 8, "none", true, 1200.5, 8000, 37, 4096, 1, 1);
        Ae2NetworkServerExtras.NetworkDetail extra = new Ae2NetworkServerExtras.NetworkDetail(
                120.0, 48.5, 4.0,
                List.of(new Ae2NetworkServerExtras.MachineEntry("ae2:drive", "ME 驱动器", 1, 2.0, 0.0, false)),
                List.of(new Ae2NetworkServerExtras.CpuEntry(0, "主CPU", 65536, 4, "any", true, false, false,
                        "minecraft:iron_ingot x16", 8, 16, List.of(), List.of())),
                List.of(new Ae2NetworkServerExtras.PatternGroupEntry("分子组装室", "ae2:molecular_assembler",
                        9, 2, List.of("minecraft:chest"))));
        JsonObject answer = Ae2ServerCompat.json(base, Optional.of(extra));
        assertEquals("online", answer.get("controller").getAsString());
        assertEquals(120.0, answer.get("power_injection").getAsDouble());
        assertEquals(48.5, answer.get("power_usage").getAsDouble());
        assertEquals(4.0, answer.get("channel_power").getAsDouble());
        assertEquals(1, answer.getAsJsonArray("machines").size());
        assertEquals(1, answer.getAsJsonArray("cpus").size());
        assertEquals(1, answer.getAsJsonArray("pattern_containers").size());
    }
}
