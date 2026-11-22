// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.maiwithu.maicraft.behavior.inventory.drops.PicksUpDrops;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 往下挖楼梯的生产实现：像玩家一样斜着往下挖，每下一级先从上往下挖开前面三格（齐头、齐脚、脚下），
 * 等上方重力方块落定重清后再走下去，把这一级挖出来的东西捡起来；挖够了就停在楼梯底，回地面顺着楼梯走上去。
 * 遇到连续重力覆盖层且 16 格内存在安全实岩顶面时，先走 2×1 双格交错穿沙井进入实岩下 3 格再平滑切回阶梯。
 */
public final class ClientStairsDown implements DigsStairsDown {

    /** 一格此刻是什么样。 */
    public enum Ground {
        /** 能挖的实心方块。 */
        DIGGABLE,
        /** 空的：空气、草丛这类走得过去的。 */
        OPEN,
        /** 水、岩浆，含水的方块也算。 */
        FLUID,
        /** 挖不动的（基岩这类），或者还没加载、看不到的。 */
        UNBREAKABLE,
        /** 工作台、箱子、熔炉、床这类设施：挖得动也不为挖楼梯拆，换个方向挖。 */
        FACILITY,
        /** 搭出来的方块（平台、地板、机器垫着的那层）：不为挖楼梯拆，到天然地面上再挖。 */
        BUILT
    }

    /** 挖楼梯要读的现场与走到下一级的动作（往下、往上挖楼梯共用）；生产实现读当刻的世界，测试用替身。 */
    public interface Site {

        /** 角色站着的那一格；不在世界里为空。 */
        Optional<BlockPos> feet(TickContext context);

        /** 脚踩实了没有：还在半空、水里时不挖下一级。 */
        boolean onGround(TickContext context);

        /** 脸朝的水平方向：楼梯先往这边挖。 */
        Direction facing(TickContext context);

        /** 一格此刻是什么样。 */
        Ground ground(TickContext context, BlockPos cell);

        /** 一格的方块注册 ID，例如 minecraft:stone。 */
        String blockType(TickContext context, BlockPos cell);

        /** 当前维度：许可检查按它找保护记录。 */
        String dimension(TickContext context);

        /** 身上能拿去垫脚的废料（泥土、废石、圆石这类，不会往下掉的），按先垫后垫排；没有为空。 */
        default List<String> fillers(TickContext context) {
            return List.of();
        }

        /** 在 cell 垫一块 filler：换到主手，对着旁边的实地点一下放进去，那一格有了实心方块才算；旁边没有能对着放的为空。 */
        default Optional<Action> placeFiller(TickContext context, BlockPos cell, String filler) {
            return Optional.empty();
        }

        /** 站在这一格上面会不会掉血（岩浆块）：下一级脚下是它就不往这边挖，走路也不肯站上去。 */
        default boolean hurtsToStandOn(TickContext context, BlockPos cell) {
            return false;
        }

        /** 这一格是不是受重力影响会塌落的方块（沙子、沙砾、模组黑沙这类）。 */
        default boolean fallingBlock(TickContext context, BlockPos cell) {
            return false;
        }

        /** 该格及正上方竖直柱内是否已有正在下落的重力实体。 */
        default boolean hasFallingEntityAbove(TickContext context, BlockPos cell) {
            return false;
        }

        /** 这一格是不是天然非重力实心岩石：穿沙井按它识别实岩顶面与 3 格实岩顶板。 */
        default boolean solidRock(TickContext context, BlockPos cell) {
            return ground(context, cell) == Ground.DIGGABLE
                    && !fallingBlock(context, cell)
                    && !hurtsToStandOn(context, cell);
        }

        /** 走到下一级（往下挖是下一级台阶，往上挖是上一级）：这一级已经挖通，只走不改地形。 */
        Action stepTo(BlockPos feet);

        /** 这一格能不能站上去开挖：脚和头空着、脚下是天然的实地、站上去不烫脚。 */
        default boolean standable(TickContext context, BlockPos feet) {
            return ground(context, feet) == Ground.OPEN && ground(context, feet.above()) == Ground.OPEN
                    && ground(context, feet.below()) == Ground.DIGGABLE && !hurtsToStandOn(context, feet.below());
        }

        /** 这一列头顶露天的地面在多高（地表的世界 y）；读不到为空。往上挖楼梯按它估还要挖几级。 */
        default OptionalInt surfaceY(TickContext context, BlockPos feet) {
            return OptionalInt.empty();
        }
    }

    /** 一直没挖到想要的方块时最多往下挖几级：沙漠、恶地底下的沙子和陶瓦可能很厚，不一直挖下去。 */
    static final int MAX_DRY_STEPS = 32;
    /** 一次最多挖几级：挖够了早就停了，这是防止没完没了的上限。 */
    static final int MAX_STEPS = 64;
    /** 同一级最多挖几下：普通方块防死循环上限。 */
    static final int MAX_DIGS_PER_STEP = 12;
    /** 遇到重力方块塌落时放宽的每级清理额度：够把几层厚的沙砾口袋清干净再迈步。 */
    static final int MAX_GRAVITY_DIGS_PER_STEP = 24;
    /** 挖空台阶后上方存在重力方块或下落实体时，原地等待落定的窗口刻数。 */
    static final int GRAVITY_SETTLE_TICKS = 12;
    /** 走入下一级失败且发现被落沙回填时，同一级最多退回重挖几次。 */
    static final int MAX_STEP_DIG_RETRIES = 3;
    /** 下一级脚下空着时再往下看几格：两格以内有实地就落到上面（连这一级共掉不到三格，摔不伤）。 */
    static final int EXTRA_DROP = 2;
    /** 长程挖矿横着挖的通道最长几格：挖满还没看见要的矿就停，如实说。 */
    static final int LEVEL_STEPS = 64;
    /** 横着挖时前面本来就空着（洞里、地上）：连着空着的格一次最多走过去几格，走到头再看一眼有没有矿。 */
    static final int OPEN_RUN = 8;
    /** 长程挖矿一次最多走几级（台阶加通道）：从高山挖到深层也够，防止没完没了。 */
    static final int MAX_TUNNEL_STEPS = 400;
    /** 连锁一行最多核几格：模组按自己的上限挖，比这还长的线这里核不完就不连锁。 */
    static final int CHAIN_REACH = 512;

    private final Site site;
    private final DigsBlocks digs;
    private final PicksUpDrops drops;
    private final PermissionCheck permission;
    private final Campground campground;

    public ClientStairsDown(Site site, DigsBlocks digs, PicksUpDrops drops, PermissionCheck permission) {
        this(site, digs, drops, permission, Campground.NOWHERE);
    }

    public ClientStairsDown(Site site, DigsBlocks digs, PicksUpDrops drops, PermissionCheck permission,
            Campground campground) {
        this.site = Objects.requireNonNull(site, "site");
        this.digs = Objects.requireNonNull(digs, "digs");
        this.drops = Objects.requireNonNull(drops, "drops");
        this.permission = Objects.requireNonNull(permission, "permission");
        this.campground = Objects.requireNonNull(campground, "campground");
    }

    @Override
    public Action digDown(Predicate<String> yields, int wantedCells, Permissions permissions) {
        return new StairsDownDigging(site, digs, drops, permission, campground, yields, wantedCells, permissions, null);
    }

    @Override
    public Optional<Action> digDown(Predicate<BlockPos> reached, int plannedCells,
            Permissions permissions, TaskRecords records) {
        return Optional.of(new StairsDownDigging(site, digs, drops, permission, campground,
                reached, plannedCells, permissions, records));
    }

    @Override
    public Optional<Action> tunnelToward(Tunnel tunnel, Permissions permissions) {
        return Optional.of(new StairsDownDigging(site, digs, drops, permission, campground,
                tunnel.yields(), tunnel.wantedCells(), permissions, tunnel));
    }
}
