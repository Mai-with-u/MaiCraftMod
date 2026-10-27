// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.ability.machine.spi.InstallReport;
import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.ability.machine.spi.PartCell;
import org.maiwithu.maicraft.behavior.construction.Blueprint;
import org.maiwithu.maicraft.behavior.construction.BlueprintCheck;
import org.maiwithu.maicraft.behavior.construction.CellPlacements;
import org.maiwithu.maicraft.behavior.construction.CellState;
import org.maiwithu.maicraft.behavior.construction.ConstructionDetails;
import org.maiwithu.maicraft.behavior.construction.ConstructionInput;
import org.maiwithu.maicraft.behavior.construction.ConstructionServices;
import org.maiwithu.maicraft.behavior.construction.ConstructionTask;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.construction.ReadsBlocks;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.child.ChildTaskAction;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 按机器蓝图施工的任务：先看档案是否已满足，普通格与流体交给施工引擎，之后装安装段、装部件、
 * 改装后设置（用 machine_configure 的同一套任务），最后整机比对并把补丁并进档案。
 *
 * <p>安装段占的格：蓝图写了方块的（传送带两端的轴、中间要清空的格）先交给引擎放好，这是装段的前提；
 * 没写的标成"由机器放"，引擎不放不清；已经装成的整段交给机器，不让引擎把带子拆回轴。联动放置规则说要等安装段的格
 * （带漏斗、隧道压在传送带上）第一遍不放，装完安装段再走一遍施工放它们。整机比对时安装段与部件的格
 * 一律出"由机器核对"，由认领它们的机器类型按最终结构核对。拆整台是把档案范围换成全清空
 * 交给同一个引擎逐格挖。施工子任务失败按它的问题结束，已放的不回滚；机器条目做不成的不
 * 拦住其余部分，各记各的结局。
 *
 * <p>施工与逐条改设置这两种子任务按动作推进：整台机器被生存需求打断时它们跟着暂停、松手，
 * 先到安全处再停、提前结束时的收尾、此刻能不能打断都转给它们，它们交代的事实并进这次的结果。
 *
 * <p>在组装好的物理结构上施工（指 s# 或跟到结构上的档案）：先等结构停稳再开工，施工中结构连着动起来就停手照实说，
 * 结构没了（最后一块拆掉）档案标成已拆除；施工引擎换上结构上的接缝，整机比对读结构地块里的格，记下的格换成局部坐标；
 * 安装段只装胶，部件与装后设置照实写结构上还不会。
 */
final class MachineBuildTask extends PhasedTask<MachineBuildTask.Phase> {

    /** （结构上先等停稳 →）看是否已满足 → 施工 → 装安装段 → 装部件 → 改设置 → 整机比对并落档案。 */
    enum Phase { WAIT_STILL, PRECHECK, CONSTRUCT, INSTALL, MOUNT, CONFIGURE, SETTLE }

    private static final String BUILD_PURPOSE = "按机器蓝图施工";
    private static final String REMOVE_PURPOSE = "拆机器";

    private final MachineBuildInput input;
    private final MachineServices services;
    private final MachineArchives archives;
    private final WorldMemory memory;
    private final Function<Permissions, ConstructionServices> constructionServices;

    /** 拆整台时施工要落的那份蓝图（档案范围的全清空）；建的时候开工那一刻按现场再定，见 sitePlan。 */
    private final Blueprint removal;
    /** 整机比对用的蓝图：安装段的格保留声明，核对时出"由机器核对"，不冒充逐格放好。 */
    private final Blueprint target;
    private final BlockPos anchor;

    /** 下一刻要推进的动作：上一刻按现场备好，原地重进阶段时交给基类。 */
    private Action prepared;
    /** 这一阶段交给的子任务（施工或一项设置），包成动作推进；一次一个，没在做为 null。 */
    private ChildTaskAction child;
    private TaskResult constructionResult;
    /** 压在还没装的安装段上的格（带漏斗、隧道压在传送带上）：第一遍施工不放，装完安装段再放。 */
    private List<PlannedCell> afterInstall = List.of();
    /** 第二遍施工（装完安装段后放压在上面的格）的结果；没有第二遍为 null。 */
    private TaskResult laterConstruction;
    private Problem carriedProblem;

    private final Deque<MachineBlueprint.Segment> toInstall = new ArrayDeque<>();
    private final Deque<MachineBlueprint.Part> toMount = new ArrayDeque<>();
    private final Deque<MachineBlueprint.Setting> toConfigure = new ArrayDeque<>();
    private final List<MachineBuildDetails.SegmentResult> segmentResults = new ArrayList<>();
    private final List<MachineBuildDetails.PartResult> partResults = new ArrayList<>();
    private final List<MachineBuildDetails.SettingResult> settingResults = new ArrayList<>();
    /** 正在装的一段、一个部件或正在改的一条：动作与子任务收场后按它核对记账。 */
    private MachineBlueprint.Segment installing;
    /** 装这一段的动作：收场时把它顺手做成的事（清掉手上记着的接线起点）并进变化。 */
    private Action laying;
    private MachineBlueprint.Part mounting;
    private MachineBlueprint.Setting configuring;
    private BlueprintCheck.Result check;
    /** 在物理结构上施工时的那几样；在世界里为 null。 */
    private final StructureConstruction onStructure;
    /** 结构上施工收场的原因：开工前一直在动、施工中动起来了、结构没了；照常收场为 null。 */
    private Problem structureStop;

    MachineBuildTask(MachineBuildInput input, MachineServices services, MachineArchives archives,
            WorldMemory memory, Function<Permissions, ConstructionServices> constructionServices) {
        super("按机器蓝图施工", input.onStructure() == null ? Phase.PRECHECK : Phase.WAIT_STILL,
                new ProgressTracker(1200, 216000));
        this.input = input;
        this.onStructure = input.onStructure() == null ? null : new StructureConstruction(input.onStructure());
        this.services = services;
        this.archives = archives;
        this.memory = memory;
        this.constructionServices = constructionServices;
        this.anchor = new BlockPos(input.anchor().x(), input.anchor().y(), input.anchor().z());
        this.removal = input.remove() ? removalPlan() : null;
        this.target = input.remove() ? removal : targetPlan();
        if (!input.remove()) {
            toInstall.addAll(input.blueprint().installations());
            toMount.addAll(input.blueprint().parts());
            toConfigure.addAll(input.blueprint().settings());
        }
    }

    @Override protected Action enter(Phase phase) {
        Action next = prepared;
        prepared = null;
        return next;
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        // 结构上施工：结构没了、动起来了就停手，转去整机比对照实收场，不追着动着的船施工。
        if (onStructure != null && phase != Phase.WAIT_STILL && phase != Phase.SETTLE && structureStop == null) {
            Optional<Next<Phase>> stop = structureStops();
            if (stop.isPresent()) return stop.get();
        }
        return switch (phase) {
            case WAIT_STILL -> waitStill();
            case PRECHECK -> precheck();
            case CONSTRUCT -> construct(context);
            case INSTALL -> install(context);
            case MOUNT -> mount(context);
            case CONFIGURE -> configure(context);
            case SETTLE -> settle();
        };
    }

    // 结构上开工前等它停稳：真人不在飞行中加配重。等满 200 刻还在动、结构没了就照实结束，一格不动。
    private Next<Phase> waitStill() {
        StructureConstruction.Wait wait = onStructure.waitStill();
        if (wait == StructureConstruction.Wait.READY) return Next.go(Phase.PRECHECK, "结构停稳了，开工");
        return wait == StructureConstruction.Wait.WAITING ? Next.stay() : Next.fail(onStructure.cannotStart(wait));
    }

    // 施工中盯着结构：没了（最后一块拆掉）或连着动起来了就停手——手上的施工按取消收尾（已确认的照记），转去整机比对照实收场。
    private Optional<Next<Phase>> structureStops() {
        Optional<Problem> stop = onStructure.stopReason();
        if (stop.isEmpty()) return Optional.empty();
        structureStop = stop.get();
        if (child != null) {
            child.close();
            child = null;
        }
        return Optional.of(Next.go(Phase.SETTLE, structureStop.message()));
    }

    // 已满足：目标是已有档案、补丁没带来任何变化，且结构与机器条目都对 → 一个动作都不做。
    private Next<Phase> precheck() {
        if (input.remove()) {
            return Next.go(Phase.CONSTRUCT, "按档案范围逐格拆");
        }
        if (input.archive() != null && !MachinePatch.changed(input.blueprint(), input.archive().blueprint())) {
            check = compareNow();
            if (check.allMatch() && nothingPending()) {
                recordProgress("开始时已和蓝图一致，一个动作都没做");
                return Next.go(Phase.SETTLE, "开始时已和蓝图一致");
            }
            return Next.go(Phase.CONSTRUCT, "有没对上的地方，从施工走一遍");
        }
        return Next.go(Phase.CONSTRUCT, "开工：普通格与流体先交给施工引擎");
    }

    // 施工：普通格与流体整段交给施工引擎这个子任务；它的核对、备料、清障、倒桶、验收、收临时方块都在里面。
    // 第一遍放普通格；装完安装段后若有压在段上的格（带漏斗、隧道），再走第二遍只放它们。
    private Next<Phase> construct(TickContext context) {
        boolean later = constructionResult != null;
        if (action() == null) {
            Optional<Blueprint> site = input.remove() ? Optional.of(removal) : later ? afterInstallPlan() : Optional.of(sitePlan());
            if (site.isEmpty()) {
                laterConstruction = TaskResult.done("压着的安装段都没装成，没有能放的格");
                return Next.go(nextWorkPhase(), "压在安装段上的格都放不了");
            }
            // 施工包成这一阶段的动作：吃饭、自卫插进来时施工跟着停下松手，建机器提前结束时它按取消收尾。
            prepared = child = new ChildTaskAction(new ConstructionTask(new ConstructionInput(site.get(),
                    input.permissions(), purpose()), construction()), childRecords());
            return Next.go(Phase.CONSTRUCT, later ? "装完安装段，再放压在上面的格" : "交施工引擎放普通格与流体");
        }
        // 施工引擎在干活：它有真实进展（放下一格、挖掉一格）由动作报给整台机器，不被一分钟没进展的卡住判定掐掉。
        if (runAction(context) instanceof ActionStatus.Running) {
            return Next.stay();
        }
        TaskResult result = child.settle();
        child = null;
        if (later) {
            laterConstruction = result;
        } else {
            constructionResult = result;
        }
        if (result.status() == TaskResult.Status.FAILED) {
            return Next.fail(onStructure == null ? result.problem() : onStructure.localize(result.problem()));
        }
        if (result.status() == TaskResult.Status.PARTIAL && carriedProblem == null) {
            carriedProblem = onStructure == null ? result.problem() : onStructure.localize(result.problem());
        }
        return Next.go(nextWorkPhase(), "现场做完，" + (nothingLeft() ? "整机比对" : "装机器自己的部分"));
    }

    // 安装段：已经是要求的样子就过；不然交给机器类型的装法（两端的轴就位后用模组自己的方式连），按最终结构核对。
    private Next<Phase> install(TickContext context) {
        if (action() != null) {
            return switch (runAction(context)) {
                case ActionStatus.Running running -> Next.stay();
                case ActionStatus.Done done -> segmentFinished("");
                case ActionStatus.Failed failed -> segmentFinished(failed.problem().message());
            };
        }
        MachineBlueprint.Segment next = toInstall.poll();
        if (next == null) {
            return Next.go(nextWorkPhase(), "安装段做完");
        }
        installing = next;
        Installation installation = absolute(next);
        if (services.installedBy(installation).isPresent()) {
            return segmentFinished("");
        }
        // 问遍机器类型，肯装的第一家给的动作直接用；都不肯就照实记下这一段没装成。结构上只有胶这种发一包就成的装得了。
        Optional<Action> offered = onStructure != null
                ? onStructure.install(services.machineTypes(), installation, input.permissions())
                : services.machineTypes().stream().map(type -> type.install(installation, input.permissions()))
                        .flatMap(Optional::stream).findFirst();
        if (offered.isPresent()) {
            prepared = offered.get();
            laying = prepared;
            return Next.go(Phase.INSTALL, "装 " + next.kind() + " 这一段");
        }
        return segmentFinished(onStructure != null ? "结构上还不会装 " + next.kind() + "（只有胶能在结构上补）"
                : noTypeFor(installation.kind()));
    }

    // 一段收场：装没装成以机器类型按最终结构核对的结论为准，动作的失败原因带在说明里；
    // 装的动作顺手做成的事（清掉手上记着的接线起点）照实记进变化。
    private Next<Phase> segmentFinished(String why) {
        if (laying instanceof InstallReport report) {
            report.changes().forEach(childRecords()::change);
        }
        laying = null;
        Installation installation = absolute(installing);
        boolean done = services.installedBy(installation).isPresent();
        segmentResults.add(new MachineBuildDetails.SegmentResult(installing.kind(),
                installation.cells().stream().map(this::where).toList(), done, why));
        if (done) {
            recordProgress("装好了 " + installing.kind());
        } else {
            recordAttempt("装 " + installing.kind(), why);
        }
        installing = null;
        return Next.go(toInstall.isEmpty() ? nextWorkPhase() : Phase.INSTALL,
                toInstall.isEmpty() ? "安装段做完" : "装好了这段，接着下一段");
    }

    // 部件：宿主就位后手持部件右键那一面；装上没有以部件出现在那一面为准。
    private Next<Phase> mount(TickContext context) {
        if (action() != null) {
            return switch (runAction(context)) {
                case ActionStatus.Running running -> Next.stay();
                case ActionStatus.Done done -> partFinished("");
                case ActionStatus.Failed failed -> partFinished(failed.problem().message());
            };
        }
        MachineBlueprint.Part next = toMount.poll();
        if (next == null) {
            return Next.go(nextWorkPhase(), "部件做完");
        }
        mounting = next;
        if (onStructure != null) {
            return partFinished("结构上还不会装部件");
        }
        PartCell part = absolute(next);
        Optional<BlockState> host = services.world().stateAt(part.host());
        if (host.isEmpty()) {
            return partFinished("宿主那一格还没加载");
        }
        for (MachineType type : services.machineTypes()) {
            if (type.partProblem(part, host.get()).isPresent()) {
                continue;
            }
            Optional<Action> willing = type.mount(part, input.permissions());
            if (willing.isEmpty()) {
                continue;
            }
            prepared = willing.get();
            return Next.go(Phase.MOUNT, "把 " + next.itemId() + " 装到宿主的" + next.side().getName() + "面");
        }
        return partFinished(noTypeForPart());
    }

    private Next<Phase> partFinished(String why) {
        PartCell part = absolute(mounting);
        boolean done;
        if (!why.isEmpty()) {
            done = false;
        } else {
            done = services.mounted(part);
            if (!done) {
                why = "动作做完了，部件没有出现在那一面";
            }
        }
        partResults.add(new MachineBuildDetails.PartResult(mounting.itemId(), where(part.host()),
                part.side().getName(), done, why));
        if (done) {
            recordProgress("装上了部件 " + mounting.itemId());
        } else {
            recordAttempt("装部件 " + mounting.itemId(), why);
        }
        mounting = null;
        return Next.go(toMount.isEmpty() ? nextWorkPhase() : Phase.MOUNT,
                toMount.isEmpty() ? "部件做完" : "装好了这个，接着下一个");
    }

    // 装后设置：逐条交给 machine_configure 的同一套任务（认机器、循环切换、读回核对），结果收回来记账。
    private Next<Phase> configure(TickContext context) {
        if (action() == null) {
            MachineBlueprint.Setting next = toConfigure.poll();
            if (next == null) {
                return Next.go(Phase.SETTLE, "设置改完，整机比对");
            }
            configuring = next;
            BlockPos at = anchor.offset(next.offset());
            if (onStructure != null) {
                // 结构上的格改设置还不会（machine_configure 指船上的格也还不会）：照实记下没改，不去点。
                settingResults.add(new MachineBuildDetails.SettingResult(next.key(), where(at), false, ""));
                recordAttempt("改设置 " + next.key(), "结构上还不会改设置");
                configuring = null;
                return Next.go(Phase.CONFIGURE, "结构上的设置还不会改，接着下一条");
            }
            // 这一条包成这一阶段的动作：走过去、开界面、循环切换时被打断跟着停下松手，提前结束时它按取消收尾。
            prepared = child = new ChildTaskAction(new MachineConfigureTask(new MachineConfigureInput(
                    new Target.Position(at.getX(), at.getY(), at.getZ(), null),
                    Map.of(next.key(), next.value()), input.permissions()), services), records());
            return Next.go(Phase.CONFIGURE, "改设置「" + next.key() + "」");
        }
        // 改设置的子任务在走过去、开界面、循环切换：它的真实进展由动作报给整台机器，
        // 远处那台机器走一分钟也不会被判"没进展"，已建好的格、装好的段不跟着失败收场。
        if (runAction(context) instanceof ActionStatus.Running) {
            return Next.stay();
        }
        TaskResult result = child.settle();
        child = null;
        MachineBlueprint.Setting done = configuring;
        configuring = null;
        boolean applied = result.status() == TaskResult.Status.DONE;
        String readback = services.readBack(anchor.offset(done.offset()), done.key());
        settingResults.add(new MachineBuildDetails.SettingResult(done.key(),
                where(anchor.offset(done.offset())), applied, readback));
        if (applied) {
            recordProgress("「" + done.key() + "」改好了，读回 " + readback);
        } else {
            recordAttempt("改设置 " + done.key(), result.summary());
            carriedProblem = carriedProblem != null ? carriedProblem : result.problem();
        }
        return Next.go(Phase.CONFIGURE, "这一条改完，接着下一条");
    }

    // 整机比对与落档：结构对不对、装没装成、档案存没存住三件事分开说，互不冒充。
    private Next<Phase> settle() {
        if (structureStop != null && structureStop.kind() == Problem.Kind.TARGET_GONE) {
            return structureGone();
        }
        if (check == null) {
            check = compareNow();
            recordProgress("整机比对：" + describeCheck());
        }
        boolean structureOk = check.allMatch();
        boolean entriesOk = segmentResults.stream().allMatch(MachineBuildDetails.SegmentResult::installed)
                && partResults.stream().allMatch(MachineBuildDetails.PartResult::mounted)
                && settingResults.stream().allMatch(MachineBuildDetails.SettingResult::applied);
        saveArchive(structureOk);
        if (onStructure != null) onStructure.finished();
        boolean allOk = structureOk && entriesOk && constructionDone() && structureStop == null;
        String summary = "机器「" + input.name() + "」"
                + (allOk ? (input.remove() ? "拆完了" : "建好了") : "做到一半：结构与机器条目详见结果细节");
        return allOk ? Next.done(TaskResult.done(summary))
                : Next.done(TaskResult.builder(TaskResult.Status.PARTIAL, summary)
                        .problem(problem()).build());
    }

    // 结构没了：最后一块方块拆掉后 Sable 删掉整个结构。已确认的拆放照算，跟在它上面的档案标成已拆除（蓝图留着）。
    private Next<Phase> structureGone() {
        onStructure.finished();
        if (input.archive() != null && !archives.save(input.archive().markRemoved())) {
            recordAttempt("存档案", "档案「" + input.name() + "」没存进去，施工结果不受影响");
        }
        if (input.remove()) {
            return Next.done(TaskResult.done("机器「" + input.name() + "」拆完了，最后一块拆掉后结构也没了"));
        }
        return Next.done(TaskResult.builder(TaskResult.Status.PARTIAL, "机器「" + input.name() + "」做到一半：结构没了")
                .problem(structureStop).build());
    }

    /** 档案更新：补丁并入或标拆除；存不进只记问题，不改判施工。 */
    private void saveArchive(boolean structureOk) {
        Instant now = Instant.now();
        MachineArchive stored;
        if (input.remove()) {
            stored = existingArchive().markRemoved();
            // 拆掉的机器从记忆里抹掉：位置上已经没有这台机器，留着旧记录只会再骗一次。结构上的不在世界记忆里。
            if (onStructure == null) memory.forget(MemoryKind.MACHINE, input.anchor());
        } else {
            stored = (input.archive() != null ? input.archive() : newArchive())
                    .withBlueprint(input.blueprint(), null, now, checkNote(structureOk));
            // 世界里的机器记进世界记忆；结构上的跟着结构走，地块里的格记进去没有用。
            if (onStructure == null) memory.rememberMachine(input.anchor(), input.name(), now);
        }
        if (!archives.save(stored)) {
            recordAttempt("存档案", "档案「" + input.name() + "」没存进去，施工结果不受影响");
        }
    }

    // 新建的档案：在结构上施工就记在那个结构上（锚点是局部坐标的原点），不然记在世界里。
    private MachineArchive newArchive() {
        return onStructure == null
                ? MachineArchive.fresh(input.name(), input.anchor().dimension(), anchor, input.blueprint(), null)
                : MachineArchive.freshOn(onStructure.structureId(), input.name(), input.anchor().dimension(), anchor,
                        input.blueprint());
    }

    private MachineArchive existingArchive() {
        return input.archive() != null ? input.archive() : archives.find(input.name()).orElseThrow(
                () -> new IllegalStateException("拆机器却找不到档案：" + input.name()));
    }

    private String checkNote(boolean structureOk) {
        return structureOk ? "整机与蓝图一致" : "还有 " + unsettledCells() + " 格没对上";
    }

    /** 组装问题：先说施工没做完的原因，再说机器条目哪几件没做成。 */
    private Problem problem() {
        if (structureStop != null) {
            return structureStop;
        }
        if (carriedProblem != null) {
            return carriedProblem;
        }
        List<String> undone = new ArrayList<>();
        countUndone(segmentResults.stream().filter(result -> !result.installed()).count(), "段安装段", undone);
        countUndone(partResults.stream().filter(result -> !result.mounted()).count(), "个部件", undone);
        countUndone(settingResults.stream().filter(result -> !result.applied()).count(), "条设置", undone);
        if (unsettledCells() > 0) {
            undone.add(unsettledCells() + " 格没对上");
        }
        if (undone.isEmpty()) {
            return Problem.of(Problem.Kind.STUCK, "没有全部做成，原因见结果细节", null);
        }
        return Problem.of(Problem.Kind.UNSUPPORTED, "还有 " + String.join("、", undone) + " 没做成",
                "缺的联动装上后，再下达一次同样的目标会接着做");
    }

    private void countUndone(long count, String unit, List<String> into) {
        if (count > 0) {
            into.add(count + " " + unit);
        }
    }

    @Override protected List<String> remaining() {
        List<String> left = new ArrayList<>();
        if (unsettledCells() > 0) {
            left.add("还有 " + unsettledCells() + " 格没对上");
        }
        for (MachineBlueprint.Segment segment : toInstall) {
            left.add("还没装安装段 " + segment.kind());
        }
        for (MachineBlueprint.Part part : toMount) {
            left.add("还没装部件 " + part.itemId());
        }
        for (MachineBlueprint.Setting setting : toConfigure) {
            left.add("还没改设置 " + setting.key());
        }
        return left;
    }

    @Override protected ResultDetails details() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (check != null) {
            for (CellState state : CellState.values()) {
                int count = check.count(state);
                if (count > 0) {
                    counts.put(state.name().toLowerCase(Locale.ROOT), count);
                }
            }
        }
        List<String> notes = new ArrayList<>();
        if (check != null && !check.complete()) {
            notes.add("有格没加载，整机比对下不了结论");
        }
        return new MachineBuildDetails(input.name(), onStructure == null ? input.anchor() : null, input.remove(),
                check == null ? null : check.complete(), counts, constructionCells(),
                segmentResults, partResults, settingResults, String.join("；", notes),
                onStructure == null ? null : onStructure.seenId(), onStructure == null ? null : onStructure.origin());
    }

    /** 施工子任务结果里的每格结局计数，两遍施工加在一起；没跑到施工（例如已满足提前收场）给空表。 */
    private Map<String, Integer> constructionCells() {
        Map<String, Integer> cells = new LinkedHashMap<>();
        for (TaskResult result : new TaskResult[]{constructionResult, laterConstruction}) {
            if (result != null && result.details() instanceof ConstructionDetails construction) {
                construction.cells().forEach((ending, count) -> cells.merge(ending, count, Integer::sum));
            }
        }
        return cells;
    }

    @Override protected String describePhase(Phase value) {
        return switch (value) {
            case WAIT_STILL -> "等结构停稳";
            case PRECHECK -> "看是否已满足";
            case CONSTRUCT -> "施工：普通格与流体";
            case INSTALL -> "装安装段";
            case MOUNT -> "装部件";
            case CONFIGURE -> "改装后设置";
            case SETTLE -> "整机比对与落档";
        };
    }

    private String purpose() {
        return input.remove() ? REMOVE_PURPOSE : BUILD_PURPOSE;
    }

    /** 还剩哪一步：施工之后按安装段 → 压在段上的格 → 部件 → 设置 → 比对的顺序挑下一个有人要做的阶段。 */
    private Phase nextWorkPhase() {
        if (!toInstall.isEmpty()) {
            return Phase.INSTALL;
        }
        // 安装段装完了：压在上面的格（带漏斗、隧道压在传送带上）这时才放得上，再走一遍施工。
        if (!afterInstall.isEmpty() && constructionResult != null && laterConstruction == null) {
            return Phase.CONSTRUCT;
        }
        if (!toMount.isEmpty()) {
            return Phase.MOUNT;
        }
        if (!toConfigure.isEmpty()) {
            return Phase.CONFIGURE;
        }
        return Phase.SETTLE;
    }

    private boolean nothingLeft() {
        return toInstall.isEmpty() && toMount.isEmpty() && toConfigure.isEmpty();
    }

    /**
     * 施工要落的那份蓝图，开工那一刻按现场定：安装段占的格里，蓝图写了方块的照常交给引擎（传送带两端的轴、
     * 中间要清空的格），没写的标成由机器放（引擎不放、不清）；已经装成的安装段整段交给机器，不让引擎把带子拆回轴。
     */
    private Blueprint sitePlan() {
        Blueprint laid = Blueprint.at(input.anchor().dimension(), anchor, Rotation.NONE, input.blueprint().cells());
        Set<BlockPos> settled = installedCells();
        Set<BlockPos> undeclared = installationCells();
        Set<BlockPos> notInstalled = installationCells();
        notInstalled.removeAll(settled);
        CellPlacements placements = placements();
        List<PlannedCell> held = new ArrayList<>();
        List<PlannedCell> adjusted = new ArrayList<>();
        for (PlannedCell cell : laid.cells()) {
            undeclared.remove(cell.pos());
            // 联动放置规则说要等的格落在还没装的安装段上（带漏斗等下面的带子）：这一遍不放，装完安装段再放。
            if (placements.waitsFor(cell).stream().anyMatch(notInstalled::contains)) {
                held.add(cell);
                continue;
            }
            adjusted.add(settled.contains(cell.pos()) ? cell.byMachine() : cell);
        }
        afterInstall = List.copyOf(held);
        for (BlockPos left : undeclared) {
            // 蓝图没逐格声明的安装段格：照样标给机器，引擎不碰，核对时由机器认。
            adjusted.add(PlannedCell.air(left).byMachine());
        }
        return new Blueprint(laid.dimension(), anchor, adjusted);
    }

    /** 第二遍施工的蓝图：压在安装段上的格，压着的段装成了才放；段没装成的照实记下不放，全都放不了为空。 */
    private Optional<Blueprint> afterInstallPlan() {
        Set<BlockPos> notInstalled = installationCells();
        notInstalled.removeAll(installedCells());
        CellPlacements placements = placements();
        List<PlannedCell> ready = new ArrayList<>();
        for (PlannedCell cell : afterInstall) {
            if (placements.waitsFor(cell).stream().anyMatch(notInstalled::contains)) {
                recordAttempt("放 " + cell.pos().toShortString(), "压着的安装段没装成，这一格没放");
            } else {
                ready.add(cell);
            }
        }
        return ready.isEmpty() ? Optional.empty() : Optional.of(new Blueprint(input.anchor().dimension(), anchor, ready));
    }

    /** 联动的放置规则：哪些格要等别的格先放好，和施工引擎问的是同一张表。 */
    private CellPlacements placements() {
        return CellPlacements.of(constructionServices.apply(input.permissions()).placementRules());
    }

    /** 此刻已经按要求装成的安装段占的格：这些格整段由机器管。 */
    private Set<BlockPos> installedCells() {
        Set<BlockPos> cells = new LinkedHashSet<>();
        for (MachineBlueprint.Segment segment : input.blueprint().installations()) {
            if (services.installedBy(absolute(segment)).isPresent()) {
                segment.offsets().forEach(offset -> cells.add(anchor.offset(offset)));
            }
        }
        return cells;
    }

    /** 比对用的蓝图：安装段占的格标成由机器核对，保留原声明，不冒充逐格放好；和看机器的整机比对同一份。 */
    private Blueprint targetPlan() {
        return ArchiveCheck.targetPlan(input.blueprint(), anchor, input.anchor().dimension());
    }

    private Set<BlockPos> installationCells() {
        Set<BlockPos> cells = new LinkedHashSet<>();
        for (MachineBlueprint.Segment segment : input.blueprint().installations()) {
            for (BlockPos offset : segment.offsets()) {
                cells.add(anchor.offset(offset));
            }
        }
        return cells;
    }

    /**
     * 拆整台的施工蓝图：档案 cells 逐格换成清空，安装段只加上它自己放下的格（机械臂、ME 线缆），交给施工引擎挖。
     * 安装段连到的另一头不拆：实机拆一份只为拉链条的档案，把链条另一头主城竖轴顶上的链条输送机也拆了，主城断了动力。
     */
    private Blueprint removalPlan() {
        MachineBlueprint from = existingArchive().blueprint();
        Set<BlockPos> declared = new LinkedHashSet<>();
        List<PlannedCell> cells = new ArrayList<>();
        for (PlannedCell cell : from.cells()) {
            cells.add(PlannedCell.air(anchor.offset(cell.pos())));
            declared.add(anchor.offset(cell.pos()));
        }
        for (MachineBlueprint.Segment segment : from.installations()) {
            for (BlockPos at : placedBy(absolute(segment))) {
                if (declared.add(at)) {
                    cells.add(PlannedCell.air(at));
                }
            }
        }
        return new Blueprint(input.anchor().dimension(), anchor, cells);
    }

    // 问遍机器类型，认这种安装段的第一家说这一段自己放了哪些格；都不认（模组停用了）就一格不拆，宁可留下也不拆错。
    private List<BlockPos> placedBy(Installation installation) {
        for (MachineType type : services.machineTypes()) {
            Optional<List<BlockPos>> placed = type.placedBy(installation);
            if (placed.isPresent()) return placed.get();
        }
        return List.of();
    }

    private BlueprintCheck.Result compareNow() {
        return BlueprintCheck.compare(target, onStructure == null ? worldView : onStructure.blocks(), Set.of());
    }

    /** 施工引擎用的接缝：在结构上施工换成结构上的读格、靠近、点、挖。 */
    private ConstructionServices construction() {
        ConstructionServices onLand = constructionServices.apply(input.permissions());
        return onStructure == null ? onLand : onStructure.services(onLand, input.permissions());
    }

    /** 施工与装段动作的记账口：结构上记下的格换成局部坐标再并进结果。 */
    private TaskRecords childRecords() {
        return onStructure == null ? records() : onStructure.records(records());
    }

    /** 结果里写一格：世界里照写，结构上写局部坐标。 */
    private String where(BlockPos at) {
        return onStructure == null ? at.toShortString() : onStructure.local(at);
    }

    /** 没对上的格数：要施工引擎动手的，加没加载下不了结论的。 */
    private long unsettledCells() {
        return check == null ? 0 : check.states().values().stream()
                .filter(state -> state.needsWork() || state == CellState.UNKNOWN).count();
    }

    private String describeCheck() {
        StringBuilder line = new StringBuilder();
        for (CellState state : CellState.values()) {
            int count = check.count(state);
            if (count > 0) {
                line.append(line.isEmpty() ? "" : "，").append(state.name().toLowerCase(Locale.ROOT)).append(" ").append(count);
            }
        }
        return line.toString();
    }

    /** 施工子任务做全了没有：没跑到施工（已满足提前收场）或两遍都做成算过。 */
    private boolean constructionDone() {
        return (constructionResult == null || constructionResult.status() != TaskResult.Status.PARTIAL)
                && (laterConstruction == null || laterConstruction.status() != TaskResult.Status.PARTIAL);
    }

    /** 还有没有没做成的机器条目：已满足判断用。 */
    private boolean nothingPending() {
        for (MachineBlueprint.Segment segment : input.blueprint().installations()) {
            if (services.installedBy(absolute(segment)).isEmpty()) {
                return false;
            }
        }
        for (MachineBlueprint.Part part : input.blueprint().parts()) {
            PartCell cell = absolute(part);
            if (services.world().stateAt(cell.host()).isEmpty() || !services.mounted(cell)) {
                return false;
            }
        }
        for (MachineBlueprint.Setting setting : input.blueprint().settings()) {
            if (!settingAlready(anchor.offset(setting.offset()), setting)) {
                return false;
            }
        }
        return true;
    }

    private boolean settingAlready(BlockPos at, MachineBlueprint.Setting wanted) {
        return wanted.value().equals(services.readBack(at, wanted.key()));
    }

    private Installation absolute(MachineBlueprint.Segment segment) {
        return new Installation(segment.kind(), segment.offsets().stream().map(anchor::offset).toList(), segment.roles());
    }

    private PartCell absolute(MachineBlueprint.Part part) {
        return new PartCell(anchor.offset(part.offset()), part.side(), part.itemId());
    }

    private String noTypeFor(String kind) {
        return services.machineTypes().isEmpty()
                ? "这个实例没有登记任何机器联动，认不出安装段 " + kind
                : "没有机器类型认安装段 " + kind;
    }

    private String noTypeForPart() {
        return services.machineTypes().isEmpty()
                ? "这个实例没有登记任何机器联动，认不出这个部件"
                : "没有机器类型肯把这个部件装在宿主的这一面";
    }

    /** 读现场给整机比对：一格有没有加载看现场视图给没给状态。 */
    private final ReadsBlocks worldView = new ReadsBlocks() {
        @Override public boolean loaded(BlockPos pos) {
            return services.world().stateAt(pos).isPresent();
        }

        @Override public BlockState state(BlockPos pos) {
            return services.world().stateAt(pos).orElse(Blocks.AIR.defaultBlockState());
        }
    };
}
