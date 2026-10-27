// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.sleep;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import java.util.List;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.survival.beds.BedCandidate;
import org.maiwithu.maicraft.behavior.survival.beds.BedChooser;
import org.maiwithu.maicraft.behavior.survival.beds.BedScanner;
import org.maiwithu.maicraft.behavior.survival.beds.ClientBedPlacer;
import org.maiwithu.maicraft.behavior.survival.beds.LiveBedFinder;
import org.maiwithu.maicraft.behavior.survival.beds.PlacesBed;
import org.maiwithu.maicraft.behavior.survival.beds.SleepRules;
import org.maiwithu.maicraft.behavior.acquire.mining.CollectsBlocks;
import org.maiwithu.maicraft.behavior.acquire.engine.OffhandContents;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsRememberedPlaces;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.behavior.survival.night.AutoSleep;
import org.maiwithu.maicraft.behavior.survival.night.NightfallNeed;
import org.maiwithu.maicraft.behavior.inventory.hands.ClientMovesToMainhand;
import org.maiwithu.maicraft.behavior.travel.planning.DestinationResolver;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.game.interaction.OverlayMessages;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.game.world.WorldTime;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.EstimatesGoal;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.GoalEstimate;
import org.maiwithu.maicraft.kernel.ability.GoalEstimate.Effort;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.TargetKind;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 睡觉能力：找一张能用的床躺下，躺下即完成，不保证睡到天亮。白天不干等：先把今晚的床备好
 * （附近没有、身上没有，就走拿到物品引擎去弄一张），然后以"现在睡不了"结束；
 * 夜里附近没床时放下自带的床，或经拿东西的引擎现做一张（杀羊取毛、合成）再睡。
 * 下界与末地的床会爆炸，直接结束不尝试。
 *
 * <p>能力是薄的：选床、靠近、放床、拿床都组合玩家行为层已有的模型；这里的知识只有
 * 睡觉特有的判断——备不备床、现在睡不睡得、床从哪来。夜间自动休息（生存需求）复用
 * 同一套选床与躺下流程，从这里拿判断与任务。
 */
public final class SleepModule implements AbilityModule, EstimatesGoal, NightfallNeed.NightRestMoves,
        NightfallNeed.PreparesNightBed {

    private final Supplier<PlayerContext> context;
    private final LiveBedFinder beds;
    private final PlacesBed placer;
    private final ItemNeeds obtain;
    private final BringsPlayerClose approaches;
    private final CollectsBlocks collects;
    private final ReadsRememberedPlaces rememberedPlaces;
    private final DestinationResolver destinations;
    /** 动作栏的读端：原版拒绝入睡时把为什么写在这里，躺下的确认靠它认出这一下没生效。 */
    private final Supplier<OverlayMessages.Shown> actionBar;
    private final Interactions interactions;
    /** 夜里自动去睡的开关：operation=auto_off / auto_on 改它，夜晚生存需求读它。 */
    private final AutoSleep autoSleep;
    /** 可睡窗口的读缝：生产实现读本地玩家的世界。 */
    private final SleepTask.ReadsSleepWindow sleepWindow =
            moment -> moment.player() != null && WorldTime.canAttemptSleep(moment.player().level());
    /** 本夜试过且睡不上的床：夜休任务来来去去，黑名单跟夜晚走，跨天清空。 */
    private final Set<BlockPos> nightTriedBeds = new HashSet<>();
    private long nightTriedDayIndex = -1;
    /** 白天结论的宽限账：每个睡觉目标上一次白天评估所在的刻（按目标实例记账，结论即清）。 */
    private final Map<Goal, Long> daytimeEvaluatedAt = new IdentityHashMap<>();
    /** 夜休失败上报的节流：同一夜同一句"没睡成"只报第一次（实机 10-16 一晚灌了 53 条），记忆跨任务活着。 */
    private final NightRestReports nightRestReports;

    public SleepModule(Supplier<PlayerContext> context, BlockScanService scans, Protection protection,
            BackpackView backpack, OffhandContents offhand,
            Interactions interactions, BringsPlayerClose approaches, CollectsBlocks collects,
            WorldMemory rememberedPlaces, DestinationResolver destinations,
            ItemNeeds obtain, Supplier<OverlayMessages.Shown> actionBar) {
        this(context, scans, protection, backpack, offhand, interactions, approaches, collects, rememberedPlaces,
                destinations, obtain, actionBar, new AutoSleep());
    }

    /** @param autoSleep 夜里自动去睡的开关，与夜晚生存需求共用一份 */
    public SleepModule(Supplier<PlayerContext> context, BlockScanService scans, Protection protection,
            BackpackView backpack, OffhandContents offhand,
            Interactions interactions, BringsPlayerClose approaches, CollectsBlocks collects,
            WorldMemory rememberedPlaces, DestinationResolver destinations,
            ItemNeeds obtain, Supplier<OverlayMessages.Shown> actionBar, AutoSleep autoSleep) {
        this.autoSleep = Objects.requireNonNull(autoSleep, "autoSleep");
        this.context = Objects.requireNonNull(context, "context");
        this.nightRestReports = new NightRestReports(() -> {
            PlayerContext current = context.get();
            return current == null || current.level() == null
                    ? -1 : WorldTime.dayIndexOf(current.level().getDayTime());
        });
        // 找床也看世界记忆里自己睡过的床，躺下后记住这一张。
        this.beds = new LiveBedFinder(context, scans, protection, backpack, offhand, rememberedPlaces);
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.placer = new ClientBedPlacer(interactions,
                new ClientMovesToMainhand(context), context);
        this.obtain = Objects.requireNonNull(obtain, "obtain");
        this.approaches = Objects.requireNonNull(approaches, "approaches");
        this.collects = Objects.requireNonNull(collects, "collects");
        this.rememberedPlaces = Objects.requireNonNull(rememberedPlaces, "rememberedPlaces");
        this.destinations = Objects.requireNonNull(destinations, "destinations");
        this.actionBar = Objects.requireNonNull(actionBar, "actionBar");
    }

    @Override
    public AbilitySpec spec() {
        return new AbilitySpec("maicraft:sleep", "找一张床躺下睡觉，白天会先备好今晚的床再报告睡不了；"
                        + "也能开、关、查夜里自动去睡（默认开）",
                AbilityDoc.forAbility("sleep"),
                ParamSpecs.of(
                        ParamSpec.of("operation", ParamType.CHOICE).defaultValue("sleep")
                                .choices("sleep", "auto_on", "auto_off", "auto_status")
                                .doc("sleep 现在去睡（默认）/ auto_off 关掉夜里自动去睡（有人说晚上别睡时用）"
                                        + " / auto_on 打开 / auto_status 查开没开").build()),
                Set.of(TargetKind.HERE, TargetKind.LANDMARK, TargetKind.POSITION, TargetKind.SEEN),
                ExecutionMode.CONTROLS_PLAYER,
                Set.of(), List.of(), Listing.LISTED);
    }

    @Override
    public StepDecision decide(StepContext step) {
        // 开关夜里自动去睡：当场改好就结束，不用进游戏动手；已经躺下的不叫醒，只管以后的夜里。
        String operation = step.goal().params().has("operation") ? step.goal().params().text("operation") : "sleep";
        switch (operation) {
            case "auto_off" -> {
                autoSleep.disable();
                return finished(TaskResult.done("夜里自动去睡已关：天黑了不会自己找床睡；"
                        + "要睡时下达 sleep，或用 auto_on 打开"));
            }
            case "auto_on" -> {
                autoSleep.enable();
                return finished(TaskResult.done("夜里自动去睡已开：天黑了有床就在干活间隙自己去睡"));
            }
            case "auto_status" -> {
                return finished(TaskResult.done(autoSleep.enabled() ? "夜里自动去睡开着" : "夜里自动去睡关着"));
            }
            default -> {
            }
        }
        PlayerContext current = context.get();
        if (current == null || current.level() == null) {
            return StepDecision.NOT_READY;
        }
        // 在下界、末地睡会爆炸：不尝试，直接结束并说明，不提问。
        if (SleepRules.bedsExplodeHere(current.level())) {
            return finished(TaskResult.builder(TaskResult.Status.FAILED, "睡不了：这个维度的床会爆炸")
                    .problem(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "这个维度的床会爆炸，睡不了", null))
                    .build());
        }
        // 白天被叫去睡不干等：先把今晚的床备好，然后报告"现在睡不了"，天黑后夜间自动休息自己去睡。
        if (!WorldTime.canAttemptSleep(current.level())) {
            return decideForDaytime(step, current);
        }
        Optional<WorldPosition> area = bedArea(step.goal());
        if (area.isEmpty() && step.goal().target() != null) {
            // 给了目标对象却解析不出（地标没记过、观察编号失效）：如实结束，换个说法重新下达。
            return finished(bedAreaUnclear(step));
        }
        // 没给目标对象就在全范围找：夜里附近有床就该去睡，不强迫说清床区。
        return new StepDecision.Run(new SleepInput(area.orElse(null)));
    }

    // 白天的两步：先备床（跑一次备床任务），备好了或备不成，都以"现在睡不了"收场并交代床备好没有。
    private StepDecision decideForDaytime(StepContext step, PlayerContext current) {
        // /time 改动刚交给服务端时，客户端的日子同步晚一拍：第一次评估先不结论，
        // 隔一拍读到新时刻再定，免得拿改动前的白天旧值把夜里睡觉误判成白天睡觉。
        // 同刻的重复评估算同一拍；结论时清账。
        Long evaluatedAt = daytimeEvaluatedAt.get(step.goal());
        long tick = step.tick().gameTick();
        if (!daytimeGraceOver(evaluatedAt, tick)) {
            daytimeEvaluatedAt.put(step.goal(), tick);
            return StepDecision.NOT_READY;
        }
        daytimeEvaluatedAt.remove(step.goal());
        long minutes = SleepRules.minutesUntilSleepable(current.level());
        String notYet = "现在睡不了，约 " + minutes + " 分钟后可睡";
        boolean bedReady = beds.bedReady(step.tick());
        if (bedReady) {
            return finished(daytimeResult(notYet, true, null));
        }
        if (step.taskResults().isEmpty()) {
            // 还没备过床：先跑备床，跑完回来重新决定这一步。
            return new StepDecision.Run(PrepareBedInput.INSTANCE, true);
        }
        // 备床已经跑过：成了就报备好，不成带着备床给的原因结束。
        TaskResult prepare = step.taskResults().get(step.taskResults().size() - 1);
        if (prepare.status() == TaskResult.Status.DONE && beds.bedReady(step.tick())) {
            return finished(daytimeResult(notYet, true, null));
        }
        return finished(daytimeResult("现在睡不了，也没弄到今晚要睡的床", false,
                prepare.problem() != null ? prepare.problem()
                        : Problem.of(Problem.Kind.NEED_ITEM, "没有床，做床还缺材料", null)));
    }

    /** 白天结论的宽限：首次评估、同刻的重复评估、或与上次隔了不止一拍的（中间跑过别的任务），
     * 都再等一拍；只有恰好隔一拍的复核才带着（已同步的）新读数结论。 */
    static boolean daytimeGraceOver(Long evaluatedAt, long tick) {
        return evaluatedAt != null && tick - evaluatedAt == 1L;
    }

    // 白天的收场：以时间不对结束，remaining 写明入睡，细节交代今晚的床备好没有。
    private TaskResult daytimeResult(String summary, boolean bedReady, Problem problem) {
        TaskResult.Builder builder = TaskResult.builder(TaskResult.Status.PARTIAL, summary)
                .remaining("入睡")
                .details(new SleepDetails(false, SleepDetails.BedSource.WORLD, bedReady, false, false));
        if (problem != null) {
            builder.problem(problem);
        }
        return builder.build();
    }

    // 目标对象落成床区：给了就在这片里选床，没给全范围找。
    private Optional<WorldPosition> bedArea(Goal goal) {
        if (goal.target() == null) {
            return Optional.empty();
        }
        var resolution = destinations.resolve(goal.target(), BedChooser.BED_AREA_RADIUS_BLOCKS);
        if (resolution instanceof DestinationResolver.Resolution.Ready ready) {
            return Optional.of(ready.destination().position());
        }
        if (resolution instanceof DestinationResolver.Resolution.AlreadyThere there) {
            return Optional.of(there.spot());
        }
        return Optional.empty();
    }

    // 目标对象说不清（地标没记过、观察编号失效）：如实结束，换个说法重新下达。
    private TaskResult bedAreaUnclear(StepContext step) {
        return TaskResult.failed("睡觉的地方说不清",
                Problem.of(Problem.Kind.NOT_FOUND, "目标对象解析不出在哪：用坐标、地点名或观察编号说明在哪片床区睡",
                        "重新下达并给 target"));
    }

    private StepDecision finished(TaskResult result) {
        return new StepDecision.Finish(result);
    }

    @Override
    public void registerTasks(TaskFactories factories) {
        factories.register(SleepInput.class, input ->
                new SleepTask(input, currentPermissions(), beds, placer, obtain, approaches,
                        interactions::useBlock, actionBar, beds::carriedBed, sleepWindow,
                        new HashSet<>()));
        factories.register(PrepareBedInput.class, input ->
                new PrepareBedTask(obtain, currentPermissions(), beds::carriedBed));
    }

    // 任务的许可用默认值：睡觉自己不读许可参数，保护与战斗档位由各模型按默认许可把关。
    private Permissions currentPermissions() {
        return Permissions.DEFAULT;
    }

    /** 夜间自动休息怎么落地：读床、放床、拿床、收床与回站位都从这里组合。 */
    @Override
    public Task nightRest(TickContext tick) {
        // 跨夜清黑名单：新的一夜，试过的床重新可试；日期读不出（角色不在）时保持原样。
        if (tick.player() != null) {
            long day = WorldTime.dayIndexOf(tick.player().level().getDayTime());
            if (day != nightTriedDayIndex) {
                nightTriedDayIndex = day;
                nightTriedBeds.clear();
            }
        }
        return new NightRestTask(new SleepInput(null), currentPermissions(), beds, placer, obtain, approaches,
                interactions::useBlock, actionBar, beds::carriedBed, collects, rememberedPlaces,
                moment -> moment.player() != null && moment.player().localPlayer() != null
                        && moment.player().localPlayer().isSleeping(),
                sleepWindow, nightTriedBeds, nightRestReports, interactions::getOutOfBed);
    }

    /** 夜晚这项生存需求读的"今晚有没有床"：附近有能用的床或身上带着床。 */
    public NightfallNeed.ReadsBedAvailability bedAvailability() {
        return beds;
    }

    // 估算：开关类操作当场改好；下界、末地睡不了；身上带着床 → 不用离开、就地放下睡；附近有能用的床 → 按距离分档；
    // 附近还没扫完 → 估不了；都没有就按备床的同一个请求估一张床（附近没羊时是弄不到，备原料不出门找）。
    @Override
    public GoalEstimate estimate(Goal goal, TickContext tick) {
        String what = goal.ability();
        String operation = goal.params().has("operation") ? goal.params().text("operation") : "sleep";
        if (!"sleep".equals(operation)) {
            return new GoalEstimate(Effort.READY, what, "当场改开关，不动手", null, GoalEstimate.SECONDS_STEP, List.of(), false);
        }
        PlayerContext current = context.get();
        if (current != null && current.level() != null && SleepRules.bedsExplodeHere(current.level())) {
            return new GoalEstimate(Effort.BLOCKED, what, "这个维度的床会爆炸，睡不了", null, null, List.of(), false);
        }
        Optional<String> carried = beds.carriedBed();
        if (carried.isPresent()) {
            return new GoalEstimate(Effort.READY, what, "身上带着 " + carried.get() + "，就地放下睡", null,
                    GoalEstimate.SECONDS_STEP, List.of(), false);
        }
        BedScanner.BedScan scan = beds.scan(tick, Set.of(), goal.permissions().protectedLandmarks());
        Optional<BedCandidate> bed = BedChooser.choose(scan.candidates(), Set.of(), bedArea(goal).orElse(null));
        if (bed.isPresent()) {
            double distance = bed.get().distance();
            return new GoalEstimate(GoalEstimate.byDistance(distance), what,
                    "附近有能用的床（" + Math.round(distance) + " 格外），走过去躺下", distance,
                    GoalEstimate.roughSeconds(distance / GoalEstimate.BLOCKS_PER_SECOND + 3), List.of(), false);
        }
        if (!scan.complete()) return GoalEstimate.stillLooking(what, "附近的床还没扫完");
        GoalEstimate made = tonightsBed();
        return new GoalEstimate(made.effort(), what, "附近没有能用的床，要备一张：" + made.how(), made.farthestBlocks(),
                made.roughSeconds(), List.of(), made.stillLooking());
    }

    /** 备今晚的床此刻多费事：和备床任务同一个请求、同一份许可。夜里该先备武器还是先备床拿它来比。 */
    public GoalEstimate tonightsBed() {
        return obtain.estimate(PrepareBedTask.TONIGHTS_BED, currentPermissions());
    }

    /** 没床时怎么备一张：经拿东西的引擎去弄一张床到身上（杀羊取毛、合成都由引擎按途径挑）。 */
    @Override
    public Task prepareBed(TickContext tick) {
        return new PrepareBedTask(obtain, currentPermissions(), beds::carriedBed);
    }
}
