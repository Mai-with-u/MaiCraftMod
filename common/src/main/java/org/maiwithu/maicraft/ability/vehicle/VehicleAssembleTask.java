// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle;

import java.util.List;
import java.util.Optional;

import org.maiwithu.maicraft.ability.vehicle.spi.VehicleAssembler;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 组装或拆回一次：问各联动的组装办法谁能做 →（拆回先把这辆的档案读下来）→ 交给它走过去、拉一次、确认 →
 * 档案跟着走：拆回改记到组装器落回的那一格，组装认出是拆回的那一艘就跟到新结构 → 照它的结局结算。
 * 已经是要的样子就直接完成；拉了没转换照实完成并写明（不判失败），动作本身做不下去才失败。
 */
final class VehicleAssembleTask extends PhasedTask<VehicleAssembleTask.Phase> {

    enum Phase { FIND, RUN, NAME }

    /** 组装出新结构后最多等几刻让它在观察里领到 s#（场景每刻刷新一次）。 */
    static final int NAME_TICKS = 10;

    private final VehicleAssembler.Request request;
    private final List<VehicleAssembler> assemblers;
    private final VehicleArchives archives;
    private VehicleAssembler.Run run;
    private VehicleArchives.Before before;
    private VehicleAssembler.Conversion conversion;
    private VehicleArchives.Handover archive;
    private int named;

    VehicleAssembleTask(VehicleAssembler.Request request, List<VehicleAssembler> assemblers, VehicleArchives archives) {
        super(request.assemble() ? "组装载具" : "拆回载具", Phase.FIND, new ProgressTracker(20L * 60, 20L * 60 * 5));
        this.request = request;
        this.assemblers = List.copyOf(assemblers);
        this.archives = archives;
    }

    @Override protected Action enter(Phase phase) {
        return phase == Phase.RUN ? run : null;
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case FIND -> find();
            case RUN -> runActionThen(context, this::converted);
            case NAME -> ++named >= NAME_TICKS || archives.seenId(conversion.structureId()).isPresent() ? settle() : Next.stay();
        };
    }

    // 问各联动谁能做这次组装：第一个答得上的来做；都答不上如实说附近没有组装器。
    // 拆回的结构拉完就没了：拉之前先把它的回路、朝向、操纵表读下来，档案才跟得走。
    private Next<Phase> find() {
        for (VehicleAssembler assembler : assemblers) {
            Optional<VehicleAssembler.Run> started = assembler.start(request);
            if (started.isPresent()) {
                run = started.get();
                if (!request.assemble()) {
                    Optional<String> structure = run.structure().or(() -> Optional.ofNullable(request.structureId()));
                    before = structure.map(archives::before).orElse(null);
                }
                return Next.go(Phase.RUN, request.assemble() ? "找到组装器，去拉杆" : "找到组装器，去拆回");
            }
        }
        return Next.fail(Problem.of(Problem.Kind.NOT_FOUND, request.assemble()
                ? request.near().toShortString() + " 附近找不到物理组装器"
                : (request.structureId() == null ? request.near().toShortString() + " 附近" : "指的结构上") + "找不到物理组装器",
                "物理组装器要贴着要组装的方块放；指组装器的坐标或结构的观察编号"));
    }

    // 拉完了：组装出新结构时先等它在观察里领到 s#，档案才能说跟到了哪个编号。
    private Next<Phase> converted() {
        conversion = run.conversion();
        if (conversion == null) return Next.fail(Problem.of(Problem.Kind.INTERNAL_ERROR, "组装做完了却没有交代结局"));
        if (request.assemble() && conversion.converted() && conversion.assembler() != null
                && archives.seenId(conversion.structureId()).isEmpty()) {
            return Next.go(Phase.NAME, "等新结构领到观察编号");
        }
        return settle();
    }

    // 结算：已经是要的样子、真的转换了、拉了没转换，三种都算做完，结果里分开写；转换了档案跟着走。
    private Next<Phase> settle() {
        String what = request.assemble() ? "组装" : "拆回";
        if (conversion.alreadyDone()) {
            return Next.done(TaskResult.done(request.assemble() ? "已经组装过，是一个会动的结构了" : "已经是方块了，不用拆"));
        }
        if (conversion.converted()) {
            archive = request.assemble() ? archives.assembled(conversion)
                    : before == null ? null : archives.disassembled(before, conversion);
            return Next.done(TaskResult.done(what + "好了" + (conversion.blocks() > 0 ? "，" + conversion.blocks() + " 格方块" : "")
                    + (archive == null ? "" : "；" + archiveLine(archive))));
        }
        return Next.done(TaskResult.done("拉了一次杆，但没有" + (request.assemble() ? "变成会动的结构" : "拆回方块")
                + (conversion.note().isBlank() ? "" : "：" + conversion.note())));
    }

    // 档案的一句话：跟到新结构时带上改过的地方、没带过去的操纵表行，同样的内容在 archive 里分条列着。
    private static String archiveLine(VehicleArchives.Handover outcome) {
        String line = outcome.note();
        if (!outcome.changes().isEmpty()) {
            line += "；和拆前比改过 " + outcome.changes().size() + " 处：" + String.join("、", outcome.changes());
        }
        if (!outcome.dropped().isEmpty()) {
            line += "；操纵表没带过去 " + outcome.dropped().size() + " 行：" + String.join("、", outcome.dropped());
        }
        return line;
    }

    @Override protected ResultDetails details() {
        return conversion == null ? ResultDetails.NONE
                : new VehicleAssembleDetails(conversion.structureId(), conversion.alreadyDone(), conversion.converted(),
                        conversion.blocks(), conversion.note(), archive);
    }

    @Override protected String describePhase(Phase value) {
        return switch (value) {
            case FIND -> "找组装器";
            case RUN -> request.assemble() ? "拉杆组装" : "拉杆拆回";
            case NAME -> "等新结构领到观察编号";
        };
    }
}
