// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.ability.machine.spi.PartCell;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.StepsInOrder;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * Simulated 的蜂蜜胶：不认领任何方块，只管一种安装段——用蜂蜜胶框住一片，让里面相邻的方块组装物理结构时连在一起。
 * 蜂蜜胶只粘物理结构，不粘机械动力的装置（转起来的轴承不会顺着它把船身拖进转子），框也不要求角是方块、两角连着，
 * 所以组装前补胶时它排在强力胶前面：角色身上有蜂蜜胶就先用它，没有就不答，交给强力胶。
 *
 * <p>粘一框：蜂蜜胶换到主手 → 走到框里最低处那块方块跟前（真人得站在附近点角、Ctrl 滚轮拉大）→ 发 Simulated 自己的那一包
 * → 等这块蜂蜜胶出现、框对得上才算。服务器只看手里有蜂蜜胶、每边不超过上限，扣一点耐久。
 */
final class HoneyGlueType implements MachineType {

    static final String KIND = "simulated:honey_glue";
    static final String ITEM = "simulated:honey_glue";
    /** 发了以后等那块胶同步过来最多这么多刻。 */
    private static final int SYNC_WAIT_TICKS = 40;

    private final SimulatedCompat compat;
    private final PlayerServices services;

    HoneyGlueType(SimulatedCompat compat, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public String id() { return KIND; }

    @Override public String name() { return "蜂蜜胶"; }

    @Override public String modId() { return SimulatedCompat.MOD_ID; }

    @Override public boolean covers(BlockState state) { return false; }

    @Override public MachineRole role() { return MachineRole.MOTION; }

    @Override public List<ExchangePoint> exchangePoints(BlockState state, BlockPos at) { return List.of(); }

    @Override public MachineState state(BlockPos at) { return MachineState.unknown("蜂蜜胶不是机器"); }

    // 补胶时先问它：蜂蜜胶不粘机械动力的装置、框也没有角和连通的讲究，身上有就比强力胶省事也稳当。
    @Override public int stickPreference() { return 1; }

    // 身上有蜂蜜胶才答：此刻真有方块的格算车身，附近现有的蜂蜜胶和强力胶都算数，缺的按 HoneyGluePlan 分框。
    @Override public Optional<List<Installation>> stickTogether(Set<BlockPos> cells) {
        PlayerContext current = services.context().get();
        ClientLevel level = current == null ? null : current.level();
        LocalPlayer player = current == null ? null : current.localPlayer();
        if (level == null || player == null || !carrying(player)) return Optional.empty();
        Set<BlockPos> body = new HashSet<>();
        for (BlockPos cell : cells) {
            if (level.isLoaded(cell) && solid(level.getBlockState(cell))) body.add(cell.immutable());
        }
        if (body.isEmpty()) return Optional.of(List.of());
        AABB area = AABB.encapsulatingFullBlocks(corner(body, false), corner(body, true)).inflate(1);
        List<HoneyGluePlan.Box> existing = compat.glueBoxes(level, area).stream().map(HoneyGlueType::box).toList();
        List<HoneyGluePlan.Box> added = HoneyGluePlan.plan(body, existing,
                cell -> !level.isLoaded(cell) || solid(level.getBlockState(cell)));
        return Optional.of(added.stream().map(box -> new Installation(KIND, List.of(box.max(), box.min()))).toList());
    }

    @Override public Optional<String> installationProblem(Installation installation) {
        if (!KIND.equals(installation.kind())) return Optional.of("蜂蜜胶不装 " + installation.kind() + " 这种安装段");
        if (installation.cells().size() != 2) return Optional.of("蜂蜜胶写这一片的两个对角：offsets 两格");
        HoneyGluePlan.Box box = boxOf(installation);
        boolean fits = box.max().getX() - box.min().getX() < HoneyGluePlan.MAX_SIZE
                && box.max().getY() - box.min().getY() < HoneyGluePlan.MAX_SIZE
                && box.max().getZ() - box.min().getZ() < HoneyGluePlan.MAX_SIZE;
        return fits ? Optional.empty() : Optional.of("一块蜂蜜胶每边最多 " + HoneyGluePlan.MAX_SIZE + " 格");
    }

    @Override public Optional<String> partProblem(PartCell part, BlockState host) {
        return Optional.of("蜂蜜胶不是装在方块某一面上的部件");
    }

    // 蜂蜜胶换到主手 → 走到框里最低处那块方块跟前 → 发包 → 等那块胶出现。
    @Override public Optional<Action> install(Installation installation, Permissions permissions) {
        if (!KIND.equals(installation.kind()) || installation.cells().size() != 2) return Optional.empty();
        HoneyGluePlan.Box box = boxOf(installation);
        return Optional.of(new StepsInOrder("用蜂蜜胶框住 " + box.min().toShortString() + " 到 " + box.max().toShortString(),
                List.of(new StepsInOrder.Step("蜂蜜胶换到主手", this::toMainhand),
                        new StepsInOrder.Step("走到这一框跟前", () -> services.approach().toward(
                                ApproachTarget.ofBlock(standNear(box)), permissions)),
                        new StepsInOrder.Step("粘上", () -> new Glues(box)))));
    }

    @Override public boolean installed(Installation installation) {
        PlayerContext current = services.context().get();
        ClientLevel level = current == null ? null : current.level();
        if (level == null || !KIND.equals(installation.kind()) || installation.cells().size() != 2) return false;
        return glued(level, boxOf(installation));
    }

    @Override public Optional<Action> mount(PartCell part, Permissions permissions) { return Optional.empty(); }

    @Override public boolean mounted(PartCell part) { return false; }

    @Override public List<MachineSetting> settings(BlockPos at) { return List.of(); }

    @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
        return Optional.empty();
    }

    @Override public Optional<Action> feed(BlockPos at, String itemId, int count, Permissions permissions) {
        return Optional.empty();
    }

    @Override public List<MachineState.Shown> output(BlockPos at) { return List.of(); }

    @Override public Optional<Action> take(BlockPos at, String itemId, int count, Permissions permissions) {
        return Optional.empty();
    }

    // 主手已经是蜂蜜胶就不用换；身上没有了（耐久用完）如实说缺蜂蜜胶。
    private Action toMainhand() {
        PlayerContext current = services.context().get();
        LocalPlayer player = current == null ? null : current.localPlayer();
        if (player != null && isHoney(player.getMainHandItem())) return new Finished(null);
        return services.toMainhand().moveToMainhand(ITEM).orElseGet(() -> new Finished(Problem.of(Problem.Kind.NEED_ITEM,
                "身上没有蜂蜜胶了（可能耐久用完）", "补一支蜂蜜胶，或者在蓝图里写强力胶")));
    }

    // 框里最低处那块方块：角色站在它跟前，够得着这一框的角，像真人一样从近处点角拉框。
    private BlockPos standNear(HoneyGluePlan.Box box) {
        PlayerContext current = services.context().get();
        ClientLevel level = current == null ? null : current.level();
        if (level == null) return box.min();
        BlockPos best = null;
        for (BlockPos cell : BlockPos.betweenClosed(box.min(), box.max())) {
            if (!level.isLoaded(cell) || !solid(level.getBlockState(cell))) continue;
            boolean lower = best == null || cell.getY() < best.getY()
                    || cell.getY() == best.getY() && cell.distSqr(box.min()) < best.distSqr(box.min());
            if (lower) best = cell.immutable();
        }
        return best == null ? box.min() : best;
    }

    private boolean glued(ClientLevel level, HoneyGluePlan.Box wanted) {
        AABB area = AABB.encapsulatingFullBlocks(wanted.min(), wanted.max()).inflate(1);
        return compat.honeyGlueBoxes(level, area).stream().map(HoneyGlueType::box).anyMatch(wanted::equals);
    }

    private static boolean carrying(LocalPlayer player) {
        return player.getInventory().hasAnyMatching(HoneyGlueType::isHoney);
    }

    private static boolean isHoney(ItemStack stack) {
        return !stack.isEmpty() && ITEM.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    // 车身算哪些格：有方块的，空气和纯流体不算。
    private static boolean solid(BlockState state) {
        return !state.isAir() && !(state.getBlock() instanceof LiquidBlock);
    }

    private static HoneyGluePlan.Box boxOf(Installation installation) {
        BlockPos one = installation.cells().get(0);
        BlockPos other = installation.cells().get(1);
        return new HoneyGluePlan.Box(new BlockPos(Math.min(one.getX(), other.getX()), Math.min(one.getY(), other.getY()),
                Math.min(one.getZ(), other.getZ())), new BlockPos(Math.max(one.getX(), other.getX()),
                Math.max(one.getY(), other.getY()), Math.max(one.getZ(), other.getZ())));
    }

    // 一块胶的包围盒换成它框住的格：两种胶的框都是从最小角到最大角再加一格，框住的格按格心算。
    static HoneyGluePlan.Box box(AABB glue) {
        return new HoneyGluePlan.Box(BlockPos.containing(glue.minX + 0.5, glue.minY + 0.5, glue.minZ + 0.5),
                BlockPos.containing(glue.maxX - 0.5, glue.maxY - 0.5, glue.maxZ - 0.5));
    }

    private static BlockPos corner(Set<BlockPos> cells, boolean high) {
        int x = high ? cells.stream().mapToInt(BlockPos::getX).max().orElseThrow() : cells.stream().mapToInt(BlockPos::getX).min().orElseThrow();
        int y = high ? cells.stream().mapToInt(BlockPos::getY).max().orElseThrow() : cells.stream().mapToInt(BlockPos::getY).min().orElseThrow();
        int z = high ? cells.stream().mapToInt(BlockPos::getZ).max().orElseThrow() : cells.stream().mapToInt(BlockPos::getZ).min().orElseThrow();
        return new BlockPos(x, y, z);
    }

    /** 立刻收场的一步：没有问题就算做完，有就按它失败。 */
    private record Finished(Problem problem) implements Action {
        @Override public ActionStatus tick(TickContext context) {
            return problem == null ? ActionStatus.done() : ActionStatus.failed(problem);
        }

        @Override public String describe() {
            return problem == null ? "蜂蜜胶已经在主手" : problem.message();
        }
    }

    /** 发一次蜂蜜胶的包，等框对得上的那块蜂蜜胶出现；等不到如实说，不再发第二次（再发会多扣一点耐久）。 */
    private final class Glues implements Action {
        private final HoneyGluePlan.Box box;
        private boolean sent;
        private int waited;

        Glues(HoneyGluePlan.Box box) {
            this.box = box;
        }

        @Override public ActionStatus tick(TickContext context) {
            if (!sent) {
                compat.honeyGlue(box.min(), box.max());
                sent = true;
                return ActionStatus.progressed();
            }
            if (glued(context.player().level(), box)) return ActionStatus.done();
            if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
            return ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                    "发了蜂蜜胶的框，胶没出现：主手要拿着蜂蜜胶，框每边不超过 " + HoneyGluePlan.MAX_SIZE + " 格"));
        }

        @Override public String describe() {
            return sent ? "等蜂蜜胶粘上" : "用蜂蜜胶框住这一片";
        }
    }
}
