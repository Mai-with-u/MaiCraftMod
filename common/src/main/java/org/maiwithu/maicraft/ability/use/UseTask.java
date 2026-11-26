// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.interaction.InteractionResult;
import org.maiwithu.maicraft.behavior.interaction.ItemUseAim;
import org.maiwithu.maicraft.behavior.interaction.SignEditor;
import org.maiwithu.maicraft.behavior.interaction.WriteSign;
import org.maiwithu.maicraft.behavior.inventory.drops.PicksUpDrops;
import org.maiwithu.maicraft.behavior.permission.ContainerWhose;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryRecord;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 用东西的任务：找到目标、拿对手上的东西、走到够得着的地方、点下去、按手势确认生效，
 * 之后写告示牌、看看点开的界面、捡起掉出的东西，做满次数才收工。
 *
 * <p>每一步的动作都在上一刻按现场备好，进入阶段时交给基类持有：被打断时基类暂停它，
 * 换阶段、结束或被取消时基类收尾它（松开按着的键、交还没等到确认的提交、关上点开的界面）。
 *
 * <p>结论分开记：确认生效的进变化，没能确认的进未确认清单且不再赌第二次；
 * 某一次没有效果就停下，如实报告做成了几次，不把没做成的次数也算进去。
 */
final class UseTask extends PhasedTask<UseTask.Phase> {

    /**
     * 找目标 → 走到没加载的地方 → 去拿缺的东西 → 备手 → 靠近 → （锄地被草压着时先打掉草）→ 交互
     * → 写字/看界面 → 捡东西/下一次。
     */
    enum Phase { RESOLVE, TRAVEL, FETCH, HAND, APPROACH, CLEAR, INTERACT, WRITE, LOOK, COLLECT }

    /** 空桶点到流动的流体时，改舀多少格以内最近的同种源格。 */
    static final int SOURCE_SEARCH_RADIUS = 8;

    private final UseInput input;
    private final UseServices services;
    private final UseTargets targets;
    private final List<String> remainingNotices = new ArrayList<>();
    /** 下一个阶段的动作：上一刻按现场备好，进入阶段时交给基类。 */
    private Action prepared;
    /** 落实下来的目标；只对手上的东西用时为 null。 */
    private ResolvedTarget target;
    /** 本次目标是否由观察编号直接点名（点名即显式许可，备手后复核结构保护时放行）。 */
    private boolean targetNamed;
    /** 动过的这格最后由谁放下：收场时写进说明，主播看得出动的是谁的东西；归属没问到为空串。 */
    private String othersAtHome = "";
    /** 出手前趁原方块还在冻结好的改动通报：确认生效时触发一次，连点同一格不重复报。 */
    private Runnable worldChangeNotice = () -> {};
    /** 要走去加载出来的那一片。 */
    private WorldPosition travelTo;
    private boolean travelled;
    /** 这一次已经去拿过缺的东西；拿回来还是没有就不再去。 */
    private boolean fetched;
    private int approachRetries;
    /** 这一下交互：出手前冻结的现场与结论的读口。 */
    private UseSeams.Built.Ready interaction;
    private String messageBefore;
    /** 出手前聊天栏读到哪了：之后收到的服务端系统消息才算这一下的回应。 */
    private long chatMark;
    /** 生效的那几下前后聊天栏收到的服务端系统消息原话。 */
    private final List<String> gameMessages = new ArrayList<>();
    private Set<Integer> dropsBefore = Set.of();
    private WriteSign writing;
    private UseSeams.MenuLook looking;
    private long appliedTimes;
    private String openedMenu;
    /** 点开的方块容器是谁的（natural_loot / self / player / no_record / unknown）；没点开方块容器为 null。 */
    private String containerWhose;
    /** 点开的容器最后由谁放下：结果细节里带一句；不是容器、归属没问到为 null。 */
    private String lastModifiedBy;
    /** 这一次点开方块容器之前，世界记忆里有没有角色亲手打开过它的记录；没点开方块容器为 null。 */
    private Boolean containerOpenedBefore;
    /** 本次交互有没有把手中物品放进不认识的加工容器，或点开了不认识的方块界面。 */
    private boolean placedIntoUnrecognizedProcessor;
    private boolean openedUnrecognizedBlockMenu;
    private boolean consumedHeldInUse;
    /** 追踪原版营火放食物状态变化，区分食物上火与拿方块贴着营火落块。 */
    private final UseChanges.CampfirePlacement campfirePlacement = new UseChanges.CampfirePlacement();
    /** 追踪手持方块物品交互前后目标邻格变化，防止该面不收料落块在旁格时误报进料。 */
    private final UseChanges.AdjacentBlockPlacement adjacentPlacement = new UseChanges.AdjacentBlockPlacement();
    /** 若非潜行点机器/方块实体却落块在邻格，保存如实说明句供收场回报；没有为 null。 */
    private String placedBesideSummary;
    /** 出手前背包物品计数快照：用于识别潜行右键拆解或转化进背包的物品。 */
    private Map<String, Integer> backpackBefore = Map.of();
    /** 交互中拆解/转化进背包的产物描述；没有为 null。 */
    private String disassembledSummary;
    /** 打开已支持模组界面时尝试顺手放入的物品 ID 及结果。 */
    private String menuInsertItem;
    private UseSeams.MenuLook.Insertion menuInsertion = UseSeams.MenuLook.Insertion.NONE;
    private String riding;
    private List<String> signLines = List.of();
    /** 连着点到哪一刻为止（第一下出手时定）；不连着点为 -1。 */
    private long keepTryingUntil = -1;
    /** 连点期间的位置跳变监视：原地静止连点若遭遇服务端瞬移或连续异常移位，立刻中止连点。 */
    private final UseDecider.PositionShiftWatch keepTryingPosition = new UseDecider.PositionShiftWatch();
    private long now;

    UseTask(UseInput input, UseServices services) {
        super("用东西", Phase.RESOLVE, new ProgressTracker(200, 20L * 60 * 10));
        this.input = input;
        this.services = services;
        this.targets = new UseTargets(input, services);
    }

    @Override protected Action enter(Phase phase) {
        Action next = prepared;
        prepared = null;
        return next;
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case RESOLVE -> resolve();
            case TRAVEL -> travel(context);
            case FETCH -> fetch(context);
            case HAND -> follow(context, this::afterHand);
            case APPROACH -> follow(context, this::interactNow);
            case CLEAR -> follow(context, this::cleared);
            case INTERACT -> interact(context);
            case WRITE -> follow(context, this::signWritten);
            case LOOK -> look(context);
            case COLLECT -> collect(context);
        };
    }

    // 找目标：落实到了先看是不是已经骑在上面、会不会炸、是不是写得了字的告示牌，再去备手。
    private Next<Phase> resolve() {
        return switch (targets.lookup()) {
            case UseTargets.Lookup.HandOnly handOnly -> {
                recordProgress("决定只对手上的东西用");
                yield prepareHand();
            }
            case UseTargets.Lookup.Scanning scanning -> {
                recordProgress(scanning.what());
                yield Next.stay();
            }
            case UseTargets.Lookup.TravelFirst travel -> startTravel(travel.where(), travel.heightKnown());
            case UseTargets.Lookup.Failed failed -> stop(failed.problem());
            case UseTargets.Lookup.Found found -> found(found.target(), found.named());
        };
    }

    private Next<Phase> found(ResolvedTarget resolved, boolean named) {
        // 指定了点哪一面：靠近与出手都只认方块朝这一面的表面；要连着点的每一下只等几刻。
        target = resolved.facing(input.face()).quickly(input.keepTrying() > 0);
        targetNamed = named;
        // 会改动它时记下最后由谁放下，收场时写进那句话，主播知情；归属没问到、自己放的不写。
        UseSeams.OthersThings others = services.others();
        String modifiedBy = others == null ? null : others.lastModifiedBy(target);
        othersAtHome = others != null && others.changesWorld(input.item(), target) && modifiedBy != null
                ? "最后由 " + modifiedBy + " 放下" : "";
        worldChangeNotice = others == null ? () -> {} : others.prepareWorldChangeNotice(input.item(), target);
        if (target.isEntity()) {
            if (services.world().riding(target.entityId())) {
                riding = target.typeId();
                return Next.done(TaskResult.done("已经骑在" + target.describe() + "上了"));
            }
            if (input.writesText()) {
                return stop(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, target.describe() + " 不是告示牌，写不了字", null));
            }
        } else {
            Optional<Problem> danger = UseDecider.explosionDanger(services.world().dimension(), target.typeId());
            if (danger.isPresent()) return stop(danger.get());
            if (input.writesText()) {
                Optional<Problem> unwritable = UseDecider.unwritableSign(services.world().sign(target.cell()), target);
                if (unwritable.isPresent()) return stop(unwritable.get());
            }
            // 工作盆对普通固体物品右键既塞不进料，点中侧面还会误写产物过滤框：备手拿料前就说明白，不白跑一趟。
            if (UseDecider.BASIN_BLOCK.equals(target.typeId()) && input.item() != null
                    && !services.world().interactsWithBasin(input.item())) {
                return stop(UseDecider.basinSolidUseProblem(input.item()));
            }
        }
        recordProgress("确定了目标：" + target.describe());
        return prepareHand();
    }

    // 目标那一片没加载：走过去，加载出来就回去重新找；走过一次还没加载就是到不了。
    private Next<Phase> startTravel(WorldPosition where, boolean heightKnown) {
        if (travelled) {
            return stop(Problem.of(Problem.Kind.UNREACHABLE, "走过去之后目标那一片还是没加载", null));
        }
        Optional<Action> walk = services.travel() == null ? Optional.empty()
                : services.travel().toward(where, heightKnown, input.permissions());
        if (walk.isEmpty()) {
            return stop(Problem.of(Problem.Kind.UNREACHABLE, "目标那一片还没加载，出行给不出走过去的走法", null));
        }
        travelTo = where;
        travelled = true;
        return goWith(Phase.TRAVEL, walk.get(), "目标那一片还没加载，先走过去");
    }

    private Next<Phase> travel(TickContext context) {
        if (services.world().loaded(new BlockPos(travelTo.x(), travelTo.y(), travelTo.z()))) {
            return Next.go(Phase.RESOLVE, "目标那一片加载出来了，重新找目标");
        }
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> Next.go(Phase.RESOLVE, "走到了，重新找目标");
            case ActionStatus.Failed failed -> stop(failed.problem());
        };
    }

    // 备手：已经拿好就靠近；在身上就分刻换到主手；身上没有先去拿一件，拿回来还没有才按缺物品收场。
    private Next<Phase> prepareHand() {
        return switch (services.hand().hold(input.item())) {
            case UseSeams.HandPlan.Ready ready -> handReady();
            case UseSeams.HandPlan.Move move -> goWith(Phase.HAND, move.action(), "把要用的东西换到手上");
            case UseSeams.HandPlan.NotCarried notCarried -> fetchMissing();
            case UseSeams.HandPlan.Cannot cannot -> cannot.problem().kind() == Problem.Kind.INVENTORY_FULL
                    && !roomMade && services.space() != null ? makeRoom() : stop(cannot.problem());
        };
    }

    // 要空手却快捷栏、背包都满了：像玩家一样先腾一格（存进网络、箱子或丢零碎），再回来备手；只腾一次。
    // 原先直接说"腾不出空手"，从机械手、置物台上拿东西的一步就停了。
    private Next<Phase> makeRoom() {
        roomMade = true;
        rePrepare = true;
        return goWith(Phase.HAND, services.space().makeRoom(1, "腾出空手去用东西", Set.of(), input.permissions(),
                records()), "背包满了，先腾一格好空出手");
    }

    private Next<Phase> fetchMissing() {
        if (fetched || services.needs() == null) {
            String why = fetched ? "去拿了一件，身上还是没有 " + input.item() : "身上没有 " + input.item();
            return stop(Problem.of(Problem.Kind.NEED_ITEM, why, "先拿到物品，再回来用"));
        }
        WantedItem wanted = input.item().startsWith("#")
                ? WantedItem.ofTag(input.item().substring(1)) : WantedItem.ofItem(input.item());
        Action fetch = services.needs().actionFor(new ItemRequest(wanted, 1, "用东西要拿在手上"), input.permissions(),
                records());
        return goWith(Phase.FETCH, fetch, "身上没有 " + input.item() + "，先去拿一件");
    }

    private Next<Phase> fetch(TickContext context) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> {
                fetched = true;
                recordProgress("拿到了 " + input.item());
                yield prepareHand();
            }
            case ActionStatus.Failed failed -> stop(Problem.of(Problem.Kind.NEED_ITEM,
                    "身上没有 " + input.item() + "，去拿也没拿到：" + failed.problem().message(),
                    failed.problem().suggestion()));
        };
    }

    // 手准备好了：只对手上的东西用就直接出手；空桶点到流动的流体改舀附近同种的源格；然后靠近。
    // 备手这一步做完：刚腾完格子就回头重新备手（这时才腾得出空手），否则手上已经好了。
    private Next<Phase> afterHand() {
        if (rePrepare) {
            rePrepare = false;
            return prepareHand();
        }
        return handReady();
    }

    private Next<Phase> handReady() {
        recordProgress(input.item() == null ? "空手准备好了" : "把 " + input.item() + " 拿到了手上");
        if (target == null) {
            return interactNow();
        }
        // 备手完成后拿实际主手物品对非点名方块目标复核结构保护：
        // 覆盖任意自定义工具标签（备手前还看不出最终拿到手的具体工具），只拦破坏性/舀流体手势，不误拦普通交互。
        if (!target.isEntity() && !targetNamed) {
            String actualHeld = services.world().heldItem().map(UseSeams.ReadsWorld.Held::itemId).orElse(input.item());
            Optional<Problem> refused = UseTargets.guardStructureAfterHand(services, target, actualHeld);
            if (refused.isPresent()) return stop(refused.get());
        }
        if (!target.isEntity() && UseDecider.BASIN_BLOCK.equals(target.typeId()) && !input.writesText()) {
            String held = services.world().heldItem().map(UseSeams.ReadsWorld.Held::itemId).orElse(input.item());
            if (held != null && !services.world().interactsWithBasin(held)) {
                return stop(UseDecider.basinSolidUseProblem(held));
            }
            // 空手取工作盆内的物品且未点名面时自动对准盆口顶面（封死时退回底面），避开水平侧面的产物过滤框。
            if (held == null && target.face() == null) {
                if (services.world().topRimOpen(target.cell())) {
                    target = target.facing(Direction.UP);
                } else if (services.world().bottomOpen(target.cell())) {
                    target = target.facing(Direction.DOWN);
                }
            }
        }
        // 机械动力合成器等限定交互面的方块，在未显式传 face 且非潜行时自动锁定正面（HORIZONTAL_FACING）靠近并右键。
        if (!target.isEntity() && !input.writesText() && !input.sneak() && target.face() == null) {
            services.world().preferredInteractFace(target.cell()).ifPresent(face -> target = target.facing(face));
        }
        if (!target.isEntity() && scoopsNow() && services.world().fluid(target.cell()) == UseSeams.ReadsWorld.Fluid.FLOWING) {
            Optional<BlockPos> source = services.world().nearestSource(target.cell(), SOURCE_SEARCH_RADIUS);
            if (source.isEmpty()) {
                return stop(Problem.of(Problem.Kind.NOT_FOUND, target.describe() + " 是流动的，"
                        + SOURCE_SEARCH_RADIUS + " 格内没有连通的同种源格可舀", null));
            }
            ResolvedTarget redirected = target.movedTo(source.get(), "点到的是流动的，改舀最近的源格");
            String heldItem = services.world().heldItem().map(UseSeams.ReadsWorld.Held::itemId).orElse(input.item());
            Optional<Problem> refused = UseTargets.guardRedirectedScoop(services, input, redirected, heldItem);
            if (refused.isPresent()) return stop(refused.get());
            target = redirected;
            recordAttempt("对流动的格子舀", "空桶只舀得起源格，改舀 " + source.get().toShortString());
        }
        return approach();
    }

    private boolean scoopsNow() {
        return ItemUseAim.gestureOf(services.world().heldItem().map(UseSeams.ReadsWorld.Held::itemId).orElse(null))
                == ItemUseAim.Gesture.SCOOP;
    }

    /** 上一次出手被挡住时站的那一格：换站位时不再站它；没被挡过为 null。 */
    private BlockPos blockedStand;
    /** 为空出手腾过一次格子了；腾完要回头重新备手。 */
    private boolean roomMade;
    private boolean rePrepare;
    /** 出手前核过一回手上的东西了：被换掉就换回来，只回头这一次。 */
    private boolean handRechecked;

    // 靠近：方块按格子、实体按它此刻的包围盒；实体已经不在了就是目标没了。
    // 写告示牌时目标带上"只站正面那一侧"，站位候选由站位判断按它筛，不再站到背面去写。
    private Next<Phase> approach() {
        Optional<ApproachTarget> shape = services.world().approachTarget(target, input.writesText(), input.sneak());
        if (shape.isEmpty()) {
            return stop(Problem.of(Problem.Kind.TARGET_GONE, target.describe() + " 已经不在了", "重新 observe"));
        }
        // 上一次站在这一格出手被挡住：换站位时把它排除。不然靠近一核对"这里看得见"就原地不动，又出手、又被挡。
        keepTryingPosition.reset();
        BlockPos avoid = blockedStand;
        Action toward = avoid == null ? services.close().toward(shape.get(), input.permissions())
                : services.close().toward(shape.get(), input.permissions(), avoid::equals);
        return goWith(Phase.APPROACH, toward, "靠近目标");
    }

    // 出手前冻结现场：动作栏原有的话、脚边原有的掉落物，之后才认得出哪些是这一下带来的。
    private Next<Phase> interactNow() {
        // 走过去的路上引擎挖、垫方块会换手上的东西（换上镐、垫脚料）：出手前再核一遍，不对就先换回来再出手，只回头一次。
        // 实机 10-13 拿着水桶走过去，到了手上变成原子拆解机，对草地右键没反应，还被它的电量变化当成生效。
        if (input.item() != null && !handRechecked
                && !(services.hand().hold(input.item()) instanceof UseSeams.HandPlan.Ready)) {
            handRechecked = true;
            recordAttempt("靠近目标", "走到了，手上的东西被换掉了，先把 " + input.item() + " 换回手上");
            return prepareHand();
        }
        return switch (services.interactions().build(target, input.writesText(), input.sneak())) {
            case UseSeams.Built.CannotAim cannot -> aimFailed(cannot.problem());
            case UseSeams.Built.ClearAbove blocked -> clearAbove(blocked);
            case UseSeams.Built.Ready ready -> {
                // 要潜行着右键却没接上按住潜行：如实说做不了，不改成直接右键（那是另一种用法，开的不是同一个东西）。
                if (input.sneak() && services.sneak() == null) {
                    ready.action().close();
                    yield stop(Problem.of(Problem.Kind.UNSUPPORTED, "这里按不了潜行，潜行右键做不了", null));
                }
                interaction = ready;
                backpackBefore = services.world().backpackCounts();
                campfirePlacement.beforeInteract(services.world(), target, ready.heldBefore(), input.sneak());
                adjacentPlacement.beforeInteract(services.world(), target);
                messageBefore = services.refusal() == null ? null : services.refusal().latestMessage().orElse(null);
                chatMark = services.refusal() == null ? 0 : services.refusal().chatMark();
                // 出手前同时留底玩家脚边与目标锚点周边已有掉落物，延伸距离交互下边缘也不漏记旧掉落。
                dropsBefore = snapshotDropsBefore();
                // 连点模式下冻结出手前站位，用于识别服务端强制瞬移或意外位置跳变。
                if (input.keepTrying() > 0) keepTryingPosition.beforeClick(services.world().position());
                // 潜行右键：整一下出手都按着潜行；连着点几下时点完先不起身，下一下直接接上。
                Action strike = input.sneak() ? services.sneak().sneakingInARow(ready.action()) : ready.action();
                yield goWith(Phase.INTERACT, strike, input.sneak() ? "到了能动手的位置，按住潜行出手" : "到了能动手的位置，出手");
            }
        };
    }

    /** 为锄地打掉过的那一格与它原来是什么：同一格打完还压着就不再打第二遍；没打过为 null。 */
    private BlockPos clearedCell;
    private String clearedBlock;

    // 锄、锹被压在上面的野生草木挡着：过了挖方块的许可就先打掉它，打掉后回到出手那一步。
    // 没接上、许可不让、同一格打过一遍还压着，就如实说目标上面压着什么、为什么没打，不绕过许可硬锄。
    private Next<Phase> clearAbove(UseSeams.Built.ClearAbove blocked) {
        String sits = target.describe() + " 上面压着 " + blocked.blockId();
        if (services.clearsAbove() == null || blocked.cell().equals(clearedCell)) {
            return stop(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, sits + "，锄、锹用不上", null));
        }
        Optional<Problem> refused = services.clearsAbove().allowed(input.permissions(), blocked.cell(),
                blocked.blockId());
        if (refused.isPresent()) {
            return stop(Problem.of(refused.get().kind(), sits + "，要先打掉它才能用；" + refused.get().message(),
                    refused.get().suggestion()));
        }
        clearedCell = blocked.cell();
        clearedBlock = blocked.blockId();
        return goWith(Phase.CLEAR, services.clearsAbove().clear(blocked.cell(), blocked.blockId()),
                sits + "，先打掉它");
    }

    // 草打掉了：记下拆掉的那一格，回到出手那一步。打的时候挖掘可能换了手上的东西，出手前再核一遍手。
    private Next<Phase> cleared() {
        recordChange(new Change(Change.Kind.BLOCK_BROKEN, clearedBlock, 1,
                "用之前打掉压在 " + target.describe() + " 上面的"));
        handRechecked = false;
        return interactNow();
    }

    // 交互有了结论就结算；没出手就失败的（瞄不准、目标没了）按站位问题处理。
    private Next<Phase> interact(TickContext context) {
        now = context.gameTick();
        if (keepTryingUntil < 0 && input.keepTrying() > 0) keepTryingUntil = now + 20L * input.keepTrying();
        return switch (runAction(context)) {
            case ActionStatus.Running running -> {
                // 连点等待确认期间每刻核对位置跳变，先推进动作再检查，避免本刻已生效（如骑乘）却被提前误杀。
                if (input.keepTrying() > 0) {
                    Optional<Problem> jumped = keepTryingPosition.checkDuringTick(services.world().position());
                    if (jumped.isPresent()) yield stop(jumped.get());
                }
                yield Next.stay();
            }
            case ActionStatus.Done done -> settle(interactionResult());
            case ActionStatus.Failed failed -> {
                InteractionResult result = interactionResult();
                yield result != null ? settle(result) : aimFailed(failed.problem());
            }
        };
    }

    private InteractionResult interactionResult() {
        Supplier<InteractionResult> result = interaction.result();
        return result == null ? null : result.get();
    }

    // 够不着、被挡住：换个站位再试一次；仍不行或别的原因原样上报。
    private Next<Phase> aimFailed(Problem problem) {
        if (problem.kind() == Problem.Kind.UNREACHABLE && target != null && approachRetries < 1) {
            approachRetries++;
            blockedStand = services.world().feet();
            recordAttempt("对" + target.describe() + "出手", problem.message() + "；换个站位再试一次");
            return approach();
        }
        return stop(problem);
    }

    // 交互结论按四种走向结算：生效记变化接着走；出乎预料照实写并收工；没能确认绝不再试；没生效就停下。
    private Next<Phase> settle(InteractionResult result) {
        if (result == null) {
            return stop(Problem.of(Problem.Kind.STUCK, "交互动作结束了却没有给出结论", null));
        }
        List<String> chatMessages = services.refusal() == null ? List.of()
                : services.refusal().systemMessagesSince(chatMark);
        List<String> refusalMessages = services.refusal() == null ? List.of()
                : services.refusal().refusalMessagesSince(chatMark);
        String held = input.item() != null ? input.item()
                : services.world().heldItem().map(UseSeams.ReadsWorld.Held::itemId).orElse("手上的东西");
        return switch (UseDecider.settle(result, newGameMessage(), refusalMessages, target == null, held)) {
            case UseDecider.Settlement.Applied applied -> {
                // 生效了照常算生效；这段时间聊天栏里服务端说的话原样附上，要不要当回事由 LLM 判断。
                gameMessages.addAll(chatMessages);
                fetched = false;
                approachRetries = 0;
                // 写字时点开编辑界面只是第一步，字写上去才算这一次做成。
                if (!input.writesText()) {
                    recordAppliedEffects(result);
                }
                // 连点还有剩余次数时若发生非骑乘位置跳变，保留本次已确认的变化与计数并按部分完成立即停手。
                if (input.keepTrying() > 0 && appliedTimes < input.count() && !ridingTarget()) {
                    Optional<Problem> jumped = keepTryingPosition.checkDuringTick(services.world().position());
                    if (jumped.isPresent()) yield stop(jumped.get());
                }
                yield afterApplied();
            }
            case UseDecider.Settlement.Unexpected unexpected -> {
                gameMessages.addAll(chatMessages);
                appliedTimes++;
                worldChangeNotice.run();
                recordChange(new Change(target != null && target.isEntity() ? Change.Kind.ENTITY_AFFECTED
                        : Change.Kind.BLOCK_CHANGED, describeWhat(), 1, "交互生效了，现场出乎预料：" + unexpected.scene()));
                yield Next.done(TaskResult.done("确认生效了 " + appliedTimes + " 次；最后这一下现场出乎预料："
                        + unexpected.scene()));
            }
            // 要求了连着点、还没到时候：先复核连点期间没有发生意外位置跳变，再接着点。
            case UseDecider.Settlement.Unconfirmed unconfirmed when now < keepTryingUntil -> retryKeepTrying();
            case UseDecider.Settlement.NotApplied notApplied when now < keepTryingUntil -> retryKeepTrying();
            case UseDecider.Settlement.Unconfirmed unconfirmed -> {
                recordUnconfirmed(new Change(Change.Kind.OTHER, describeWhat(), 1, unconfirmed.scene()));
                yield Next.done(UseDecider.unconfirmed(appliedTimes, unconfirmed.scene()));
            }
            case UseDecider.Settlement.NotApplied notApplied -> {
                // 空手右键原版营火无效果且游戏没有给其他拒绝提示时，直接给出营火不支持右键取物的游戏事实。
                boolean emptyHandCampfire = notApplied.problem().kind() == Problem.Kind.NOT_POSSIBLE_HERE
                        && target != null && !target.isEntity() && interaction.heldBefore() == null
                        && (UseChanges.CAMPFIRE_BLOCKS.contains(target.typeId())
                                || services.world().campfire(target.cell()).isPresent());
                yield stop(emptyHandCampfire ? UseDecider.campfireEmptyHandProblem() : notApplied.problem());
            }
        };
    }

    // 连点重试前复核站位：发生单次大幅跳变或连续异常跳动时中止连点，避免继续盲目右键。
    private Next<Phase> retryKeepTrying() {
        Optional<Problem> jumped = keepTryingPosition.checkBeforeRetry(services.world().position());
        return jumped.isPresent() ? stop(jumped.get()) : interactNow();
    }

    private boolean ridingTarget() {
        return target != null && target.isEntity() && services.world().riding(target.entityId());
    }

    // 出手后动作栏新冒出来的话才算游戏对这一下的回应，出手前就挂着的旧话不算。
    private Optional<String> newGameMessage() {
        if (services.refusal() == null) return Optional.empty();
        return services.refusal().latestMessage().filter(message -> !message.equals(messageBefore));
    }

    // 生效之后：写告示牌，或看看点开的界面，然后捡东西、做下一次。
    private Next<Phase> afterApplied() {
        if (input.writesText()) {
            Optional<SignEditor> editor = services.signEditors() == null ? Optional.empty()
                    : services.signEditors().current();
            if (editor.isEmpty()) {
                return stop(Problem.of(Problem.Kind.REFUSED_BY_GAME, "告示牌的编辑界面没有打开", null));
            }
            writing = new WriteSign(editor.get(), input.textLines());
            return goWith(Phase.WRITE, writing, "编辑界面开了，开始写字");
        }
        BlockPos clickedCell = target != null && !target.isEntity() ? target.cell() : null;
        Direction clickedFace = target != null && !target.isEntity() ? target.face() : null;
        // 拿着指定物品不潜行点开非普通储物箱的模组界面（如 AE2 驱动器）且手中物品还在时，顺手尝试放进空槽。
        String insertItem = input.item() != null && !input.sneak() && !consumedHeldInUse
                && interaction.heldBefore() != null && target != null && !target.isEntity()
                && (services.facilities() == null || !services.facilities().isContainer(target.typeId()))
                ? interaction.heldBefore().itemId() : null;
        menuInsertItem = insertItem;
        Optional<UseSeams.MenuLook> look = services.menus() == null ? Optional.empty()
                : services.menus().opened(interaction.menuBefore(), clickedCell, clickedFace, insertItem);
        if (look.isPresent()) {
            looking = look.get();
            return goWith(Phase.LOOK, looking, insertItem != null ? "点开了界面，尝试放入手中物品并查看内容" : "点开了界面，看看里面有什么");
        }
        return collectOrNext();
    }

    // 写完告示牌：实际写下的字进细节，被截掉的部分进剩余；字写上去才算这一次做成。
    private Next<Phase> signWritten() {
        WriteSign.Written written = writing.written().orElseThrow();
        appliedTimes++;
        worldChangeNotice.run();
        signLines = written.lines();
        recordChange(new Change(Change.Kind.BLOCK_CHANGED, describeWhat(), 1,
                "告示牌写上了字：" + String.join(" / ", signLines)));
        written.missing().forEach(line -> remainingNotices.add("没写上去的字：" + line));
        return collectOrNext();
    }

    // 看完界面：终端界面直接记录真实网络状态与存货（不写假空箱子进世界记忆）；普通容器列得出内容就记进世界记忆。
    private Next<Phase> look(TickContext context) {
        ActionStatus status = runAction(context);
        if (status instanceof ActionStatus.Running) return Next.stay();
        if (looking.isNetworkTerminal()) {
            Optional<String> terminalSummary = looking.networkTerminalSummary();
            if (terminalSummary.isPresent()) {
                openedMenu = terminalSummary.get();
                recordProgress("看清了点开的终端网络存货");
            } else {
                recordAttempt("列出点开的界面", "终端界面开了，网络存货一直没同步过来");
            }
            if (status instanceof ActionStatus.Failed failed) {
                recordAttempt("关上界面", failed.problem().message());
            }
            return collectOrNext();
        }
        Optional<Map<String, Integer>> contents = looking.contents();
        if (contents.isPresent()) {
            openedMenu = UseSummary.formatMenuContents(contents.get());
            if (target != null && !target.isEntity()) {
                // 先在把这次开箱写进世界记忆之前记下"此前有没有开过"，回答协议要求的"这一次点开之前开没开过"。
                WorldPosition spot = new WorldPosition(target.cell().getX(), target.cell().getY(), target.cell().getZ(),
                        services.world().dimension());
                boolean openedBefore = services.memory() != null
                        && services.memory().recordAt(MemoryKind.CONTAINER, spot).map(MemoryRecord::openedBefore).orElse(false);
                boolean naturalLoot = services.facilities() != null
                        && services.facilities().isNaturalLootContainer(target.typeId());
                ContainerWhose origin = services.others() == null
                        ? new ContainerWhose(naturalLoot ? ContainerWhose.Kind.NATURAL_LOOT : ContainerWhose.Kind.UNKNOWN,
                                null, openedBefore)
                        : services.others().containerWhose(target, naturalLoot, openedBefore);
                containerWhose = origin.wire();
                lastModifiedBy = origin.lastModifiedBy();
                containerOpenedBefore = origin.openedBySelf();
                if (services.others() != null) {
                    // 先在把这次开箱写进世界记忆前通报新发现的稀罕战利品，按上回记忆去重。
                    services.others().reportRareLoot(target, origin, spot, looking.preciousItems());
                }
            }
            rememberContainer(List.copyOf(contents.get().keySet()));
            menuInsertion = looking.insertion();
            if (menuInsertion == UseSeams.MenuLook.Insertion.INSERTED && menuInsertItem != null) {
                recordChange(new Change(Change.Kind.ITEM_STORED, menuInsertItem, 1,
                        "放进了 " + describeTarget()));
            }
            recordProgress("看清了点开的界面");
        } else {
            if (looking.unrecognizedLayout() && target != null && !target.isEntity()) {
                openedUnrecognizedBlockMenu = true;
                recordAttempt("列出点开的界面", "这个方块的界面不在已支持的布局里，看不出里面有什么、也不确认怎么加工，已关上界面");
            } else {
                recordAttempt("列出点开的界面", "界面认不出或内容一直没同步完，列不出里面有什么");
            }
        }
        if (status instanceof ActionStatus.Failed failed) {
            recordAttempt("关上界面", failed.problem().message());
        }
        return collectOrNext();
    }

    // 放下来的是床、工作台、熔炉这类设施：生效确认一过就往世界记忆补一条「自己放下」的账，
    // 「收回自己放下的东西」认的就是这条；睡过的账由夜休睡下时再记。
    // 现场找不到放下的方块落在哪格（没同步过来、放出去的其实是别的）就不记，不瞎猜位置。
    private void rememberPlacedFacility() {
        if (services.memory() == null || services.facilities() == null) return;
        PlacedFacility.judge(services.world(), interaction, target, services.facilities()).ifPresent(placed -> {
            BlockPos cell = placed.cell();
            services.memory().rememberWorkstationUsed(new WorldPosition(cell.getX(), cell.getY(), cell.getZ(),
                    services.world().dimension()), placed.blockType(), Instant.now());
        });
    }

    // 世界记忆按物品记"里面有什么"（取东西时按它找箱子），件数以到场再开为准；
    // 压印器、样板供应器等机器虽能打开界面看槽位，但不是通用储物箱，不写进容器表以免存取东西误把它当箱子。
    private void rememberContainer(List<String> itemIds) {
        if (services.memory() == null || target == null || target.isEntity()) return;
        if (services.facilities() != null && !services.facilities().isContainer(target.typeId())) return;
        BlockPos cell = target.cell();
        services.memory().rememberContainerOpened(new WorldPosition(cell.getX(), cell.getY(), cell.getZ(),
                services.world().dimension()), target.typeId(), itemIds, Instant.now());
    }

    // 生效后按出手前后真实世界变化记账：识别拆解转化、营火上食、邻格落块或未知加工容器，避免误报。
    private void recordAppliedEffects(InteractionResult result) {
        appliedTimes++;
        worldChangeNotice.run();
        UseChanges.AppliedFinding finding = UseChanges.recordAppliedEffects(this::recordChange, services.world(),
                services.facilities(), target, interaction, result, backpackBefore,
                adjacentPlacement, campfirePlacement, input.sneak());
        if (finding.consumedHeld()) consumedHeldInUse = true;
        if (finding.disassembledSummary() != null) disassembledSummary = finding.disassembledSummary();
        if (finding.placedIntoProcessor()) placedIntoUnrecognizedProcessor = true;
        if (finding.placedBesideSummary() != null) placedBesideSummary = finding.placedBesideSummary();
        rememberPlacedFacility();
    }

    // 顺手捡起这一下新掉出来的东西：扫描圆心锚定在被交互目标坐标并收窄因果半径与时长上限，
    // 防止点机器或方块时反被 6-8 格外运转中的刷料粉碎轮掉落链磁吸漂移。
    private Next<Phase> collectOrNext() {
        if (services.drops() == null) return nextOrDone();
        BlockPos anchor = interactionPickupAnchor();
        return goWith(Phase.COLLECT,
                services.drops().pickUpNewSince(dropsBefore, PicksUpDrops.Scope.aroundInteraction(anchor)),
                "捡起掉出的东西");
    }

    private BlockPos interactionPickupAnchor() {
        if (target == null) return services.world().feet();
        if (!target.isEntity()) return target.cell();
        return services.world().entity(target.entityId())
                .map(UseSeams.ReadsWorld.SeenEntity::cell)
                .orElseGet(services.world()::feet);
    }

    private Set<Integer> snapshotDropsBefore() {
        if (services.drops() == null) return Set.of();
        Set<Integer> before = new HashSet<>(services.drops().nearby());
        before.addAll(services.drops().nearby(PicksUpDrops.Scope.aroundInteraction(interactionPickupAnchor())));
        return before;
    }

    private Next<Phase> collect(TickContext context) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> {
                recordProgress("捡起了掉出的东西");
                yield nextOrDone();
            }
            case ActionStatus.Failed failed -> {
                recordAttempt("捡起掉出的东西", failed.problem().message());
                yield nextOrDone();
            }
        };
    }

    private Next<Phase> nextOrDone() {
        // 持物非潜行对原版炉类只点开界面未放入任何物品时立即失败说明并扣回预加计数，不再误报“确认生效（里面有：空的）”。
        Optional<Problem> furnaceNoInsert = UseDecider.vanillaFurnaceNoInsert(target, input.item(), input.sneak(),
                consumedHeldInUse, looking != null && !looking.isNetworkTerminal(), menuInsertion, openedMenu,
                target != null && !target.isEntity() && services.world().isVanillaFurnace(target.cell()));
        if (furnaceNoInsert.isPresent()) {
            if (appliedTimes > 0) appliedTimes--;
            return Next.fail(furnaceNoInsert.get());
        }
        // 非潜行持方块物品点机器却落块在邻格：扣回本次生效计数、清空临时标记并立即停手，绝不虚计或继续乱放后续次数。
        if (placedBesideSummary != null) {
            String message = placedBesideSummary;
            placedBesideSummary = null;
            if (appliedTimes > 0) appliedTimes--;
            return stop(UseDecider.placedBesideProblem(message));
        }
        if (appliedTimes < input.count()) {
            keepTryingPosition.reset();
            recordProgress("第 " + appliedTimes + " 次做完了，做下一次");
            return prepareHand();
        }
        String tail = doorNow() + (othersAtHome.isEmpty() ? "" : "；它" + othersAtHome);
        String campfireSummary = campfirePlacement.summary(services.world(), target, disassembledSummary != null);
        return Next.done(UseSummary.finish(new UseSummary.Facts(describeTarget(), input.item(), appliedTimes, tail,
                placedIntoUnrecognizedProcessor, openedUnrecognizedBlockMenu,
                consumedHeldInUse, openedMenu, disassembledSummary, menuInsertion, menuInsertItem, campfireSummary)));
    }

    // 开关门、活板门、栅栏门：收场时按现场写明门现在开着还是关着，点两下回到原样也看得出来；别的方块不加。
    private String doorNow() {
        if (target == null || target.isEntity()) return "";
        return services.world().doorOpen(target.cell()).map(open -> open ? "；门现在开着" : "；门现在关着").orElse("");
    }

    // 子动作的通用走向：还在做就等；做完了执行给定走向；失败了按"停下"结算。
    private Next<Phase> follow(TickContext context, Supplier<Next<Phase>> whenDone) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> whenDone.get();
            case ActionStatus.Failed failed -> stop(failed.problem());
        };
    }

    // 停下：一次都没做成按失败；做成过几次按部分完成，写明做成了几次。
    private Next<Phase> stop(Problem problem) {
        return appliedTimes == 0 ? Next.fail(problem)
                : Next.done(UseDecider.stopped(appliedTimes, input.count(), problem));
    }

    private Next<Phase> goWith(Phase phase, Action action, String why) {
        prepared = action;
        return Next.go(phase, why);
    }

    // 变化里"动了什么"：目标是实体记实体类型，是方块记方块类型，只对手上的东西用记物品。
    private String describeWhat() {
        if (target != null) return target.typeId();
        return input.item() != null ? input.item() : "空手";
    }

    private String describeTarget() {
        return target == null ? "手上的东西" : target.describe();
    }

    @Override protected ResultDetails details() {
        Boolean unrecognized = placedIntoUnrecognizedProcessor || openedUnrecognizedBlockMenu ? Boolean.TRUE : null;
        return new UseDetails(appliedTimes, UseDecider.usedOn(target, input), openedMenu, containerWhose,
                lastModifiedBy, containerOpenedBefore, signLines, riding, gameMessages, unrecognized);
    }

    @Override protected List<String> remaining() {
        List<String> left = new ArrayList<>(remainingNotices);
        if (appliedTimes < input.count()) {
            left.add("还有 " + (input.count() - appliedTimes) + " 次没做");
        }
        return left;
    }

    @Override protected String describePhase(Phase value) {
        return UseSummary.describePhase(value);
    }
}
