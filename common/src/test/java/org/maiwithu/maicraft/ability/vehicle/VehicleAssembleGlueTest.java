// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.ability.machine.api.ArchivedMachines;
import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.ability.machine.spi.PartCell;
import org.maiwithu.maicraft.ability.vehicle.api.VehicleControls;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleAssembler;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 组装前补胶：组装器在机器档案里时先把缺的胶一框框补上再拉杆；已经盖全直接拉；不在档案里照旧直接拉；
 * 补胶没补成就不拉杆，照实说已经粘了哪些、还差哪些。用替身组装办法、档案与机器类型推任务，不进世界。
 */
class VehicleAssembleGlueTest {

    private static final BlockPos ASSEMBLER = new BlockPos(3, 65, 3);
    private static final Set<BlockPos> SHIP = Set.of(new BlockPos(3, 64, 3), ASSEMBLER);
    private static final TickContext TICK = TickContext.of(0, null);

    @TempDir
    Path folder;

    @Test
    void 缺胶先补上再拉杆() {
        Installation segment = new Installation("test:glue", List.of(ASSEMBLER, new BlockPos(3, 64, 3)));
        Glue types = new Glue(List.of(segment), true);
        Pull pull = new Pull();
        TaskResult result = run(pull, archived("飞艇"), types);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertTrue(pull.pulled, "补完胶拉了杆");
        assertEquals(List.of(segment), types.installedSegments, "缺的那一框粘上了");
        VehicleAssembleDetails.Glue glue = details(result).glued();
        assertEquals("飞艇", glue.archive());
        assertEquals(1, glue.added().size());
        assertTrue(result.summary().startsWith("先补了 1 框胶"), result.summary());
    }

    @Test
    void 两家都能粘_先用先后值大的那家() {
        Installation superSegment = new Installation("create:super_glue", List.of(ASSEMBLER, new BlockPos(3, 64, 3)));
        Installation honeySegment = new Installation("simulated:honey_glue", List.of(ASSEMBLER, new BlockPos(3, 64, 3)));
        Glue superGlue = new Glue("create:super_glue", List.of(superSegment), true, 0, true);
        Glue honey = new Glue("simulated:honey_glue", List.of(honeySegment), true, 1, true);
        Pull pull = new Pull();
        TaskResult result = run(pull, archived("飞艇"), List.of(superGlue, honey));
        assertTrue(pull.pulled);
        assertEquals(List.of(honeySegment), honey.installedSegments, "身上有蜂蜜胶就先用它");
        assertTrue(superGlue.installedSegments.isEmpty(), "强力胶这次没用上");
        assertTrue(details(result).glued().added().getFirst().startsWith("simulated:honey_glue"),
                "结果写明是哪种胶：" + details(result).glued().added());
    }

    @Test
    void 先问的那家答不上_交给下一家() {
        Installation superSegment = new Installation("create:super_glue", List.of(ASSEMBLER, new BlockPos(3, 64, 3)));
        Glue superGlue = new Glue("create:super_glue", List.of(superSegment), true, 0, true);
        Glue honey = new Glue("simulated:honey_glue", List.of(), true, 1, false);
        Pull pull = new Pull();
        run(pull, archived("飞艇"), List.of(superGlue, honey));
        assertTrue(pull.pulled);
        assertEquals(List.of(superSegment), superGlue.installedSegments, "身上没有蜂蜜胶就用强力胶");
    }

    @Test
    void 胶已经盖全_直接拉杆() {
        Glue types = new Glue(List.of(), true);
        Pull pull = new Pull();
        TaskResult result = run(pull, archived("飞艇"), types);
        assertTrue(pull.pulled);
        assertTrue(types.installedSegments.isEmpty(), "没有要补的就不粘");
        assertEquals("档案里的方块胶已经盖全", details(result).glued().note());
    }

    @Test
    void 组装器不在档案里_照旧直接拉杆() {
        Glue types = new Glue(List.of(new Installation("test:glue", List.of(ASSEMBLER, ASSEMBLER.below()))), true);
        Pull pull = new Pull();
        TaskResult result = run(pull, (dimension, cell) -> Optional.empty(), types);
        assertTrue(pull.pulled);
        assertTrue(types.asked.isEmpty(), "没有档案就不问胶");
        VehicleAssembleDetails.Glue glue = details(result).glued();
        assertNull(glue.archive());
        assertTrue(glue.note().contains("没查胶"), glue.note());
    }

    @Test
    void 补胶没补成_不拉杆照实说() {
        Installation first = new Installation("test:glue", List.of(ASSEMBLER, new BlockPos(3, 64, 3)));
        Installation second = new Installation("test:glue", List.of(new BlockPos(4, 64, 3), new BlockPos(3, 64, 3)));
        Glue types = new Glue(List.of(first, second), false);
        Pull pull = new Pull();
        TaskResult result = run(pull, archived("飞艇"), types);
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertFalse(pull.pulled, "胶没粘上就不拉杆");
        assertEquals(Problem.Kind.REFUSED_BY_GAME, result.problem().kind());
        assertTrue(result.problem().message().startsWith("补胶没补成，没拉杆"), result.problem().message());
        VehicleAssembleDetails.Glue glue = details(result).glued();
        assertTrue(glue.added().isEmpty());
        assertEquals(2, glue.remaining().size(), "没粘上的这一框和后面那一框都列出来");
    }

    @Test
    void 拆回不查胶() {
        Glue types = new Glue(List.of(new Installation("test:glue", List.of(ASSEMBLER, ASSEMBLER.below()))), true);
        Pull pull = new Pull();
        VehicleAssembleTask task = new VehicleAssembleTask(
                new VehicleAssembler.Request(false, ASSEMBLER, null, Permissions.DEFAULT), List.of(request -> Optional.of(pull)),
                archives(), new AssemblyGlue(archived("飞艇"), List.of(types), () -> "minecraft:overworld"),
                new MachineArchiveFollow(archived("飞艇"), () -> "minecraft:overworld"));
        TaskResult result = finish(task);
        assertTrue(pull.pulled);
        assertTrue(types.asked.isEmpty());
        assertNull(details(result).glued());
    }

    private TaskResult run(Pull pull, ArchivedMachines archived, Glue types) {
        return run(pull, archived, List.of(types));
    }

    private TaskResult run(Pull pull, ArchivedMachines archived, List<MachineType> types) {
        VehicleAssembleTask task = new VehicleAssembleTask(
                new VehicleAssembler.Request(true, ASSEMBLER, null, Permissions.DEFAULT), List.of(request -> Optional.of(pull)),
                archives(), new AssemblyGlue(archived, types, () -> "minecraft:overworld"),
                new MachineArchiveFollow(archived, () -> "minecraft:overworld"));
        return finish(task);
    }

    private static TaskResult finish(VehicleAssembleTask task) {
        task.start(TICK);
        for (int i = 0; i < 200; i++) {
            if (task.tick(TICK) instanceof TickResult.Finished finished) return finished.result();
        }
        throw new AssertionError("任务 200 刻没结束");
    }

    private static VehicleAssembleDetails details(TaskResult result) {
        return assertInstanceOf(VehicleAssembleDetails.class, result.details());
    }

    private static ArchivedMachines archived(String name) {
        return (dimension, cell) -> SHIP.contains(cell) ? Optional.of(new ArchivedMachines.Machine(name, SHIP)) : Optional.empty();
    }

    private VehicleArchives archives() {
        DocumentStore documents = new DocumentStore(folder.resolve("world.db"));
        return new VehicleArchives(new VehicleControls(documents, "w"), documents, "w", List.of(),
                () -> "minecraft:overworld", id -> Optional.empty());
    }

    /** 组装办法替身：拉一次就转换成结构；组装器就在 ASSEMBLER。 */
    private static final class Pull implements VehicleAssembler.Run {
        boolean pulled;

        @Override public ActionStatus tick(TickContext context) {
            pulled = true;
            return ActionStatus.done();
        }

        @Override public VehicleAssembler.Conversion conversion() {
            return pulled ? new VehicleAssembler.Conversion(false, true, "ship", SHIP.size(), "") : null;
        }

        @Override public Optional<BlockPos> assembler() {
            return Optional.of(ASSEMBLER);
        }

        @Override public String describe() {
            return "替身拉杆";
        }
    }

    /** 会粘胶的机器类型替身：问缺什么时答还没粘上的那几段；粘的动作一刻做完，sticks 为假时胶不出现。 */
    private static final class Glue implements MachineType {
        private final String kind;
        private final List<Installation> needed;
        private final boolean sticks;
        private final int preference;
        private final boolean answers;
        final List<Installation> installedSegments = new ArrayList<>();
        final List<Set<BlockPos>> asked = new ArrayList<>();

        Glue(List<Installation> needed, boolean sticks) {
            this("test:glue", needed, sticks, 0, true);
        }

        /**
         * @param preference 先后值
         * @param answers    答不答"还缺哪几段"（蜂蜜胶身上没有时不答）
         */
        Glue(String kind, List<Installation> needed, boolean sticks, int preference, boolean answers) {
            this.kind = kind;
            this.needed = needed;
            this.sticks = sticks;
            this.preference = preference;
            this.answers = answers;
        }

        @Override public int stickPreference() {
            return preference;
        }

        @Override public Optional<List<Installation>> stickTogether(Set<BlockPos> cells) {
            asked.add(new HashSet<>(cells));
            if (!answers) return Optional.empty();
            return Optional.of(needed.stream().filter(segment -> !installedSegments.contains(segment)).toList());
        }

        @Override public Optional<Action> install(Installation installation, Permissions permissions) {
            if (!installation.kind().equals(kind)) return Optional.empty();
            return Optional.of(new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    if (sticks) installedSegments.add(installation);
                    return ActionStatus.done();
                }

                @Override public String describe() {
                    return "替身粘胶";
                }
            });
        }

        @Override public boolean installed(Installation installation) {
            return installedSegments.contains(installation);
        }

        @Override public String id() { return kind; }
        @Override public String name() { return "胶"; }
        @Override public String modId() { return "test"; }
        @Override public boolean covers(BlockState state) { return false; }
        @Override public MachineRole role() { return MachineRole.MOTION; }
        @Override public List<ExchangePoint> exchangePoints(BlockState state, BlockPos at) { return List.of(); }
        @Override public MachineState state(BlockPos at) { return MachineState.unknown("替身"); }
        @Override public Optional<String> installationProblem(Installation installation) { return Optional.empty(); }
        @Override public Optional<String> partProblem(PartCell part, BlockState host) { return Optional.empty(); }
        @Override public Optional<Action> mount(PartCell part, Permissions permissions) { return Optional.empty(); }
        @Override public boolean mounted(PartCell part) { return false; }
        @Override public List<MachineSetting> settings(BlockPos at) { return List.of(); }
        @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
            return Optional.empty();
        }
        @Override public Optional<Action> feed(BlockPos at, String itemId, int count, Permissions permissions) {
            return Optional.empty();
        }
        @Override public List<MachineState.Shown> output(BlockPos at) { return List.of(); }
        @Override public Optional<Action> take(BlockPos at, String itemId, int count, Permissions permissions) {
            return Optional.empty();
        }
    }
}
