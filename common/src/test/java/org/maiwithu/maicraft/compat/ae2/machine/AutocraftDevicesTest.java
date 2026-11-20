// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.ae2.Ae2MenuSlots;
import org.maiwithu.maicraft.compat.ae2.Ae2Patterns;
import org.maiwithu.maicraft.kernel.goal.Permissions;

/**
 * 自动合成核心设备离线测试：覆盖样板供应器 5 项设置与推送方向扳手切换路径、
 * 分子组装室单机样板警告与 5 速卡读数、压印器 3 项设置与 4 升级槽、合成 CPU 读数与控制键。
 */
class AutocraftDevicesTest {

    private static final BlockPos AT = new BlockPos(0, 70, 0);

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 样板供应器五项设置与推送方向最短扳手点击计算() {
        // 方块版有 push_direction（5 项），部件版不含 push_direction（4 项）。
        Ae2MachineCache.ProviderCache cached = new Ae2MachineCache.ProviderCache(
                "yes", "until_result", "yes", 12, "until_result", "minecraft:iron_ingot ×1", List.of());
        List<MachineSetting> blockSettings = PatternProviderOps.providerSettings(Optional.of(cached), true, "north");
        assertEquals(5, blockSettings.size());
        assertEquals("push_direction", blockSettings.getLast().key());
        assertEquals("north", blockSettings.getLast().value());

        List<MachineSetting> partSettings = PatternProviderOps.providerSettings(Optional.of(cached), false, "none");
        assertEquals(4, partSettings.size());
        assertTrue(partSettings.stream().noneMatch(s -> "push_direction".equals(s.key())));

        // 读数包含 crafting_locked_reason 与 unlock_stack。
        Ae2MachineCache cache = new Ae2MachineCache();
        cache.putProvider(AT, null, cached);
        Ae2Compat compat = MachineTestFakes.compatWith(Ae2MenuSlots.NONE, Ae2Patterns.NONE, MachineTestFakes.emptyGrid());
        PatternProviderOps ops = new PatternProviderOps(compat, Ae2MachineReads.NONE, cache);
        Map<String, Object> extra = ops.extraReadings(AT, null);
        assertEquals("until_result", extra.get("crafting_locked_reason"));
        assertEquals("minecraft:iron_ingot ×1", extra.get("unlock_stack"));
        assertEquals(12L, extra.get("priority"));

        // 推送方向最短扳手点击计算：ALL -> north 点 south 一次直达；north -> all 点 north 一次直达；south -> north 点 north 直达。
        assertEquals(Optional.of(Direction.SOUTH), PatternProviderOps.nextWrenchFace("all", "north"));
        assertEquals(Optional.of(Direction.NORTH), PatternProviderOps.nextWrenchFace("north", "all"));
        assertEquals(Optional.of(Direction.NORTH), PatternProviderOps.nextWrenchFace("south", "north"));
        assertEquals(Optional.empty(), PatternProviderOps.nextWrenchFace("north", "north"));
    }

    @Test
    void 分子组装室单机样板警告与五张加速卡读数() {
        Ae2MachineReads.AssemblerView live = new Ae2MachineReads.AssemblerView(true);
        Ae2MachineReads.AssemblerMenuView withPattern = new Ae2MachineReads.AssemblerMenuView(
                50, true, "minecraft:crafting_table",
                List.of("ae2:speed_card", "ae2:speed_card", "ae2:speed_card", "ae2:speed_card", "ae2:speed_card"));
        MachineState state = MolecularAssemblerOps.buildState(live, Optional.of(withPattern));
        assertEquals(MachineState.Activity.RUNNING, state.activity());
        assertEquals(50L, state.readings().get("craft_progress"));
        assertEquals(5L, state.readings().get("speed_cards"));
        assertEquals(false, state.readings().get("accepts_provider_plans"));
        assertTrue(state.note().contains(MolecularAssemblerOps.STANDALONE_PATTERN_WARNING));

        Ae2MachineReads.AssemblerMenuView emptyPattern = new Ae2MachineReads.AssemblerMenuView(
                0, false, "", List.of("ae2:speed_card", "empty", "empty", "empty", "empty"));
        MachineState freeState = MolecularAssemblerOps.buildState(live, Optional.of(emptyPattern));
        assertEquals(true, freeState.readings().get("accepts_provider_plans"));
        assertEquals(1L, freeState.readings().get("speed_cards"));
    }

    @Test
    void 压印器三项设置与四升级槽_合成CPU读数与控制键() {
        Ae2MachineReads.InscriberMenuView inscriber = new Ae2MachineReads.InscriberMenuView(
                "yes", "yes", "4", 100, 200, List.of("ae2:speed_card", "ae2:speed_card", "empty", "empty"));
        List<MachineSetting> inscriberSettings = InscriberSettingsOps.inscriberSettings(Optional.of(inscriber));
        assertEquals(7, inscriberSettings.size());
        assertEquals("separate_sides", inscriberSettings.get(0).key());
        assertEquals("yes", inscriberSettings.get(0).value());
        assertEquals("auto_export", inscriberSettings.get(1).key());
        assertEquals("input_capacity", inscriberSettings.get(2).key());
        assertEquals("4", inscriberSettings.get(2).value());
        assertEquals(List.of("1", "4", "64"), inscriberSettings.get(2).choices());

        // 合成 CPU 缓存读数与 scheduling_mode / job_action 配置键。
        Ae2MachineCache cache = new Ae2MachineCache();
        cache.putCpu(AT, new Ae2MachineCache.CpuCache("player_only", true, "cancel", List.of()));
        Ae2Compat compat = MachineTestFakes.compatWith(Ae2MenuSlots.NONE, Ae2Patterns.NONE, MachineTestFakes.emptyGrid());
        CraftingCpuOps cpuOps = new CraftingCpuOps(compat, Ae2MachineReads.NONE, cache);
        Map<String, Object> extra = cpuOps.extraReadings(AT, null);
        assertEquals("player_only", extra.get("scheduling_mode"));
        assertEquals(true, extra.get("cant_store_items"));
        assertTrue(extra.containsKey("storage_warning"));

        List<MachineSetting> cpuSettings = cpuOps.settings(AT, MachineTestFakes.offlineServices())
                .orElseThrow().settings(AT);
        assertEquals(2, cpuSettings.size());
        assertEquals("scheduling_mode", cpuSettings.get(0).key());
        assertEquals(List.of("any", "player_only", "machine_only"), cpuSettings.get(0).choices());
        assertEquals("job_action", cpuSettings.get(1).key());
        assertEquals(List.of("cancel", "toggle_pause"), cpuSettings.get(1).choices());
        assertFalse(cpuOps.settings(AT, MachineTestFakes.offlineServices()).orElseThrow()
                .change(AT, "job_action", "invalid", Permissions.DEFAULT).isPresent());
    }
}
