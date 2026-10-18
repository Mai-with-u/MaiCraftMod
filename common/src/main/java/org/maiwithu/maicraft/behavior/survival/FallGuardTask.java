// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.landing.CatchesFall;
import org.maiwithu.maicraft.behavior.landing.LandingFetch;
import org.maiwithu.maicraft.behavior.landing.spi.LandingMaterials;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 落地防护临时任务：意外掉下去、预计会掉血时，就地找落点（正下方或周围两格内空中推得过去的，水优先），
 * 用身上带着的水桶、蛛网、浆果、藤蔓、黏液块或干草（或落点现成的水源、蛛网）接住自己，落地站稳后收回自己放的。
 *
 * <p>顺序：找落点 → 接住（边掉边把东西拿到手上、往落点推、够得着时只放一次、落地站稳、收回）。
 * 身上没带落地料时，先从随身无线终端现取一桶水再走这一套；取不到或来不及就如实说，落地伤害由游戏结算。
 * 落点本来就是水不用做事；下面是虚空救不了。救命不看任务许可；收不回的东西留在原地，结果里写清楚。
 */
public final class FallGuardTask extends PhasedTask<FallGuardTask.Phase> {

    private static final Logger LOG = LoggerFactory.getLogger(FallGuardTask.class);

    public enum Phase { FIND, FETCH, CATCH }

    /** 没带落地料时先取的那一件：水桶优先；取到别的也按落地救援自己认。 */
    private static final List<String> WANTED_LANDING_MATERIAL = List.of("minecraft:water_bucket");

    private final SurvivalSituation.SituationReader reader;
    /** 就地找落点与办法，交给一次空中救援；救不了为 null。 */
    private final Function<PlayerContext, CatchesFall> rescues;
    /** 身上没带落地料时，能从哪儿现取一件（联动模组登记；没有就当取不到）。 */
    private final Supplier<List<LandingMaterials>> landingMaterials;
    /** 正在进行的取物动作；进了 FETCH 阶段由它推进。 */
    private Action fetchAction;
    private CatchesFall rescue;

    public FallGuardTask(SurvivalSituation.SituationReader reader, Function<PlayerContext, CatchesFall> rescues) {
        this(reader, rescues, List::of);
    }

    public FallGuardTask(SurvivalSituation.SituationReader reader, Function<PlayerContext, CatchesFall> rescues,
                         Supplier<List<LandingMaterials>> landingMaterials) {
        super("落地防护", Phase.FIND, new ProgressTracker(20L * 30, 20L * 60));
        this.reader = reader;
        this.rescues = rescues;
        this.landingMaterials = landingMaterials;
    }

    @Override
    protected Action enter(Phase phase) {
        return switch (phase) {
            case FETCH -> fetchAction;
            case CATCH -> new CatchFall(rescue);
            case FIND -> null;
        };
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        if (phase == Phase.FETCH) return fetchThenCatch(context);
        if (phase == Phase.CATCH) return runActionThen(context, this::concluded);
        SurvivalSituation situation = reader.read(context);
        PlayerContext player = context.player();
        if (situation == null || player == null) return Next.stay();
        if (!situation.falling()) return Next.done(TaskResult.done("落地防护：已经没在往下掉了"));
        if (situation.landsInWater()) return Next.done(TaskResult.done("落地防护：下面就是水，落进水里不用自救"));
        if (situation.overVoid()) {
            return Next.fail(Problem.of(Problem.Kind.DANGER, "下面是虚空，什么都接不住这一掉"));
        }
        // 插进来到这一刻处境可能变了（踩空一格就落到下一层）：这一掉已经不会掉血就不出手。
        if (situation.landsUnhurt()) return Next.done(TaskResult.done("落地防护：这一掉不会掉血，不用接"));
        // 实机在平台上施工时一格的落差也开喷气背包刹车，难查：接手时把现场记一行。
        var self = player.localPlayer();
        if (self != null) {
            LOG.info("[落地防护] 接手：脚 {}，下落速度 {}，已掉 {} 格", self.blockPosition().toShortString(),
                    String.format("%.2f", self.getDeltaMovement().y), String.format("%.1f", self.fallDistance));
        }
        rescue = rescues.apply(player);
        if (rescue == null) {
            // 下界放水会蒸发，水桶接不住自己：别让 LLM 照着建议去备水，也别从网络里白取一桶。实机 10-14 下界落地防护叫它带水桶。
            if (player.level() != null && player.level().dimensionType().ultraWarm()) {
                return Next.fail(Problem.of(Problem.Kind.DANGER,
                        "这一掉会掉血，可身上没有能接住自己的东西（干草、黏液块、蛛网……；这里放水会蒸发，水桶接不住）；落地伤害由游戏结算",
                        "在下界高处走动时带干草块或黏液块，掉下去时就能接住自己"));
            }
            // 身上没带落地料：原地从随身无线终端取一件（水桶），取到了再按落地救援放。
            fetchAction = startFetch(player);
            if (fetchAction != null) {
                return Next.go(Phase.FETCH, fetchAction.describe());
            }
            return Next.fail(Problem.of(Problem.Kind.DANGER,
                    "这一掉会掉血，可身上没有能接住自己的东西（水桶、干草、黏液块、蛛网……），落点附近也没有现成的水；落地伤害由游戏结算",
                    "高处干活时身上带一桶水（或干草、黏液块），掉下去时就能接住自己"));
        }
        return Next.go(Phase.CATCH, "用" + rescue.itemId() + "接住，落点 " + rescue.landingFeet().toShortString());
    }

    // 从身上带着的无线终端取一件落地料；取不了（没带、没连上网络、网络里没有）给 null，照旧如实说没有东西接。
    private Action startFetch(PlayerContext player) {
        for (LandingMaterials materials : landingMaterials.get()) {
            try {
                Optional<Action> action = materials.fetch(player, WANTED_LANDING_MATERIAL);
                if (action.isPresent()) {
                    return new LandingFetch(action.get(), materials.describe(), "水桶");
                }
            } catch (RuntimeException failure) {
                LOG.info("[落地防护] 从{}取落地料没成：{}", materials.describe(), failure.getMessage());
            }
        }
        return null;
    }

    // 落地料取完（或取不成）：取到了就重新找落点、交给整套落地救援；没取到就说清是取料没成。
    private Next<Phase> fetchThenCatch(TickContext context) {
        ActionStatus status = runAction(context);
        if (status instanceof ActionStatus.Running) return Next.stay();
        if (status instanceof ActionStatus.Failed failure) {
            // 取物动作没走完也可能东西已经进包了（例如收手时正在关界面）：先按身上的再看一次，有就接着放。
            PlayerContext player = context.player();
            CatchesFall late = player == null ? null : rescues.apply(player);
            if (late != null) {
                rescue = late;
                return Next.go(Phase.CATCH, "用" + rescue.itemId() + "接住，落点 " + rescue.landingFeet().toShortString());
            }
            return Next.fail(Problem.of(Problem.Kind.DANGER,
                    "这一掉会掉血，可身上没有能接住自己的东西，从无线终端现取也没成：" + failure.problem().message(),
                    "高处干活时身上带一桶水（或干草、黏液块），掉下去时就能接住自己"));
        }
        PlayerContext player = context.player();
        rescue = player == null ? null : rescues.apply(player);
        if (rescue == null) {
            return Next.fail(Problem.of(Problem.Kind.DANGER,
                    "这一掉会掉血；从无线终端取到了东西，可这个落点放不下或用不上",
                    "高处干活时身上带一桶水（或干草、黏液块），掉下去时就能接住自己"));
        }
        return Next.go(Phase.CATCH, "用" + rescue.itemId() + "接住，落点 " + rescue.landingFeet().toShortString());
    }

    // 接住这一掉收场：留在原地没收回的格子记进变化；救没救成按救援自己的认定。
    private Next<Phase> concluded() {
        List<BlockPos> leftInPlace = new ArrayList<>();
        for (CatchesFall.Change change : rescue.drainChanges()) {
            if (!leftInPlace.remove(change.position())) leftInPlace.add(change.position());
        }
        for (BlockPos cell : leftInPlace) {
            recordChange(new Change(Change.Kind.BLOCK_PLACED, rescue.itemId(), 1,
                    "落地救援放在 " + cell.toShortString() + "，没收回，还留在那里"));
        }
        if (rescue.failed()) return Next.fail(Problem.of(Problem.Kind.DANGER, rescue.detail()));
        return Next.done(TaskResult.done("落地防护：" + rescue.detail()));
    }

    @Override
    protected String describePhase(Phase value) {
        return switch (value) {
            case FIND -> "找能接住自己的落点";
            case FETCH -> fetchAction == null ? "从无线终端取落地料" : fetchAction.describe();
            case CATCH -> rescue == null ? "接住这一掉" : "接住这一掉：" + rescue.detail();
        };
    }

    @Override
    public Interruptibility interruptibility(TickContext context) {
        // 高速下落的半路停下来等于放弃自救；只有同时被埋这种更急的事才打断得了。
        return Interruptibility.UNSAFE_TO_STOP;
    }

    /** 接住这一掉的动作：每刻推进救援、按它的意思往落点推；被打断时救援收手（没确认的放置交还、打开的背包关上）。 */
    private static final class CatchFall implements Action {
        private final CatchesFall rescue;
        private PlayerContext last;

        CatchFall(CatchesFall rescue) {
            this.rescue = rescue;
        }

        @Override
        public ActionStatus tick(TickContext context) {
            PlayerContext player = context.player();
            last = player;
            rescue.tick(player);
            if (rescue.complete()) return ActionStatus.done();
            player.input().applySteering(yaw -> rescue.steering(player, yaw),
                    player.localPlayer().getYRot(), player.clientTick());
            return ActionStatus.running();
        }

        @Override
        public void close() {
            if (!rescue.complete() && last != null) rescue.stop(last, "落地防护被打断");
        }

        @Override
        public Interruptibility interruptibility() {
            return Interruptibility.UNSAFE_TO_STOP;
        }

        @Override
        public String describe() {
            return "接住这一掉：" + rescue.detail();
        }
    }
}
