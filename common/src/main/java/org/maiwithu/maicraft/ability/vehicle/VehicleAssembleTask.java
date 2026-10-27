// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleAssembler;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 组装或拆回一次：问各联动的组装办法谁能做 →（拆回先把这辆的档案读下来）→（组装时组装器在机器档案里，先把档案的方块
 * 没被胶框住的地方一框框补上）→ 交给它走过去、拉一次、确认 → 档案跟着走：操纵表拆回改记到组装器落回的那一格、
 * 组装认出是拆回的那一艘就跟到新结构，机器档案组装时平移进地块、拆回时按落点和转的整 90° 跟回世界 → 照它的结局结算。
 * 已经是要的样子就直接完成；拉了没转换照实完成并写明（不判失败），动作本身做不下去才失败。
 * 补胶没补成就不拉杆：胶不全时拉杆不报错，只会把船身撕下一部分变成结构，比不拉更难收拾。
 */
final class VehicleAssembleTask extends PhasedTask<VehicleAssembleTask.Phase> {

    enum Phase { FIND, GLUE, RUN, NAME }

    /** 组装出新结构后最多等几刻让它在观察里领到 s#（场景每刻刷新一次）。 */
    static final int NAME_TICKS = 10;

    private final VehicleAssembler.Request request;
    private final List<VehicleAssembler> assemblers;
    private final VehicleArchives archives;
    private final AssemblyGlue glue;
    private final MachineArchiveFollow machines;
    private VehicleAssembler.Run run;
    private VehicleArchives.Before before;
    private VehicleAssembler.Conversion conversion;
    private VehicleArchives.Handover archive;
    private VehicleAssembleDetails.MachineArchiveMove machineArchive;
    private int named;
    // 补胶：组装器所在的机器档案、它的方块、还要补的几段、这一刻在粘的那段、粘上了的与没粘上的。
    private String glueArchive;
    private Set<BlockPos> glueBlocks = Set.of();
    private final Deque<Installation> toGlue = new ArrayDeque<>();
    private Installation gluing;
    private final List<String> glued = new ArrayList<>();
    private List<String> unglued = List.of();
    private String glueNote;

    // 补胶要拿胶、走到一框框的角上，比只拉一次杆久：总时长放到 15 分钟，一分钟没进展照样按卡住算。
    VehicleAssembleTask(VehicleAssembler.Request request, List<VehicleAssembler> assemblers, VehicleArchives archives,
            AssemblyGlue glue, MachineArchiveFollow machines) {
        super(request.assemble() ? "组装载具" : "拆回载具", Phase.FIND, new ProgressTracker(20L * 60, 20L * 60 * 15));
        this.request = request;
        this.assemblers = List.copyOf(assemblers);
        this.archives = archives;
        this.glue = glue;
        this.machines = Objects.requireNonNull(machines, "machines");
    }

    @Override protected Action enter(Phase phase) {
        return switch (phase) {
            case RUN -> run;
            case GLUE -> glue.install(gluing = toGlue.peek(), request.permissions()).orElse(null);
            default -> null;
        };
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case FIND -> find();
            case GLUE -> glueOne(context);
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
                return request.assemble() ? checkGlue() : Next.go(Phase.RUN, "找到组装器，去拆回");
            }
        }
        return Next.fail(Problem.of(Problem.Kind.NOT_FOUND, request.assemble()
                ? request.near().toShortString() + " 附近找不到物理组装器"
                : (request.structureId() == null ? request.near().toShortString() + " 附近" : "指的结构上") + "找不到物理组装器",
                "物理组装器要贴着要组装的方块放；指组装器的坐标或结构的观察编号"));
    }

    // 拉杆前看胶：组装器在哪份机器档案里，那份档案的方块盖没盖全。不在档案里、没有联动答得上、已经盖全，都直接去拉杆；
    // 缺的一框框排好去补。
    private Next<Phase> checkGlue() {
        Optional<BlockPos> at = run.assembler();
        if (at.isEmpty()) return Next.go(Phase.RUN, "找到组装器，去拉杆");
        AssemblyGlue.Check check = glue.check(at.get());
        if (check.archive() == null) {
            glueNote = "组装器不在任何机器档案里，不知道整艘是哪些方块，没查胶";
            return Next.go(Phase.RUN, "找到组装器，去拉杆");
        }
        glueArchive = check.archive();
        glueBlocks = check.blocks();
        if (check.missing().isEmpty()) {
            glueNote = "没有能看胶的联动（机械动力的联动没接上），没查胶";
            return Next.go(Phase.RUN, "找到组装器，去拉杆");
        }
        if (check.missing().get().isEmpty()) {
            glueNote = "档案里的方块胶已经盖全";
            return Next.go(Phase.RUN, "胶已经盖全，去拉杆");
        }
        toGlue.addAll(check.missing().get());
        return Next.go(Phase.GLUE, "档案「" + glueArchive + "」还缺 " + toGlue.size() + " 框胶，先补胶");
    }

    // 粘一框：动作做完再核对胶真的出现了才算；都粘完再看一遍盖全没有，盖全了去拉杆。
    private Next<Phase> glueOne(TickContext context) {
        if (action() == null) {
            return glueFailed(Problem.of(Problem.Kind.UNSUPPORTED, "没有联动肯粘 " + gluing.kind() + " 这一段"));
        }
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Failed failed -> glueFailed(failed.problem());
            case ActionStatus.Done done -> glueDone();
        };
    }

    private Next<Phase> glueDone() {
        if (!glue.installed(gluing)) {
            return glueFailed(Problem.of(Problem.Kind.REFUSED_BY_GAME, AssemblyGlue.describe(gluing) + " 这一框粘了，胶没出现"));
        }
        glued.add(AssemblyGlue.describe(gluing));
        recordProgress("粘上 " + AssemblyGlue.describe(gluing));
        toGlue.poll();
        if (!toGlue.isEmpty()) return Next.go(Phase.GLUE, "接着补下一框");
        Optional<List<Installation>> left = glue.missing(glueBlocks);
        if (left.isPresent() && !left.get().isEmpty()) {
            unglued = left.get().stream().map(AssemblyGlue::describe).toList();
            return Next.fail(Problem.of(Problem.Kind.REFUSED_BY_GAME, "补了 " + glued.size() + " 框胶，还有 " + unglued.size()
                    + " 处没盖住，没拉杆：" + String.join("、", unglued), "看那几处是不是夹着档案外的方块、或方块没连着"));
        }
        glueNote = "补了 " + glued.size() + " 框胶";
        return Next.go(Phase.RUN, "胶补全了，去拉杆");
    }

    // 补胶没补成：不拉杆，照实说哪一框为什么没粘上、已经粘了哪些、还差哪些。
    private Next<Phase> glueFailed(Problem problem) {
        unglued = toGlue.stream().map(AssemblyGlue::describe).toList();
        String done = glued.isEmpty() ? "" : "；已经粘了 " + String.join("、", glued);
        return Next.fail(Problem.of(problem.kind(), "补胶没补成，没拉杆：" + problem.message() + done
                + "；还差 " + unglued.size() + " 框", problem.suggestion()));
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
        String glueLine = glued.isEmpty() ? "" : "先补了 " + glued.size() + " 框胶，";
        if (conversion.converted()) {
            archive = request.assemble() ? archives.assembled(conversion)
                    : before == null ? null : archives.disassembled(before, conversion);
            machineArchive = followMachineArchive();
            return Next.done(TaskResult.done(glueLine + what + "好了"
                    + (conversion.blocks() > 0 ? "，" + conversion.blocks() + " 格方块" : "")
                    + (archive == null ? "" : "；" + archiveLine(archive))
                    + (machineArchive == null ? "" : "；" + machineArchive.note())));
        }
        return Next.done(TaskResult.done(glueLine + "拉了一次杆，但没有" + (request.assemble() ? "变成会动的结构" : "拆回方块")
                + (conversion.note().isBlank() ? "" : "：" + conversion.note())));
    }

    // 机器档案跟着走：组装平移进地块；拆回按拆前结构的朝向算转了几个 90°，读不到朝向就留在结构上照实说。
    private VehicleAssembleDetails.MachineArchiveMove followMachineArchive() {
        if (request.assemble()) return machines.afterAssemble(conversion);
        if (before == null) return null;
        Double heading = before.view().map(view -> view.state().heading()).orElse(null);
        return machines.afterDisassemble(before.structureId(), heading, conversion);
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

    // 补胶只要查过就写进细节（补胶没补成、没拉杆时也有）；没查过胶（拆回、车站组车）没有这一节。
    @Override protected ResultDetails details() {
        VehicleAssembleDetails.Glue glueReport = glueNote == null && glueArchive == null ? null
                : new VehicleAssembleDetails.Glue(glueArchive, List.copyOf(glued), unglued,
                        glueNote == null ? "补胶没补成，没拉杆" : glueNote);
        if (conversion == null) {
            return glueReport == null ? ResultDetails.NONE
                    : new VehicleAssembleDetails("", false, false, -1, "", null, glueReport, null);
        }
        return new VehicleAssembleDetails(conversion.structureId(), conversion.alreadyDone(), conversion.converted(),
                conversion.blocks(), conversion.note(), archive, glueReport, machineArchive);
    }

    @Override protected String describePhase(Phase value) {
        return switch (value) {
            case FIND -> "找组装器";
            case GLUE -> "补胶";
            case RUN -> request.assemble() ? "拉杆组装" : "拉杆拆回";
            case NAME -> "等新结构领到观察编号";
        };
    }
}
