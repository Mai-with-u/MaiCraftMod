// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.LongPredicate;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.ability.machine.spi.StructureWorksites;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.construction.ConstructionServices;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.construction.ReadsBlocks;
import org.maiwithu.maicraft.behavior.recipe.GameRecipeTable;
import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 在组装好的物理结构上按机器蓝图施工：停稳才开工、一直在动照实结束一格不动、施工中动起来就停手照实说、
 * 档案记在结构上且变化按局部坐标写、部件与设置结构上还不会、安装段只用结构上的装法、结构没了档案标拆除。
 */
class MachineBuildOnStructureTest {

    private static final String DIM = "minecraft:overworld";
    /** 原点（结构上的物理组装器）在地块里的格：地块里的坐标是远处的大数。 */
    private static final BlockPos ORIGIN = new BlockPos(20_000_000, 100, 20_000_000);

    @TempDir
    Path tempDir;

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 两格铁块的配重：原点东边一格、西边一格。 */
    private static MachineBlueprint ballast() {
        BlockState iron = Blocks.IRON_BLOCK.defaultBlockState();
        return new MachineBlueprint(List.of(PlannedCell.block(new BlockPos(1, 0, 0), iron, Set.of()),
                PlannedCell.block(new BlockPos(-1, 0, 0), iron, Set.of())), List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void 结构在动_先等停稳再开工() {
        Rig rig = new Rig(tempDir);
        rig.worksite.moving = tick -> tick <= 30;
        MachineBuildTask task = rig.task(rig.newOnStructure(ballast()));
        for (int i = 0; i < 39; i++) rig.step(task);
        assertFalse(rig.world.blocks.containsKey(ORIGIN.east()), "还在动、停稳不满 10 刻时一格不放");
        TaskResult result = rig.finish(task);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary() + " / " + result.problem());
        assertEquals(Blocks.IRON_BLOCK, rig.world.blocks.get(ORIGIN.east()).getBlock());
    }

    @Test
    void 一直在动_两百刻后照实结束_一格不动() {
        Rig rig = new Rig(tempDir);
        rig.worksite.moving = tick -> true;
        TaskResult result = rig.finish(rig.task(rig.newOnStructure(ballast())));
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.STUCK, result.problem().kind());
        assertTrue(result.problem().message().contains("一直在动"), result.problem().message());
        assertFalse(rig.world.blocks.containsKey(ORIGIN.east()));
        assertTrue(rig.archives.find("飞艇").isEmpty(), "一格没动不建档");
    }

    @Test
    void 施工中动起来_停手照实说() {
        Rig rig = new Rig(tempDir);
        // 走过去要好几刻：施工正在半路时船被撞动了。
        rig.site.approach = new MachineTestDoubles.Scripted(40, () -> { });
        rig.worksite.moving = tick -> tick > 20;
        TaskResult result = rig.finish(rig.task(rig.newOnStructure(ballast())));
        assertEquals(TaskResult.Status.PARTIAL, result.status(), result.summary());
        assertEquals(Problem.Kind.STUCK, result.problem().kind());
        assertTrue(result.problem().message().contains("动起来了"), result.problem().message());
        assertEquals("ship-1", rig.archives.find("飞艇").orElseThrow().structure(), "补丁照样并进档案，记在结构上");
    }

    @Test
    void 档案记在结构上_变化按局部坐标写_细节给结构与原点() {
        Rig rig = new Rig(tempDir);
        TaskResult result = rig.finish(rig.task(rig.newOnStructure(ballast())));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary() + " / " + result.problem());
        MachineArchive archive = rig.archives.find("飞艇").orElseThrow();
        assertEquals("ship-1", archive.structure());
        assertEquals(ORIGIN, archive.anchor(), "锚点是局部坐标的原点（物理组装器）");
        for (Change change : result.changes()) {
            assertFalse(change.note().contains("20000"), "变化里不给地块里的大数：" + change.note());
        }
        assertTrue(result.changes().stream().anyMatch(change -> change.note().contains("1, 0, 0")), result.changes().toString());
        MachineBuildDetails details = (MachineBuildDetails) result.details();
        assertEquals("s1", details.structure());
        assertTrue(details.localOrigin().contains("物理组装器"), details.localOrigin());
        assertNull(details.anchor(), "地块里的锚点不给");
        assertTrue(rig.worksite.forgot, "施工完作废结构的逐格读结果");
    }

    @Test
    void 部件与设置_结构上还不会_不去点() {
        Rig rig = new Rig(tempDir);
        MachineBlueprint plan = new MachineBlueprint(ballast().cells(),
                List.of(new MachineBlueprint.Part(new BlockPos(1, 0, 0), Direction.NORTH, "test:part")), List.of(),
                List.of(new MachineBlueprint.Setting(new BlockPos(1, 0, 0), "mode", "fast")), List.of());
        TaskResult result = rig.finish(rig.task(rig.newOnStructure(plan)));
        assertEquals(TaskResult.Status.PARTIAL, result.status());
        MachineBuildDetails details = (MachineBuildDetails) result.details();
        assertFalse(details.parts().getFirst().mounted());
        assertEquals("结构上还不会装部件", details.parts().getFirst().note());
        assertEquals("1, 0, 0", details.parts().getFirst().at(), "部件的格写局部坐标");
        assertFalse(details.settings().getFirst().applied());
        assertTrue(rig.type.touched.stream().noneMatch(step -> step.startsWith("mount") || step.startsWith("change")),
                rig.type.touched.toString());
    }

    @Test
    void 安装段_只用结构上的装法() {
        Rig rig = new Rig(tempDir);
        MachineBlueprint plan = new MachineBlueprint(ballast().cells(), List.of(),
                List.of(new MachineBlueprint.Segment("test:glue", List.of(new BlockPos(-1, 0, 0), new BlockPos(1, 0, 0)))),
                List.of(), List.of());
        rig.type.structureInstallActions.add(new MachineTestDoubles.Scripted(1, () -> rig.type.installedOk = segment -> true));
        TaskResult result = rig.finish(rig.task(rig.newOnStructure(plan)));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary() + " / " + result.problem());
        assertTrue(rig.type.touched.contains("install-on-structure:test:glue"), rig.type.touched.toString());
        assertTrue(rig.type.touched.stream().noneMatch(step -> step.equals("install:test:glue")), "不走地上的装法");
        assertEquals(List.of("-1, 0, 0", "1, 0, 0"), ((MachineBuildDetails) result.details()).installations().getFirst().cells());
    }

    @Test
    void 拆整台拆到结构没了_档案标拆除() {
        Rig rig = new Rig(tempDir);
        rig.world.blocks.put(ORIGIN.east(), Blocks.IRON_BLOCK.defaultBlockState());
        rig.world.blocks.put(ORIGIN.west(), Blocks.IRON_BLOCK.defaultBlockState());
        MachineArchive archive = MachineArchive.freshOn("ship-1", "飞艇", DIM, ORIGIN, ballast());
        assertTrue(rig.archives.save(archive));
        // 最后一块拆掉后 Sable 删掉整个结构：两格都空了就看不到了。
        rig.worksite.present = () -> !rig.site.state(ORIGIN.east()).isAir() || !rig.site.state(ORIGIN.west()).isAir();
        MachineBuildInput remove = new MachineBuildInput(true, null, at(ORIGIN), "飞艇", archive, Permissions.DEFAULT, "拆",
                rig.on(new StructureOrigin(ORIGIN, StructureOrigin.ARCHIVE, "飞艇")));
        TaskResult result = rig.finish(rig.task(remove));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary() + " / " + result.problem());
        assertTrue(result.summary().contains("结构也没了"), result.summary());
        assertTrue(rig.archives.find("飞艇").orElseThrow().removed(), "档案标成已拆除");
    }

    private static WorldPosition at(BlockPos cell) {
        return new WorldPosition(cell.getX(), cell.getY(), cell.getZ(), DIM);
    }

    /** 结构上的工地替身：读写和地上那份同一张世界表，动没动、在不在按测试给的脚本答。 */
    private static final class FakeWorksite implements StructureWorksites.Worksite {
        final MachineBuildTaskTest.FakeSite site;
        final long[] now;
        LongPredicate moving = tick -> false;
        BooleanSupplier present = () -> true;
        boolean forgot;

        FakeWorksite(MachineBuildTaskTest.FakeSite site, long[] now) {
            this.site = site;
            this.now = now;
        }

        @Override public String structureId() { return "ship-1"; }
        @Override public boolean present() { return present.getAsBoolean(); }
        @Override public boolean moving() { return moving.test(now[0]); }
        @Override public String name() { return ""; }
        @Override public Optional<BlockPos> assembler() { return Optional.of(ORIGIN); }
        @Override public BlockPos plotCenter() { return ORIGIN; }
        @Override public BlockPos min() { return ORIGIN.offset(-3, -3, -3); }
        @Override public BlockPos max() { return ORIGIN.offset(3, 3, 3); }
        @Override public BlockPos plotCellAt(Vec3 world) { return BlockPos.containing(world); }
        @Override public ConstructionServices services(ConstructionServices onLand, Permissions permissions) { return onLand; }
        @Override public BringsPlayerClose close() { return site; }
        @Override public ReadsBlocks blocks() { return site; }
        @Override public void forgetScan() { forgot = true; }
    }

    /** 机器类型、现场、档案库、世界记忆与结构上的工地替身，一刻一刻推进施工。 */
    private static final class Rig {
        final MachineTestDoubles.FakeMachineType type = new MachineTestDoubles.FakeMachineType("test:press", "测试压机",
                MachineRole.PROCESSING, state -> state.is(Blocks.FURNACE));
        final MachineTestDoubles.FakeWorld world = new MachineTestDoubles.FakeWorld();
        final MachineBuildTaskTest.FakeSite site = new MachineBuildTaskTest.FakeSite(world.blocks);
        final long[] now = {0};
        final FakeWorksite worksite = new FakeWorksite(site, now);
        final MachineArchives archives;
        final WorldMemory memory;
        final MachineServices services;

        Rig(Path tempDir) {
            world.dimension = DIM;
            // 船身：原点那一格（物理组装器）是实心的，配重贴着它放。
            world.blocks.put(ORIGIN, Blocks.OAK_PLANKS.defaultBlockState());
            site.carried.put("minecraft:iron_block", 8);
            DocumentStore documents = new DocumentStore(tempDir.resolve("state.sqlite"));
            archives = new MachineArchives(documents, "world1");
            memory = new WorldMemory(documents, "world1");
            services = new MachineServices(() -> null, world, List.of(type), List.of(),
                    new MachineTestDoubles.FakeArea(), new RecipeLookup(List.of(), new GameRecipeTable(Optional::empty)),
                    site, site, new MachineTestDoubles.FakeClicks(), new MachineTestDoubles.FakeSeen(),
                    new MachineTestDoubles.FakePlaces());
        }

        OnStructure on(StructureOrigin origin) {
            return new OnStructure("ship-1", "s1", worksite, origin);
        }

        /** 结构上还没有档案：按原点（物理组装器）新建一份"飞艇"。 */
        MachineBuildInput newOnStructure(MachineBlueprint blueprint) {
            return new MachineBuildInput(false, blueprint, at(ORIGIN), "飞艇", null, Permissions.DEFAULT, "测试",
                    on(new StructureOrigin(ORIGIN, StructureOrigin.ASSEMBLER, null)));
        }

        MachineBuildTask task(MachineBuildInput input) {
            MachineBuildTask task = new MachineBuildTask(input, services, archives, memory, permissions -> site.services());
            task.start(tick());
            return task;
        }

        TickResult step(MachineBuildTask task) {
            return task.tick(tick());
        }

        TaskResult finish(MachineBuildTask task) {
            for (int i = 0; i < 5000; i++) {
                if (step(task) instanceof TickResult.Finished finished) return finished.result();
            }
            throw new AssertionError("五千刻都没做完");
        }

        private TickContext tick() {
            long tick = ++now[0];
            return new TickContext() {
                @Override public long gameTick() {
                    return tick;
                }

                @Override public PlayerContext player() {
                    return null;
                }
            };
        }
    }
}
