// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.ability.machine.spi.StructureWorksites;
import org.maiwithu.maicraft.behavior.construction.ConstructionServices;
import org.maiwithu.maicraft.behavior.construction.ReadsBlocks;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TaskRecords;

/**
 * 机器施工落在组装好的物理结构上时多出来的几样：开工前等结构停稳（连着 10 刻不动，200 刻还在动就照实结束），
 * 施工中盯着它（连着 10 刻在动就停手，不追着动着的船施工），施工接缝换成结构上的，整机比对读结构地块里的格；
 * 安装段只有发一包就成的（胶）在结构上装，部件与装后设置在结构上还不会。施工一路记下的格、问题里列的格都是地块里的大数，
 * 交出去之前换成局部坐标（以档案锚点为零）。
 */
final class StructureConstruction {

    /** 连着这么多刻不动才算停稳、才开工。 */
    static final int STILL_TICKS = 10;
    /** 开工前最多等几刻（VA-07）。 */
    static final int MAX_WAIT_TICKS = 200;
    /** 施工中连着这么多刻在动就停手。 */
    static final int MOVING_TICKS = 10;

    /** 开工前等停稳的结论：停稳了、还在等、等满了还在动、结构没了。 */
    enum Wait { READY, WAITING, STILL_MOVING, GONE }

    private final MachineBuildInput.OnStructure on;
    private int still;
    private int waited;
    private int movingFor;

    StructureConstruction(MachineBuildInput.OnStructure on) {
        this.on = Objects.requireNonNull(on, "on");
    }

    /** 开工前每刻问一次：连着 10 刻不动算停稳；在动就接着等，等满 200 刻照实结束。 */
    Wait waitStill() {
        if (!on.worksite().present()) return Wait.GONE;
        if (on.worksite().moving()) {
            still = 0;
            return ++waited > MAX_WAIT_TICKS ? Wait.STILL_MOVING : Wait.WAITING;
        }
        return ++still >= STILL_TICKS ? Wait.READY : Wait.WAITING;
    }

    /** 施工中每刻问一次：连着 10 刻在动（被撞、被风吹、从地上挖推的）就该停手了。 */
    boolean movedDuringWork() {
        if (on.worksite().moving()) return ++movingFor >= MOVING_TICKS;
        movingFor = 0;
        return false;
    }

    /** 开工前没等到停稳（等满了还在动、结构没了）：一格没动，照实说。 */
    Problem cannotStart(Wait wait) {
        return wait == Wait.GONE
                ? Problem.of(Problem.Kind.TARGET_GONE, "那个结构看不到了（被拆光或走远了），一格没动", "走近重新 observe 再指")
                : Problem.of(Problem.Kind.STUCK, "结构一直在动，等了 " + MAX_WAIT_TICKS / 20 + " 秒没停稳，一格没动",
                        "让它停稳（落地、关掉动力）再下达");
    }

    /** 施工中每刻问一次该不该停手：结构没了（最后一块拆掉后 Sable 删掉整个结构），或连着动起来了；不用停为空。 */
    Optional<Problem> stopReason() {
        if (!on.worksite().present()) {
            return Optional.of(Problem.of(Problem.Kind.TARGET_GONE, "结构没了（最后一块方块拆掉后整个结构就没了），停手"));
        }
        if (movedDuringWork()) {
            return Optional.of(Problem.of(Problem.Kind.STUCK, "施工中结构动起来了（被撞、被风吹、从地上挖会推船），停手",
                    "等它停稳再下达一次同样的目标，会从没做完的地方接着做"));
        }
        return Optional.empty();
    }

    /** 施工引擎用的接缝：读格、靠近、放置预测、点击、挖换成结构上的，其余照用地上那份。 */
    ConstructionServices services(ConstructionServices onLand, Permissions permissions) {
        return on.worksite().services(onLand, permissions);
    }

    /** 整机比对读结构地块里的格；没加载的如实说没加载。 */
    ReadsBlocks blocks() {
        return on.worksite().blocks();
    }

    /** 在结构上装这一段：问遍机器类型，肯在结构上装的第一家给的动作（只有胶这种发一包就成的）；都不肯为空。 */
    Optional<Action> install(List<MachineType> types, Installation installation, Permissions permissions) {
        for (MachineType type : types) {
            Optional<Action> offered = type.installOnStructure(installation, permissions, on.worksite().close());
            if (offered.isPresent()) return offered;
        }
        return Optional.empty();
    }

    /** 地块里的一格写成局部坐标。 */
    String local(BlockPos plotCell) {
        return on.origin().local(plotCell).toShortString();
    }

    /** 文字里地块里的格换成局部坐标。 */
    String localize(String text) {
        return on.origin().localize(text, on.worksite().min(), on.worksite().max());
    }

    /** 问题里列的格换成局部坐标；没有问题给 null。 */
    Problem localize(Problem problem) {
        if (problem == null) return null;
        return Problem.of(problem.kind(), localize(problem.message()), localize(problem.suggestion()));
    }

    /** 施工子任务的记账口：记下来的格换成局部坐标再并进这次的结果。 */
    TaskRecords records(TaskRecords outer) {
        return new TaskRecords() {
            @Override public void change(Change change) {
                outer.change(localize(change));
            }

            @Override public void unconfirmed(Change change) {
                outer.unconfirmed(localize(change));
            }

            @Override public void attempt(String tried, String whatHappened) {
                outer.attempt(localize(tried), localize(whatHappened));
            }
        };
    }

    private Change localize(Change change) {
        return new Change(change.kind(), change.what(), change.count(), localize(change.note()));
    }

    /** 施工改了方块：这个结构下次重新逐格读，观察和看载具照新结构读。 */
    void finished() {
        on.worksite().forgetScan();
    }

    /** 指它的观察编号 s#；指档案名时为 null。 */
    String seenId() {
        return on.seenId();
    }

    /** 局部坐标的原点一句话。 */
    String origin() {
        return on.origin().describe();
    }

    /** 结构身份。 */
    String structureId() {
        return on.structureId();
    }
}
