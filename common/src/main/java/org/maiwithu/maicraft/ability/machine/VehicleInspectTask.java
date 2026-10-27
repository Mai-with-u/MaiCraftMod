// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.ability.machine.spi.StructureWorksites;
import org.maiwithu.maicraft.ability.vehicle.api.StabilizedPlan;
import org.maiwithu.maicraft.ability.vehicle.api.VehicleControls;
import org.maiwithu.maicraft.ability.vehicle.spi.FlightStabilizers;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleReader;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TaskInput;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 看一辆载具（machine_inspect 指结构的观察编号 s#）：问各联动的读载具办法谁读得了，读一次交出 vehicle 一节——
 * 种类、座位、每个控制件输入接到谁与推出来的角色、姿态与接地，再合上操纵表（试过的、LLM 定的）查必需角色缺哪个；
 * 装着飞行稳定器（Create: Flight Control）时另列一节：每个稳定器的样子、代理每个面接到哪个键、开的时候走哪条路。
 * 要受力时再问服务端（要几刻）：开着动力与关着动力两种静态情况往哪边倒、升力比重量，要配重再给几处位置；
 * 最多等 10 秒，问不到照实写原因，载具的其余部分照样交。只读。
 *
 * <p>认得出结构上的工地时多给 local 一节：结构局部坐标的原点是哪一格、方块范围、座位与配重建议在局部坐标的哪一格——
 * 在船上施工（machine_build 指 s#）照这一套写偏移。指跟到结构上的机器档案名时，再按局部坐标和档案整机比对。
 */
final class VehicleInspectTask extends PhasedTask<VehicleInspectTask.Phase> {

    enum Phase { READ, BALANCE }

    /** 等受力的回答最多几刻。 */
    static final int BALANCE_TICKS = 200;

    /**
     * 看哪辆。
     *
     * @param structureId 结构身份
     * @param seenId      指它的观察编号（说的时候用）；指档案名时是"档案「名字」所在的结构"
     * @param balance     要不要受力：forces 只看受力 / ballast 再给配重建议；不要为 null
     * @param worksite    结构上的工地（给局部坐标）；没装能施工的联动为 null
     * @param origin      局部坐标的原点；没有工地为 null
     * @param archive     指的是跟在结构上的机器档案时那一份（要整机比对）；指 s# 为 null
     */
    record Input(String structureId, String seenId, String balance, StructureWorksites.Worksite worksite,
                 StructureOrigin origin, MachineArchive archive) implements TaskInput {
        Input {
            Objects.requireNonNull(structureId, "structureId");
            Objects.requireNonNull(seenId, "seenId");
        }

        /** 只看载具、不给局部坐标。 */
        Input(String structureId, String seenId, String balance) {
            this(structureId, seenId, balance, null, null, null);
        }

        @Override public String describe() {
            return "看一眼载具 " + seenId;
        }
    }

    /**
     * 结果细节：vehicle 一节、合并后的操纵表、受力（没要为 null）、飞行稳定器（没装为 null）、
     * 结构局部坐标（认不出工地为 null）、和跟在上面的档案整机比对（指 s# 为 null）。
     */
    record Details(VehicleReader.View vehicle, VehicleControls.ControlMap controlMap, VehicleReader.Balance balance,
                   StabilizedPlan.Section stabilizers, Local local, ArchiveCheck.Result archiveCheck)
            implements ResultDetails {}

    /**
     * 结构局部坐标：在船上施工照这一套写偏移，地块里的大数不给。
     *
     * @param origin  原点是哪一格，一句话
     * @param archive 跟在这个结构上的机器档案名；没有为 null
     * @param min     方块范围的最小角
     * @param max     方块范围的最大角
     * @param seats   座位所在的格
     * @param ballast 配重建议所在的格（没要配重为空）
     */
    record Local(String origin, String archive, BlockPos min, BlockPos max, List<BlockPos> seats, List<Ballast> ballast) {
        Local {
            seats = List.copyOf(seats);
            ballast = List.copyOf(ballast);
        }
    }

    /** 一处配重建议：放在局部坐标的哪一格、放什么，放之前与之后两种情况里最坏的那个。 */
    record Ballast(BlockPos at, String block, double before, double after) {}

    private final Input input;
    private final MachineVehicles vehicles;
    private final MachineServices services;
    private VehicleReader.View view;
    private VehicleControls.ControlMap controlMap;
    private StabilizedPlan.Section stabilizers;
    private VehicleReader.BalanceQuery query;
    private VehicleReader.Balance balance;
    private ArchiveCheck.Result archiveCheck;
    private String summary;
    private int waited;

    VehicleInspectTask(Input input, MachineVehicles vehicles, MachineServices services) {
        super("看载具", Phase.READ, new ProgressTracker(100, 400));
        this.input = input;
        this.vehicles = vehicles;
        this.services = services;
    }

    @Override protected Action enter(Phase phase) {
        return null;
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        return phase == Phase.READ ? read() : balance();
    }

    // 第一个读得了的来读；都读不了如实说。读到了一句话交代种类、座位、输入几个推出了角色；要受力就去问。
    private Next<Phase> read() {
        Optional<VehicleReader.View> read = vehicles.read(input.structureId());
        if (read.isEmpty()) {
            return Next.fail(Problem.of(Problem.Kind.NOT_FOUND, input.seenId() + " 读不了：附近没有这个结构，或没有能读它的联动",
                    "离近一点重新 observe 再指"));
        }
        view = read.get();
        controlMap = VehicleControls.merge(view.kind(), view.controls(), vehicles.controls().stored(view.structureId()));
        long known = view.controls().stream().filter(control -> !control.role().equals("unknown")).count();
        String roles = view.controls().stream().map(control -> control.control() + " " + control.role())
                .collect(Collectors.joining("、"));
        summary = input.seenId() + " 是 " + view.kind() + "：" + view.seats().size() + " 个座位、"
                + view.controls().size() + " 个输入（推出角色的 " + known + " 个" + (roles.isEmpty() ? "" : "：" + roles)
                + "），" + view.state().contact() + (view.complete() ? "" : "；没读全")
                + (controlMap.missing().isEmpty() ? "；开它要的角色都齐了" : "；开它还缺：" + String.join("、", controlMap.missing()));
        // 装着飞行稳定器：列进控制回路，并说清 vehicle_drive 开它时走"设目标高度 + 给方向"还是照旧按键、为什么。
        Optional<FlightStabilizers.Setup> setup = vehicles.stabilizers(view.structureId());
        if (setup.isPresent() && !setup.get().stabilizers().isEmpty()) {
            StabilizedPlan.Plan plan = StabilizedPlan.of(view.kind(), view.controls(), setup.get());
            stabilizers = StabilizedPlan.section(setup.get(), plan);
            summary += "；装着 " + setup.get().stabilizers().size() + " 个飞行稳定器，"
                    + (plan.ready() ? "开的时候设目标高度、按键给方向（不用升力、转向键）" : "开的时候照旧按键：" + plan.why());
        }
        summary += localLine();
        if (input.balance() == null) return Next.done(TaskResult.done(summary));
        query = vehicles.readers().stream().map(reader -> reader.balance(input.structureId(), "ballast".equals(input.balance())))
                .flatMap(Optional::stream).findFirst().orElse(null);
        if (query == null) {
            balance = unknown("没有能问这辆受力的联动");
            return Next.done(TaskResult.done(summary + "；受力读不了：" + balance.why()));
        }
        return Next.go(Phase.BALANCE, "问服务端受力");
    }

    // 局部坐标的原点一句话；指跟在结构上的档案时按局部坐标和档案整机比一遍。
    private String localLine() {
        if (input.worksite() == null || input.origin() == null) return "";
        String line = "；局部坐标的原点是" + input.origin().describe();
        if (input.archive() != null) {
            archiveCheck = ArchiveCheck.compareOnStructure(input.archive(), services, input.worksite().blocks());
            line += "；和档案「" + input.archive().name() + "」比：" + (archiveCheck.allMatch() ? "全部对上"
                    : archiveCheck.differences().size() + " 格没对上" + (archiveCheck.complete() ? "" : "、有格没加载"));
        }
        return line;
    }

    // 等受力的回答：答上了一句话说两种情况；10 秒还没答上照实说。
    private Next<Phase> balance() {
        Optional<VehicleReader.Balance> answer = query.poll();
        if (answer.isEmpty()) {
            if (++waited < BALANCE_TICKS) return Next.stay();
            balance = unknown("问了 " + BALANCE_TICKS / 20 + " 秒服务端还没答上");
            return Next.done(TaskResult.done(summary + "；受力：" + balance.why()));
        }
        balance = answer.get();
        if (!balance.known()) return Next.done(TaskResult.done(summary + "；受力读不了：" + balance.why()));
        String cases = balance.cases().stream().map(found -> found.name() + found.tendency()).collect(Collectors.joining("；"));
        return Next.done(TaskResult.done(summary + "；受力：" + cases + "，升力比重量 " + balance.liftRatio()
                + (balance.ballast().isEmpty() ? "" : "；配重建议 " + balance.ballast().size() + " 处")));
    }

    private static VehicleReader.Balance unknown(String why) {
        return new VehicleReader.Balance(false, why, 0, 0, false, List.of(), List.of(), "");
    }

    // 局部坐标一节：座位、配重建议的世界位置按结构此刻的位姿落回地块里的格，再减去原点。
    private Local local() {
        if (input.worksite() == null || input.origin() == null) return null;
        StructureOrigin origin = input.origin();
        StructureWorksites.Worksite worksite = input.worksite();
        List<BlockPos> seats = view == null ? List.of() : view.seats().stream()
                .map(seat -> origin.local(worksite.plotCellAt(new Vec3(seat.x(), seat.y(), seat.z())))).toList();
        List<Ballast> ballast = balance == null ? List.of() : balance.ballast().stream()
                .map(spot -> new Ballast(origin.local(worksite.plotCellAt(new Vec3(spot.x(), spot.y(), spot.z()))),
                        spot.block(), spot.before(), spot.after())).toList();
        return new Local(origin.describe(), origin.archive(), origin.local(worksite.min()), origin.local(worksite.max()),
                seats, ballast);
    }

    @Override protected ResultDetails details() {
        return view == null ? ResultDetails.NONE : new Details(view, controlMap, balance, stabilizers, local(), archiveCheck);
    }

    @Override protected String describePhase(Phase value) {
        return value == Phase.READ ? "读载具" : "问受力";
    }
}
