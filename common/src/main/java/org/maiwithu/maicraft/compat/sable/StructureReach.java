// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.approach.ProtectedCells;
import org.maiwithu.maicraft.behavior.navigation.StraightWalk;
import org.maiwithu.maicraft.behavior.navigation.util.BlockPassability;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.StepsInOrder;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.InteractionRange;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 靠近物理结构上的一格：走到够得着、看得见它的地方——人在船上就在甲板上挪，不在就先上船，或者站到船边的地上。
 * 此刻站着就看得见、够得着就不挪。不然挑站位：甲板上能站的格，船边地上站得住、不撞船体、身体不压着那一格的格；
 * 先按眼睛离那一格多远筛，再从那里的眼睛真射一条射线，第一下打中那一格（那一面）的才要。挖方块先挑船上——站在地上挖
 * 船上的方块，Sable 会把船推一下——船上一处都不行才去地上。挑一处走过去，到了再看一次；不行换下一处，试满几处如实说够不着。
 * 船在动先等它停，等太久如实说还在动。只走、只看，不往船上垫方块，不拆船。
 */
final class StructureReach implements Action {

    /** 最多换几处站位。 */
    static final int MAX_TRIES = 3;
    /** 船在动最多等几刻（VA-07）。 */
    static final int MAX_WAIT_TICKS = 200;
    /** 每次挑站位最多真射几处候选。 */
    static final int CHECK_LIMIT = 24;
    /** 挑站位时离交互距离留的余量（格）：站到了人不在格子正中也够得着。 */
    static final double REACH_MARGIN = 0.3;
    /** 船边地上的站位在那一格周围多远里找（格）。 */
    static final int GROUND_RADIUS = 4;
    /** 代价：要先上船、先下船各多算几格路；挖方块从地上挖再多算一大截，船上一处都不行才轮到它。 */
    static final double BOARD_COST = 6;
    static final double LEAVE_COST = 6;
    static final double DIG_FROM_GROUND_COST = 50;

    /** 站位在哪：船上的甲板，或船边的地上。 */
    enum Where { DECK, GROUND }

    /**
     * 一处站位。
     *
     * @param where    船上还是地上
     * @param deck     船上时是甲板格（地块坐标）；地上为 null
     * @param ground   地上时是脚站的那一格（世界坐标）；船上为 null
     * @param standing 此刻站上去脚底中心在世界里的位置
     * @param cost     先试哪处：越小越先
     */
    record Spot(Where where, BlockPos deck, BlockPos ground, Vec3 standing, double cost) {

        /** 换过的站位按这个认，不再挑第二回。 */
        String key() {
            return where == Where.DECK ? "deck " + deck.toShortString() : "ground " + ground.toShortString();
        }
    }

    private final StructureReads reads;
    private final PlayerServices services;
    private final Permissions permissions;
    private final String structureId;
    private final ApproachTarget target;
    private final ProtectedCells avoid;
    private final boolean digging;

    private Action current;
    private final Set<String> tried = new HashSet<>();
    private int tries;
    private int waited;
    private String lastWhy = "";

    /**
     * @param target  结构上的那一格（地块坐标）；给了面就要看得见那一面
     * @param avoid   不许当脚站的格（地块坐标）：放方块时要放的那几格
     * @param digging 要挖掉它：先挑船上的站位，也不站在它正上方
     */
    StructureReach(StructureReads reads, PlayerServices services, Permissions permissions, String structureId,
            ApproachTarget target, ProtectedCells avoid, boolean digging) {
        this.reads = Objects.requireNonNull(reads, "reads");
        this.services = Objects.requireNonNull(services, "services");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.structureId = Objects.requireNonNull(structureId, "structureId");
        this.target = Objects.requireNonNull(target, "target");
        this.avoid = avoid == null ? cell -> false : avoid;
        this.digging = digging;
    }

    @Override public ActionStatus tick(TickContext tick) {
        PlayerContext context = services.context().get();
        ClientLevel level = context == null ? null : context.level();
        LocalPlayer player = context == null ? null : context.localPlayer();
        if (level == null || player == null || context.input() == null) {
            return ActionStatus.failed(Problem.of(Problem.Kind.INTERNAL_ERROR, "角色不在，走不到船上那一格跟前"));
        }
        Optional<SableStructures.Structure> ship = StructureScene.find(reads, level, player, structureId);
        if (ship.isEmpty()) {
            return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "那个结构看不到了（被拆光或走远了）"));
        }
        if (current != null) return walking(tick);
        // 船在动不挑站位、不在甲板上走：等它停，等太久如实说还在动。
        if (StructureGeometry.moving(ship.get())) {
            context.input().halt(player);
            if (++waited > MAX_WAIT_TICKS) {
                return ActionStatus.failed(Problem.of(Problem.Kind.STUCK,
                        "结构一直在动，等了 " + MAX_WAIT_TICKS / 20 + " 秒没停，没走过去"));
            }
            return ActionStatus.running();
        }
        waited = 0;
        if (seenFrom(level, player, ship.get(), player.getEyePosition(), InteractionRange.blockReach(player))) {
            return ActionStatus.done();
        }
        if (tries >= MAX_TRIES) {
            return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE, "换了 " + tries + " 处站位还是够不着、看不见船上那一格"
                    + (lastWhy.isBlank() ? "" : "（最后一处：" + lastWhy + "）")));
        }
        Optional<Spot> best = choose(level, player, ship.get());
        if (best.isEmpty()) {
            return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE,
                    "船上和船边都找不到够得着、看得见船上那一格的站位" + (tried.isEmpty() ? "" : "（试过的不算）")));
        }
        tries++;
        tried.add(best.get().key());
        current = move(level, player, ship.get(), best.get());
        return ActionStatus.progressed();
    }

    // 正在走去一处站位：走完下一刻再看一次；走不过去记下原因，下一刻换下一处。
    private ActionStatus walking(TickContext tick) {
        ActionStatus status = current.tick(tick);
        if (status instanceof ActionStatus.Running) return status;
        current.close();
        current = null;
        if (status instanceof ActionStatus.Failed failed) lastWhy = failed.problem().message();
        return ActionStatus.progressed();
    }

    // 从这只眼睛看那一格：外形上的瞄准点换到世界，够得着、第一下打中那一格（给了面就那一面）。
    private boolean seenFrom(ClientLevel level, LocalPlayer player, SableStructures.Structure ship, Vec3 eye, double reach) {
        BlockState state = reads.block(level, ship.id(), target.block());
        if (state == null) return false;
        List<AABB> boxes = state.getShape(level, target.block()).toAabbs();
        return StructureSight.visible(level, player, ship.pose(), eye, target.block(), target.face(), reach,
                StructureSight.aimPoints(target.block(), boxes, target.face())) != null;
    }

    // 挑站位：甲板格与船边地上的格按代价排，没试过的里头从眼睛真射得到那一格的第一处。
    private Optional<Spot> choose(ClientLevel level, LocalPlayer player, SableStructures.Structure ship) {
        double reach = InteractionRange.blockReach(player) - REACH_MARGIN;
        double eyeHeight = player.getEyeHeight();
        Vec3 aim = ship.pose().toWorld(target.bounds().getCenter());
        List<Spot> spots = new ArrayList<>(deckSpots(level, player, ship, aim, reach, eyeHeight));
        spots.addAll(groundSpots(level, player, ship, aim, reach, eyeHeight));
        spots.removeIf(spot -> tried.contains(spot.key()));
        spots.sort(Comparator.comparingDouble(Spot::cost));
        int checked = 0;
        for (Spot spot : spots) {
            if (++checked > CHECK_LIMIT) break;
            if (seenFrom(level, player, ship, spot.standing().add(0, eyeHeight, 0), reach)) return Optional.of(spot);
        }
        return Optional.empty();
    }

    // 甲板上能站的格：不站在要放的格里、不站在要挖的那格正上方，眼睛离那一格大致够得着。
    private List<Spot> deckSpots(ClientLevel level, LocalPlayer player, SableStructures.Structure ship, Vec3 aim,
            double reach, double eyeHeight) {
        boolean onShip = ship.carriesPlayer();
        List<Spot> spots = new ArrayList<>();
        for (StructureGeometry.Deck cell : DeckMode.deck(reads, level, ship)) {
            BlockPos local = cell.local();
            if (avoid.contains(local) || local.equals(target.block()) || local.above().equals(target.block())) continue;
            if (digging && local.below().equals(target.block())) continue;
            if (cell.world().add(0, eyeHeight, 0).distanceTo(aim) > reach + 1) continue;
            double cost = cell.world().distanceTo(player.position()) + (onShip ? 0 : BOARD_COST);
            spots.add(new Spot(Where.DECK, local, null, cell.world(), cost));
        }
        return spots;
    }

    // 船边地上的格：站得住、身体不撞船体、不压着要放的那一格，眼睛离那一格大致够得着。挖方块从地上挖多算一大截。
    private List<Spot> groundSpots(ClientLevel level, LocalPlayer player, SableStructures.Structure ship, Vec3 aim,
            double reach, double eyeHeight) {
        boolean onShip = ship.carriesPlayer();
        List<AABB> hull = DeckMode.hull(reads, level, ship);
        AABB cellBox = StructureGeometry.toWorld(ship.pose(), new AABB(target.block()));
        BlockPos around = BlockPos.containing(aim);
        List<Spot> spots = new ArrayList<>();
        for (int dx = -GROUND_RADIUS; dx <= GROUND_RADIUS; dx++) {
            for (int dz = -GROUND_RADIUS; dz <= GROUND_RADIUS; dz++) {
                for (int dy = -4; dy <= 1; dy++) {
                    BlockPos feet = around.offset(dx, dy, dz);
                    Vec3 standing = Vec3.atBottomCenterOf(feet);
                    if (standing.add(0, eyeHeight, 0).distanceTo(aim) > reach + 1) continue;
                    if (!BlockPassability.isDryStandable(level, feet) || DeckMode.touches(hull, feet)) continue;
                    if (standingBox(feet).intersects(cellBox)) continue;
                    double cost = standing.distanceTo(player.position()) + (onShip ? LEAVE_COST : 0)
                            + (digging ? DIG_FROM_GROUND_COST : 0);
                    spots.add(new Spot(Where.GROUND, null, feet.immutable(), standing, cost));
                }
            }
        }
        return spots;
    }

    // 人站在这一格（脚）时占的那一块。
    private static AABB standingBox(BlockPos feet) {
        return new AABB(feet.getX() + 0.2, feet.getY(), feet.getZ() + 0.2, feet.getX() + 0.8, feet.getY() + 1.8,
                feet.getZ() + 0.8);
    }

    // 走过去：船上的站位——人在船上就在甲板上走，不在就先从离它近的那一边上船；
    // 地上的站位——人在船上先下船，再走到那一格跟前、直着走进去站好。
    private Action move(ClientLevel level, LocalPlayer player, SableStructures.Structure ship, Spot spot) {
        boolean onShip = ship.carriesPlayer();
        if (spot.where() == Where.DECK) {
            Action across = new DeckLeg(reads, services, permissions, DeckMode.Kind.ACROSS, structureId, spot.deck(), null);
            if (onShip) return across;
            DeckMode.Boarding boarding = DeckMode.boarding(reads, level, player, ship, spot.standing(), 1.0);
            if (boarding.pair() == null) return new Refused(Problem.of(Problem.Kind.UNREACHABLE, boarding.problem()));
            DeckMode.Pair pair = boarding.pair();
            return new StepsInOrder("上船站到够得着那一格的地方", List.of(
                    new StepsInOrder.Step("上船", () -> new DeckLeg(reads, services, permissions, DeckMode.Kind.BOARD,
                            structureId, pair.deck().local(), pair.ground())),
                    new StepsInOrder.Step("在船上走过去", () -> pair.deck().local().equals(spot.deck()) ? null : across)));
        }
        List<StepsInOrder.Step> steps = new ArrayList<>();
        if (onShip) {
            DeckMode.Pair pair = DeckMode.leaving(reads, level, player, ship);
            if (pair == null) return new Refused(Problem.of(Problem.Kind.UNREACHABLE, "船沿下面找不到落差三格以内、站得住的地方"));
            steps.add(new StepsInOrder.Step("下船", () -> new DeckLeg(reads, services, permissions, DeckMode.Kind.LEAVE,
                    structureId, pair.deck().local(), pair.ground())));
        }
        steps.add(new StepsInOrder.Step("走到船边那一格跟前",
                () -> services.approach().toward(ApproachTarget.ofBlock(spot.ground().below()), permissions)));
        steps.add(new StepsInOrder.Step("站上去", () -> new StraightWalk(services.context(), spot.standing(), 0.3,
                DeckLeg.STEP_TICKS, "站到船边够得着的那一格")));
        return new StepsInOrder("站到船边够得着那一格的地方", steps);
    }

    @Override public void pause() {
        if (current != null) current.pause();
        halt();
    }

    @Override public void close() {
        if (current != null) {
            current.close();
            current = null;
        }
        halt();
    }

    private void halt() {
        PlayerContext context = services.context().get();
        if (context != null && context.input() != null && context.localPlayer() != null) {
            context.input().halt(context.localPlayer());
        }
    }

    @Override public Interruptibility interruptibility() {
        return current == null ? Interruptibility.BETWEEN_ACTIONS : current.interruptibility();
    }

    @Override public String describe() {
        return "走到够得着船上那一格的地方" + (current == null ? "" : "：" + current.describe());
    }
}
