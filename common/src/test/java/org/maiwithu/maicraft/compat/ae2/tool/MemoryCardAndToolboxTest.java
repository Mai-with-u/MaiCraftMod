// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.kernel.goal.Permissions;

/**
 * AE2 内存卡 12 组件只读解析与清空、网络工具 9 格升级卡箱来源离线测试。
 */
class MemoryCardAndToolboxTest {

    @BeforeAll
    static void 引导注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 内存卡暴露全部十二项同步导出组件的摘要与清空核对() {
        Ae2ToolReads.MemoryCardSnapshot filled = new Ae2ToolReads.MemoryCardSnapshot(
                "ME Export Bus",
                "主线输出",
                12,
                "ae2:me_p2p_tunnel",
                "A3F1",
                "red,blue,green,white/black,yellow,cyan,purple",
                3,
                Map.of("REDSTONE_CONTROLLED", "HIGH_SIGNAL"),
                4,
                64L,
                2,
                "east",
                true);
        Ae2ToolReads.MemoryCardSnapshot blank = new Ae2ToolReads.MemoryCardSnapshot(
                "", "", null, "", "", "", 0, Map.of(), 0, null, 0, "", false);

        Ae2ToolReads reads = new Ae2ToolReads() {
            @Override public Optional<MemoryCardSnapshot> readMemoryCard(ItemStack stack) {
                return Optional.of(stack.is(Items.PAPER) ? filled : blank);
            }
        };
        MemoryCardSettings settings = new MemoryCardSettings(
                ToolTestFakes.compat(),
                reads,
                ToolTestFakes.services(() -> null, Map.of(), List.of()),
                stack -> stack.is(Items.PAPER) || stack.is(Items.MAP) ? MemoryCardSettings.MEMORY_CARD : "");

        ItemStack filledCard = new ItemStack(Items.PAPER);
        ItemStack blankCard = new ItemStack(Items.MAP);
        assertTrue(settings.covers(filledCard));
        assertFalse(settings.covers(new ItemStack(Items.STICK)));

        Map<String, String> values = new LinkedHashMap<>();
        for (MachineSetting setting : settings.settings(filledCard)) {
            values.put(setting.key(), setting.value());
        }
        assertEquals("ME Export Bus", values.get("source"));
        assertEquals("主线输出", values.get("custom_name"));
        assertEquals("12", values.get("priority"));
        assertEquals("ae2:me_p2p_tunnel", values.get("p2p_type"));
        assertEquals("A3F1", values.get("p2p_frequency"));
        assertEquals("red,blue,green,white/black,yellow,cyan,purple", values.get("color"));
        assertEquals("3", values.get("upgrades_count"));
        assertEquals("false", values.get("clear"));
        String summary = values.get("settings_summary");
        assertTrue(summary.contains("REDSTONE_CONTROLLED=HIGH_SIGNAL"));
        assertTrue(summary.contains("过滤槽 4 项"));
        assertTrue(summary.contains("发信阈值 64"));
        assertTrue(summary.contains("样板 2 张"));
        assertTrue(summary.contains("推送方向 east"));

        assertFalse(settings.holds(filledCard, MemoryCardSettings.KEY_CLEAR, "true"));
        assertTrue(settings.holds(blankCard, MemoryCardSettings.KEY_CLEAR, "true"));
        assertTrue(settings.change(0, Map.of("clear", "true"), Permissions.DEFAULT).isPresent());
        assertTrue(settings.change(0, Map.of("clear", "false"), Permissions.DEFAULT).isEmpty());
        assertTrue(settings.change(0, Map.of("priority", "5"), Permissions.DEFAULT).isEmpty());
    }

    @Test
    void 网络工具箱按九格升级卡库存计数并归属随身途径() {
        List<ItemStack> cards = List.of(
                new ItemStack(Items.REDSTONE, 4),
                new ItemStack(Items.GLOWSTONE_DUST, 2),
                new ItemStack(Items.REDSTONE, 3));
        assertEquals(7, NetworkToolboxSource.matchingCount(cards, "minecraft:redstone"::equals));
        assertEquals(2, NetworkToolboxSource.matchingCount(cards, "minecraft:glowstone_dust"::equals));
        assertEquals(0, NetworkToolboxSource.matchingCount(cards, "minecraft:diamond"::equals));

        NetworkToolboxSource source = new NetworkToolboxSource(ToolTestFakes.compat(), Ae2ToolReads.NONE,
                ToolTestFakes.services(() -> null, Map.of(), List.of()));
        assertEquals(AcquireVia.CARRIED, source.via());
    }
}
