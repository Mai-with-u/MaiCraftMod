// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.loot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.inventory.containers.ContainerChooser;
import org.maiwithu.maicraft.behavior.inventory.containers.LootContainerChooser;
import org.maiwithu.maicraft.behavior.inventory.containers.LootsContainer;
import org.maiwithu.maicraft.behavior.permission.ContainerWhose;
import org.maiwithu.maicraft.behavior.travel.planning.LandmarkResolver;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
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
 * 搜刮任务：挑容器（点名那一格，或在半径内挑最近没开过的战利品容器）→ 清开压住盖子的天然方块 →
 * 点开按价值分级取物并结算战利品账目。
 */
final class LootTask extends PhasedTask<LootTask.Phase> {

    enum Phase { CHOOSE, DIG_LID, LOOT }

    private final LootInput input;
    private final LootSeams.World world;
    private final Deque<LootContainerChooser.Entry> candidates = new ArrayDeque<>();
    private final List<Problem> openFailures = new ArrayList<>();
    private boolean namedTarget;
    private LootContainerChooser.Entry current;
    private LootsContainer looting;
    private LootsContainer.Report settledReport;
    private Action prepared;

    LootTask(LootInput input, LootSeams.World world) {
        super("搜刮战利品容器", Phase.CHOOSE, new ProgressTracker(200, 20L * 60 * 5));
        this.input = Objects.requireNonNull(input, "input");
        this.world = Objects.requireNonNull(world, "world");
    }

    @Override
    protected Action enter(Phase phase) {
        Action next = prepared;
        prepared = null;
        return next;
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case CHOOSE -> choose();
            case DIG_LID -> digLid(context);
            case LOOT -> loot(context);
        };
    }

    private Next<Phase> choose() {
        return switch (input.target()) {
            case null -> chooseInArea(world.feet());
            case Target.Here ignored -> chooseInArea(world.feet());
            case Target.Seen seen when seen.id().startsWith("e") -> chooseEntity(seen.id());
            case Target.Seen seen -> chooseSeen(seen.id());
            case Target.Position pos -> pos.y() == null
                    ? Next.fail(Problem.of(Problem.Kind.INVALID_PARAMETER, "点名容器坐标必须给全 y", null))
                    : chooseNamedCell(new WorldPosition(pos.x(), pos.y(), pos.z(), pos.dimension()), false);
            case Target.Landmark landmark -> chooseLandmark(landmark.name());
            default -> Next.fail(Problem.of(Problem.Kind.INVALID_PARAMETER,
                    "搜刮不接受这种目标：" + input.target().kind(), null));
        };
    }

    private Next<Phase> chooseSeen(String seenId) {
        Optional<WorldPosition> at = world.seen(seenId);
        if (at.isEmpty()) {
            return Next.fail(Problem.of(Problem.Kind.TARGET_GONE, "观察编号 " + seenId + " 对应的东西已经不在了",
                    "重新 observe"));
        }
        return seenId.startsWith("f")
                ? chooseInArea(new BlockPos(at.get().x(), at.get().y(), at.get().z()))
                : chooseNamedCell(at.get(), true);
    }

    private Next<Phase> chooseEntity(String seenId) {
        Optional<LootSeams.EntityContainer> entity = world.entityContainer(seenId);
        if (entity.isEmpty()) {
            return Next.fail(Problem.of(Problem.Kind.TARGET_GONE, "观察编号 " + seenId + " 对应的实体已经不在了",
                    "重新 observe"));
        }
        if (!entity.get().lootable()) {
            return Next.fail(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    seenId + "（" + entity.get().typeId() + "）不是战利品容器", null));
        }
        namedTarget = true;
        looting = world.lootEntity(entity.get(), input.permissions());
        prepared = looting;
        return Next.go(Phase.LOOT, "去搜刮 " + seenId);
    }

    private Next<Phase> chooseLandmark(String name) {
        var resolved = LandmarkResolver.resolve(name, world::landmark, world::signs);
        if (resolved instanceof LandmarkResolver.Resolution.Missing missing) {
            return Next.fail(Problem.of(Problem.Kind.NOT_FOUND, missing.sentence(), null));
        }
        WorldPosition pos = ((LandmarkResolver.Resolution.Found) resolved).position();
        if (world.containerAt(pos, input.permissions().protectedLandmarks()).isPresent()) {
            return chooseNamedCell(pos, false);
        }
        return chooseInArea(new BlockPos(pos.x(), pos.y(), pos.z()));
    }

    private Next<Phase> chooseNamedCell(WorldPosition at, boolean explicitlySeen) {
        namedTarget = true;
        Optional<LootContainerChooser.Candidate> found = world.containerAt(at,
                input.permissions().protectedLandmarks());
        if (found.isEmpty()) {
            return Next.fail(Problem.of(Problem.Kind.NOT_FOUND,
                    "坐标（" + at.x() + ", " + at.y() + ", " + at.z() + "）处没有容器", null));
        }
        LootContainerChooser.Candidate item = found.get();
        if (!explicitlySeen && !item.storageUse().usable()) {
            return Next.fail(Problem.of(Problem.Kind.NEED_APPROVAL,
                    item.container().name() + item.storageUse().describe(), "用观察编号点名它，或放开保护范围"));
        }
        ContainerChooser.Candidate c = item.container();
        if (!c.layoutKnown()) {
            return Next.fail(Problem.of(Problem.Kind.UNSUPPORTED, c.name() + " 的界面布局还不认识，不能搜刮", null));
        }
        if (c.catSitting() || c.lid() == ContainerChooser.Lid.BLOCKED_BY_FOREIGN
                || (c.lid() == ContainerChooser.Lid.BLOCKED_BY_NATURAL && !mayDigLid())) {
            return Next.fail(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, c.name() + " 的盖子打不开", null));
        }
        candidates.add(new LootContainerChooser.Entry(c, item.origin(),
                c.lid() == ContainerChooser.Lid.BLOCKED_BY_NATURAL));
        return startNextCandidate();
    }

    private Next<Phase> chooseInArea(BlockPos center) {
        if (center == null) {
            return Next.fail(Problem.of(Problem.Kind.NOT_FOUND, "这一刻角色不在世界里，找不了战利品容器", null));
        }
        List<LootContainerChooser.Candidate> around = world.around(center, input.radius(),
                input.permissions().protectedLandmarks());
        LootContainerChooser.Pick pick = LootContainerChooser.chooseUnopened(around, mayDigLid(), input.radius());
        if (pick instanceof LootContainerChooser.Pick.NotFound notFound) {
            if (!world.scanComplete()) {
                recordProgress("正在扫附近 " + input.radius() + " 格内的战利品容器");
                return Next.stay();
            }
            return Next.fail(Problem.of(Problem.Kind.NOT_FOUND, notFound.searched(), null));
        }
        candidates.addAll(((LootContainerChooser.Pick.Chosen) pick).ordered());
        return startNextCandidate();
    }

    private Next<Phase> startNextCandidate() {
        current = candidates.pollFirst();
        if (current == null) {
            Problem last = openFailures.isEmpty()
                    ? Problem.of(Problem.Kind.NOT_FOUND, "附近没有能打开的战利品容器", null)
                    : openFailures.getLast();
            return Next.fail(last);
        }
        WorldPosition at = current.container().at();
        if (current.digLidFirst()) {
            Optional<Action> dig = world.digLid(new BlockPos(at.x(), at.y() + 1, at.z()), input.permissions());
            if (dig.isEmpty()) {
                recordAttempt("挖开压住盖子的方块", "无法建立挖掘动作");
                return startNextCandidate();
            }
            prepared = dig.get();
            return Next.go(Phase.DIG_LID, "先挖开压在盖子上的天然方块");
        }
        looting = world.lootBlock(current, input.permissions());
        prepared = looting;
        return Next.go(Phase.LOOT, "走到并搜刮 " + current.container().name());
    }

    private Next<Phase> digLid(TickContext context) {
        return switch (runAction(context)) {
            case ActionStatus.Running ignored -> Next.stay();
            case ActionStatus.Done ignored -> {
                looting = world.lootBlock(current, input.permissions());
                prepared = looting;
                yield Next.go(Phase.LOOT, "盖子清开了，打开搜刮 " + current.container().name());
            }
            case ActionStatus.Failed failed -> {
                openFailures.add(failed.problem());
                recordAttempt("挖开 " + current.container().name() + " 上方的方块", failed.problem().message());
                yield namedTarget ? Next.fail(failed.problem()) : startNextCandidate();
            }
        };
    }

    private Next<Phase> loot(TickContext context) {
        return switch (runAction(context)) {
            case ActionStatus.Running ignored -> Next.stay();
            case ActionStatus.Done ignored -> finishFromReport();
            case ActionStatus.Failed failed -> {
                looting.report().ifPresent(r -> settledReport = r);
                openFailures.add(failed.problem());
                recordAttempt("搜刮 " + (current == null ? "实体容器" : current.container().name()),
                        failed.problem().message());
                yield namedTarget ? Next.fail(failed.problem()) : startNextCandidate();
            }
        };
    }

    private Next<Phase> finishFromReport() {
        settledReport = looting.report().orElseThrow();
        for (String note : settledReport.unconfirmed()) {
            recordUnconfirmed(new Change(Change.Kind.OTHER, settledReport.block(), 1, note));
        }
        if (settledReport.nonLootRefused()) {
            String who = settledReport.origin().whose() == ContainerWhose.Kind.SELF ? "你自己"
                    : settledReport.origin().lastModifiedBy() != null ? settledReport.origin().lastModifiedBy() : "别的玩家";
            return Next.fail(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    "这是" + who + "放的箱子，要拿里面的东西用 obtain 点名拿",
                    "需要拿指定物品时用 obtain 点名要拿的物品与件数"));
        }
        if (settledReport.opened().isEmpty()) {
            return Next.done(TaskResult.done("开了，是空的"));
        }
        for (LootsContainer.GradedItem item : settledReport.taken()) {
            recordChange(new Change(Change.Kind.ITEM_GAINED, item.item(), item.count(), "搜刮战利品容器"));
        }
        if (settledReport.taken().isEmpty() && settledReport.backpackFull()) {
            return Next.fail(Problem.of(Problem.Kind.INVENTORY_FULL,
                    "背包塞满了，战利品容器里的东西一件也放不进背包",
                    "用 deposit 存东西或 drop 腾出格子后再来拿"));
        }
        return Next.done(TaskResult.done(summarizeSuccess(settledReport)));
    }

    private static String summarizeSuccess(LootsContainer.Report report) {
        if (report.taken().isEmpty()) {
            return "开了，里面只有建材、零碎或垃圾，没有拿";
        }
        List<String> parts = new ArrayList<>(report.taken().size());
        for (LootsContainer.GradedItem item : report.taken()) {
            parts.add(item.item() + " ×" + item.count());
        }
        String summary = "搜刮拿走了 " + String.join("、", parts);
        if (report.backpackFull()) {
            summary += "；背包满了，其余放不下的留在了箱里";
        }
        return summary;
    }

    private boolean mayDigLid() {
        var level = input.permissions().changeBlocks();
        return level == Permissions.BlockChanges.NATURAL || level == Permissions.BlockChanges.ANY;
    }

    @Override
    protected ResultDetails details() {
        return settledReport == null ? ResultDetails.NONE : LootDetails.from(settledReport);
    }

    @Override
    protected String describePhase(Phase value) {
        return switch (value) {
            case CHOOSE -> "挑战利品容器";
            case DIG_LID -> "挖开压住盖子的方块";
            case LOOT -> "打开并搜刮战利品容器";
        };
    }
}
