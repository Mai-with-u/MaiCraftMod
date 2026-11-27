// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.advancedae;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.ability.machine.spi.FullRun;
import org.maiwithu.maicraft.ability.machine.spi.RunRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.inventory.containers.FeedReport;
import org.maiwithu.maicraft.behavior.menu.OutputLook;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 反应仓方块直点桶交互动作：不打开反应仓界面，直接手持流体桶右键方块触发 {@code useBucket}。
 *
 * <p>支持两路交互与一条流体产物桶收货整段运行：
 * <ul>
 * <li><b>倒桶投料</b>：逐桶将满桶切到主手、靠近并右键反应仓方块，等服务端方块确认号确认主手变为空桶；
 * 满罐、异种流体冲突或方块拒收导致意外开窗时立即中止并如实报告，不死循环连点。</li>
 * <li><b>空桶舀取</b>：逐桶手持空桶右键反应仓方块，从输出流体槽（槽 0）舀出 1000 mB 并确认主手变为目标流体桶。</li>
 * </ul>
 */
public final class ReactionChamberBucketFeed implements Action, FeedReport {

    /** 原版空桶物品注册 ID。 */
    public static final String EMPTY_BUCKET_ID = "minecraft:bucket";
    /** 一桶流体的标准容量（mB）。F 游戏事实。 */
    public static final long BUCKET_MB = 1_000L;

    private enum Stage { CHECK_TANK, HOLD_BUCKET, APPROACH, USE_BLOCK, DONE }

    private final BlockPos at;
    private final String bucketItemId;
    private final String expectedFluidId;
    private final int count;
    private final Supplier<Action> approachFactory;
    private final Function<String, Optional<Action>> toMainhandFactory;
    private final Function<InteractionConfirmation, Action> useBlockFactory;
    private final Supplier<Optional<AdvancedAeReads.TankSlot>> inputTankReader;
    private final Supplier<String> mainHandReader;
    private final IntSupplier containerIdReader;
    private final Runnable closeUnexpectedMenu;

    private final List<String> notes = new ArrayList<>();
    private Stage stage = Stage.CHECK_TANK;
    private Action step;
    private boolean approached;
    private int delivered;
    private int containerBeforeClick;

    /** 生产构造：按玩家行为服务靠近、切主手并直点反应仓方块倒桶。 */
    public ReactionChamberBucketFeed(BlockPos at, String bucketItemId, String expectedFluidId, int count,
                                     Permissions permissions, PlayerServices services,
                                     Supplier<Optional<AdvancedAeReads.TankSlot>> inputTankReader) {
        this(at, bucketItemId, expectedFluidId, count,
                () -> services.approach().toward(ApproachTarget.ofBlock(at), permissions),
                id -> services.toMainhand().moveToMainhand(id),
                confirmation -> services.interactions().useBlock(at, confirmation),
                inputTankReader,
                () -> mainHandId(services.context().get()),
                () -> containerIdOf(services.context().get()),
                () -> closeMenuIfOpen(services.context().get()));
    }

    /** 离线测试构造：注入靠近、切主手、右键与储罐读端替身。 */
    ReactionChamberBucketFeed(BlockPos at, String bucketItemId, String expectedFluidId, int count,
                              Supplier<Action> approachFactory,
                              Function<String, Optional<Action>> toMainhandFactory,
                              Function<InteractionConfirmation, Action> useBlockFactory,
                              Supplier<Optional<AdvancedAeReads.TankSlot>> inputTankReader,
                              Supplier<String> mainHandReader,
                              IntSupplier containerIdReader,
                              Runnable closeUnexpectedMenu) {
        this.at = Objects.requireNonNull(at, "at").immutable();
        this.bucketItemId = Objects.requireNonNull(bucketItemId, "bucketItemId");
        this.expectedFluidId = expectedFluidId == null ? "" : expectedFluidId;
        this.count = Math.max(1, count);
        this.approachFactory = Objects.requireNonNull(approachFactory, "approachFactory");
        this.toMainhandFactory = Objects.requireNonNull(toMainhandFactory, "toMainhandFactory");
        this.useBlockFactory = Objects.requireNonNull(useBlockFactory, "useBlockFactory");
        this.inputTankReader = Objects.requireNonNull(inputTankReader, "inputTankReader");
        this.mainHandReader = Objects.requireNonNull(mainHandReader, "mainHandReader");
        this.containerIdReader = Objects.requireNonNull(containerIdReader, "containerIdReader");
        this.closeUnexpectedMenu = Objects.requireNonNull(closeUnexpectedMenu, "closeUnexpectedMenu");
    }

    @Override public int delivered() {
        return delivered;
    }

    @Override public List<String> notes() {
        return List.copyOf(notes);
    }

    @Override public ActionStatus tick(TickContext tick) {
        return switch (stage) {
            case CHECK_TANK -> checkTankBeforePour();
            case HOLD_BUCKET -> holdBucket(tick);
            case APPROACH -> approach(tick);
            case USE_BLOCK -> useBlock(tick);
            case DONE -> ActionStatus.done();
        };
    }

    // 每次倒桶前核对输入流体槽（槽 1）：满罐或存有异种流体时立即停手，不强行盲点。
    private ActionStatus checkTankBeforePour() {
        if (delivered >= count) {
            stage = Stage.DONE;
            return ActionStatus.done();
        }
        Optional<AdvancedAeReads.TankSlot> tankOpt = inputTankReader.get();
        if (tankOpt.isPresent()) {
            AdvancedAeReads.TankSlot tank = tankOpt.get();
            if (!tank.isEmpty() && !expectedFluidId.isEmpty() && !tank.fluidId().equals(expectedFluidId)) {
                String why = "反应仓输入流体槽（槽 1）存有异种流体 " + tank.fluidId() + "（" + tank.amountMb()
                        + " mB），无法倒入 " + expectedFluidId + "，请先清空输入流体槽";
                return stopOrFail(Problem.Kind.NOT_POSSIBLE_HERE, why);
            }
            if (tank.capacityMb() - tank.amountMb() < BUCKET_MB) {
                String why = "反应仓输入流体槽已满（" + tank.amountMb() + "/" + tank.capacityMb()
                        + " mB），剩余空间不足 1000 mB，已中止继续倒桶";
                return stopOrFail(Problem.Kind.NOT_POSSIBLE_HERE, why);
            }
        }
        stage = Stage.HOLD_BUCKET;
        return ActionStatus.progressed();
    }

    private ActionStatus holdBucket(TickContext tick) {
        if (bucketItemId.equals(mainHandReader.get())) {
            finishStep();
            stage = approached ? Stage.USE_BLOCK : Stage.APPROACH;
            return ActionStatus.progressed();
        }
        if (step == null) {
            Optional<Action> move = toMainhandFactory.apply(bucketItemId);
            if (move.isEmpty()) {
                return stopOrFail(Problem.Kind.NEED_ITEM, "身上没有可切到主手的 " + bucketItemId);
            }
            step = move.get();
        }
        ActionStatus status = step.tick(tick);
        if (status instanceof ActionStatus.Running) return status;
        finishStep();
        if (status instanceof ActionStatus.Failed failed) {
            return stopOrFail(failed.problem().kind(), failed.problem().message());
        }
        if (!bucketItemId.equals(mainHandReader.get())) {
            return stopOrFail(Problem.Kind.NEED_ITEM, "未能将 " + bucketItemId + " 切到主手");
        }
        stage = approached ? Stage.USE_BLOCK : Stage.APPROACH;
        return ActionStatus.progressed();
    }

    private ActionStatus approach(TickContext tick) {
        if (step == null) step = approachFactory.get();
        ActionStatus status = step.tick(tick);
        if (status instanceof ActionStatus.Running) return status;
        finishStep();
        if (status instanceof ActionStatus.Failed failed) {
            return stopOrFail(failed.problem().kind(), failed.problem().message());
        }
        approached = true;
        stage = Stage.USE_BLOCK;
        return ActionStatus.progressed();
    }

    // 瞄准反应仓方块右键倒桶，经服务端方块确认号核对主手变为空桶；若意外开窗说明方块拒收，立即关窗止损。
    private ActionStatus useBlock(TickContext tick) {
        if (step == null) {
            containerBeforeClick = containerIdReader.getAsInt();
            InteractionConfirmation confirmation = pourConfirmation(containerBeforeClick,
                    mainHandReader, containerIdReader);
            step = useBlockFactory.apply(confirmation);
        }
        ActionStatus status = step.tick(tick);
        if (status instanceof ActionStatus.Running) return status;
        finishStep();
        if (status instanceof ActionStatus.Failed) {
            boolean openedUnexpectedly = containerIdReader.getAsInt() != containerBeforeClick;
            if (openedUnexpectedly) {
                closeUnexpectedMenu.run();
            }
            String why = openedUnexpectedly
                    ? "反应仓拒收了 " + bucketItemId + "（可能输入流体槽已满或存有异种流体，方块转而打开了界面），已中止倒桶"
                    : "手持 " + bucketItemId + " 右键反应仓未确认变为空桶，已中止继续倒桶";
            return stopOrFail(Problem.Kind.REFUSED_BY_GAME, why);
        }
        delivered++;
        stage = delivered >= count ? Stage.DONE : Stage.CHECK_TANK;
        return delivered >= count ? ActionStatus.done() : ActionStatus.progressed();
    }

    private ActionStatus stopOrFail(Problem.Kind kind, String message) {
        finishStep();
        stage = Stage.DONE;
        if (delivered > 0) {
            notes.add("已倒入 " + delivered + "/" + count + " 桶 " + bucketItemId + " 后中止：" + message);
            return ActionStatus.done();
        }
        return ActionStatus.failed(Problem.of(kind, message));
    }

    /** 构造倒桶确认条件：等待服务端方块确认号并核对主手变为 minecraft:bucket，意外开窗判 DIVERGED。 */
    static InteractionConfirmation pourConfirmation(int beforeContainerId,
                                                    Supplier<String> mainHandReader,
                                                    IntSupplier containerIdReader) {
        return InteractionConfirmation.requiringBlockAcknowledgement(
                InteractionConfirmation.expiresAsNotApplied(ctx -> {
                    int liveContainer = ctx != null && ctx.localPlayer() != null
                            ? ctx.localPlayer().containerMenu.containerId : containerIdReader.getAsInt();
                    if (liveContainer != beforeContainerId) {
                        return InteractionConfirmation.Verdict.DIVERGED;
                    }
                    String held = ctx != null && ctx.localPlayer() != null
                            ? mainHandId(ctx) : mainHandReader.get();
                    return EMPTY_BUCKET_ID.equals(held)
                            ? InteractionConfirmation.Verdict.APPLIED
                            : InteractionConfirmation.Verdict.PENDING;
                }));
    }

    /** 构造空桶舀取确认条件：等待服务端方块确认号并核对主手变为目标满桶，意外开窗判 DIVERGED。 */
    static InteractionConfirmation scoopConfirmation(String targetBucketId, int beforeContainerId,
                                                     Supplier<String> mainHandReader,
                                                     IntSupplier containerIdReader) {
        return InteractionConfirmation.requiringBlockAcknowledgement(
                InteractionConfirmation.expiresAsNotApplied(ctx -> {
                    int liveContainer = ctx != null && ctx.localPlayer() != null
                            ? ctx.localPlayer().containerMenu.containerId : containerIdReader.getAsInt();
                    if (liveContainer != beforeContainerId) {
                        return InteractionConfirmation.Verdict.DIVERGED;
                    }
                    String held = ctx != null && ctx.localPlayer() != null
                            ? mainHandId(ctx) : mainHandReader.get();
                    return targetBucketId.equals(held)
                            ? InteractionConfirmation.Verdict.APPLIED
                            : InteractionConfirmation.Verdict.PENDING;
                }));
    }

    /** 创建空桶右键反应仓舀取流体产物桶的收货动作（实现 OutputLook）。 */
    public static OutputLook scoop(BlockPos at, String targetBucketId, String expectedFluidId, int count,
                                   Permissions permissions, PlayerServices services,
                                   Supplier<Optional<AdvancedAeReads.TankSlot>> outputTankReader) {
        return new BucketScoopAction(at, targetBucketId, expectedFluidId, count,
                () -> services.approach().toward(ApproachTarget.ofBlock(at), permissions),
                id -> services.toMainhand().moveToMainhand(id),
                confirmation -> services.interactions().useBlock(at, confirmation),
                outputTankReader,
                () -> mainHandId(services.context().get()),
                () -> containerIdOf(services.context().get()),
                () -> closeMenuIfOpen(services.context().get()));
    }

    /** 离线测试入口：注入替身构造空桶舀取动作。 */
    static OutputLook scoopForTest(BlockPos at, String targetBucketId, String expectedFluidId, int count,
                                   Supplier<Action> approachFactory,
                                   Function<String, Optional<Action>> toMainhandFactory,
                                   Function<InteractionConfirmation, Action> useBlockFactory,
                                   Supplier<Optional<AdvancedAeReads.TankSlot>> outputTankReader,
                                   Supplier<String> mainHandReader,
                                   IntSupplier containerIdReader,
                                   Runnable closeUnexpectedMenu) {
        return new BucketScoopAction(at, targetBucketId, expectedFluidId, count,
                approachFactory, toMainhandFactory, useBlockFactory,
                outputTankReader, mainHandReader, containerIdReader, closeUnexpectedMenu);
    }

    /** 创建流体产物桶整段运行（备空桶 -> 必要时等待反应仓出液 -> 空桶直点舀取满桶）。 */
    public static FullRun fluidBucketRun(BlockPos at, RunRequest request, String targetFluidId,
                                         Permissions permissions, PlayerServices services,
                                         Supplier<Optional<AdvancedAeReads.ChamberView>> chamberReader) {
        return new FluidBucketScoopRun(at, request, targetFluidId, permissions, services, chamberReader);
    }

    private void finishStep() {
        if (step != null) {
            step.close();
            step = null;
        }
    }

    @Override public void pause() {
        if (step != null) step.pause();
    }

    @Override public void close() {
        finishStep();
    }

    @Override public Interruptibility interruptibility() {
        return step != null ? step.interruptibility() : Interruptibility.WORKING;
    }

    @Override public String describe() {
        return "手持 " + bucketItemId + " 直点反应仓倒桶（" + delivered + "/" + count + "）";
    }

    static String mainHandId(PlayerContext ctx) {
        if (ctx == null || ctx.localPlayer() == null) return "";
        ItemStack held = ctx.localPlayer().getMainHandItem();
        return held.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
    }

    static int containerIdOf(PlayerContext ctx) {
        if (ctx == null || ctx.localPlayer() == null) return 0;
        return ctx.localPlayer().containerMenu.containerId;
    }

    static void closeMenuIfOpen(PlayerContext ctx) {
        if (ctx != null && ctx.localPlayer() != null
                && ctx.localPlayer().containerMenu != ctx.localPlayer().inventoryMenu) {
            ctx.localPlayer().closeContainer();
        }
    }

    /** 空桶右键反应仓从输出流体槽（槽 0）舀取流体产物桶的动作。 */
    private static final class BucketScoopAction implements OutputLook {

        private enum ScoopStage { CHECK_TANK, HOLD_EMPTY, APPROACH, USE_BLOCK, DONE }

        private final BlockPos at;
        private final String targetBucketId;
        private final String expectedFluidId;
        private final int count;
        private final Supplier<Action> approachFactory;
        private final Function<String, Optional<Action>> toMainhandFactory;
        private final Function<InteractionConfirmation, Action> useBlockFactory;
        private final Supplier<Optional<AdvancedAeReads.TankSlot>> outputTankReader;
        private final Supplier<String> mainHandReader;
        private final IntSupplier containerIdReader;
        private final Runnable closeUnexpectedMenu;

        private final List<String> notes = new ArrayList<>();
        private ScoopStage stage = ScoopStage.CHECK_TANK;
        private Action step;
        private boolean approached;
        private boolean baselineRecorded;
        private int seen;
        private int taken;
        private int containerBeforeClick;

        BucketScoopAction(BlockPos at, String targetBucketId, String expectedFluidId, int count,
                          Supplier<Action> approachFactory,
                          Function<String, Optional<Action>> toMainhandFactory,
                          Function<InteractionConfirmation, Action> useBlockFactory,
                          Supplier<Optional<AdvancedAeReads.TankSlot>> outputTankReader,
                          Supplier<String> mainHandReader,
                          IntSupplier containerIdReader,
                          Runnable closeUnexpectedMenu) {
            this.at = at.immutable();
            this.targetBucketId = Objects.requireNonNull(targetBucketId, "targetBucketId");
            this.expectedFluidId = expectedFluidId == null ? "" : expectedFluidId;
            this.count = Math.max(1, count);
            this.approachFactory = approachFactory;
            this.toMainhandFactory = toMainhandFactory;
            this.useBlockFactory = useBlockFactory;
            this.outputTankReader = outputTankReader;
            this.mainHandReader = mainHandReader;
            this.containerIdReader = containerIdReader;
            this.closeUnexpectedMenu = closeUnexpectedMenu;
        }

        @Override public int seen() { return seen; }

        @Override public int taken() { return taken; }

        @Override public List<String> notes() { return List.copyOf(notes); }

        @Override public ActionStatus tick(TickContext tick) {
            return switch (stage) {
                case CHECK_TANK -> checkOutputTank();
                case HOLD_EMPTY -> holdEmptyBucket(tick);
                case APPROACH -> approach(tick);
                case USE_BLOCK -> useBlock(tick);
                case DONE -> ActionStatus.done();
            };
        }

        private ActionStatus checkOutputTank() {
            if (taken >= count) {
                stage = ScoopStage.DONE;
                return ActionStatus.done();
            }
            Optional<AdvancedAeReads.TankSlot> tankOpt = outputTankReader.get();
            if (tankOpt.isPresent()) {
                AdvancedAeReads.TankSlot tank = tankOpt.get();
                boolean matches = !tank.isEmpty()
                        && (expectedFluidId.isEmpty() || tank.fluidId().equals(expectedFluidId));
                int available = matches ? (int) (tank.amountMb() / BUCKET_MB) : 0;
                if (!baselineRecorded) {
                    seen = available;
                    baselineRecorded = true;
                }
                if (available < 1) {
                    String why = "反应仓输出流体槽（槽 0）不足 1000 mB "
                            + (expectedFluidId.isEmpty() ? "流体" : expectedFluidId)
                            + "（当前 " + (tank.isEmpty() ? "空" : tank.fluidId() + " " + tank.amountMb() + " mB") + "）";
                    return stopOrFail(Problem.Kind.NOT_POSSIBLE_HERE, why);
                }
            }
            stage = ScoopStage.HOLD_EMPTY;
            return ActionStatus.progressed();
        }

        private ActionStatus holdEmptyBucket(TickContext tick) {
            if (EMPTY_BUCKET_ID.equals(mainHandReader.get())) {
                finishStep();
                stage = approached ? ScoopStage.USE_BLOCK : ScoopStage.APPROACH;
                return ActionStatus.progressed();
            }
            if (step == null) {
                Optional<Action> move = toMainhandFactory.apply(EMPTY_BUCKET_ID);
                if (move.isEmpty()) {
                    return stopOrFail(Problem.Kind.NEED_ITEM, "身上没有空桶（minecraft:bucket）可舀取反应仓流体产物");
                }
                step = move.get();
            }
            ActionStatus status = step.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            finishStep();
            if (status instanceof ActionStatus.Failed failed) {
                return stopOrFail(failed.problem().kind(), failed.problem().message());
            }
            if (!EMPTY_BUCKET_ID.equals(mainHandReader.get())) {
                return stopOrFail(Problem.Kind.NEED_ITEM, "未能将空桶切到主手");
            }
            stage = approached ? ScoopStage.USE_BLOCK : ScoopStage.APPROACH;
            return ActionStatus.progressed();
        }

        private ActionStatus approach(TickContext tick) {
            if (step == null) step = approachFactory.get();
            ActionStatus status = step.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            finishStep();
            if (status instanceof ActionStatus.Failed failed) {
                return stopOrFail(failed.problem().kind(), failed.problem().message());
            }
            approached = true;
            stage = ScoopStage.USE_BLOCK;
            return ActionStatus.progressed();
        }

        private ActionStatus useBlock(TickContext tick) {
            if (step == null) {
                containerBeforeClick = containerIdReader.getAsInt();
                InteractionConfirmation confirmation = scoopConfirmation(targetBucketId, containerBeforeClick,
                        mainHandReader, containerIdReader);
                step = useBlockFactory.apply(confirmation);
            }
            ActionStatus status = step.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            finishStep();
            if (status instanceof ActionStatus.Failed) {
                boolean openedUnexpectedly = containerIdReader.getAsInt() != containerBeforeClick;
                if (openedUnexpectedly) closeUnexpectedMenu.run();
                return stopOrFail(Problem.Kind.REFUSED_BY_GAME,
                        "空桶右键反应仓未确认舀出 " + targetBucketId + "（可能输出流体槽不足 1000 mB）");
            }
            taken++;
            stage = taken >= count ? ScoopStage.DONE : ScoopStage.CHECK_TANK;
            return taken >= count ? ActionStatus.done() : ActionStatus.progressed();
        }

        private ActionStatus stopOrFail(Problem.Kind kind, String message) {
            finishStep();
            stage = ScoopStage.DONE;
            if (taken > 0) {
                notes.add("已舀取 " + taken + "/" + count + " 桶 " + targetBucketId + "：" + message);
                return ActionStatus.done();
            }
            return ActionStatus.failed(Problem.of(kind, message));
        }

        private void finishStep() {
            if (step != null) {
                step.close();
                step = null;
            }
        }

        @Override public void pause() { if (step != null) step.pause(); }

        @Override public void close() { finishStep(); }

        @Override public Interruptibility interruptibility() {
            return step != null ? step.interruptibility() : Interruptibility.WORKING;
        }

        @Override public String describe() {
            return "空桶右键反应仓舀取 " + targetBucketId + "（" + taken + "/" + count + "）";
        }
    }

    /** 流体产物桶整段运行：必要时先等反应仓产出流体，再备空桶直点反应仓舀取目标满桶。 */
    private static final class FluidBucketScoopRun implements FullRun {

        private enum RunStage { WAIT_FLUID, PREPARE_BUCKETS, SCOOP, DONE }

        private final BlockPos at;
        private final RunRequest request;
        private final String targetFluidId;
        private final Permissions permissions;
        private final PlayerServices services;
        private final Supplier<Optional<AdvancedAeReads.ChamberView>> chamberReader;

        private final List<String> notes = new ArrayList<>();
        private RunStage stage = RunStage.WAIT_FLUID;
        private Action step;
        private OutputLook scoop;
        private int produced;
        private int waitedTicks;

        FluidBucketScoopRun(BlockPos at, RunRequest request, String targetFluidId,
                            Permissions permissions, PlayerServices services,
                            Supplier<Optional<AdvancedAeReads.ChamberView>> chamberReader) {
            this.at = at.immutable();
            this.request = Objects.requireNonNull(request, "request");
            this.targetFluidId = Objects.requireNonNull(targetFluidId, "targetFluidId");
            this.permissions = Objects.requireNonNull(permissions, "permissions");
            this.services = Objects.requireNonNull(services, "services");
            this.chamberReader = Objects.requireNonNull(chamberReader, "chamberReader");
        }

        @Override public int produced() { return produced; }

        @Override public boolean complete() { return produced >= Math.max(1, request.count()); }

        @Override public List<String> notes() { return List.copyOf(notes); }

        @Override public ActionStatus tick(TickContext tick) {
            return switch (stage) {
                case WAIT_FLUID -> waitFluid();
                case PREPARE_BUCKETS -> prepareBuckets(tick);
                case SCOOP -> runScoop(tick);
                case DONE -> ActionStatus.done();
            };
        }

        private ActionStatus waitFluid() {
            Optional<AdvancedAeReads.ChamberView> viewOpt = chamberReader.get();
            if (viewOpt.isEmpty()) {
                return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "这里已经读不到反应仓"));
            }
            AdvancedAeReads.ChamberView view = viewOpt.get();
            AdvancedAeReads.TankSlot out = view.outputFluid();
            boolean hasEnough = !out.isEmpty() && out.fluidId().equals(targetFluidId) && out.amountMb() >= BUCKET_MB;
            if (hasEnough) {
                stage = RunStage.PREPARE_BUCKETS;
                return ActionStatus.progressed();
            }
            int maxWait = Math.max(200, request.maxSeconds() > 0 ? request.maxSeconds() * 20 : 60 * 20);
            if (view.working() && ++waitedTicks <= maxWait) {
                return ActionStatus.running();
            }
            return ActionStatus.failed(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    "反应仓输出流体槽里不足 1000 mB " + targetFluidId + "（当前 "
                            + (out.isEmpty() ? "空" : out.fluidId() + " " + out.amountMb() + " mB") + "）"));
        }

        private ActionStatus prepareBuckets(TickContext tick) {
            int want = Math.max(1, request.count());
            if (step == null) {
                int carried = countCarriedEmptyBuckets();
                if (carried >= want) {
                    stage = RunStage.SCOOP;
                    return ActionStatus.progressed();
                }
                step = services.needs().actionFor(
                        new ItemRequest(WantedItem.ofItem(EMPTY_BUCKET_ID), want - carried, "舀取反应仓流体产物用的空桶"),
                        permissions);
            }
            ActionStatus status = step.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            step.close();
            step = null;
            if (status instanceof ActionStatus.Failed) return status;
            stage = RunStage.SCOOP;
            return ActionStatus.progressed();
        }

        private ActionStatus runScoop(TickContext tick) {
            if (scoop == null) {
                scoop = scoop(at, request.item(), targetFluidId, Math.max(1, request.count()),
                        permissions, services, () -> chamberReader.get().map(AdvancedAeReads.ChamberView::outputFluid));
            }
            ActionStatus status = scoop.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            produced = scoop.taken();
            notes.addAll(scoop.notes());
            scoop.close();
            scoop = null;
            stage = RunStage.DONE;
            return status;
        }

        private int countCarriedEmptyBuckets() {
            return services.backpack().stacks().stream()
                    .filter(stack -> EMPTY_BUCKET_ID.equals(stack.itemId()))
                    .mapToInt(stack -> stack.count())
                    .sum();
        }

        @Override public void pause() {
            if (step != null) step.pause();
            if (scoop != null) scoop.pause();
        }

        @Override public void close() {
            if (step != null) { step.close(); step = null; }
            if (scoop != null) { scoop.close(); scoop = null; }
        }

        @Override public Interruptibility interruptibility() {
            if (scoop != null) return scoop.interruptibility();
            return step != null ? step.interruptibility() : Interruptibility.WORKING;
        }

        @Override public String describe() {
            return "从反应仓输出流体槽舀取 " + request.item() + "（" + produced + "/" + Math.max(1, request.count()) + "）";
        }
    }
}
