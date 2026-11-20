// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.server.compat.ae2.Ae2NetworkServerExtras.CpuEntry;
import org.maiwithu.maicraft.server.compat.ae2.Ae2NetworkServerExtras.MachineEntry;
import org.maiwithu.maicraft.server.compat.ae2.Ae2NetworkServerExtras.NetworkDetail;
import org.maiwithu.maicraft.server.compat.ae2.Ae2NetworkServerExtras.PatternGroupEntry;
import org.maiwithu.maicraft.server.compat.ae2.Ae2NetworkServerExtras.StockAmount;

/** AE2 服务端网络扩展明细：验证向后兼容合并既有字段及耗电分组、CPU 在途明细与样板容器序列化。 */
class Ae2NetworkServerExtrasTest {

    @Test
    void 合并到原有网络回答时不覆盖旧字段并完整写入扩展明细() {
        JsonObject base = Ae2ServerCompat.json(new Ae2GridReads.GridSummary("9f01", 24, "online", false, "default",
                12, 8, "", true, 16000.0, 32000.0, 50, 8192, 2, 1));
        NetworkDetail detail = new NetworkDetail(
                120.5, 48.25, 4.0,
                List.of(
                        new MachineEntry("ae2:drive", "ME 驱动器", 2, 4.0, 0.0, false),
                        new MachineEntry("ae2:vibration_chamber", "振动室", 1, 0.0, 40.0, false)),
                List.of(
                        new CpuEntry(0, "主合成器", 65536L, 4, "any", true, false, false,
                                "ae2:logic_processor x16", 6L, 16L,
                                List.of(new StockAmount("ae2:printed_logic_processor", 10L)),
                                List.of(new StockAmount("minecraft:redstone", 10L))),
                        new CpuEntry(1, "", 16384L, 1, "player_only", false, true, true,
                                "", 0L, 0L, List.of(), List.of())),
                List.of(
                        new PatternGroupEntry("分子组装室", "ae2:molecular_assembler", 9, 3,
                                List.of("ae2:logic_processor", "ae2:calculation_processor"))));

        Ae2NetworkServerExtras.appendTo(base, detail);

        // 原有字段完全保留。
        assertTrue(base.get("found").getAsBoolean());
        assertEquals("9f01", base.get("grid").getAsString());
        assertEquals(24, base.get("nodes").getAsInt());
        assertEquals(8192L, base.get("stored_amount").getAsLong());

        // 新增能量收支与机器分组字段。
        assertEquals(120.5, base.get("power_injection").getAsDouble(), 1e-6);
        assertEquals(48.25, base.get("power_usage").getAsDouble(), 1e-6);
        assertEquals(4.0, base.get("channel_power").getAsDouble(), 1e-6);
        JsonArray machines = base.getAsJsonArray("machines");
        assertEquals(2, machines.size());
        assertEquals("ae2:drive", machines.get(0).getAsJsonObject().get("item").getAsString());
        assertEquals(40.0, machines.get(1).getAsJsonObject().get("generation_capacity").getAsDouble(), 1e-6);

        // 合成 CPU 含在途与待产物料、暂停与无法入库标记。
        JsonArray cpus = base.getAsJsonArray("cpus");
        assertEquals(2, cpus.size());
        JsonObject firstCpu = cpus.get(0).getAsJsonObject();
        assertEquals(0, firstCpu.get("serial").getAsInt());
        assertEquals("主合成器", firstCpu.get("name").getAsString());
        assertEquals(65536L, firstCpu.get("storage").getAsLong());
        assertEquals(4, firstCpu.get("co_processors").getAsInt());
        assertEquals("any", firstCpu.get("mode").getAsString());
        assertTrue(firstCpu.get("busy").getAsBoolean());
        assertFalse(firstCpu.get("cant_store").getAsBoolean());
        assertEquals(1, firstCpu.getAsJsonArray("active").size());
        assertEquals("ae2:printed_logic_processor",
                firstCpu.getAsJsonArray("active").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(1, firstCpu.getAsJsonArray("pending").size());
        assertTrue(cpus.get(1).getAsJsonObject().get("cant_store").getAsBoolean());

        // 样板容器分组与产出列表。
        JsonArray containers = base.getAsJsonArray("pattern_containers");
        assertEquals(1, containers.size());
        JsonObject group = containers.get(0).getAsJsonObject();
        assertEquals("分子组装室", group.get("name").getAsString());
        assertEquals(9, group.get("total_slots").getAsInt());
        assertEquals(3, group.get("used_slots").getAsInt());
        assertEquals(2, group.getAsJsonArray("outputs").size());
    }

    @Test
    void 空扩展实现永远返回空() {
        assertTrue(Ae2NetworkServerExtras.NONE.detail(null, null).isEmpty());
    }
}
