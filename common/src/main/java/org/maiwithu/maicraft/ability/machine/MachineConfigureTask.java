// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.ability.machine.spi.StructureWorksites;
import org.maiwithu.maicraft.kernel.goal.Permissions;
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
 * 改机器设置的任务：认机器、一次报全不认识的设置项、已经对的不再动，
 * 之后逐项交给机器类型的改法（手持配置器点面、开界面加规则），每改一下读回核对。
 *
 * <p>配置器一类是循环切换：改完读回不是要的值就再改一下，读回的值转了一圈还没到
 * 就如实说做不到，不无限点下去。改好一项记一项；后面的项做不了时，前面的照实算数。
 *
 * <p>改组装好的物理结构上的机器（船上的无线红石打字机）：在结构的方块里找认这些设置项的那台，几台时挑离角色最近的
 * （开船时同名的键也按最近的那台按）；读、改都走机器类型在结构上的那一套，走过去用结构上的靠近——坐在座位上够得着就坐着改，
 * 够不着不离开座位、照实说；船在动先等它停。说的时候写局部坐标。
 */
final class MachineConfigureTask extends PhasedTask<MachineConfigureTask.Phase> {

    /** 认机器 → 逐项改。 */
    enum Phase { RESOLVE, CHANGE }

    private final MachineConfigureInput input;
    private final MachineServices services;
    private final MachineTargets targets;
    /** 落实下来的机器。 */
    private MachineType type;
    private BlockPos cell;
    /** 还没改好的项，按给定的顺序。 */
    private final Map<String, String> pending = new LinkedHashMap<>();
    /** 每项在改之前读到的值；改完读回的值进结果。 */
    private final List<MachineConfigureDetails.Applied> applied = new ArrayList<>();
    /** 每项设置在读回时见过的值：同一个值再出现说明转满了一圈。 */
    private final Map<String, Set<String>> seenValues = new LinkedHashMap<>();
    /** 当前正在改的项。 */
    private String currentKey;
    /** 下一刻要推进的动作：上一刻按现场备好，进入阶段时交给基类。 */
    private Action prepared;
    /** 结构上的那台说的时候写哪一格、船上一共几台；改世界里的为空串。 */
    private String onStructureNote = "";

    /** 结构上逐格找机器最多找这么多格；再大的结构不在这里找。 */
    static final int MAX_STRUCTURE_CELLS = 32_768;

    MachineConfigureTask(MachineConfigureInput input, MachineServices services) {
        super("改机器设置", Phase.RESOLVE, new ProgressTracker(600, 3600));
        this.input = input;
        this.services = services;
        this.targets = new MachineTargets(services);
    }

    @Override protected Action enter(Phase phase) {
        Action next = prepared;
        prepared = null;
        return next;
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case RESOLVE -> resolve();
            case CHANGE -> change(context);
        };
    }

    // 认机器：目标那一格没加载按到不了说；没有机器类型认领以不支持结束；不认识的设置项一次报全。
    private Next<Phase> resolve() {
        if (input.onStructure() != null) {
            Optional<Problem> notFound = locateOnStructure();
            if (notFound.isPresent()) return Next.fail(notFound.get());
            return checkKeys();
        }
        MachineTargets.Resolution resolved = targets.resolve(input.target());
        if (resolved instanceof MachineTargets.Resolution.DeadEnd dead) {
            return Next.fail(dead.problem());
        }
        MachineTargets.Resolution.Found found = (MachineTargets.Resolution.Found) resolved;
        BlockState state = found.state();
        if (state == null) {
            return Next.fail(Problem.of(Problem.Kind.UNREACHABLE,
                    "目标那一格还没加载，看不了机器", "先走近一点再试"));
        }
        type = services.claiming(state);
        cell = found.cell();
        if (type == null) {
            return Next.fail(services.unclaimedHere(state, "改设置"));
        }
        return checkKeys();
    }

    // 不认识的设置项一次报全；已经就是要的值的不动。
    private Next<Phase> checkKeys() {
        List<String> unknownKeys = unknownKeys(settingsNow());
        if (!unknownKeys.isEmpty()) {
            List<String> legal = settingsNow().stream().map(MachineSetting::key).toList();
            return Next.fail(Problem.of(Problem.Kind.INVALID_PARAMETER,
                    "这台机器不认识这些设置项：" + String.join("、", unknownKeys)
                            + "；它认的是：" + String.join("、", legal)));
        }
        // 已经就是要的值的项不动：逐项记下"本来就是"，结果里能看到读回的值。
        for (Map.Entry<String, String> wanted : input.settings().entrySet()) {
            String now = readBack(wanted.getKey());
            if (!now.equals(wanted.getValue())) {
                pending.put(wanted.getKey(), wanted.getValue());
                seenValues.put(wanted.getKey(), new LinkedHashSet<>());
            } else {
                applied.add(new MachineConfigureDetails.Applied(wanted.getKey(), now, now));
            }
        }
        if (pending.isEmpty()) {
            recordProgress("每项设置本来就是要求的样子");
            return Next.done(TaskResult.done(describeMachine() + "的设置不用改"));
        }
        currentKey = pending.keySet().iterator().next();
        recordProgress("要改 " + pending.size() + " 项设置");
        return Next.go(Phase.CHANGE, "开始逐项改设置");
    }

    // 逐项改：改一下 → 读回 → 不对再改；读回的值见过一次说明转满了一圈，这一项做不到。
    private Next<Phase> change(TickContext context) {
        if (action() != null) {
            return switch (runAction(context)) {
                case ActionStatus.Running running -> Next.stay();
                // 改的那一下做完了：重进阶段收掉旧动作，下一刻读回核对。
                case ActionStatus.Done done -> Next.go(Phase.CHANGE,
                        "「" + currentKey + "」点过了，读回看看");
                case ActionStatus.Failed failed -> stop(failed.problem());
            };
        }
        String now = readBack(currentKey);
        String wanted = pending.get(currentKey);
        if (now.equals(wanted)) {
            return itemDone(now);
        }
        if (!seenValues.get(currentKey).add(now)) {
            // 同一个值第二次出现：这一项在几个值之间打转，到不了要的值。
            return stop(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, describeMachine()
                    + "的「" + currentKey + "」在几个值之间打转，读回 " + now
                    + "，到不了要的 " + wanted));
        }
        recordProgress("「" + currentKey + "」读回 " + now + "，还要改成 " + wanted);
        Optional<Action> changing = input.onStructure() == null
                ? type.change(cell, currentKey, wanted, input.permissions())
                : type.changeOnStructure(input.onStructure().structureId(), cell, currentKey, wanted, input.permissions(),
                        input.onStructure().worksite().closeKeepingSeat());
        return changing
                .map(action -> {
                    prepared = action;
                    return Next.go(Phase.CHANGE, "改「" + currentKey + "」");
                })
                .orElseGet(() -> stop(Problem.of(Problem.Kind.UNSUPPORTED,
                        describeMachine() + "改不了「" + currentKey + "」这一项")));
    }

    // 一项改好：记变化与读回，换下一项；全改完才结束。
    private Next<Phase> itemDone(String after) {
        String before = seenValues.get(currentKey).isEmpty() ? after : firstSeen(currentKey);
        applied.add(new MachineConfigureDetails.Applied(currentKey, before, after));
        recordChange(new Change(Change.Kind.BLOCK_CHANGED, type.id(), 1,
                currentKey + "：" + before + " → " + after));
        recordProgress("「" + currentKey + "」改成 " + after);
        pending.remove(currentKey);
        currentKey = pending.isEmpty() ? null : pending.keySet().iterator().next();
        if (currentKey == null) {
            return Next.done(TaskResult.done(describeMachine() + "的 " + applied.size() + " 项设置都改好了"));
        }
        return Next.stay();
    }

    // 停下：一项都没改成按失败；已经改成的照实算数，剩下的进剩余说明。
    private Next<Phase> stop(Problem problem) {
        if (applied.isEmpty()) {
            return Next.fail(problem);
        }
        return Next.done(TaskResult.builder(TaskResult.Status.PARTIAL,
                        describeMachine() + "的设置改好了 " + applied.size() + " 项，没改完")
                .problem(problem).build());
    }

    /** 读回一项设置的现值；这一项被拿掉了或读不到时空串，按"读不到"继续。 */
    private String readBack(String key) {
        return settingsNow().stream()
                .filter(setting -> setting.key().equals(key))
                .map(MachineSetting::value)
                .findFirst()
                .orElse("");
    }

    private String firstSeen(String key) {
        return seenValues.get(key).iterator().next();
    }

    /** 不认识的设置项，一次报全。 */
    private List<String> unknownKeys(List<MachineSetting> known) {
        Set<String> keys = new LinkedHashSet<>(known.stream().map(MachineSetting::key).toList());
        return input.settings().keySet().stream().filter(key -> !keys.contains(key)).toList();
    }

    private String describeMachine() {
        return type.name() + onStructureNote;
    }

    /** 这台机器此刻的设置项：结构上的按结构身份读地块里那一台。 */
    private List<MachineSetting> settingsNow() {
        return input.onStructure() == null ? type.settings(cell)
                : type.settingsOnStructure(input.onStructure().structureId(), cell);
    }

    /**
     * 在结构的方块里找认这些设置项的机器：逐格看是哪种机器、在结构上读得到哪些设置项，全认的才算；
     * 几台都认时挑离角色最近的那台（开船时同名的键也按最近的那台按）。找不到交问题。
     */
    private Optional<Problem> locateOnStructure() {
        OnStructure on = input.onStructure();
        StructureWorksites.Worksite worksite = on.worksite();
        BlockPos min = worksite.min();
        BlockPos max = worksite.max();
        long size = (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1);
        if (size > MAX_STRUCTURE_CELLS) {
            return Optional.of(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "这个结构太大（" + size + " 格），在上面找机器读不过来"));
        }
        BlockPos me = services.world().playerSpot().map(spot -> worksite.plotCellAt(Vec3.atBottomCenterOf(spot.pos())))
                .orElse(min);
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos at : BlockPos.betweenClosed(min, max)) {
            if (!worksite.blocks().loaded(at)) continue;
            BlockState state = worksite.blocks().state(at);
            MachineType claiming = state.isAir() ? null : services.claiming(state);
            if (claiming == null) continue;
            Set<String> keys = new HashSet<>(claiming.settingsOnStructure(on.structureId(), at).stream()
                    .map(MachineSetting::key).toList());
            if (keys.containsAll(input.settings().keySet())) found.add(at.immutable());
        }
        if (found.isEmpty()) {
            return Optional.of(Problem.of(Problem.Kind.UNSUPPORTED, "船上没有认「" + String.join("、", input.settings().keySet())
                    + "」这些设置项的机器", "船上改得了的是无线红石打字机的键（key.W 这类）；部件的设置框在船上还不会改"));
        }
        cell = found.stream().min(Comparator.comparingDouble(at -> at.distSqr(me))).orElseThrow();
        type = services.claiming(worksite.blocks().state(cell));
        onStructureNote = "（船上局部坐标 " + on.origin().local(cell).toShortString() + " 那台"
                + (found.size() > 1 ? "；船上有 " + found.size() + " 台，改的是离角色最近的这台" : "") + "）";
        return Optional.empty();
    }

    @Override protected ResultDetails details() {
        return new MachineConfigureDetails(applied);
    }

    @Override protected List<String> remaining() {
        return List.copyOf(pending.keySet());
    }

    @Override protected String describePhase(Phase value) {
        return switch (value) {
            case RESOLVE -> "认机器、对设置项";
            case CHANGE -> "逐项改并读回";
        };
    }
}
