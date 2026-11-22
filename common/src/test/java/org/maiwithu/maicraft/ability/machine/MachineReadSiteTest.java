// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.ability.vehicle.api.VehicleControls;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.recipe.GameRecipeTable;
import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.param.ParseResult;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/** 读现场成机器蓝图：默认纯只读抄蓝图不建档；显式 save_archive=true 且给名字才建档记世界记忆、名字被占不覆盖、指档案不另建。 */
class MachineReadSiteTest {

    private static final BlockPos PRESS = new BlockPos(2, 64, 3);
    private static final String DIM = "minecraft:overworld";

    @TempDir
    Path tempDir;

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 读现场默认纯只读_包围盒里的原版方块一起抄_不建档也不写世界记忆() {
        Rig rig = new Rig(tempDir);
        // L 形三格机器，包围盒里还夹着一格石头：石头不是机器也要抄进去。
        rig.machine(PRESS, PRESS.east(), PRESS.east().above());
        rig.blocks.put(PRESS.above(), Blocks.STONE.defaultBlockState());
        TaskResult result = rig.readSite(positionOf(PRESS), false, null);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        MachineDetails.SiteRead site = ((MachineDetails) result.details()).site();
        assertNull(site.archive(), "默认纯只读不生成自动档案名");
        assertFalse(site.archived(), "默认纯只读不保存永久档案");
        assertEquals(PRESS, site.anchor(), "锚点是包围盒最小角");
        assertEquals(List.of(2, 2, 1), site.size());
        assertEquals(4, site.cells(), "三格机器加一格石头");
        assertTrue(site.complete());
        assertTrue(rig.archives.find("机器@" + PRESS.getX() + "," + PRESS.getY() + "," + PRESS.getZ()).isEmpty());
        assertTrue(rig.memory.allRecords().stream().noneMatch(record -> record.kind() == MemoryKind.MACHINE));
        // 正文能原样喂回 machine_build。
        assertEquals(4, MachineBlueprints.parse(site.blueprint()).cells().size());
        assertTrue(result.summary().contains("读成了机器蓝图"), result.summary());
    }

    @Test
    void 显式save_archive为true并给名字_建档并记进世界记忆() {
        Rig rig = new Rig(tempDir);
        rig.machine(PRESS, PRESS.east(), PRESS.east().above());
        rig.blocks.put(PRESS.above(), Blocks.STONE.defaultBlockState());
        TaskResult result = rig.readSite(positionOf(PRESS), true, "压板机");
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        MachineDetails.SiteRead site = ((MachineDetails) result.details()).site();
        assertEquals("压板机", site.archive());
        assertTrue(site.archived());
        MachineArchive archive = rig.archives.find("压板机").orElseThrow();
        assertEquals(4, archive.blueprint().cells().size());
        assertTrue(rig.memory.allRecords().stream().anyMatch(record -> record.kind() == MemoryKind.MACHINE
                && "压板机".equals(record.blockType())));
    }

    @Test
    void target_不在机器上就挑最近的那一台() {
        Rig rig = new Rig(tempDir);
        rig.machine(PRESS);
        rig.machine(PRESS.east(5));
        TaskResult result = rig.readSite(positionOf(PRESS.east(4)), true, "远的那台");
        MachineDetails.SiteRead site = ((MachineDetails) result.details()).site();
        assertEquals(PRESS.east(5), site.anchor());
        assertEquals("远的那台", site.archive());
        assertTrue(site.archived());
    }

    @Test
    void 名字已经有档案在用_不覆盖_蓝图照给() {
        Rig rig = new Rig(tempDir);
        MachineBlueprint old = new MachineBlueprint(List.of(
                PlannedCell.block(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), Set.of())),
                List.of(), List.of(), List.of(), List.of());
        rig.archives.save(MachineArchive.fresh("压板机", DIM, new BlockPos(100, 64, 100), old, null));
        rig.machine(PRESS);
        TaskResult result = rig.readSite(positionOf(PRESS), true, "压板机");
        MachineDetails.SiteRead site = ((MachineDetails) result.details()).site();
        assertFalse(site.archived());
        assertTrue(site.notes().stream().anyMatch(note -> note.contains("已经有一台机器在用")), site.notes().toString());
        assertEquals(new BlockPos(100, 64, 100), rig.archives.find("压板机").orElseThrow().anchor(), "旧档案没被覆盖");
        assertEquals(1, site.cells());
    }

    @Test
    void 指档案读_不另建档_锚点用档案的() {
        Rig rig = new Rig(tempDir);
        MachineBlueprint archived = new MachineBlueprint(List.of(
                PlannedCell.block(BlockPos.ZERO, Blocks.FURNACE.defaultBlockState(), Set.of()),
                PlannedCell.block(new BlockPos(0, 1, 0), Blocks.STONE.defaultBlockState(), Set.of())),
                List.of(), List.of(), List.of(), List.of());
        MachineArchive archive = MachineArchive.fresh("压板机", DIM, PRESS, archived, null);
        rig.archives.save(archive);
        rig.machine(PRESS);
        rig.blocks.put(PRESS.above(), Blocks.DIRT.defaultBlockState());
        MachineInspectTask task = new MachineInspectTask(new MachineInspectInput(new Target.Landmark("压板机"), 8,
                archive, true, false, null), rig.services, rig.archives, rig.memory);
        MachineDetails details = (MachineDetails) rig.finish(task).details();
        assertEquals("压板机", details.site().archive());
        assertFalse(details.site().archived());
        assertEquals(PRESS, details.site().anchor());
        assertEquals(2, details.site().cells(), "档案格一起框进来：上面那格现在是泥土，照现场抄");
        assertEquals(1, details.check().differences().size(), "整机比对照给");
        assertTrue(rig.memory.allRecords().stream().noneMatch(record -> record.kind() == MemoryKind.MACHINE));
    }

    @Test
    void 一台认得的机器都没有_按没找到收场() {
        Rig rig = new Rig(tempDir);
        TaskResult result = rig.readSite(positionOf(PRESS), false, null);
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.NOT_FOUND, result.problem().kind());
    }

    @Test
    void 计划阶段校验save_archive与显式名字规则() {
        Rig rig = new Rig(tempDir);
        MachineVehicles noVehicles = new MachineVehicles(List.of(), new VehicleControls(rig.documents, "world1"),
                id -> Optional.empty(), List.of(), id -> Optional.empty(), List.of());
        MachineInspectModule module = new MachineInspectModule(rig.services, rig.archives, rig.memory, noVehicles);

        // 非 read_site 传 save_archive 直接报 INVALID_PARAMETER。
        JsonObject inspectWithFlag = new JsonObject();
        inspectWithFlag.addProperty("operation", "inspect");
        inspectWithFlag.addProperty("save_archive", true);
        TaskResult wrongOp = assertInstanceOf(StepDecision.Finish.class,
                decide(module, positionOf(PRESS), inspectWithFlag)).result();
        assertEquals(Problem.Kind.INVALID_PARAMETER, wrongOp.problem().kind());

        // read_site 默认不传 save_archive：生成纯只读任务输入，不建档。
        JsonObject defaultRead = new JsonObject();
        defaultRead.addProperty("operation", "read_site");
        MachineInspectInput readOnly = assertInstanceOf(MachineInspectInput.class,
                assertInstanceOf(StepDecision.Run.class, decide(module, positionOf(PRESS), defaultRead)).input());
        assertTrue(readOnly.readSite());
        assertFalse(readOnly.saveArchive());
        assertNull(readOnly.name());

        // read_site 传 save_archive=true 但无 name 且 target 为坐标：计划阶段直接报 INVALID_PARAMETER，绝不生成"机器@x,y,z"。
        JsonObject saveWithoutName = new JsonObject();
        saveWithoutName.addProperty("operation", "read_site");
        saveWithoutName.addProperty("save_archive", true);
        TaskResult missingName = assertInstanceOf(StepDecision.Finish.class,
                decide(module, positionOf(PRESS), saveWithoutName)).result();
        assertEquals(Problem.Kind.INVALID_PARAMETER, missingName.problem().kind());

        // read_site 传 save_archive=true 且 target 是记过的地标名（非已有档案）：用地标名建档。
        MachineInspectInput fromLandmark = assertInstanceOf(MachineInspectInput.class,
                assertInstanceOf(StepDecision.Run.class,
                        decide(module, new Target.Landmark("东区压机"), saveWithoutName)).input());
        assertTrue(fromLandmark.saveArchive());
        assertEquals("东区压机", fromLandmark.name());

        // read_site 传 save_archive=true 且 target 指已有档案：保持按已有档案读并比对，不要求 name。
        MachineBlueprint bp = new MachineBlueprint(List.of(
                PlannedCell.block(BlockPos.ZERO, Blocks.FURNACE.defaultBlockState(), Set.of())),
                List.of(), List.of(), List.of(), List.of());
        rig.archives.save(MachineArchive.fresh("压板机", DIM, PRESS, bp, null));
        MachineInspectInput existing = assertInstanceOf(MachineInspectInput.class,
                assertInstanceOf(StepDecision.Run.class,
                        decide(module, new Target.Landmark("压板机"), saveWithoutName)).input());
        assertEquals("压板机", existing.archive().name());
        assertNull(existing.name());
    }

    private static StepDecision decide(MachineInspectModule module, Target target, JsonObject raw) {
        ParseResult parsed = module.spec().paramSpecs().parse(raw);
        assertTrue(parsed.ok(), () -> parsed.errors().toString());
        ParamValues params = parsed.params();
        Goal goal = Goal.of("maicraft:machine_inspect", target, params);
        return module.decide(new StepContext() {
            @Override public Goal goal() { return goal; }
            @Override public int stepIndex() { return 0; }
            @Override public TickContext tick() { return null; }
        });
    }

    private static Target positionOf(BlockPos cell) {
        return new Target.Position(cell.getX(), cell.getY(), cell.getZ(), null);
    }

    /** 离线替身：认领熔炉的机器类型、按摆好的格给的范围读端与现场视图、真的档案库与世界记忆。 */
    private static final class Rig {
        final MachineTestDoubles.FakeMachineType type = new MachineTestDoubles.FakeMachineType("machinetest:press",
                "测试压机", MachineRole.PROCESSING, state -> state.is(Blocks.FURNACE));
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        final DocumentStore documents;
        final MachineArchives archives;
        final WorldMemory memory;
        final MachineServices services;
        private long ticks;

        Rig(Path tempDir) {
            documents = new DocumentStore(tempDir.resolve("state.sqlite"));
            archives = new MachineArchives(documents, "world1");
            memory = new WorldMemory(documents, "world1");
            ReadsMachineArea area = (dimension, center, radius) -> new ReadsMachineArea.Round(blocks.entrySet().stream()
                    .filter(entry -> !entry.getValue().isAir())
                    .map(entry -> new ReadsMachineArea.Cell(entry.getKey(), entry.getValue(), true)).toList(),
                    0, true, false);
            services = new MachineServices(() -> null, new MachineWorldView() {
                @Override public Optional<Spot> playerSpot() {
                    return Optional.of(new Spot(BlockPos.ZERO, DIM));
                }

                @Override public Optional<BlockState> stateAt(BlockPos at) {
                    return Optional.of(blocks.getOrDefault(at, Blocks.AIR.defaultBlockState()));
                }
            }, List.of(type), List.of(), area, new RecipeLookup(List.of(), new GameRecipeTable(Optional::empty)),
                    new MachineTestDoubles.FakeNeeds(), new MachineTestDoubles.FakeClose(),
                    new MachineTestDoubles.FakeClicks(), new MachineTestDoubles.FakeSeen(),
                    new MachineTestDoubles.FakePlaces());
        }

        void machine(BlockPos... cells) {
            for (BlockPos cell : cells) blocks.put(cell, Blocks.FURNACE.defaultBlockState());
        }

        TaskResult readSite(Target target, boolean saveArchive, String name) {
            return finish(new MachineInspectTask(new MachineInspectInput(target, 8, null, true, saveArchive, name),
                    services, archives, memory));
        }

        TaskResult finish(MachineInspectTask task) {
            task.start(tick());
            for (int i = 0; i < 200; i++) {
                if (task.tick(tick()) instanceof TickResult.Finished finished) return finished.result();
            }
            throw new AssertionError("两百刻都没读完");
        }

        TickContext tick() {
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
}
