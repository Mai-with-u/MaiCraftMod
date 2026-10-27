// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.ability.machine.spi.StructureWorksites;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.construction.ConstructionServices;
import org.maiwithu.maicraft.behavior.construction.ReadsBlocks;
import org.maiwithu.maicraft.behavior.recipe.GameRecipeTable;
import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 在船上改机器的设置（船上的无线红石打字机绑键）：在结构的方块里找认这些设置项的那台，几台改离角色最近的，
 * 读改走结构上的那一套、靠近用保留座位的那一种，结果写局部坐标；船上没有认的照实说不支持。
 */
class MachineConfigureOnStructureTest {

    /** 局部坐标原点（结构上的物理组装器）所在的地块格。 */
    private static final BlockPos ORIGIN = new BlockPos(20_000_000, 100, 20_000_000);

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 停着的船：地块里的格就是测试里的格；两种靠近各给一个，看用的是哪一种。 */
    private static final class Site implements StructureWorksites.Worksite {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        final BringsPlayerClose reach = (target, permissions) -> null;
        final BringsPlayerClose keepingSeat = (target, permissions) -> null;

        @Override public String structureId() { return "ship-1"; }
        @Override public boolean present() { return true; }
        @Override public boolean moving() { return false; }
        @Override public String name() { return ""; }
        @Override public Optional<BlockPos> assembler() { return Optional.of(ORIGIN); }
        @Override public BlockPos plotCenter() { return ORIGIN; }
        @Override public BlockPos min() { return ORIGIN.offset(-1, -1, -1); }
        @Override public BlockPos max() { return ORIGIN.offset(6, 1, 1); }
        @Override public BlockPos plotCellAt(Vec3 world) { return BlockPos.containing(world); }
        @Override public ConstructionServices services(ConstructionServices onLand, Permissions permissions) { return onLand; }
        @Override public BringsPlayerClose close() { return reach; }
        @Override public BringsPlayerClose closeKeepingSeat() { return keepingSeat; }
        @Override public void forgetScan() { }

        @Override public ReadsBlocks blocks() {
            return new ReadsBlocks() {
                @Override public boolean loaded(BlockPos pos) { return true; }
                @Override public BlockState state(BlockPos pos) { return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
            };
        }
    }

    /** 机器类型替身认熔炉（当船上的无线红石打字机）；现场与角色站在哪由测试摆。 */
    private static final class Rig {
        final MachineTestDoubles.FakeMachineType type = new MachineTestDoubles.FakeMachineType("test:typewriter",
                "测试无线红石打字机", MachineRole.CONTROL, state -> state.is(Blocks.FURNACE));
        final MachineTestDoubles.FakeWorld world = new MachineTestDoubles.FakeWorld();
        final Site site = new Site();
        final MachineServices services = new MachineServices(() -> null, world, List.of(type), List.of(),
                new MachineTestDoubles.FakeArea(), new RecipeLookup(List.of(), new GameRecipeTable(Optional::empty)),
                new MachineTestDoubles.FakeNeeds(), new MachineTestDoubles.FakeClose(), new MachineTestDoubles.FakeClicks(),
                new MachineTestDoubles.FakeSeen(), new MachineTestDoubles.FakePlaces());
        private long ticks;

        Rig() {
            site.blocks.put(ORIGIN, Blocks.FURNACE.defaultBlockState());
            type.settingValues.put("key.W", "none");
            type.onChange.put("key.W", () -> type.settingValues.put("key.W", "minecraft:iron_ingot + minecraft:redstone"));
            world.playerAt = ORIGIN;
        }

        TaskResult run() {
            MachineConfigureInput input = new MachineConfigureInput(new Target.Seen("s1"),
                    Map.of("key.W", "minecraft:iron_ingot + minecraft:redstone"), Permissions.DEFAULT,
                    new OnStructure("ship-1", "s1", site, new StructureOrigin(ORIGIN, StructureOrigin.ASSEMBLER, null)));
            MachineConfigureTask task = new MachineConfigureTask(input, services);
            task.start(tick());
            for (int i = 0; i < 100; i++) {
                if (task.tick(tick()) instanceof TickResult.Finished finished) return finished.result();
            }
            throw new AssertionError("一百刻都没改完");
        }

        private TickContext tick() {
            long now = ++ticks;
            return new TickContext() {
                @Override public long gameTick() {
                    return now;
                }

                @Override public PlayerContext player() {
                    return null;
                }
            };
        }
    }

    @Test
    void 船上一台_改键读回相等_走过去用保留座位的靠近() {
        Rig rig = new Rig();
        rig.type.onStructure = true;
        TaskResult result = rig.run();
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary() + " / " + result.problem());
        assertSame(rig.site.keepingSeat, rig.type.structureReach, "坐着够得着就坐着改，够不着不离开座位");
        assertTrue(result.summary().contains("船上局部坐标 0, 0, 0 那台"), result.summary());
        assertEquals("minecraft:iron_ingot + minecraft:redstone",
                ((MachineConfigureDetails) result.details()).applied().getFirst().after());
    }

    @Test
    void 船上两台_改离角色最近的那台() {
        Rig rig = new Rig();
        rig.type.onStructure = true;
        BlockPos far = ORIGIN.east(5);
        rig.site.blocks.put(far, Blocks.FURNACE.defaultBlockState());
        rig.world.playerAt = far.west();
        TaskResult result = rig.run();
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary() + " / " + result.problem());
        assertTrue(rig.type.touched.contains("change-on-structure:key.W@" + far.toShortString()), rig.type.touched.toString());
        assertTrue(result.summary().contains("船上有 2 台，改的是离角色最近的这台"), result.summary());
        assertTrue(result.summary().contains("5, 0, 0"), "说的是局部坐标：" + result.summary());
    }

    @Test
    void 船上没有认这些设置项的机器_照实说不支持() {
        Rig rig = new Rig();
        TaskResult result = rig.run();
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.UNSUPPORTED, result.problem().kind());
        assertTrue(result.problem().message().contains("船上没有认"), result.problem().message());
        assertTrue(rig.type.touched.isEmpty(), "一下也没点");
    }

    @Test
    void 操纵表的键和机器设置的键分得开() {
        assertTrue(VehicleControlsTask.controlsKey("打字机:W"));
        assertTrue(VehicleControlsTask.controlsKey("打字机：空格"));
        assertTrue(VehicleControlsTask.controlsKey(" forward "));
        assertFalse(VehicleControlsTask.controlsKey("key.W"));
    }
}
