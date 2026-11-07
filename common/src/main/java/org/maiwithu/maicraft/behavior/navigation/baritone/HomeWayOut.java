// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import baritone.api.IBaritone;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BlockStateInterface;
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
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.BaseTorchBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CandleCakeBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.GrindstoneBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.acquire.mining.VanillaBlockDrops;
import org.maiwithu.maicraft.behavior.inventory.hands.ClientMovesToMainhand;
import org.maiwithu.maicraft.behavior.navigation.util.BlockPassability;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 被困在自己家里时的出路：门和栅栏门走路本来就会推开，走到这一步说明附近没有能开的门；
 * 这时只水平拆开选好的那一两格墙（{@link HomeBreach}）出去，出去后停下，用同一种方块补回去，再按原目标接着走。
 *
 * <p>拆的时候寻路只许挖那一两格，家里四周别的格都当保护格；选好的墙寻路不肯挖（贴着水这类）就换下一面。
 * 人穿过拆开的格子、脚也离开屋里那一片才停下补：拆完最后一格时人还在屋里，这时补就把自己又封回去了。
 * 补的时候人站在外面、不站在要补的格里，贴着旁边结实的一面放，等游戏确认那一格真长出了方块才算补回；
 * 身上没有那种方块、走远了够不着、放不上去、被叫停，都如实记下没补回，不硬补。
 * 真挖开了哪格、是谁放的、补没补回写进这一趟出行的经过；补完收起这一段，同一趟里再被困在家里重新找墙。
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
    /** 一次被困最多换几面墙：选好的墙一格没挖开寻路就说出不去，换下一面再算。 */
    private static final int MAX_WALLS = 3;

    /** 这一段出路走到哪一步。 */
    enum Stage { IDLE, NO_WAY, BREACHING, REFILL_PENDING, REFILLING, DONE }

    /** 补墙这一刻怎么样：人还没穿出缺口接着走、刚停下开始补、正在补、补完了、补到一半被叫停。 */
    enum Step { WALK_ON, STARTED, BUSY, FINISHED, STOPPED }

    private Stage stage = Stage.IDLE;
    private HomeBreach breach;
    /** 每格拆之前是什么方块：补回去用同一种。 */
    private final Map<BlockPos, Block> before = new LinkedHashMap<>();
    /** 每格是谁放的（别人放的才有，写名字）：真挖开了那一格才算拆了别人的。 */
    private final Map<BlockPos, String> owners = new LinkedHashMap<>();
    /** 引擎确认真挖开了的格：只有这些写成"拆开"、只补这些。 */
    private final Set<BlockPos> dug = new LinkedHashSet<>();
    /** 每格补得怎么样：补回了，或者没补回的原因。 */
    private final Map<BlockPos, String> refills = new LinkedHashMap<>();
    /** 要补的格，从下往上：真挖开了的那几格。 */
    private final List<BlockPos> toRefill = new ArrayList<>();
    /** 这一次被困里寻路不肯挖、换掉的墙：再找时跳过。 */
    private final LongSet refused = new LongOpenHashSet();
    /** 这一趟补墙时点过的格：确认晚到时那一格是补回去的墙，不是垫路的临时方块；收起一段后也留着。 */
    private final LongSet refilled = new LongOpenHashSet();
    /** 人的身体碰到过拆开的格子：进过缺口以后再离开，才算出来了。 */
    private boolean enteredGap;
    private int walls;
    private int cellIndex;
    private int cellTicks;
    /** 正在补的这一格点过了：方块出来以后不再点，只等游戏确认。 */
    private boolean clickedCell;
    private Action handing;

    Stage stage() {
        return stage;
    }

    /** 这一次被困已经按"被困在家里"处理着：不再按坑里那样升许可乱挖。补完收起后为假。 */
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
     * 正在拆的墙一格还没挖开、寻路就说出不去（多半是寻路自己不肯挖：贴着水、冰、悬空的沙砾），这面不拆了，换下一面再算；
     * 已经挖开过的不换。寻路不挖的格选墙时也不选，和寻路同一条规矩。
     *
     * @param sacred 这一趟另外保护的格：选墙时也不碰
     */
    boolean tryStart(PlayerContext context, IBaritone engine, BlockPos feet, LongSet sacred) {
        boolean retry = stage == Stage.BREACHING && dug.isEmpty() && walls < MAX_WALLS;
        if (started() && !retry) return false;
        if (retry) breach.cells().forEach(cell -> refused.add(cell.asLong()));
        ClientLevel level = context.level();
        BlockStateInterface engineView = new BlockStateInterface(engine.getPlayerContext());
        Optional<HomeBreach> chosen = HomeBreach.find(new LiveTrapCells(level), feet,
                WalkDropPolicy.maxDrop(context.localPlayer()),
                cell -> level.isLoaded(cell) && !refused.contains(cell.asLong()) && breakableWall(level, cell, sacred)
                        && !MovementHelper.avoidBreaking(engineView, cell.getX(), cell.getY(), cell.getZ(),
                                level.getBlockState(cell)),
                cell -> fragile(level.getBlockState(cell)),
                cell -> knocksOff(level, cell) || framesOn(level, cell));
        if (chosen.isEmpty()) {
            // 换不出别的墙就照原样留着正在拆的那面：说明里写清选的是哪格、一格没拆成。
            if (!retry) stage = Stage.NO_WAY;
            return false;
        }
        Map<BlockPos, Block> blocks = new LinkedHashMap<>();
        Map<BlockPos, String> placedBy = new LinkedHashMap<>();
        for (BlockPos cell : chosen.get().cells()) {
            blocks.put(cell, level.getBlockState(cell).getBlock());
            NavigationProtection.placedByOther(cell).ifPresent(owner -> placedBy.put(cell, TrapEscape.nameOf(owner, context)));
        }
        begin(chosen.get(), blocks, placedBy);
        return true;
    }

    /**
     * 选好了要拆的墙，开始拆：记下每格原来是什么方块、是谁放的（别人放的写名字）。换墙时前一面的账清掉。
     */
    void begin(HomeBreach chosen, Map<BlockPos, Block> blocks, Map<BlockPos, String> placedBy) {
        breach = chosen;
        stage = Stage.BREACHING;
        walls++;
        before.clear();
        before.putAll(blocks);
        owners.clear();
        owners.putAll(placedBy);
        dug.clear();
        refills.clear();
        toRefill.clear();
        enteredGap = false;
    }

    /** 引擎确认挖开了一格：是正在拆的墙就记下真拆开了它。没挖开的格不写进经过、也不补。 */
    void noteDug(BlockPos cell) {
        if ((stage == Stage.BREACHING || stage == Stage.REFILL_PENDING) && breach.cells().contains(cell)) {
            dug.add(cell.immutable());
        }
    }

    /**
     * 这一格是不是补墙时点过的那几格：补墙点下去的确认可能晚几刻才落到走路那边，
     * 那一格是补回去的墙，不是垫路的临时方块，不进垫块清单，免得结果里写成"没有自动收回"引着人回去拆。
     */
    boolean refilledWall(BlockPos cell) {
        return refilled.contains(cell.asLong());
    }

    /**
     * 卡坑判断说出得去了：拆完最后一格的那一刻人往往还站在屋里，先等人穿过缺口再补。
     * 一格都没拆开就出得去了（有人开了门、墙被别的东西弄开），没有要补的，这一段收起。
     */
    void exited() {
        if (stage != Stage.BREACHING) return;
        if (dug.isEmpty()) {
            reset();
            return;
        }
        stage = Stage.REFILL_PENDING;
    }

    /**
     * 补墙前每刻看一眼人走到哪了：身体碰着拆开的格子就记下进过缺口；进过以后身体离开那几格、脚也不在屋里那一片了，
     * 才算出来了，停下补墙。拆完最后一格时人多半还站在屋里，这时补就把自己又封回去了。
     *
     * @param inGap 身体此刻和拆开的哪一格有重叠
     * @param feet  脚下这一格
     */
    Step leaveGap(boolean inGap, BlockPos feet) {
        if (stage != Stage.REFILL_PENDING) return Step.WALK_ON;
        if (inGap) {
            enteredGap = true;
            return Step.WALK_ON;
        }
        if (!enteredGap || breach.pocket().contains(feet.asLong())) return Step.WALK_ON;
        stage = Stage.REFILLING;
        toRefill.clear();
        breach.cells().stream().filter(dug::contains).forEach(toRefill::add);
        toRefill.forEach(cell -> refilled.add(cell.asLong()));
        cellIndex = 0;
        cellTicks = 0;
        clickedCell = false;
        handing = null;
        return Step.STARTED;
    }

    /** 正在补的那一格；都补完了为空，这一段记为补完。 */
    Optional<BlockPos> refillingCell() {
        if (stage != Stage.REFILLING) return Optional.empty();
        if (cellIndex < toRefill.size()) return Optional.of(toRefill.get(cellIndex));
        stage = Stage.DONE;
        return Optional.empty();
    }

    /** 正在补的这一格有了结论（补回了、没补回的原因），换下一格。 */
    void settleCell(String result) {
        refills.put(toRefill.get(cellIndex), result);
        cellIndex++;
        cellTicks = 0;
        clickedCell = false;
        handing = null;
    }

    /**
     * 补到一半被叫停：正在补的这一格和后面的都记"被叫停"，这一格点过、方块已经出来了的记"放上了，没等到游戏确认"。
     * 这一段记为补完，不再按原目标接着走，经过由收场时记。
     *
     * @param shownInWorld 正在补的这一格此刻看得到原来那种方块
     */
    void stopRefilling(boolean shownInWorld) {
        boolean first = true;
        while (stage == Stage.REFILLING && cellIndex < toRefill.size()) {
            settleCell(first && clickedCell && shownInWorld ? "放上了，没等到游戏确认（被叫停）" : "没补回：被叫停");
            first = false;
        }
        stage = Stage.DONE;
    }

    /** 补完、经过记好以后收起这一段：同一趟里又被困在家里时重新找墙。补墙时点过的格子还记着。 */
    void reset() {
        stage = Stage.IDLE;
        breach = null;
        before.clear();
        owners.clear();
        dug.clear();
        refills.clear();
        toRefill.clear();
        refused.clear();
        walls = 0;
        enteredGap = false;
        cellIndex = 0;
        cellTicks = 0;
        clickedCell = false;
        handing = null;
    }

    /**
     * 补墙这一刻占不占身体：人穿出缺口、离开屋里就撤路线、松键、站住，一格格补；正在补的这几刻站着不算没进展；
     * 补完调 resume 按原目标重新上路，再收起这一段。没在补、人还没穿出去时不占；被叫停就不再补，交回走路按停下收场。
     */
    boolean holdsPlayer(PlayerContext context, LocalPlayer player, EngineClicks clicks, ClientMovesToMainhand hands,
            BaritoneInternals owner, NavigationProgress displacement, boolean stopRequested, Runnable resume) {
        if (stage != Stage.REFILL_PENDING && stage != Stage.REFILLING) return false;
        Step step = refillTick(context, player, clicks, hands, stopRequested);
        if (step == Step.WALK_ON || step == Step.STOPPED) return false;
        if (step == Step.STARTED) {
            owner.pathing().forceCancel();
            owner.engine().getInputOverrideHandler().clearAllKeys();
            owner.dropLookTarget();
            context.input().releaseAll(player);
        }
        displacement.restart(context.clientTick());
        if (step == Step.FINISHED) {
            resume.run();
            reset();
        }
        return true;
    }

    /**
     * 补墙推进一刻。人还没穿出缺口时接着走，穿出来了就停下，从下往上一格格补：
     * 换上那种方块、转头对准旁边结实的一面、点一下，等游戏确认那一格真长出了方块。被叫停就把没补的记下、不再补。
     */
    private Step refillTick(PlayerContext context, LocalPlayer player, EngineClicks clicks, ClientMovesToMainhand hands,
            boolean stopRequested) {
        ClientLevel level = context.level();
        if (stage == Stage.REFILL_PENDING) {
            // 被叫停时不开始补：补一格要好几秒，停不下来。
            if (stopRequested) return Step.WALK_ON;
            boolean inGap = breach.cells().stream().anyMatch(cell -> inside(player, cell));
            return leaveGap(inGap, player.blockPosition());
        }
        if (stopRequested) {
            stopRefilling(refillingCell().map(cell -> shows(level, cell)).orElse(false));
            return Step.STOPPED;
        }
        for (Optional<BlockPos> next = refillingCell(); next.isPresent(); next = refillingCell()) {
            BlockPos cell = next.get();
            Optional<String> settled = settled(level, player, hands, cell);
            if (settled.isPresent()) {
                settleCell(settled.get());
                continue;
            }
            if (++cellTicks > PLACE_TIMEOUT_TICKS) {
                settleCell(clickedCell && shows(level, cell) ? "放上了，没等到游戏确认" : "没补回：放不上去");
                continue;
            }
            if (placeStep(context, player, clicks, hands, cell)) settleCell("已补回");
            return Step.BUSY;
        }
        return Step.FINISHED;
    }

    // 这一格已经有结论了没有：别的东西占了那一格、身上没有、走远了够不着、人站在格里、找不到贴着放的面。
    // 点过、那种方块已经出来了的还没结论：等游戏确认。
    private Optional<String> settled(ClientLevel level, LocalPlayer player, ClientMovesToMainhand hands, BlockPos cell) {
        BlockState now = level.getBlockState(cell);
        Block wanted = before.get(cell);
        if (!now.canBeReplaced()) {
            return clickedCell && now.getBlock() == wanted ? Optional.empty() : Optional.of("没补：那一格已经有方块了");
        }
        Item item = wanted.asItem();
        if (item == Items.AIR) return Optional.of("没补回：" + idOf(wanted) + " 没有对应的物品");
        if (handing == null && !hands.carried(itemId(item))) return Optional.of("没补回：身上没有 " + itemId(item));
        if (player.getEyePosition().distanceTo(Vec3.atCenterOf(cell)) > REACH) return Optional.of("没补回：出来后已经走远了");
        if (inside(player, cell)) return Optional.of("没补回：人站在那一格里");
        if (support(level, player, cell).isEmpty()) return Optional.of("没补回：旁边没有能贴着放的面");
        return Optional.empty();
    }

    // 补一格的一刻：先把那种方块换到主手，再对准贴着的那一面，转到位了点一下；方块出来以后不再点，
    // 只推进那一下的确认。游戏确认落到的正是这一格才算补回。
    private boolean placeStep(PlayerContext context, LocalPlayer player, EngineClicks clicks, ClientMovesToMainhand hands,
            BlockPos cell) {
        String wanted = itemId(before.get(cell).asItem());
        if (handing != null) {
            ActionStatus status = handing.tick(TickContext.of(context.level().getGameTime(), context));
            if (!(status instanceof ActionStatus.Running)) handing = null;
            return false;
        }
        boolean shown = shows(context.level(), cell);
        if (!shown && !itemId(player.getMainHandItem().getItem()).equals(wanted)) {
            handing = hands.moveToMainhand(wanted).orElse(null);
            return false;
        }
        boolean aimed = false;
        if (!shown) {
            Rotation aim = support(context.level(), player, cell).orElse(null);
            if (aim == null) return false;
            context.input().requestLook(aim.getYaw(), aim.getPitch(), context.clientTick());
            aimed = Math.abs(Mth.wrapDegrees(player.getYRot() - aim.getYaw())) <= AIM_DEGREES
                    && Math.abs(player.getXRot() - aim.getPitch()) <= AIM_DEGREES;
        }
        if (clicks.tick(context, false, !shown, aimed) != EngineClicks.Doing.NOTHING) clickedCell = true;
        return clicks.takePlaced().filter(cell::equals).isPresent();
    }

    // 这一格此刻看得到拆之前那种方块：客户端先放上去，游戏确认之前也看得到。
    private boolean shows(ClientLevel level, BlockPos cell) {
        return level.getBlockState(cell).getBlock() == before.get(cell);
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

    // 身体和这一格有没有重叠：站在要补的格里就放不进去；穿过缺口时也靠它认出人进过缺口。
    private static boolean inside(LocalPlayer player, BlockPos cell) {
        return player.getBoundingBox().deflate(0.05).intersects(new AABB(cell));
    }

    /**
     * 这一格墙拆不拆得：有碰撞、拆得动，不是床、门、活板门、栅栏门、带方块实体的家具，上面没压着床和家具，
     * 在家里、不是功能方块、不在记住的区域里，也不在这一趟另外保护的格里。没加载的由调用方先挡掉。
     */
    static boolean breakableWall(BlockGetter level, BlockPos cell, LongSet sacred) {
        if (sacred.contains(cell.asLong())) return false;
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

    /**
     * 挖掉掉的不是自己、补不回原样的：玻璃、玻璃板碎了，石头掉圆石、草方块掉泥土、书架掉书、树叶多半什么都不掉。
     * 身上多半没有原来那种方块，补回去就缺一块，同样多时最后才拆。
     */
    static boolean fragile(BlockState state) {
        Block block = state.getBlock();
        return state.is(BlockTags.IMPERMEABLE) || block instanceof IronBarsBlock && block != Blocks.IRON_BARS
                || !VanillaBlockDrops.dropsItself(idOf(block));
    }

    /**
     * 拆掉这一格会不会带掉别的东西：上面压着沙子、砂砾这类会掉下来的；四周有贴在它上面、立在它上面、
     * 吊在它下面的东西（火把、告示牌、梯子、按钮、拉杆、旗帜、灯笼、地毯、花）。展示框和画由现场另看。
     */
    static boolean knocksOff(BlockGetter level, BlockPos cell) {
        if (BlockPassability.breakReleasesFallingBlock(level, cell)) return true;
        for (Direction side : Direction.values()) {
            BlockState next = level.getBlockState(cell.relative(side));
            if (!next.isAir() && hangsOn(next).orElse(null) == side.getOpposite()) return true;
        }
        return false;
    }

    /**
     * 这块东西靠哪一面的方块挂着、立着：贴在墙上的（火把、告示牌、梯子、旗帜、绊线钩、贴墙的按钮拉杆、紫水晶簇）朝背后，
     * 可可豆朝它长着的那根原木；立在上面的（火把、告示牌、旗帜、压力板、铁轨、红石、地毯、花草、花盆、雪层、蜡烛、蛋糕、门）
     * 朝下；吊着的（灯笼、天花板上的按钮、悬挂告示牌）朝上。自己立得住的、两边都能挂的（墙上的悬挂告示牌）为空。
     */
    static Optional<Direction> hangsOn(BlockState state) {
        Block block = state.getBlock();
        if (state.hasProperty(BlockStateProperties.HANGING)) {
            return Optional.of(state.getValue(BlockStateProperties.HANGING) ? Direction.UP : Direction.DOWN);
        }
        if (block instanceof FaceAttachedHorizontalDirectionalBlock && !(block instanceof GrindstoneBlock)) {
            return Optional.of(switch (state.getValue(BlockStateProperties.ATTACH_FACE)) {
                case FLOOR -> Direction.DOWN;
                case CEILING -> Direction.UP;
                case WALL -> state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
            });
        }
        if (block instanceof WallTorchBlock || block instanceof RedstoneWallTorchBlock || block instanceof WallSignBlock
                || block instanceof LadderBlock || block instanceof WallBannerBlock || block instanceof TripWireHookBlock) {
            return Optional.of(state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite());
        }
        if (block instanceof AmethystClusterBlock) return Optional.of(state.getValue(BlockStateProperties.FACING).getOpposite());
        if (block instanceof CocoaBlock) return Optional.of(state.getValue(BlockStateProperties.HORIZONTAL_FACING));
        if (block instanceof WallHangingSignBlock) return Optional.empty();
        if (block instanceof CeilingHangingSignBlock) return Optional.of(Direction.UP);
        if (block instanceof BaseTorchBlock || block instanceof SignBlock || block instanceof BannerBlock
                || block instanceof BasePressurePlateBlock || block instanceof BaseRailBlock || block instanceof DiodeBlock
                || block instanceof RedStoneWireBlock || block instanceof CarpetBlock || block instanceof BushBlock
                || block instanceof FlowerPotBlock || block instanceof SnowLayerBlock || block instanceof CandleBlock
                || block instanceof CakeBlock || block instanceof CandleCakeBlock || block instanceof DoorBlock) {
            return Optional.of(Direction.DOWN);
        }
        return Optional.empty();
    }

    // 墙上挂着展示框、画：它们贴着的那一格被拆掉就掉下来。
    private static boolean framesOn(ClientLevel level, BlockPos cell) {
        AABB wall = new AABB(cell);
        return !level.getEntitiesOfClass(HangingEntity.class, wall.inflate(1.0), hanging -> {
            Direction facing = hanging.getDirection();
            return hanging.getBoundingBox().move(-facing.getStepX() * 0.5, -facing.getStepY() * 0.5,
                    -facing.getStepZ() * 0.5).deflate(1.0E-7).intersects(wall);
        }).isEmpty();
    }

    /**
     * 这一段出路的经过：真挖开了哪格（什么方块、谁放的）、补没补回；选好的墙一格没挖开的写"没拆成"。例如
     * "在自己家里被围住，附近没有能开的门：水平拆开 (1, 64, 0) 的 minecraft:oak_planks（玩家甲 放的），已补回 出来"。
     * 还没选墙为空。
     */
    String story() {
        if (breach == null) return "";
        String lead = "在自己家里被围住，附近没有能开的门：";
        if (dug.isEmpty()) {
            List<String> chosen = breach.cells().stream().map(this::cellName).toList();
            return lead + "选好要水平拆开 " + String.join("；", chosen) + " 出去，一格也没拆成";
        }
        boolean out = stage == Stage.REFILLING || stage == Stage.DONE;
        List<String> cells = new ArrayList<>();
        for (BlockPos cell : breach.cells()) {
            if (!dug.contains(cell)) {
                cells.add(cellName(cell) + " 没拆成");
                continue;
            }
            String owner = owners.containsKey(cell) ? "（" + owners.get(cell) + " 放的）" : "";
            cells.add(cellName(cell) + owner + "，" + refills.getOrDefault(cell, out ? "没来得及补" : "拆开了没补"));
        }
        return lead + "水平拆开 " + String.join("；", cells) + (out ? " 出来" : "");
    }

    private String cellName(BlockPos cell) {
        return "(" + cell.getX() + ", " + cell.getY() + ", " + cell.getZ() + ") 的 " + idOf(before.get(cell));
    }

    /** 这一段还没收起就收场了（拆到一半、拆了没补、补到一半被叫停）：照实交出经过；没选过墙、已经补完收起的给空串。 */
    String unfinishedStory() {
        return story();
    }

    /** 真挖开的格里有没有别人放的：有就报一条事件。选好了没挖开的不算。 */
    boolean brokeOthers() {
        return dug.stream().anyMatch(owners::containsKey);
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
