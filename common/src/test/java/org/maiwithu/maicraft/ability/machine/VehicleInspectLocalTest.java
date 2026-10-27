// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.ability.machine.spi.StructureWorksites;
import org.maiwithu.maicraft.ability.vehicle.api.VehicleControls;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleReader;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.construction.ConstructionServices;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.construction.ReadsBlocks;
import org.maiwithu.maicraft.behavior.recipe.GameRecipeTable;
import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * machine_inspect 看载具时的结构局部坐标：座位、配重建议的世界位置按位姿落回地块再减去原点；
 * 指跟在船上的档案时按局部坐标整机比对，没对上的格写局部坐标。认不出工地时没有这一节。
 */
class VehicleInspectLocalTest {

    /** 地块里的格 = 世界里的格 + 这一截（结构停着没转）。 */
    private static final BlockPos SHIFT = new BlockPos(20_000_000, 36, 20_000_000);
    private static final BlockPos ORIGIN = new BlockPos(20_000_010, 100, 20_000_010);

    @TempDir
    Path tempDir;

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 停着的船：地块里的格就是世界里的格挪一截。 */
    private static final class Site implements StructureWorksites.Worksite {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        @Override public String structureId() { return "ship-1"; }
        @Override public boolean present() { return true; }
        @Override public boolean moving() { return false; }
        @Override public String name() { return ""; }
        @Override public Optional<BlockPos> assembler() { return Optional.of(ORIGIN); }
        @Override public BlockPos plotCenter() { return ORIGIN; }
        @Override public BlockPos min() { return ORIGIN.offset(-2, 0, -2); }
        @Override public BlockPos max() { return ORIGIN.offset(2, 3, 2); }
        @Override public BlockPos plotCellAt(Vec3 world) { return BlockPos.containing(world).offset(SHIFT); }
        @Override public ConstructionServices services(ConstructionServices onLand, Permissions permissions) { return onLand; }
        @Override public BringsPlayerClose close() { return (target, permissions) -> null; }
        @Override public void forgetScan() { }

        @Override public ReadsBlocks blocks() {
            return new ReadsBlocks() {
                @Override public boolean loaded(BlockPos pos) { return true; }
                @Override public BlockState state(BlockPos pos) { return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
            };
        }
    }

    /** 读载具替身：一个座位，配重建议一处，都按世界坐标（格心）给。 */
    private static final class Reader implements VehicleReader {
        @Override public Optional<View> read(String structureId) {
            return Optional.of(new View(structureId, "", "airship", true, List.of(new Seat(-9.5, 64.5, -9.5, true)),
                    List.of(), new State(false, 0, 0, 0, 0, "grounded", ""), ""));
        }

        @Override public Optional<BalanceQuery> balance(String structureId, boolean ballast) {
            return Optional.of(() -> Optional.of(new Balance(true, "", 100, 1.2, false, List.of(),
                    List.of(new Ballast(-7.5, 64.5, -10.5, "minecraft:iron_block", 8, 3, 1)), "")));
        }
    }

    private TaskResult run(VehicleInspectTask.Input input) {
        DocumentStore documents = new DocumentStore(tempDir.resolve("state.sqlite"));
        MachineVehicles vehicles = new MachineVehicles(List.of(new Reader()), new VehicleControls(documents, "w"),
                id -> Optional.of("ship-1"), List.of(), id -> Optional.empty(), List.of());
        MachineServices services = new MachineServices(() -> null, new MachineTestDoubles.FakeWorld(), List.of(), List.of(),
                new MachineTestDoubles.FakeArea(), new RecipeLookup(List.of(), new GameRecipeTable(Optional::empty)),
                null, null, new MachineTestDoubles.FakeClicks(), new MachineTestDoubles.FakeSeen(),
                new MachineTestDoubles.FakePlaces());
        VehicleInspectTask task = new VehicleInspectTask(input, vehicles, services);
        task.start(TickContext.of(0, null));
        for (long tick = 1; tick < 50; tick++) {
            if (task.tick(TickContext.of(tick, null)) instanceof TickResult.Finished finished) return finished.result();
        }
        throw new AssertionError("五十刻没看完");
    }

    @Test
    void 座位与配重建议按局部坐标给_原点说清是哪一格() {
        Site site = new Site();
        TaskResult result = run(new VehicleInspectTask.Input("ship-1", "s1", "ballast", site,
                new StructureOrigin(ORIGIN, StructureOrigin.ASSEMBLER, null), null));
        VehicleInspectTask.Details details = (VehicleInspectTask.Details) result.details();
        VehicleInspectTask.Local local = details.local();
        assertTrue(local.origin().contains("物理组装器"), local.origin());
        // 座位世界格 (-10, 64, -10) → 地块 (19999990, 100, 19999990)，原点在东南十格。
        assertEquals(List.of(new BlockPos(-20, 0, -20)), local.seats());
        assertEquals(new BlockPos(-18, 0, -21), local.ballast().getFirst().at());
        assertEquals(new BlockPos(-2, 0, -2), local.min());
        assertTrue(result.summary().contains("局部坐标的原点是结构上的物理组装器"), result.summary());
        assertNull(details.archiveCheck(), "指 s# 不比档案");
    }

    @Test
    void 指跟在船上的档案_按局部坐标整机比对() {
        Site site = new Site();
        site.blocks.put(ORIGIN, Blocks.OAK_PLANKS.defaultBlockState());
        MachineArchive archive = MachineArchive.freshOn("ship-1", "飞艇", "minecraft:overworld", ORIGIN,
                new MachineBlueprint(List.of(PlannedCell.block(BlockPos.ZERO, Blocks.OAK_PLANKS.defaultBlockState(), Set.of()),
                        PlannedCell.block(new BlockPos(1, 0, 0), Blocks.IRON_BLOCK.defaultBlockState(), Set.of())),
                        List.of(), List.of(), List.of(), List.of()));
        TaskResult result = run(new VehicleInspectTask.Input("ship-1", "档案「飞艇」所在的结构", null, site,
                new StructureOrigin(ORIGIN, StructureOrigin.ARCHIVE, "飞艇"), archive));
        ArchiveCheck.Result check = ((VehicleInspectTask.Details) result.details()).archiveCheck();
        assertEquals(1, check.differences().size());
        assertEquals(new BlockPos(1, 0, 0), check.differences().getFirst().at(), "没对上的格写局部坐标");
        assertTrue(result.summary().contains("和档案「飞艇」比：1 格没对上"), result.summary());
    }

    @Test
    void 认不出工地_没有局部坐标一节() {
        TaskResult result = run(new VehicleInspectTask.Input("ship-1", "s1", null));
        assertNull(((VehicleInspectTask.Details) result.details()).local());
    }
}
