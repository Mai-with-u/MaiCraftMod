// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.interaction.DefaultInteractionSender;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.interaction.ScriptedInteractionSender;
import org.maiwithu.maicraft.game.interaction.SettledConfirmations;
import org.maiwithu.maicraft.kernel.result.RawTextLeaks;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;

/**
 * 瞄准交互动作的离线场景：镜头没转到位不出手；命中了别的换瞄准点再试；
 * 提交时冻结现场；确认的四种结论各自如实上报；任务收尾时未确认的提交按不确定交还。
 */
class AimAndInteractTest {

    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final BlockPos TARGET = new BlockPos(2, 64, 2);

    private static BlockHitResult hitAt(BlockPos pos) {
        return new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
    }

    private static AimAndInteract blockAction(InteractionTestFakes.FakeScene scene, InteractionConfirmation confirmation) {
        return new AimAndInteract(new InteractionTarget.BlockTarget(TARGET), confirmation, context -> scene);
    }

    /** 推进到动作提交为止（转头替身让第二刻就到位）；返回提交那刻的状态。 */
    private static ActionStatus runUntilSubmitted(AimAndInteract action, InteractionTestFakes.FakeContext context) {
        ActionStatus status = action.tick(context.asTickContext());
        context.advance();
        status = action.tick(context.asTickContext());
        context.advance();
        return status;
    }

    @Test
    void itDoesNotSubmitBeforeTheCameraActuallySettles() {
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        InteractionTestFakes.FakeContext context = new InteractionTestFakes.FakeContext().withSender(sender);
        InteractionTestFakes.FakeScene scene = new InteractionTestFakes.FakeScene(context);
        scene.ray = hitAt(TARGET);
        AimAndInteract action = blockAction(scene, c -> InteractionConfirmation.Verdict.APPLIED);
        // 第一刻只发出转头请求：发出转头不算转到位，不能出手。
        assertTrue(action.tick(context.asTickContext()) instanceof ActionStatus.Running);
        assertEquals(1, context.input.lookRequests.size(), "每刻都该朝着目标转头");
        assertTrue(sender.submissions.isEmpty(), "镜头没到位不能出手");
    }

    @Test
    void aBusyInteractionEntranceWaitsInsteadOfFailing() {
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        InteractionTestFakes.FakeContext context = new InteractionTestFakes.FakeContext().withSender(sender);
        InteractionTestFakes.FakeScene scene = new InteractionTestFakes.FakeScene(context);
        scene.ray = hitAt(TARGET);
        AimAndInteract action = blockAction(scene, c -> InteractionConfirmation.Verdict.APPLIED);
        // 提交那一刻交互入口不收（本刻不交回等待记录）：动作留在原地等待，不算失败，也不记提交。
        sender.busyThisTick = true;
        assertTrue(runUntilSubmitted(action, context) instanceof ActionStatus.Running);
        assertTrue(sender.submissions.isEmpty(), "没被收下的提交不留现场");
        assertNull(action.result());

        // 下一刻入口空出来了：原样再提交一次，按确认走完，全程没有异常把任务掀翻。
        sender.busyThisTick = false;
        assertTrue(action.tick(context.asTickContext()) instanceof ActionStatus.Running);
        assertEquals(1, sender.submissions.size(), "入口空出来后重新提交");
        sender.nextStatus = PendingInteraction.Status.CONFIRMED_APPLIED;
        context.advance();
        assertTrue(action.tick(context.asTickContext()) instanceof ActionStatus.Done);
        assertEquals(InteractionVerdict.APPLIED, action.result().verdict());
    }

    @Test
    void aSettledAimThatHitsTheTargetSubmitsAndConfirms() {
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        InteractionTestFakes.FakeContext context = new InteractionTestFakes.FakeContext().withSender(sender);
        InteractionTestFakes.FakeScene scene = new InteractionTestFakes.FakeScene(context);
        scene.ray = hitAt(TARGET);
        AimAndInteract action = blockAction(scene, c -> InteractionConfirmation.Verdict.PENDING);
        ActionStatus submitted = runUntilSubmitted(action, context);
        assertTrue(submitted instanceof ActionStatus.Running running && running.progressed(), "提交本刻算真实进展");
        assertEquals(1, sender.submissions.size());
        assertEquals(TARGET, sender.submissions.getFirst().hit().getBlockPos(), "提交的是核对过的那次命中");
        assertEquals(20, sender.submissions.getFirst().timeoutTicks());

        // 确认条件还每刻在等：留在原地等，不动第二次。
        assertTrue(action.tick(context.asTickContext()) instanceof ActionStatus.Running);
        assertNull(action.result());

        // 确认生效：动作完成，结论带上现场。
        sender.nextStatus = PendingInteraction.Status.CONFIRMED_APPLIED;
        context.advance();
        assertTrue(action.tick(context.asTickContext()) instanceof ActionStatus.Done);
        assertNotNull(action.result());
        assertEquals(InteractionVerdict.APPLIED, action.result().verdict());
        assertTrue(action.result().scene().contains("射线命中方块"), "现场要说明提交时射线命中了谁");
        assertEquals(Interruptibility.WORKING, action.interruptibility());
    }

    @Test
    void aBlockedCrosshairOnBlockUseSubmitsConstructedDirectHit() {
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        InteractionTestFakes.FakeContext context = new InteractionTestFakes.FakeContext().withSender(sender);
        InteractionTestFakes.FakeScene scene = new InteractionTestFakes.FakeScene(context);
        // 前景草叶或邻格凸边挡住了准星射线：到站位后的普通方块右键不再换站位，直接交构造好的目标格命中发包。
        scene.ray = hitAt(new BlockPos(3, 64, 2));
        AimAndInteract action = blockAction(scene, c -> InteractionConfirmation.Verdict.PENDING);
        ActionStatus submitted = runUntilSubmitted(action, context);
        assertTrue(submitted instanceof ActionStatus.Running running && running.progressed());
        assertEquals(1, sender.submissions.size());
        assertEquals(TARGET, sender.submissions.getFirst().hit().getBlockPos(), "直发包仍精准打在目标格上");
    }

    @Test
    void aHeldItemRayBlockedByWorldStillRetriesThenReportsUnreachable() {
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        InteractionTestFakes.FakeContext context = new InteractionTestFakes.FakeContext().withSender(sender);
        InteractionTestFakes.FakeScene scene = new InteractionTestFakes.FakeScene(context);
        // 桶舀水倒水、水上放船走手持物品自身射线：服务端按真实视线结算，射线没对上时照旧重试后报到不了。
        scene.ray = hitAt(new BlockPos(3, 64, 2));
        AimAndInteract action = new AimAndInteract(new InteractionTarget.BlockTarget(TARGET),
                AimAndInteract.Gesture.USE_HELD_ITEM, InteractionHand.MAIN_HAND,
                c -> InteractionConfirmation.Verdict.APPLIED, ctx -> scene);
        ActionStatus status = null;
        for (int i = 0; i < 40; i++) {
            status = action.tick(context.asTickContext());
            context.advance();
            if (status instanceof ActionStatus.Failed) break;
        }
        assertTrue(status instanceof ActionStatus.Failed, "物品真实射线被挡时必须按到不了收尾");
        assertTrue(failedMessage(status).contains("需要换站位"));
        assertTrue(failedMessage(status).contains("方块 3"));
        assertEquals("UNREACHABLE", failedKind(status));
        assertTrue(sender.submissions.isEmpty(), "桶类真实射线没对准绝不乱发包");
    }

    @Test
    void aSlowCameraSubmitsAfterShortTurnCapInsteadOfFailing() {
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        InteractionTestFakes.FakeContext context = new InteractionTestFakes.FakeContext().withSender(sender);
        // 镜头替身始终停在反方向（模拟低帧率或大角度转头慢）：前 6 刻保持平滑演出不出手，满 7 刻贴齐视角直接出手。
        InteractionTestFakes.FakeScene sluggishScene = new InteractionTestFakes.FakeScene(context);
        sluggishScene.fixedView = new Vec3(-1.0, 0.0, 0.0);
        sluggishScene.ray = hitAt(TARGET);
        AimAndInteract action = blockAction(sluggishScene, c -> InteractionConfirmation.Verdict.PENDING);
        for (int i = 1; i < AimAndInteract.TURN_MAX_TICKS; i++) {
            ActionStatus waiting = action.tick(context.asTickContext());
            assertTrue(waiting instanceof ActionStatus.Running running && !running.progressed(),
                    "未满短上限前继续等镜头转过去：第 " + i + " 刻");
            assertTrue(sender.submissions.isEmpty());
            context.advance();
        }
        ActionStatus submitted = action.tick(context.asTickContext());
        assertTrue(submitted instanceof ActionStatus.Running running && running.progressed(), "满短上限当刻直接出手");
        assertEquals(1, sender.submissions.size());
    }

    @Test
    void verdictsAreReportedFaithfully() {
        assertVerdict(PendingInteraction.Status.CONFIRMED_NOT_APPLIED, InteractionVerdict.NOT_APPLIED);
        assertVerdict(PendingInteraction.Status.DIVERGED, InteractionVerdict.UNEXPECTED);
        assertVerdict(PendingInteraction.Status.UNCERTAIN, InteractionVerdict.UNCONFIRMED);
    }

    private static void assertVerdict(PendingInteraction.Status scripted, InteractionVerdict expected) {
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        InteractionTestFakes.FakeContext context = new InteractionTestFakes.FakeContext().withSender(sender);
        InteractionTestFakes.FakeScene scene = new InteractionTestFakes.FakeScene(context);
        scene.ray = hitAt(TARGET);
        AimAndInteract action = blockAction(scene, c -> InteractionConfirmation.Verdict.PENDING);
        runUntilSubmitted(action, context);
        sender.nextStatus = scripted;
        ActionStatus status = action.tick(context.asTickContext());
        assertTrue(status instanceof ActionStatus.Failed, scripted + " 不该算成功");
        assertNotNull(action.result());
        assertEquals(expected, action.result().verdict());
        if (expected == InteractionVerdict.NOT_APPLIED) {
            assertEquals("REFUSED_BY_GAME", failedKind(status), "没生效按被游戏拒绝上报");
        }
        if (expected == InteractionVerdict.UNCONFIRMED) {
            assertEquals(Interruptibility.WORKING, action.interruptibility(), "已经收尾的动作不再挡打断");
        }
    }

    /**
     * 游戏没让这一下生效时交给上层的问题说明：目标格前后都按方块名说，确认记录是真实提交方到期收场的那句中文，
     * 不夹 Block{…} 这类方块对象串，也不夹英文句子。
     */
    @Test
    void aRefusedUseReadsAsChineseAndNamesTheBlock() {
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        InteractionTestFakes.FakeContext context = new InteractionTestFakes.FakeContext().withSender(sender);
        InteractionTestFakes.FakeScene scene = new InteractionTestFakes.FakeScene(context);
        scene.ray = hitAt(TARGET);
        scene.blocks.put(TARGET, Blocks.STONE.defaultBlockState());
        AimAndInteract action = blockAction(scene, c -> InteractionConfirmation.Verdict.PENDING);
        runUntilSubmitted(action, context);
        sender.nextStatus = PendingInteraction.Status.CONFIRMED_NOT_APPLIED;
        sender.nextDetail = SettledConfirmations.expiredWithoutChange();
        ActionStatus status = action.tick(context.asTickContext());
        assertEquals("REFUSED_BY_GAME", failedKind(status));
        String message = failedMessage(status);
        RawTextLeaks.assertNone(message);
        String stone = Blocks.STONE.getName().getString();
        assertTrue(message.contains("目标格提交前是" + stone + "，现在是" + stone), message);
    }

    /** 经真实的交互提交方点一下：离线调用原版必然出错，没能确认的问题说明同样是中文结论句。 */
    @Test
    void anUnconfirmedUseThroughTheRealSenderReadsAsChinese() {
        InteractionTestFakes.FakeContext context = new InteractionTestFakes.FakeContext();
        context.sender = new DefaultInteractionSender(null);
        InteractionTestFakes.FakeScene scene = new InteractionTestFakes.FakeScene(context);
        scene.ray = hitAt(TARGET);
        scene.blocks.put(TARGET, Blocks.STONE.defaultBlockState());
        AimAndInteract action = blockAction(scene, c -> InteractionConfirmation.Verdict.PENDING);
        runUntilSubmitted(action, context);
        ActionStatus status = action.tick(context.asTickContext());
        assertEquals("STUCK", failedKind(status));
        RawTextLeaks.assertNone(failedMessage(status));
        assertTrue(failedMessage(status).contains("确认记录：右键方块时游戏出错"), failedMessage(status));
    }

    @Test
    void closingBeforeConfirmationHandsBackTheUncertainSubmission() {
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        InteractionTestFakes.FakeContext context = new InteractionTestFakes.FakeContext().withSender(sender);
        InteractionTestFakes.FakeScene scene = new InteractionTestFakes.FakeScene(context);
        scene.ray = hitAt(TARGET);
        AimAndInteract action = blockAction(scene, c -> InteractionConfirmation.Verdict.PENDING);
        runUntilSubmitted(action, context);
        // 提交了但确认还没等到，任务就结束了：交还等待，结论如实记没能确认。
        assertEquals(Interruptibility.UNSAFE_TO_STOP, action.interruptibility(), "等确认时不能被打断");
        action.close();
        assertTrue(sender.retireCalled);
        assertNotNull(action.result());
        assertEquals(InteractionVerdict.UNCONFIRMED, action.result().verdict());
    }

    @Test
    void aLiquidCellIsPointedAtThroughItsSurface() {
        // 对水面用东西（实机：水道旁放船三连"看不到任何一面"）：液体格没有碰撞面，
        // 指没指到按流体自己的射线核对；指到了水面就走原生的那一下，放船成不成由原版结算。
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        InteractionTestFakes.FakeContext context = new InteractionTestFakes.FakeContext().withSender(sender);
        InteractionTestFakes.FakeScene scene = new InteractionTestFakes.FakeScene(context);
        scene.blocks.put(TARGET, Blocks.WATER.defaultBlockState());
        scene.liquidPointsAt.add(TARGET);
        // 原版准星不认流体：对水面的普通射线是未命中。
        scene.ray = BlockHitResult.miss(Vec3.atCenterOf(TARGET), Direction.UP, TARGET);
        AimAndInteract action = blockAction(scene, c -> InteractionConfirmation.Verdict.PENDING);
        ActionStatus submitted = runUntilSubmitted(action, context);
        assertTrue(submitted instanceof ActionStatus.Running running && running.progressed(), "指到水面就出手");
        assertEquals(1, sender.submissions.size());
        assertEquals(TARGET, sender.submissions.getFirst().hit().getBlockPos(), "提交的还是目标那一格");
    }

    @Test
    void aLiquidCellTheSightCannotReachStillAsksForAnotherStand() {
        // 视线实际没指到水面（比如隔着岸沿）：核对不过关，换瞄准点用尽后按到不了收尾，绝不出手。
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        InteractionTestFakes.FakeContext context = new InteractionTestFakes.FakeContext().withSender(sender);
        InteractionTestFakes.FakeScene scene = new InteractionTestFakes.FakeScene(context);
        scene.blocks.put(TARGET, Blocks.WATER.defaultBlockState());
        // 原版准星不认流体：对水面的普通射线是未命中，流体射线也没指到水面。
        scene.ray = BlockHitResult.miss(Vec3.atCenterOf(TARGET), Direction.UP, TARGET);
        AimAndInteract action = blockAction(scene, c -> InteractionConfirmation.Verdict.APPLIED);
        ActionStatus status = null;
        for (int i = 0; i < 40 && !(status instanceof ActionStatus.Failed); i++) {
            status = action.tick(context.asTickContext());
            context.advance();
        }
        assertEquals("UNREACHABLE", failedKind(status));
        assertTrue(sender.submissions.isEmpty(), "没核对过命中就绝不出手");
    }

    @Test
    void aHeldBoatOnWaterSubmitsAsUseItemWithoutABlockHit() {
        // 手持船对水 use（实机：点击提交了、船没放出去）：原版放船不点水那一格，
        // 是"使用手里物品"，船自己沿视线按流体的规则找落点。提交的因此是不带方块命中的那一下，
        // 成没成由确认逐刻核对（方块不变、界面不开，手上少一个船才是放成）。
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        InteractionTestFakes.FakeContext context = new InteractionTestFakes.FakeContext().withSender(sender);
        InteractionTestFakes.FakeScene scene = new InteractionTestFakes.FakeScene(context);
        scene.blocks.put(TARGET, Blocks.WATER.defaultBlockState());
        scene.itemPointsAt.add(TARGET);
        // 原版准星不认流体：对水面的普通射线是未命中，放船核对走的是流体射线那条。
        scene.ray = BlockHitResult.miss(Vec3.atCenterOf(TARGET), Direction.UP, TARGET);
        AimAndInteract action = new AimAndInteract(new InteractionTarget.BlockTarget(TARGET),
                AimAndInteract.Gesture.USE_HELD_ITEM, InteractionHand.MAIN_HAND,
                c -> InteractionConfirmation.Verdict.PENDING, player -> scene);
        ActionStatus submitted = runUntilSubmitted(action, context);
        assertTrue(submitted instanceof ActionStatus.Running running && running.progressed(), "指到水面就出手");
        assertEquals(1, sender.submissions.size());
        assertEquals(PendingInteraction.Kind.USE_ITEM, sender.submissions.getFirst().kind(),
                "放船走的是使用手里物品，不是右键水那一格");
        assertNull(sender.submissions.getFirst().hit(), "使用手里物品不带方块命中");
    }

    private static String failedMessage(ActionStatus status) {
        return ((ActionStatus.Failed) status).problem().message();
    }

    private static String failedKind(ActionStatus status) {
        return ((ActionStatus.Failed) status).problem().kind().name();
    }
    @Test
    void aHiddenBlockWithinReachConstructsDirectHitWhileOutOfReachFailsImmediately() {
        // 站位在服务端放行距离内但目标表面全被草叶遮挡：构造朝向眼睛最近面的精确命中转头提交。
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        InteractionTestFakes.FakeContext context = new InteractionTestFakes.FakeContext().withSender(sender);
        InteractionTestFakes.FakeScene scene = new InteractionTestFakes.FakeScene(context);
        scene.hidden.add(TARGET);
        AimAndInteract withinReach = blockAction(scene, c -> InteractionConfirmation.Verdict.PENDING);
        ActionStatus submitted = runUntilSubmitted(withinReach, context);
        assertTrue(submitted instanceof ActionStatus.Running running && running.progressed());
        assertEquals(1, sender.submissions.size());
        assertEquals(TARGET, sender.submissions.getFirst().hit().getBlockPos());

        // 真超出了服务端放行距离：不白转头，第一刻就按到不了交回换站位。
        ScriptedInteractionSender farSender = new ScriptedInteractionSender();
        InteractionTestFakes.FakeContext farContext = new InteractionTestFakes.FakeContext().withSender(farSender);
        InteractionTestFakes.FakeScene farScene = new InteractionTestFakes.FakeScene(farContext);
        BlockPos farBlock = new BlockPos(10, 64, 10);
        farScene.hidden.add(farBlock);
        AimAndInteract outOfReach = new AimAndInteract(new InteractionTarget.BlockTarget(farBlock),
                c -> InteractionConfirmation.Verdict.APPLIED, ctx -> farScene);
        ActionStatus status = outOfReach.tick(farContext.asTickContext());
        assertEquals("UNREACHABLE", failedKind(status));
        assertTrue(farContext.input.lookRequests.isEmpty(), "超出服务端放行距离时不白转头");
        assertTrue(farSender.submissions.isEmpty());
    }

    /** 挂在目标格北面的终端面板：12×12 像素、厚 2 像素，贴着这一格的北侧。 */
    private static final AABB PANEL = new AABB(2 + 2 / 16.0, 64 + 2 / 16.0, 2, 2 + 14 / 16.0, 64 + 14 / 16.0, 2 + 2 / 16.0);

    private static AimAndInteract partAction(InteractionTestFakes.FakeScene scene) {
        return new AimAndInteract(new InteractionTarget.BlockTarget(TARGET, PANEL),
                c -> InteractionConfirmation.Verdict.PENDING, context -> scene);
    }

    @Test
    void aPartIsClickedOnlyWhereTheHitLandsOnThePart() {
        // 看得见的部位在面板上、准星也落在面板上：出手，提交的命中点在面板里。
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        InteractionTestFakes.FakeContext context = new InteractionTestFakes.FakeContext().withSender(sender);
        InteractionTestFakes.FakeScene scene = new InteractionTestFakes.FakeScene(context);
        scene.visibleAt.put(TARGET, PANEL.getCenter());
        scene.ray = new BlockHitResult(PANEL.getCenter(), Direction.NORTH, TARGET, false);
        runUntilSubmitted(partAction(scene), context);
        assertEquals(1, sender.submissions.size());
        assertTrue(PANEL.contains(sender.submissions.getFirst().hit().getLocation()));
    }

    @Test
    void hittingTheCableCoreStillSubmitsDirectHitInsideThePart() {
        // 同一格、准星被线缆芯挡住：直发包直接用落在面板框内的精确命中提交，不再因线缆挡准星换站位。
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        InteractionTestFakes.FakeContext context = new InteractionTestFakes.FakeContext().withSender(sender);
        InteractionTestFakes.FakeScene scene = new InteractionTestFakes.FakeScene(context);
        scene.visibleAt.put(TARGET, PANEL.getCenter());
        scene.ray = hitAt(TARGET);
        ActionStatus submitted = runUntilSubmitted(partAction(scene), context);
        assertTrue(submitted instanceof ActionStatus.Running running && running.progressed());
        assertEquals(1, sender.submissions.size());
        assertTrue(PANEL.inflate(1.0e-3).contains(sender.submissions.getFirst().hit().getLocation()));
    }

    @Test
    void anOccludedPartWithinReachConstructsPartCenterHitWhileFarPartFails() {
        // 站位在距离内但可见射线只打到线缆芯：直发包按面板框中心构造精确命中提交。
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        InteractionTestFakes.FakeContext context = new InteractionTestFakes.FakeContext().withSender(sender);
        InteractionTestFakes.FakeScene scene = new InteractionTestFakes.FakeScene(context);
        ActionStatus submitted = runUntilSubmitted(partAction(scene), context);
        assertTrue(submitted instanceof ActionStatus.Running running && running.progressed());
        assertEquals(1, sender.submissions.size());
        assertTrue(PANEL.inflate(1.0e-3).contains(sender.submissions.getFirst().hit().getLocation()));

        // 超出放行距离的部件照旧不转头、报到不了。
        BlockPos farCell = new BlockPos(10, 64, 10);
        AABB farPanel = PANEL.move(8, 0, 8);
        ScriptedInteractionSender farSender = new ScriptedInteractionSender();
        InteractionTestFakes.FakeContext farContext = new InteractionTestFakes.FakeContext().withSender(farSender);
        InteractionTestFakes.FakeScene farScene = new InteractionTestFakes.FakeScene(farContext);
        ActionStatus status = new AimAndInteract(new InteractionTarget.BlockTarget(farCell, farPanel),
                c -> InteractionConfirmation.Verdict.PENDING, ctx -> farScene).tick(farContext.asTickContext());
        assertEquals("UNREACHABLE", failedKind(status));
        assertTrue(farSender.submissions.isEmpty());
    }

    @Test
    void aWholeCellPartIsTheWholeBlock() {
        // 部件框正好是整格：按整格方块瞄，外形伸出格外的部分（链条输送机的轮缘）也算点到；真部件照旧按框。
        assertNull(new InteractionTarget.BlockTarget(TARGET, new AABB(TARGET)).part());
        assertEquals(PANEL, new InteractionTarget.BlockTarget(TARGET, PANEL).part());
    }

    @Test
    void aFaceIsClickedWhereverTheHitFacesThatWay() {
        // 放方块点朝上的那一面：命中点在格子外的轮缘上也行，只要点中这一格、面朝上。
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        InteractionTestFakes.FakeContext context = new InteractionTestFakes.FakeContext().withSender(sender);
        InteractionTestFakes.FakeScene scene = new InteractionTestFakes.FakeScene(context);
        Vec3 flange = new Vec3(TARGET.getX() + 1.5, TARGET.getY() + 0.875, TARGET.getZ() + 0.5);
        scene.visibleAt.put(TARGET, flange);
        scene.ray = new BlockHitResult(flange, Direction.UP, TARGET, false);
        runUntilSubmitted(new AimAndInteract(InteractionTarget.BlockTarget.facing(TARGET, Direction.UP),
                c -> InteractionConfirmation.Verdict.PENDING, player -> scene), context);
        assertEquals(1, sender.submissions.size());
    }

    @Test
    void hittingAnotherFaceWithCrosshairStillSubmitsConstructedRequestedFace() {
        // 点名顶面而准星扫到了北面：直发包按点名的 UP 面构造精确命中提交，不会错放到北边邻格，也不白换站位。
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        InteractionTestFakes.FakeContext context = new InteractionTestFakes.FakeContext().withSender(sender);
        InteractionTestFakes.FakeScene scene = new InteractionTestFakes.FakeScene(context);
        scene.ray = new BlockHitResult(Vec3.atCenterOf(TARGET), Direction.NORTH, TARGET, false);
        AimAndInteract action = new AimAndInteract(InteractionTarget.BlockTarget.facing(TARGET, Direction.UP),
                c -> InteractionConfirmation.Verdict.PENDING, player -> scene);
        ActionStatus submitted = runUntilSubmitted(action, context);
        assertTrue(submitted instanceof ActionStatus.Running running && running.progressed());
        assertEquals(1, sender.submissions.size());
        assertEquals(Direction.UP, sender.submissions.getFirst().hit().getDirection());
        assertEquals(TARGET, sender.submissions.getFirst().hit().getBlockPos());
    }

    @Test
    void aReplaceableCellIsClickedWhateverFaceTheRayHits() {
        // 这一格里是能直接放进去的东西（雪层、草）：准星指中它的任何一面，原版右键都会把东西替换着放进这一格，
        // 不再因命中面不朝点名的方向就当被挡住——放格卡死时射线命中的正是要放的那格自己。
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        InteractionTestFakes.FakeContext context = new InteractionTestFakes.FakeContext().withSender(sender);
        InteractionTestFakes.FakeScene scene = new InteractionTestFakes.FakeScene(context);
        scene.blocks.put(TARGET, Blocks.SNOW.defaultBlockState());
        scene.ray = new BlockHitResult(Vec3.atCenterOf(TARGET), Direction.NORTH, TARGET, false);
        runUntilSubmitted(new AimAndInteract(InteractionTarget.BlockTarget.facing(TARGET, Direction.UP),
                c -> InteractionConfirmation.Verdict.PENDING, player -> scene), context);
        assertEquals(1, sender.submissions.size());
        assertEquals(TARGET, sender.submissions.getFirst().hit().getBlockPos(), "提交的就是这一格");
    }

    @Test
    void anEntityClickSettlesOnPassengerConfirmationWithoutAnyMenu() {
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        InteractionTestFakes.FakeContext context = new InteractionTestFakes.FakeContext().withSender(sender);
        InteractionTestFakes.FakeScene scene = new InteractionTestFakes.FakeScene(context);
        Entity boat = fakeBoat();
        scene.ray = new EntityHitResult(boat, boat.getBoundingBox().getCenter());
        // 骑乘的确认就是"角色真成了乘客"：确认条件一直还没满足时等，没界面可开。
        AimAndInteract action = new AimAndInteract(new InteractionTarget.EntityTarget(boat),
                c -> InteractionConfirmation.Verdict.PENDING, player -> scene);
        ActionStatus submitted = runUntilSubmitted(action, context);
        assertTrue(submitted instanceof ActionStatus.Running running && running.progressed(), "提交本刻算真实进展");
        assertEquals(1, sender.submissions.size());
        assertEquals(PendingInteraction.Kind.INTERACT_ENTITY, sender.submissions.getFirst().kind(),
                "实体目标走右键实体的原生入口，不是点方块也不是用手里物品");
        // 乘客关系同步下来（确认生效）：动作完成，全程没有任何界面。
        sender.nextStatus = PendingInteraction.Status.CONFIRMED_APPLIED;
        context.advance();
        assertTrue(action.tick(context.asTickContext()) instanceof ActionStatus.Done);
        assertEquals(InteractionVerdict.APPLIED, action.result().verdict());
    }

    @Test
    void anEntityRideThatNeverSettlesReportsUnconfirmed() {
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        InteractionTestFakes.FakeContext context = new InteractionTestFakes.FakeContext().withSender(sender);
        InteractionTestFakes.FakeScene scene = new InteractionTestFakes.FakeScene(context);
        Entity boat = fakeBoat();
        scene.ray = new EntityHitResult(boat, boat.getBoundingBox().getCenter());
        AimAndInteract action = new AimAndInteract(new InteractionTarget.EntityTarget(boat),
                c -> InteractionConfirmation.Verdict.PENDING, player -> scene);
        runUntilSubmitted(action, context);
        // 确认窗过期也没坐上去：如实按没能确认上报，不冒充骑上了。
        sender.nextStatus = PendingInteraction.Status.UNCERTAIN;
        sender.nextDetail = "确认时限到了还没看到结果";
        ActionStatus status = action.tick(context.asTickContext());
        assertTrue(status instanceof ActionStatus.Failed);
        assertEquals(InteractionVerdict.UNCONFIRMED, action.result().verdict());
        assertTrue(action.result().scene().contains("实体（编号"), "现场要说明目标是哪只实体");
    }

    @Test
    void aRayThroughTheVehiclePassengerStillCountsAsAimingAtTheVehicle() {
        // 船里坐着人、射线被乘客挡住：右键实体交的是点名实体的交互请求，与准星扫过谁无关，
        // 乘客不算遮挡——照常提交右键这条船，不要求换站位（空船能点、有人的船点不出是缺陷）。
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        InteractionTestFakes.FakeContext context = new InteractionTestFakes.FakeContext().withSender(sender);
        InteractionTestFakes.FakeScene scene = new InteractionTestFakes.FakeScene(context);
        Entity boat = fakeBoat();
        // 离线替身没有世界、骑不上去（startRiding 要发游戏事件），只声明它坐在这条船上。
        Entity passenger = fakeRidingBoat(boat);
        scene.ray = new EntityHitResult(passenger, passenger.getBoundingBox().getCenter());
        AimAndInteract action = new AimAndInteract(new InteractionTarget.EntityTarget(boat),
                c -> InteractionConfirmation.Verdict.PENDING, player -> scene);
        ActionStatus submitted = runUntilSubmitted(action, context);
        assertTrue(submitted instanceof ActionStatus.Running running && running.progressed(), "乘客挡住不算被挡，本刻提交");
        assertEquals(1, sender.submissions.size());
        assertEquals(PendingInteraction.Kind.INTERACT_ENTITY, sender.submissions.getFirst().kind());
    }

    @Test
    void anUnrelatedEntityInTheRayDoesNotBlockDirectEntitySubmissionWithinReach() {
        // 路过的生物或草丛挡在准星线上：右键实体按目标实体编号发包，在放行距离内直接提交，不再被路过实体卡死换站位。
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        InteractionTestFakes.FakeContext context = new InteractionTestFakes.FakeContext().withSender(sender);
        InteractionTestFakes.FakeScene scene = new InteractionTestFakes.FakeScene(context);
        Entity boat = fakeBoat();
        Entity stray = fakeBoat();
        scene.ray = new EntityHitResult(stray, stray.getBoundingBox().getCenter());
        AimAndInteract action = new AimAndInteract(new InteractionTarget.EntityTarget(boat),
                c -> InteractionConfirmation.Verdict.PENDING, player -> scene);
        ActionStatus submitted = runUntilSubmitted(action, context);
        assertTrue(submitted instanceof ActionStatus.Running running && running.progressed());
        assertEquals(1, sender.submissions.size());
        assertEquals(PendingInteraction.Kind.INTERACT_ENTITY, sender.submissions.getFirst().kind());

        // 超出放行距离的实体照旧报到不了。
        Entity farBoat = fakeBoat();
        farBoat.setPos(10.0, 64.0, 10.0);
        ScriptedInteractionSender farSender = new ScriptedInteractionSender();
        InteractionTestFakes.FakeContext farContext = new InteractionTestFakes.FakeContext().withSender(farSender);
        InteractionTestFakes.FakeScene farScene = new InteractionTestFakes.FakeScene(farContext);
        ActionStatus status = new AimAndInteract(new InteractionTarget.EntityTarget(farBoat),
                c -> InteractionConfirmation.Verdict.PENDING, player -> farScene).tick(farContext.asTickContext());
        assertEquals("UNREACHABLE", failedKind(status));
        assertTrue(farSender.submissions.isEmpty());
    }

    /** 离线替身的载具：只为拿一只活着、有包围盒的实体，不接触真实世界（ItemEntity 这类要在世界里出生）。 */
    private static Entity fakeBoat() {
        Entity boat = new Entity(EntityType.BOAT, null) {
            @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {}
            @Override protected void readAdditionalSaveData(CompoundTag tag) {}
            @Override protected void addAdditionalSaveData(CompoundTag tag) {}
        };
        boat.setPos(2.0, 64.0, 2.0);
        return boat;
    }

    /** 声明坐在替身载具上的乘客：只让根载具指向那条船，凑出"同一载具链"的判断。 */
    private static Entity fakeRidingBoat(Entity boat) {
        Entity passenger = new Entity(EntityType.BOAT, null) {
            @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {}
            @Override protected void readAdditionalSaveData(CompoundTag tag) {}
            @Override protected void addAdditionalSaveData(CompoundTag tag) {}
            @Override public Entity getRootVehicle() {
                return boat;
            }
        };
        passenger.setPos(2.0, 64.0, 2.0);
        return passenger;
    }
}
