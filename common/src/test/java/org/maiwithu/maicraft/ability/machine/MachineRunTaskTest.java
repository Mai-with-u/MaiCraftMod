// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.FeedReport;
import org.maiwithu.maicraft.ability.machine.spi.FuelUse;
import org.maiwithu.maicraft.ability.machine.spi.IngredientFeed;
import org.maiwithu.maicraft.ability.machine.spi.FullRun;
import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.ability.machine.spi.RunRequest;
import org.maiwithu.maicraft.behavior.recipe.GameRecipeTable;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.behavior.recipe.ShownIngredient;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.behavior.recipe.ShownStack;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用机器做东西的任务：选工序、备料、投料、开机只拨一次、出口只算新增、收产物按 collect；
 * 出口在界面里的投完料隔一个周期点开看、几回没出收手、被叫走回来接着等。
 */
class MachineRunTaskTest {

    private static final BlockPos CELL = new BlockPos(6, 64, 6);
    private static final String INGOT = "test:ingot";
    private static final String PLATE = "test:plate";

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 一条压板配方：一台压机、一个铁锭进、一块铁板出。 */
    private static ShownRecipe pressingRecipe() {
        return new ShownRecipe("test:pressing/plate", "test:pressing", "压制",
                List.of(ShownStack.item("test:press", "测试压机", 1)),
                List.of(ShownIngredient.of(ShownStack.item(INGOT, "铁锭", 1))),
                List.of(), List.of(ShownStack.item(PLATE, "铁板", 1)), null);
    }

    @Test
    void 出够数量拿回背包_按完成收场() {
        Rig rig = new Rig();
        rig.machineRunsAtFullSpeed();
        TaskResult result = rig.run(new MachineRunInput(positionOf(CELL), PLATE, 2, true, 5,
                Permissions.DEFAULT));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        MachineRunDetails details = (MachineRunDetails) result.details();
        assertEquals(2, details.produced());
        assertEquals(2, details.collected());
        assertEquals("test:pressing", details.recipe().category());
        assertEquals(2, details.batches());
        assertEquals(1, details.fed().size());
        assertEquals(2, details.fed().get(0).count());
        assertTrue(result.changes().stream().anyMatch(change -> change.kind() == Change.Kind.ITEM_GAINED
                && PLATE.equals(change.what()) && change.count() == 2));
        assertTrue(result.changes().stream().anyMatch(change -> change.kind() == Change.Kind.ITEM_CONSUMED
                && INGOT.equals(change.what())));
        assertEquals(1, rig.needs.requests.size(), "一种原料一次备料请求，按批次放大");
        assertEquals(2, rig.needs.requests.get(0).count());
    }

    @Test
    void 机器停着_走近右键拨一次再等() {
        Rig rig = new Rig();
        rig.type.state = () -> new MachineState(MachineState.Activity.OFFLINE, null, null, List.of(),
                Map.of(), "");
        // 只开机：没给 item，拨完开关就算完成。
        TaskResult result = rig.run(new MachineRunInput(positionOf(CELL), null, 1, true, 5,
                Permissions.DEFAULT));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(1, rig.close.calls);
        assertEquals(List.of(CELL), rig.clicks.clicked);
        assertTrue(result.summary().contains("开机了"), result.summary());
    }

    @Test
    void 机器本来在转_不拨开关() {
        Rig rig = new Rig();
        rig.machineRunsAtFullSpeed();
        rig.type.state = () -> new MachineState(MachineState.Activity.RUNNING, null, null, List.of(),
                Map.of(), "");
        TaskResult result = rig.run(new MachineRunInput(positionOf(CELL), null, 1, true, 5,
                Permissions.DEFAULT));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(0, rig.clicks.clicked.size(), "在转就不拨");
    }

    @Test
    void 到等待上限还没出够_按部分完成附读数() {
        Rig rig = new Rig();
        rig.machineRunsAtFullSpeed();
        // 只出得出一半：投两批的料，出口只长出一件。
        rig.type.feedActions.clear();
        rig.type.feedActions.add(new MachineTestDoubles.Scripted(1,
                () -> rig.type.output.put(PLATE, rig.type.output.getOrDefault(PLATE, 0) + 1)));
        TaskResult result = rig.run(new MachineRunInput(positionOf(CELL), PLATE, 2, true, 1,
                Permissions.DEFAULT));
        assertEquals(TaskResult.Status.PARTIAL, result.status(), result.summary());
        assertEquals(1, ((MachineRunDetails) result.details()).produced());
        assertTrue(result.problem().message().contains("没等到全部产物"), result.problem().message());
    }

    @Test
    void collect为假_出了货也留在出口() {
        Rig rig = new Rig();
        rig.machineRunsAtFullSpeed();
        TaskResult result = rig.run(new MachineRunInput(positionOf(CELL), PLATE, 1, false, 5,
                Permissions.DEFAULT));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        // 机器投一次料出满一批（替身一次出两件）：出了就算数，不收就不该有拿到东西的变化。
        assertEquals(2, ((MachineRunDetails) result.details()).produced());
        assertEquals(0, ((MachineRunDetails) result.details()).collected());
        assertTrue(result.changes().stream().noneMatch(change -> change.kind() == Change.Kind.ITEM_GAINED),
                "不收就不该有拿到东西的变化");
    }

    @Test
    void 查不到这台机器能做的配方_按做不了说() {
        Rig rig = new Rig();
        rig.machineRunsAtFullSpeed();
        // 查看器只认另一台机器：压板配方挂在工作站 test:other 上。
        rig.viewer.putMaking(PLATE, List.of(new ShownRecipe("other:plate", "other", "其它",
                List.of(ShownStack.item("test:other", "别的机器", 1)),
                List.of(ShownIngredient.of(ShownStack.item(INGOT, "铁锭", 1))),
                List.of(), List.of(ShownStack.item(PLATE, "铁板", 1)), null)));
        TaskResult result = rig.run(new MachineRunInput(positionOf(CELL), PLATE, 1, true, 5,
                Permissions.DEFAULT));
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, result.problem().kind());
    }

    @Test
    void 认不出的机器_按不支持结束() {
        Rig rig = new Rig();
        rig.machineRunsAtFullSpeed();
        rig.world.blocks.put(CELL, Blocks.DISPENSER.defaultBlockState());
        TaskResult result = rig.run(new MachineRunInput(positionOf(CELL), PLATE, 1, true, 5,
                Permissions.DEFAULT));
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.UNSUPPORTED, result.problem().kind());
    }

    @Test
    void 备料拿不齐_按缺东西收场() {
        Rig rig = new Rig();
        rig.machineRunsAtFullSpeed();
        rig.needs.fail = true;
        TaskResult result = rig.run(new MachineRunInput(positionOf(CELL), PLATE, 1, true, 5,
                Permissions.DEFAULT));
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.NEED_ITEM, result.problem().kind());
    }

    @Test
    void 扔进去的投法按真进去的件数记_没进去的写进说明() {
        Rig rig = new Rig();
        rig.machineRunsAtFullSpeed();
        rig.type.feedActions.clear();
        rig.type.feedActions.add(new ReportedFeed(1, List.of("1 件落在 3,64,0，没落进 0,64,0"),
                () -> rig.type.output.put(PLATE, 2)));
        TaskResult result = rig.run(new MachineRunInput(positionOf(CELL), PLATE, 2, true, 5,
                Permissions.DEFAULT));
        MachineRunDetails details = (MachineRunDetails) result.details();
        assertEquals(1, details.fed().get(0).count(), "要投两件，进去一件就记一件");
        assertTrue(details.notes().contains("1 件落在 3,64,0，没落进 0,64,0"));
        assertTrue(result.changes().stream().anyMatch(change -> change.kind() == Change.Kind.ITEM_CONSUMED
                && INGOT.equals(change.what()) && change.count() == 1));
    }

    @Test
    void 出口在界面里_这里点不开界面_投料后就收手_不说做好了() {
        Rig rig = new Rig();
        rig.machineRunsAtFullSpeed();
        rig.type.outputVisible = false;
        // 替身没接界面：开工前看不了、回头也看不了，投完料照实收手。
        TaskResult result = rig.run(new MachineRunInput(positionOf(CELL), PLATE, 2, true, 5,
                Permissions.DEFAULT));
        assertEquals(TaskResult.Status.PARTIAL, result.status(), result.summary());
        assertEquals(Problem.Kind.UNSUPPORTED, result.problem().kind());
        MachineRunDetails details = (MachineRunDetails) result.details();
        assertEquals(2, details.fed().get(0).count(), "料照样投进去了");
        assertEquals(0, details.collected(), "看不见的出口不去收");
        assertTrue(details.notes().stream().anyMatch(note -> note.contains("use 打开")));
    }

    @Test
    void 发电机烧燃料_item当燃料备齐直接投_不查配方不拨开关() {
        Rig rig = new Rig();
        rig.type.fuel = FuelUse.fuel("能在熔炉里烧的东西");
        rig.type.state = () -> MachineState.unknown("在不在发电要打开界面看");
        rig.type.feedActions.add(new MachineTestDoubles.Scripted(1, () -> { }));
        // 查看器里一条配方都没有：烧燃料的机器不该去查。
        TaskResult result = rig.run(new MachineRunInput(positionOf(CELL), "minecraft:coal", 8, true, 5,
                Permissions.DEFAULT));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertTrue(result.summary().contains("8 件 minecraft:coal 当燃料"), result.summary());
        assertEquals(1, rig.needs.requests.size(), "燃料先备齐");
        assertEquals(8, rig.needs.requests.get(0).count());
        MachineRunDetails details = (MachineRunDetails) result.details();
        assertEquals(8, details.fed().get(0).count());
        assertTrue(details.notes().stream().anyMatch(note -> note.contains("能在熔炉里烧的东西")), details.notes().toString());
        assertEquals(0, rig.clicks.clicked.size(), "发电机有燃料自己烧，不去右键");
        assertTrue(result.changes().stream().anyMatch(change -> change.kind() == Change.Kind.ITEM_CONSUMED
                && "minecraft:coal".equals(change.what()) && change.count() == 8));
    }

    @Test
    void 燃料格只放进去一部分_按部分完成说清() {
        Rig rig = new Rig();
        rig.type.fuel = FuelUse.fuel("能在熔炉里烧的东西");
        rig.type.feedActions.add(new ReportedFeed(5, List.of("界面里放进去 5 件，要的是 8 件"), () -> { }));
        TaskResult result = rig.run(new MachineRunInput(positionOf(CELL), "minecraft:coal", 8, true, 5,
                Permissions.DEFAULT));
        assertEquals(TaskResult.Status.PARTIAL, result.status(), result.summary());
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, result.problem().kind());
        assertTrue(result.remaining().stream().anyMatch(line -> line.contains("3 件燃料")), result.remaining().toString());
    }

    @Test
    void 不吃料的发电机给了item_照实说不用投() {
        Rig rig = new Rig();
        rig.type.fuel = FuelUse.noFuel("靠阳光发电");
        TaskResult result = rig.run(new MachineRunInput(positionOf(CELL), "minecraft:coal", 1, true, 5,
                Permissions.DEFAULT));
        assertEquals(TaskResult.Status.FAILED, result.status(), result.summary());
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, result.problem().kind());
        assertTrue(result.problem().message().contains("靠阳光发电"), result.problem().message());
        assertEquals(0, rig.needs.requests.size(), "不用投就不去备料");
    }

    /** 一条灌注配方：一个铁锭加 10 份碳（查看器列成"别的东西"）出一块铁板。 */
    private static ShownRecipe infusingRecipe() {
        return new ShownRecipe("test:infusing/plate", "test:infusing", "灌注",
                List.of(ShownStack.item("test:press", "测试压机", 1)),
                List.of(ShownIngredient.of(ShownStack.item(INGOT, "铁锭", 1)),
                        ShownIngredient.of(new ShownStack(ShownStack.Kind.OTHER, "test:carbon", "碳", 10, 1))),
                List.of(), List.of(ShownStack.item(PLATE, "铁板", 1)), null);
    }

    @Test
    void 化学品能用物品换_换料排在主料前面先备先投() {
        Rig rig = new Rig();
        rig.viewer.putMaking(PLATE, List.of(infusingRecipe()));
        rig.type.ingredientFeed = ingredient -> Optional.of(new IngredientFeed("test:coal", 2, "往附加格投 test:coal"));
        rig.type.feedActions.add(new MachineTestDoubles.Scripted(1, () -> { }));
        rig.type.feedActions.add(new MachineTestDoubles.Scripted(1, () -> rig.type.output.put(PLATE, 2)));
        rig.type.takeActions.add(new MachineTestDoubles.Scripted(1, () -> { }));
        TaskResult result = rig.run(new MachineRunInput(positionOf(CELL), PLATE, 2, true, 5,
                Permissions.DEFAULT));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(List.of(20L), rig.type.ingredientAmounts, "要的量是配方的 10 份乘两批");
        assertEquals(List.of("test:coal", INGOT), rig.needs.requests.stream().map(request -> request.wanted().specifier())
                .toList(), "换料先备");
        assertEquals(List.of("test:coal", INGOT), rig.type.fedItems, "换料先投，再投主料");
        MachineRunDetails details = (MachineRunDetails) result.details();
        assertTrue(details.notes().contains("往附加格投 test:coal"), details.notes().toString());
        assertTrue(details.notes().stream().noneMatch(note -> note.contains("带不进机器")), details.notes().toString());
    }

    @Test
    void 化学品换不了_照旧写带不进机器() {
        Rig rig = new Rig();
        rig.viewer.putMaking(PLATE, List.of(infusingRecipe()));
        rig.type.feedActions.add(new MachineTestDoubles.Scripted(1, () -> rig.type.output.put(PLATE, 2)));
        rig.type.takeActions.add(new MachineTestDoubles.Scripted(1, () -> { }));
        TaskResult result = rig.run(new MachineRunInput(positionOf(CELL), PLATE, 1, true, 5,
                Permissions.DEFAULT));
        MachineRunDetails details = (MachineRunDetails) result.details();
        assertTrue(details.notes().stream().anyMatch(note -> note.contains("test:carbon") && note.contains("带不进机器")),
                details.notes().toString());
        assertEquals(List.of(INGOT), rig.type.fedItems);
    }

    @Test
    void 出口在界面里_投完料等一个周期再点开看_出了就收() {
        Rig rig = new Rig();
        long[] fedAt = rig.menuMachineMakes(2);
        TaskResult result = rig.run(new MachineRunInput(positionOf(CELL), PLATE, 2, true, 300,
                Permissions.DEFAULT));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        MachineRunDetails details = (MachineRunDetails) result.details();
        assertEquals(2, details.produced());
        assertEquals(2, details.collected());
        assertEquals(1, details.looks(), "出了就收，一回就够");
        assertEquals(List.of(false, true), rig.clicks.lookCollects, "开工前看一眼不收，回头看的那一眼收");
        assertTrue(rig.clicks.lookTicks.get(1) - fedAt[0] >= LookBackSchedule.DEFAULT_CYCLE_TICKS,
                "投完料至少等一个加工周期才回去看：" + rig.clicks.lookTicks + "，投料在第 " + fedAt[0] + " 刻");
        assertTrue(result.changes().stream().anyMatch(change -> change.kind() == Change.Kind.ITEM_GAINED
                && PLATE.equals(change.what()) && change.count() == 2));
    }

    @Test
    void 出口在界面里_开工前就有的不算这次做的() {
        Rig rig = new Rig();
        rig.menuMachineMakes(2);
        rig.clicks.menu.put(PLATE, 3);
        TaskResult result = rig.run(new MachineRunInput(positionOf(CELL), PLATE, 2, true, 300,
                Permissions.DEFAULT));
        MachineRunDetails details = (MachineRunDetails) result.details();
        assertEquals(2, details.produced(), "开工前看到的三件是旧货");
        assertEquals(5, details.collected(), "收的时候连旧货一起拿走了");
        assertTrue(details.notes().stream().anyMatch(note -> note.contains("开工前就在出口里")),
                details.notes().toString());
    }

    @Test
    void 出口在界面里_连着三回都没出_按部分完成收场附读数() {
        Rig rig = new Rig();
        rig.menuMachineMakes(0);
        TaskResult result = rig.run(new MachineRunInput(positionOf(CELL), PLATE, 2, true, 300,
                Permissions.DEFAULT));
        assertEquals(TaskResult.Status.PARTIAL, result.status(), result.summary());
        assertEquals(Problem.Kind.STUCK, result.problem().kind());
        assertTrue(result.problem().message().contains("连着 " + LookBackSchedule.EMPTY_LOOKS_LIMIT + " 回"),
                result.problem().message());
        assertTrue(result.problem().message().contains("机器读数"), result.problem().message());
        MachineRunDetails details = (MachineRunDetails) result.details();
        assertEquals(LookBackSchedule.EMPTY_LOOKS_LIMIT, details.looks());
        assertTrue(details.waitedSeconds()
                        >= LookBackSchedule.EMPTY_LOOKS_LIMIT * LookBackSchedule.DEFAULT_CYCLE_TICKS / 20,
                "等了 " + details.waitedSeconds() + " 秒");
        assertEquals(1, rig.needs.requests.size(), "料只备一回、投一回");
        assertTrue(result.summary().contains("看了 3 回"), result.summary());
    }

    @Test
    void 出口在界面里_等的时候被叫走_回来接着等同一批() {
        Rig rig = new Rig();
        rig.menuMachineMakes(2);
        MachineRunTask task = rig.start(new MachineRunInput(positionOf(CELL), PLATE, 2, true, 300,
                Permissions.DEFAULT));
        for (int i = 0; i < 50; i++) {
            rig.step(task);
        }
        // 投完料在等：手上没有动作，饿了吃一口、天黑去睡都插得进来。
        assertEquals(Interruptibility.BETWEEN_ACTIONS, task.interruptibility(rig.tick()));
        assertEquals(1, rig.clicks.lookTicks.size(), "还没到回头看的时候，只有开工前那一眼");
        task.pause();
        // 去吃饭、睡觉的这段时间游戏照走，机器照做。
        rig.ticks += 400;
        TaskResult result = rig.finish(task);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        MachineRunDetails details = (MachineRunDetails) result.details();
        assertEquals(2, details.collected());
        assertEquals(1, details.looks(), "回来到点了直接看一眼");
        assertEquals(1, details.fed().size(), "接着等同一批，不重投料");
        assertEquals(1, rig.needs.requests.size());
    }

    /** 机器自己从头做到尾的替身：一刻做完，报做出几件、做全没有与说明。 */
    private record WholeRun(int produced, boolean complete, List<String> notes) implements FullRun {
        @Override public ActionStatus tick(TickContext context) {
            return ActionStatus.done();
        }

        @Override public String describe() {
            return "整段替身";
        }
    }

    @Test
    void 机器自己从头做到尾_按它交回的件数结算() {
        Rig rig = new Rig();
        List<RunRequest> asked = new ArrayList<>();
        rig.type.fullRun = request -> {
            asked.add(request);
            return Optional.of(new WholeRun(1, true, List.of("上传完了")));
        };
        TaskResult result = rig.run(new MachineRunInput(positionOf(CELL), "create:schematic", 1, true, 5,
                Permissions.DEFAULT, "house.nbt", null, 0));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals("house.nbt", asked.getFirst().file());
        MachineRunDetails details = (MachineRunDetails) result.details();
        assertEquals(1, details.produced());
        assertTrue(details.notes().contains("上传完了"));
        assertTrue(result.changes().stream().anyMatch(change -> change.kind() == Change.Kind.ITEM_GAINED
                && "create:schematic".equals(change.what())));
    }

    @Test
    void 机器自己做到一半_按部分完成收场() {
        Rig rig = new Rig();
        rig.type.fullRun = request -> Optional.of(new WholeRun(5, false, List.of("缺 minecraft:stone")));
        TaskResult result = rig.run(new MachineRunInput(positionOf(CELL), null, 1, true, 5,
                Permissions.DEFAULT, "house.nbt", new BlockPos(10, 64, 10), 90));
        assertEquals(TaskResult.Status.PARTIAL, result.status(), result.summary());
        assertEquals(Problem.Kind.STUCK, result.problem().kind());
        assertEquals(5, ((MachineRunDetails) result.details()).produced());
    }

    @Test
    void 给了蓝图却不是收蓝图的机器_当场说() {
        Rig rig = new Rig();
        TaskResult result = rig.run(new MachineRunInput(positionOf(CELL), null, 1, true, 5,
                Permissions.DEFAULT, "house.nbt", null, 0));
        assertEquals(TaskResult.Status.FAILED, result.status(), result.summary());
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, result.problem().kind());
    }

    /** 交得出实情的投料动作替身：一刻做完，报进去几件与说明。 */
    private static final class ReportedFeed implements Action, FeedReport {
        private final int delivered;
        private final List<String> notes;
        private final Runnable onEnd;

        ReportedFeed(int delivered, List<String> notes, Runnable onEnd) {
            this.delivered = delivered;
            this.notes = notes;
            this.onEnd = onEnd;
        }

        @Override public ActionStatus tick(TickContext context) {
            onEnd.run();
            return ActionStatus.done();
        }

        @Override public int delivered() {
            return delivered;
        }

        @Override public List<String> notes() {
            return notes;
        }

        @Override public String describe() {
            return "扔料替身";
        }
    }

    private static Target positionOf(BlockPos cell) {
        return new Target.Position(cell.getX(), cell.getY(), cell.getZ(), null);
    }

    /** 一套离线替身：投料一完成出口就出满，机器一直读作在转。 */
    private static final class Rig {
        final MachineTestDoubles.FakeMachineType type =
                new MachineTestDoubles.FakeMachineType("test:press", "测试压机",
                        MachineRole.PROCESSING, state -> state.is(Blocks.FURNACE));
        final MachineTestDoubles.FakeWorld world = new MachineTestDoubles.FakeWorld();
        final MachineTestDoubles.FakeNeeds needs = new MachineTestDoubles.FakeNeeds();
        final MachineTestDoubles.FakeClose close = new MachineTestDoubles.FakeClose();
        final MachineTestDoubles.FakeClicks clicks = new MachineTestDoubles.FakeClicks();
        final MachineTestDoubles.FakeViewer viewer = new MachineTestDoubles.FakeViewer();
        final MachineServices services;

        Rig() {
            world.blocks.put(CELL, Blocks.FURNACE.defaultBlockState());
            type.state = () -> new MachineState(MachineState.Activity.RUNNING, null, null, List.of(),
                    Map.of(), "");
            services = new MachineServices(() -> null, world, List.of(type), List.of(),
                    new MachineTestDoubles.FakeArea(),
                    new RecipeLookup(List.of(viewer), new GameRecipeTable(Optional::empty)),
                    needs, close, clicks, new MachineTestDoubles.FakeSeen(),
                    new MachineTestDoubles.FakePlaces());
        }

        // 投料一完成，出口立刻长出要做的两件；取货一步收场。
        void machineRunsAtFullSpeed() {
            viewer.putMaking(PLATE, List.of(pressingRecipe()));
            viewer.putAtWorkstation("test:press", List.of(pressingRecipe()));
            type.feedActions.add(new MachineTestDoubles.Scripted(1,
                    () -> type.output.put(PLATE, type.output.getOrDefault(PLATE, 0) + 2)));
            type.takeActions.add(new MachineTestDoubles.Scripted(1, () -> { }));
        }

        /**
         * 出口在界面里的机器：开工前界面里是空的，投料一完成输出格就有 made 件（要回去点开才看得见）。
         *
         * @return 第几刻投的料（投完才写进去）
         */
        long[] menuMachineMakes(int made) {
            machineRunsAtFullSpeed();
            type.outputVisible = false;
            clicks.menu = new HashMap<>();
            clicks.now = () -> ticks;
            long[] fedAt = {-1};
            type.feedActions.clear();
            type.feedActions.add(new MachineTestDoubles.Scripted(1, () -> {
                fedAt[0] = ticks;
                if (made > 0) clicks.menu.merge(PLATE, made, Integer::sum);
            }));
            return fedAt;
        }

        TaskResult run(MachineRunInput input) {
            return finish(start(input));
        }

        MachineRunTask start(MachineRunInput input) {
            MachineRunTask task = new MachineRunTask(input, services);
            task.start(tick());
            return task;
        }

        TickResult step(MachineRunTask task) {
            return task.tick(tick());
        }

        TaskResult finish(MachineRunTask task) {
            for (int i = 0; i < 3000; i++) {
                if (step(task) instanceof TickResult.Finished finished) {
                    return finished.result();
                }
            }
            throw new AssertionError("三千刻都没做完");
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

        long ticks;
    }
}
