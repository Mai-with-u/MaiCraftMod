// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.inventory.hands.ClientMovesToMainhand;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 被困在自己家里时的出路：门和栅栏门走路本来就会推开，走到这一步说明附近没有能开的门；
 * 这时只水平拆开选好的那一两格墙（{@link HomeBreach}）出去，出去后停下，用同一种方块补回去，再按原目标接着走。
 *
 * <p>拆的时候寻路只许挖那一两格，家里四周别的格都当保护格。补的时候人站在外面、不站在要补的格里，
 * 贴着旁边结实的一面放；身上没有那种方块、走远了够不着、放不上去，都如实记下没补回，不硬补。
 * 拆了哪格、是谁放的、补没补回写进这一趟出行的经过。
 */
final class HomeWayOut {

    /** 补一格最多试这么多刻：转头、换手、点一下等确认都在里面。 */
    private static final int PLACE_TIMEOUT_TICKS = 80;
    /** 补的时候眼睛离那一格中心最远这么远：比原版够得着的距离留一点余量。 */
    private static final double REACH = 4.0;
    /** 镜头转到离瞄点这么近才点。 */
    private static final float AIM_DEGREES = 2.5f;
    /** 比这还硬的墙不拆（黑曜石、远古残骸这类）：拆一格要很久，直播里看着像卡住。 */
    private static final float MAX_HARDNESS = 6.0f;

    /** 这一段出路走到哪一步。 */
    enum Stage { IDLE, NO_WAY, BREACHING, REFILL_PENDING, REFILLING, DONE }

    /** 补墙这一刻怎么样：人还在拆开的格里接着走、刚停下开始补、正在补、补完了。 */
    enum Step { WALK_ON, STARTED, BUSY, FINISHED }

    private Stage stage = Stage.IDLE;
    private HomeBreach breach;
    /** 每格拆之前是什么方块：补回去用同一种。 */
    private final Map<BlockPos, Block> before = new LinkedHashMap<>();
    /** 每格是谁放的（别人放的才有，写名字）。 */
    private final Map<BlockPos, String> owners = new LinkedHashMap<>();
    /** 每格补得怎么样：补回了，或者没补回的原因。 */
    private final Map<BlockPos, String> refills = new LinkedHashMap<>();
    private int cellIndex;
    private int cellTicks;
    private Action handing;

    Stage stage() {
        return stage;
    }

    /** 这一趟已经按"被困在家里"处理过：不再按坑里那样升许可乱挖。 */
    boolean started() {
        return stage != Stage.IDLE;
    }

    /** 正在拆墙出去：寻路的保护格要加上家里的外壳，只放开选中的那几格。 */
    boolean breaching() {
        return stage == Stage.BREACHING;
    }

    /** 这一趟的保护格：正拆墙出去时加上家里四周的外壳，只放开选中的那几格；没在拆原样给回。 */
    LongSet withShell(LongSet sacred) {
        if (!breaching()) return sacred;
        LongSet all = new LongOpenHashSet(sacred);
        all.addAll(breach.shell());
        return all;
    }

    /** 拆墙出去时选中要拆的那几格；没在拆为空。 */
    LongSet breakable() {
        if (!breaching()) return LongSets.emptySet();
        LongSet cells = new LongOpenHashSet();
        breach.cells().forEach(cell -> cells.add(cell.asLong()));
        return cells;
    }

    /**
     * 被困在自己家里时找墙：找到了就记下每格原来是什么、是谁放的，开始拆；找不到就记下没出路，按走不通如实收场。
     * 已经处理过一次的不再找。
     *
     * @param sacred 这一趟另外保护的格：选墙时也不碰
     */
    boolean tryStart(PlayerContext context, BlockPos feet, LongSet sacred) {
        if (started()) return false;
        ClientLevel level = context.level();
        Optional<HomeBreach> chosen = HomeBreach.find(new LiveTrapCells(level), feet,
                WalkDropPolicy.maxDrop(context.localPlayer()), cell -> breakableWall(level, cell, sacred),
                cell -> fragile(level, cell));
        if (chosen.isEmpty()) {
            stage = Stage.NO_WAY;
            return false;
        }
        start(chosen.get(), level, owner -> TrapEscape.nameOf(owner, context));
        return true;
    }

    /**
     * 选好了要拆的墙：记下每格原来是什么、是谁放的，开始拆。
     *
     * @param names 归属记录上的玩家编号换成名字（不在线写"一位不在线的玩家"）
     */
    private void start(HomeBreach chosen, ClientLevel level, Function<String, String> names) {
        breach = chosen;
        stage = Stage.BREACHING;
        for (BlockPos cell : chosen.cells()) {
            before.put(cell, level.getBlockState(cell).getBlock());
            NavigationProtection.placedByOther(cell).ifPresent(owner -> owners.put(cell, names.apply(owner)));
        }
    }

    /**
     * 这一格是不是拆开后正在补、已经补过的那几格墙：补墙点下去的确认可能晚几刻才落到走路那边，
     * 那一格是补回去的墙，不是垫路的临时方块，不进垫块清单，免得结果里写成"没有自动收回"引着人回去拆。
     */
    boolean refilledWall(BlockPos cell) {
        return (stage == Stage.REFILLING || stage == Stage.DONE) && breach.cells().contains(cell);
    }

    /** 出来了（站在地上、从这里只走就出得去）：等人离开拆开的格子就停下补墙。 */
    void exited() {
        if (stage == Stage.BREACHING) stage = Stage.REFILL_PENDING;
    }

    /**
     * 补墙这一刻占不占身体：人离开拆开的格子就撤路线、松键、站住，一格格补；正在补的这几刻站着不算没进展；
     * 补完调 resume 按原目标重新上路。没在补、人还在拆开的格里时不占。
     */
    boolean holdsPlayer(PlayerContext context, LocalPlayer player, EngineClicks clicks, ClientMovesToMainhand hands,
            BaritoneInternals owner, NavigationProgress displacement, Runnable resume) {
        if (stage != Stage.REFILL_PENDING && stage != Stage.REFILLING) return false;
        Step step = refillTick(context, player, clicks, hands);
        if (step == Step.WALK_ON) return false;
        if (step == Step.STARTED) {
            owner.pathing().forceCancel();
            owner.engine().getInputOverrideHandler().clearAllKeys();
            owner.dropLookTarget();
            context.input().releaseAll(player);
        }
        displacement.restart(context.clientTick());
        if (step == Step.FINISHED) resume.run();
        return true;
    }

    /**
     * 补墙推进一刻。人还站在拆开的格里时接着走，离开了就停下，从下往上一格格补：
     * 换上那种方块、转头对准旁边结实的一面、点一下等游戏确认那一格真长出了方块。
     */
    private Step refillTick(PlayerContext context, LocalPlayer player, EngineClicks clicks, ClientMovesToMainhand hands) {
        ClientLevel level = context.level();
        if (stage == Stage.REFILL_PENDING) {
            if (breach.cells().stream().anyMatch(cell -> inside(player, cell))) return Step.WALK_ON;
            stage = Stage.REFILLING;
            cellIndex = 0;
            cellTicks = 0;
            return Step.STARTED;
        }
        while (cellIndex < breach.cells().size()) {
            BlockPos cell = breach.cells().get(cellIndex);
            Optional<String> settled = settled(level, player, hands, cell);
            if (settled.isPresent()) {
                nextCell(cell, settled.get());
                continue;
            }
            if (++cellTicks > PLACE_TIMEOUT_TICKS) {
                nextCell(cell, "没补回：放不上去");
                continue;
            }
            placeStep(context, player, clicks, hands, cell);
            return Step.BUSY;
        }
        stage = Stage.DONE;
        return Step.FINISHED;
    }

    // 这一格已经有结论了没有：补上了、本来就有方块、身上没有、走远了够不着、找不到贴着放的面。
    private Optional<String> settled(ClientLevel level, LocalPlayer player, ClientMovesToMainhand hands, BlockPos cell) {
        BlockState now = level.getBlockState(cell);
        Block wanted = before.get(cell);
        if (!now.canBeReplaced()) {
            return Optional.of(now.getBlock() == wanted && cellTicks > 0 ? "已补回" : "没补：那一格已经有方块了");
        }
        Item item = wanted.asItem();
        if (item == Items.AIR) return Optional.of("没补回：" + idOf(wanted) + " 没有对应的物品");
        if (handing == null && !hands.carried(itemId(item))) return Optional.of("没补回：身上没有 " + itemId(item));
        if (player.getEyePosition().distanceTo(Vec3.atCenterOf(cell)) > REACH) return Optional.of("没补回：出来后已经走远了");
        if (inside(player, cell)) return Optional.of("没补回：人站在那一格里");
        if (support(level, player, cell).isEmpty()) return Optional.of("没补回：旁边没有能贴着放的面");
        return Optional.empty();
    }

    // 补一格的一刻：先把那种方块换到主手，再对准贴着的那一面，转到位了点一下。
    private void placeStep(PlayerContext context, LocalPlayer player, EngineClicks clicks, ClientMovesToMainhand hands,
            BlockPos cell) {
        String wanted = itemId(before.get(cell).asItem());
        if (handing != null) {
            ActionStatus status = handing.tick(TickContext.of(context.level().getGameTime(), context));
            if (status instanceof ActionStatus.Running) return;
            handing = null;
            return;
        }
        if (!itemId(player.getMainHandItem().getItem()).equals(wanted)) {
            handing = hands.moveToMainhand(wanted).orElse(null);
            return;
        }
        Rotation aim = support(context.level(), player, cell).orElse(null);
        if (aim == null) return;
        context.input().requestLook(aim.getYaw(), aim.getPitch(), context.clientTick());
        boolean aimed = Math.abs(Mth.wrapDegrees(player.getYRot() - aim.getYaw())) <= AIM_DEGREES
                && Math.abs(player.getXRot() - aim.getPitch()) <= AIM_DEGREES;
        clicks.tick(context, false, true, aimed);
        clicks.takePlaced();
    }

    private void nextCell(BlockPos cell, String result) {
        refills.put(cell, result);
        cellIndex++;
        cellTicks = 0;
        handing = null;
    }

    /**
     * 贴着放的那一面：要补的格子旁边结实、不会点开界面的一格，从眼睛看过去第一下就打在它朝着这一格的那一面上。
     * 先找脚下那面，再找四周，最后头顶。
     */
    static Optional<Rotation> support(ClientLevel level, LocalPlayer player, BlockPos cell) {
        Vec3 eyes = player.getEyePosition();
        List<Direction> order = new ArrayList<>(List.of(Direction.DOWN));
        order.addAll(Direction.Plane.HORIZONTAL.stream().toList());
        order.add(Direction.UP);
        for (Direction side : order) {
            BlockPos against = cell.relative(side);
            BlockState state = level.getBlockState(against);
            if (state.canBeReplaced() || state.getCollisionShape(level, against).isEmpty() || state.hasBlockEntity()) continue;
            Vec3 face = Vec3.atCenterOf(cell).add(side.getStepX() * 0.5, side.getStepY() * 0.5, side.getStepZ() * 0.5);
            if (eyes.distanceTo(face) > REACH + 0.5) continue;
            Rotation rotation = RotationUtils.calcRotationFromVec3d(eyes, face,
                    new Rotation(player.getYRot(), player.getXRot()));
            Vec3 toward = face.subtract(eyes).normalize().scale(REACH + 0.5);
            HitResult hit = level.clip(new ClipContext(eyes, eyes.add(toward), ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE, player));
            if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK
                    && block.getBlockPos().equals(against) && block.getDirection() == side.getOpposite()) {
                return Optional.of(rotation);
            }
        }
        return Optional.empty();
    }

    // 身体和这一格有没有重叠：站在要补的格里就放不进去。
    private static boolean inside(LocalPlayer player, BlockPos cell) {
        return player.getBoundingBox().deflate(0.05).intersects(new AABB(cell));
    }

    /**
     * 这一格墙拆不拆得：有碰撞、拆得动，不是床、门、活板门、栅栏门、带方块实体的家具，上面没压着床和家具，
     * 在家里、不是功能方块、不在记住的区域里，也不在这一趟另外保护的格里。
     */
    static boolean breakableWall(ClientLevel level, BlockPos cell, LongSet sacred) {
        if (!level.isLoaded(cell) || sacred.contains(cell.asLong())) return false;
        BlockState state = level.getBlockState(cell);
        if (state.isAir() || state.liquid() || state.getCollisionShape(level, cell).isEmpty()) return false;
        Block block = state.getBlock();
        if (block instanceof BedBlock || block instanceof DoorBlock || block instanceof TrapDoorBlock
                || block instanceof FenceGateBlock || state.hasBlockEntity()) return false;
        float hardness = state.getDestroySpeed(level, cell);
        if (hardness < 0 || hardness > MAX_HARDNESS) return false;
        BlockState above = level.getBlockState(cell.above());
        if (above.getBlock() instanceof BedBlock || above.hasBlockEntity()) return false;
        return NavigationProtection.breakableAtHome(cell, state);
    }

    /** 一拆就碎、掉不回原物的：玻璃、玻璃板、冰、树叶。只在别无选择时才拆。 */
    static boolean fragile(ClientLevel level, BlockPos cell) {
        BlockState state = level.getBlockState(cell);
        return state.is(BlockTags.IMPERMEABLE) || state.is(BlockTags.ICE) || state.is(BlockTags.LEAVES)
                || state.getBlock() instanceof IronBarsBlock;
    }

    /**
     * 这一段出路的经过，例如"在自己家里被围住，附近没有能开的门：水平拆开 (1, 64, 0) 的 minecraft:oak_planks
     * （玩家甲 放的）出来，已补回"。还没拆过为空。
     */
    String story() {
        if (breach == null) return "";
        List<String> cells = new ArrayList<>();
        for (BlockPos cell : breach.cells()) {
            String owner = owners.containsKey(cell) ? "（" + owners.get(cell) + " 放的）" : "";
            String result = refills.getOrDefault(cell, stage == Stage.BREACHING ? "" : "没来得及补");
            cells.add("(" + cell.getX() + ", " + cell.getY() + ", " + cell.getZ() + ") 的 " + idOf(before.get(cell)) + owner
                    + (result.isEmpty() ? "" : "，" + result));
        }
        return "在自己家里被围住，附近没有能开的门：水平拆开 " + String.join("；", cells) + " 出来";
    }

    /** 拆了墙、还没补完这一趟就收场了：拆了哪格、补到哪一步；没拆过、已经补完记过的给空串。 */
    String unfinishedStory() {
        return breach == null || stage == Stage.DONE ? "" : story();
    }

    /** 拆的墙里有没有别人放的：有就报一条事件。 */
    boolean brokeOthers() {
        return !owners.isEmpty();
    }

    /**
     * 走不通时补的那一句：附近没门、没有能水平拆开出去的墙，或者拆开那几格也算不出路。只有在家里被围住时才补。
     */
    Problem explain(Problem problem, String around) {
        if (problem == null || stage != Stage.NO_WAY && stage != Stage.BREACHING) return problem;
        String why = stage == Stage.NO_WAY
                ? "在自己家里被围住：附近没有能开的门，也没有能水平拆开一两格就出去的墙（地板、床、家具、记住的区域里的不拆）"
                : story() + "，可是还是算不出出去的路";
        return Problem.of(problem.kind(), problem.message() + "；" + why + (around.isEmpty() ? "" : "；" + around),
                "开门、点名拆哪一格，或者换个目标");
    }

    private static String idOf(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    private static String itemId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }
}
