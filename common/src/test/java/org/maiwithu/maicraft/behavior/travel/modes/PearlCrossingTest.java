// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.modes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.travel.spi.OfferSink;
import org.maiwithu.maicraft.behavior.travel.spi.TravelOffer;
import org.maiwithu.maicraft.behavior.travel.spi.TravelRequest;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickResult;
import org.maiwithu.maicraft.behavior.travel.settle.JourneyDetails;
import org.maiwithu.maicraft.behavior.travel.modes.EndIslands;
import org.maiwithu.maicraft.behavior.travel.planning.TravelWorldView;
import org.maiwithu.maicraft.behavior.travel.planning.TravelPlanner;
import org.maiwithu.maicraft.behavior.travel.planning.TravelDestination;
import org.maiwithu.maicraft.behavior.travel.planning.JourneyTask;
import org.maiwithu.maicraft.behavior.travel.modes.EndIslands;
import org.maiwithu.maicraft.behavior.travel.TravelFakes;
import org.maiwithu.maicraft.behavior.travel.DimensionFakes;

/**
 * 过折跃门：报价只在末地、隔着虚空时非它不可、要稀有品许可；一段里先备珍珠、走到站位、扔、等落到虚空另一边，
 * 扔歪了记一笔再扔一次，两次都歪就交回；站着看不见门洞换站位不算扔过。
 */
class PearlCrossingTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final String PEARL = PearlCrossingLeg.PEARL;
    /** 主岛那一圈正东那扇门，和正南那扇门。 */
    private static final BlockPos EAST_GATE = new BlockPos(96, 75, 0);
    private static final BlockPos SOUTH_GATE = new BlockPos(-1, 75, 96);
    /** 正东那扇门旁有人垫好的落脚处：门东边三格、脚低一格。 */
    private static final BlockPos STANCE = EAST_GATE.offset(3, -1, 0);
    private static final Permissions RARE = Permissions.DEFAULT.mergedWith(null, null, true, null, null, null);

    private final DimensionFakes.FakeWorld world = new DimensionFakes.FakeWorld();
    private final DimensionFakes.FakeWalks walks = new DimensionFakes.FakeWalks();
    private final DimensionFakes.FakeToMainhand hands = new DimensionFakes.FakeToMainhand();
    private final PearlSupply supply = new PearlSupply();
    private final DimensionFakes.Throws throwing = new DimensionFakes.Throws();
    private final DimensionTravelServices services = new DimensionTravelServices(world, () -> null, walks, supply,
            hands, throwing, null, null, null, null, null, null);
    private final TravelFakes.TestTick tick = new TravelFakes.TestTick();

    PearlCrossingTest() {
        world.dimension = DimensionRoute.END;
        world.feet = STANCE;
        gateAt(EAST_GATE);
        world.blocks.put(STANCE.below(), Blocks.END_STONE.defaultBlockState());
    }

    private void gateAt(BlockPos gate) {
        DimensionFakes.gateAt(world, gate);
    }

    private void carryPearls(int count) {
        world.stacks.removeIf(stack -> PEARL.equals(stack.itemId()));
        if (count > 0) world.stacks.add(new BackpackStack(PEARL, count, 16, false, false, true, false));
    }

    private int pearls() {
        return world.stacks.stream().filter(stack -> PEARL.equals(stack.itemId())).mapToInt(BackpackStack::count).sum();
    }

    private TravelRequest request(BlockPos to, Permissions permissions, String via) {
        return new TravelRequest(world.feet, to, world.dimension, 2, permissions, Map.of(), via);
    }

    private Answers ask(TravelRequest request) {
        Answers answers = new Answers();
        new PearlCrossing(services).offer(request, answers);
        return answers;
    }

    /** 推进一段到结束，最多这么多刻。 */
    private ActionStatus run(Action leg, int maxTicks) {
        for (int i = 0; i < maxTicks; i++) {
            ActionStatus status = leg.tick(tick);
            tick.nextTick();
            if (!(status instanceof ActionStatus.Running)) return status;
        }
        throw new AssertionError(maxTicks + " 刻内没有结束：" + leg.describe());
    }

    @Test
    void 不在末地_两头同一片_都不出报价() {
        world.dimension = DimensionRoute.OVERWORLD;
        Answers overworld = ask(request(new BlockPos(1100, 64, 0), RARE, null));
        assertEquals(Problem.Kind.UNSUPPORTED, overworld.declinedKind);
        assertTrue(overworld.reason.contains("只在末地"), overworld.reason);

        world.dimension = DimensionRoute.END;
        Answers sameSide = ask(request(new BlockPos(20, 64, 20), RARE, PearlCrossing.VIA.name()));
        assertEquals(Problem.Kind.UNSUPPORTED, sameSide.declinedKind);
        assertTrue(sameSide.reason.contains("两头都在主岛"), sameSide.reason);
        assertFalse(new PearlCrossing(services).claims(request(new BlockPos(20, 64, 20), RARE, null)));
    }

    @Test
    void use_rare_items关着也照样出报价_珍珠就是过门用的() {
        Answers answers = ask(request(new BlockPos(1100, 64, 0), Permissions.DEFAULT, null));
        assertEquals(null, answers.declinedKind, answers.reason);
        assertFalse(answers.offers.isEmpty(), "跨虚空时照样给过门的报价");
    }

    @Test
    void 隔着虚空非它不可_每扇活门一份报价_起点是门() {
        gateAt(SOUTH_GATE);
        PearlCrossing mode = new PearlCrossing(services);
        TravelRequest across = request(new BlockPos(1100, 64, 0), RARE, null);
        assertTrue(mode.claims(across), "主岛去外岛走路过不去");
        assertTrue(mode.worksIn(DimensionRoute.END));
        assertFalse(mode.worksIn(DimensionRoute.OVERWORLD));
        assertTrue(mode.goesWithoutDestination());

        Answers answers = ask(across);
        assertNull(answers.declinedKind);
        assertEquals(List.of(EAST_GATE, SOUTH_GATE), answers.offers.stream().map(TravelOffer::from).toList(),
                "离人近的在前，起点写门的位置");
        assertEquals(EndIslands.exitEstimate(EAST_GATE), answers.offers.get(0).to());
        // 行程挑过完门离目的地最近的那扇：正东那扇送到 (1024, 64, 0)，正南那扇送到 (0, 64, 1024)。
        assertTrue(TravelPlanner.total(answers.offers.get(0), new BlockPos(1100, 64, 0))
                < TravelPlanner.total(answers.offers.get(1), new BlockPos(1100, 64, 0)));
    }

    @Test
    void 门没扫完不答_扫完没有就说清看没看到那一圈() {
        world.blocks.clear();
        world.enqueueScan(new DimensionWorldView.Scan(List.of(), false));
        Answers waiting = ask(request(new BlockPos(1100, 64, 0), RARE, null));
        assertTrue(waiting.offers.isEmpty());
        assertNull(waiting.declinedKind, "没扫完不下没有的结论");

        Answers unseen = ask(request(new BlockPos(1100, 64, 0), RARE, null));
        assertEquals(Problem.Kind.NOT_FOUND, unseen.declinedKind);
        assertTrue(unseen.reason.contains("没加载"), unseen.reason);

        for (BlockPos slot : EndIslands.ringSlots()) world.blocks.put(slot, Blocks.AIR.defaultBlockState());
        Answers none = ask(request(new BlockPos(1100, 64, 0), RARE, null));
        assertTrue(none.reason.contains("一扇折跃门都没有"), none.reason);
    }

    @Test
    void 身上没珍珠先去弄一颗_扔一次就到了外岛_落地站稳() {
        carryPearls(0);
        throwing.plan.add(new DimensionFakes.Throw(null, () -> {
            carryPearls(pearls() - 1);
            world.feet = new BlockPos(1030, 62, 4);
        }));
        PearlCrossingLeg leg = new PearlCrossingLeg(EAST_GATE, RARE, services);

        assertInstanceOf(ActionStatus.Done.class, run(leg, 100));
        assertEquals(1, supply.asked.size(), "缺珍珠交拿东西的引擎弄一颗");
        assertEquals(1, supply.asked.get(0).count());
        assertEquals(List.of(PEARL), hands.moved);
        assertEquals(List.of(EAST_GATE), throwing.aimedAt);
        assertTrue(walks.started.isEmpty(), "本来就站在能扔的落脚处，不用走");
        assertEquals(1, leg.changes().stream().filter(change -> change.kind() == Change.Kind.ITEM_CONSUMED).count());
        assertTrue(leg.summary().contains("落在外岛"), leg.summary());
    }

    @Test
    void 扔歪一次_记一笔再扔一次过去了() {
        carryPearls(2);
        throwing.plan.add(new DimensionFakes.Throw(null, () -> carryPearls(pearls() - 1)));
        throwing.plan.add(new DimensionFakes.Throw(null, () -> {
            carryPearls(pearls() - 1);
            world.feet = new BlockPos(1030, 62, 4);
        }));
        PearlCrossingLeg leg = new PearlCrossingLeg(EAST_GATE, RARE, services);

        assertInstanceOf(ActionStatus.Done.class, run(leg, PearlCrossingLeg.WAIT_TICKS + 100));
        assertEquals(2, throwing.aimedAt.size());
        assertTrue(supply.asked.isEmpty(), "身上还有一颗，不用去弄");
        assertTrue(leg.summary().contains("第 1 颗珍珠从"), leg.summary());
        assertEquals(2, leg.changes().size(), "两颗珍珠都扔掉了");
    }

    @Test
    void 两次都没进门_交回走不通_两次经过都写上() {
        carryPearls(5);
        throwing.plan.add(new DimensionFakes.Throw(null, () -> carryPearls(pearls() - 1)));
        throwing.plan.add(new DimensionFakes.Throw(null, () -> carryPearls(pearls() - 1)));
        PearlCrossingLeg leg = new PearlCrossingLeg(EAST_GATE, RARE, services);

        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class,
                run(leg, 2 * PearlCrossingLeg.WAIT_TICKS + 100));
        assertEquals(Problem.Kind.UNREACHABLE, failed.problem().kind());
        String message = failed.problem().message();
        assertTrue(message.contains("第 1 颗") && message.contains("第 2 颗"), message);
        assertTrue(message.length() < 400, "两次经过各一句，长度有界：" + message.length());
        assertEquals(3, pearls(), "第三颗不扔");
    }

    @Test
    void 站着看不见门洞_换个站位再扔_不算扔过() {
        carryPearls(1);
        throwing.plan.add(new DimensionFakes.Throw(Problem.of(Problem.Kind.UNREACHABLE, "从当前位置看不到门洞"), null));
        throwing.plan.add(new DimensionFakes.Throw(null, () -> {
            carryPearls(0);
            world.feet = new BlockPos(1030, 62, 4);
        }));
        PearlCrossingLeg leg = new PearlCrossingLeg(EAST_GATE, RARE, services);

        assertInstanceOf(ActionStatus.Done.class, run(leg, 100));
        assertEquals(1, walks.started.size(), "换站位走了一趟");
        assertTrue(leg.summary().startsWith("过折跃门 " + EAST_GATE.toShortString() + "：扔了 1 颗"), leg.summary());
    }

    @Test
    void 不许垫方块又没有现成落脚处_说要放开许可垫方块() {
        world.blocks.remove(STANCE.below());
        world.feet = new BlockPos(80, 60, 0);
        carryPearls(1);
        Permissions noBlocks = RARE.mergedWith(Permissions.BlockChanges.NONE, null, null, null, null, null);
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class,
                run(new PearlCrossingLeg(EAST_GATE, noBlocks, services), 20));
        assertEquals(Problem.Kind.UNREACHABLE, failed.problem().kind());
        assertTrue(failed.problem().message().contains("change_blocks=none"), failed.problem().message());

        // 许可垫方块：把要垫一块才站得住的脚位一起交给走到。
        assertInstanceOf(ActionStatus.Running.class, new PearlCrossingLeg(EAST_GATE, RARE, services).tick(tick));
        PearlCrossingLeg leg = new PearlCrossingLeg(EAST_GATE, RARE, services);
        leg.tick(tick);
        leg.tick(tick);
        assertEquals(1, walks.started.size());
    }

    @Test
    void 珍珠弄不到_如实结束这一段() {
        carryPearls(0);
        supply.failure = Problem.of(Problem.Kind.NEED_ITEM, "附近没有末影人，也没有存着珍珠的箱子");
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class,
                run(new PearlCrossingLeg(EAST_GATE, RARE, services), 20));
        assertEquals(Problem.Kind.NEED_ITEM, failed.problem().kind());
        assertTrue(failed.problem().message().startsWith("备末影珍珠没成"), failed.problem().message());
    }

    @Test
    void 等传送时停下不安全() {
        carryPearls(1);
        throwing.plan.add(new DimensionFakes.Throw(null, () -> carryPearls(0)));
        PearlCrossingLeg leg = new PearlCrossingLeg(EAST_GATE, RARE, services);
        for (int i = 0; i < 10; i++) {
            leg.tick(tick);
            tick.nextTick();
        }
        assertEquals(Interruptibility.UNSAFE_TO_STOP, leg.interruptibility());
    }

    @Test
    void 走向站位悬在半空被叫先到安全处再停_落地站稳才停_不扔珍珠() {
        world.feet = new BlockPos(90, 64, 0);
        carryPearls(1);
        TravelFakes.ScriptedWalk walk = new TravelFakes.ScriptedWalk(
                WalkReport.onTheWay(new BlockPos(91, 65, 0), false),
                WalkReport.onTheWay(new BlockPos(92, 66, 0), false),
                WalkReport.onTheWay(new BlockPos(93, 66, 0), false),
                WalkReport.stopped(new BlockPos(93, 65, 0), false));
        walk.airborne = true;
        walks.enqueue(walk);
        PearlCrossingLeg leg = new PearlCrossingLeg(EAST_GATE, RARE, services);
        for (int i = 0; i < 3; i++) {
            leg.tick(tick);
            tick.nextTick();
        }
        assertEquals(1, walks.started.size(), "走向门旁的站位");
        assertEquals(Interruptibility.UNSAFE_TO_STOP, leg.interruptibility(), "垫柱跳到一半停不得");

        // 悬在半空被要求先到安全处再停：转给走路去落地，落地前这一段照常推进、不收掉半空的走路，落地才停下。
        leg.requestSafeStop();
        assertEquals(1, walk.safeStops, "先停稳转给了走路");
        assertInstanceOf(ActionStatus.Running.class, leg.tick(tick), "还在半空，不停");
        tick.nextTick();
        assertEquals(0, walk.closes, "还没落地，走路不收掉");
        ActionStatus.Failed stopped = assertInstanceOf(ActionStatus.Failed.class, run(leg, 10));
        assertTrue(stopped.problem().message().contains("按要求停下"), stopped.problem().message());
        assertEquals(WalkReport.State.STOPPED, walk.report().state(), "落地站稳了才停");
        assertTrue(throwing.aimedAt.isEmpty(), "停下前没扔珍珠");
    }

    @Test
    void 走向站位站在地上被叫先停稳_下一刻就停() {
        world.feet = new BlockPos(90, 64, 0);
        carryPearls(1);
        walks.enqueue(new TravelFakes.ScriptedWalk(
                WalkReport.onTheWay(new BlockPos(91, 64, 0), false),
                WalkReport.onTheWay(new BlockPos(92, 64, 0), false)));
        PearlCrossingLeg leg = new PearlCrossingLeg(EAST_GATE, RARE, services);
        for (int i = 0; i < 3; i++) {
            leg.tick(tick);
            tick.nextTick();
        }
        leg.requestSafeStop();
        ActionStatus.Failed stopped = assertInstanceOf(ActionStatus.Failed.class, leg.tick(tick));
        assertTrue(stopped.problem().message().contains("按要求停下"), stopped.problem().message());
    }

    @Test
    void 行程里隔着虚空只问它_挑朝着目的地的门_过去后走完剩下的路() {
        gateAt(SOUTH_GATE);
        carryPearls(1);
        throwing.plan.add(new DimensionFakes.Throw(null, () -> {
            carryPearls(0);
            world.feet = new BlockPos(1030, 62, 4);
        }));
        List<TravelDestination> walked = new ArrayList<>();
        TaskResult result = runJourney(new BlockPos(1100, 64, 0), walked, 200);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(List.of(EAST_GATE), throwing.aimedAt, "正东那扇送到的地方离目的地近");
        assertEquals(1, walked.size(), "过门后走完剩下的路");
        JourneyDetails details = assertInstanceOf(JourneyDetails.class, result.details());
        assertEquals(List.of("end_gateway", "walk"), details.legs().stream().map(JourneyDetails.Leg::via).toList());
    }

    @Test
    void 一扇门扔两颗都没过去_行程换下一扇() {
        gateAt(SOUTH_GATE);
        carryPearls(3);
        throwing.plan.add(new DimensionFakes.Throw(null, () -> carryPearls(pearls() - 1)));
        throwing.plan.add(new DimensionFakes.Throw(null, () -> carryPearls(pearls() - 1)));
        throwing.plan.add(new DimensionFakes.Throw(null, () -> {
            carryPearls(pearls() - 1);
            world.feet = new BlockPos(1030, 62, 4);
        }));
        List<TravelDestination> walked = new ArrayList<>();
        TaskResult result = runJourney(new BlockPos(1100, 64, 0), walked, 3 * PearlCrossingLeg.WAIT_TICKS + 300);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(List.of(EAST_GATE, EAST_GATE, SOUTH_GATE), throwing.aimedAt);
        JourneyDetails details = assertInstanceOf(JourneyDetails.class, result.details());
        assertEquals(List.of("end_gateway", "end_gateway", "walk"),
                details.legs().stream().map(JourneyDetails.Leg::via).toList());
        assertTrue(result.attempts().stream().anyMatch(attempt -> attempt.result().contains("都没过去")),
                "没过成的那扇门记进试过的办法");
    }

    /** 在主岛上跑一趟去外岛某处的行程：只登记折跃门这一种方式，走路段一刻就到。 */
    private TaskResult runJourney(BlockPos to, List<TravelDestination> walked, int maxTicks) {
        TravelWorldView spots = DimensionFakes.spots(world);
        JourneyTask journey = new JourneyTask(new JourneyTask.Journey(
                TravelDestination.confirmed(new WorldPosition(to.getX(), to.getY(), to.getZ(), DimensionRoute.END), 2),
                null, Map.of(), RARE, 0), spots, List.of(new PearlCrossing(services)), destination -> {
                    walked.add(destination);
                    return new DimensionFakes.ArrivingWalk();
                });
        journey.start(tick);
        for (int i = 0; i < maxTicks; i++) {
            if (journey.tick(tick) instanceof TickResult.Finished finished) return finished.result();
            tick.nextTick();
        }
        throw new AssertionError(maxTicks + " 刻内行程没有结束：" + journey.describe());
    }

    /** 收报价的口：记下报价，或说送不了的原因。 */
    private static final class Answers implements OfferSink {
        final List<TravelOffer> offers = new ArrayList<>();
        Problem.Kind declinedKind;
        String reason = "";

        @Override public void offer(TravelOffer offer) {
            offers.add(offer);
        }

        @Override public void decline(Problem.Kind kind, String reason) {
            this.declinedKind = kind;
            this.reason = reason;
        }
    }

    /** 拿珍珠的替身：弄到一颗就放进背包；也可以按给定问题弄不到。 */
    private final class PearlSupply implements ItemNeeds {
        final List<ItemRequest> asked = new ArrayList<>();
        Problem failure;

        @Override public Action actionFor(ItemRequest request, Permissions permissions) {
            asked.add(request);
            if (failure != null) return DimensionFakes.FakeObtain.failing(failure);
            return DimensionFakes.FakeObtain.immediate(() -> carryPearls(pearls() + request.count()));
        }
    }
}
