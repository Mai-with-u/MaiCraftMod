// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;

/**
 * 坑壁：卡坑脱困没出去时，角色脚下、头顶与四周身体两格高的实心格，每格记下走路为什么没挖开它。
 * 写进走不通的说明，一格没挖时 LLM 由此知道挡着的是谁放的、受保护、不算天然地形，还是明明能挖却算不出路，
 * 以及这时还剩哪条出路，不必再猜许可够不够。
 *
 * @param cells 挡着的格子，按读的先后：脚下、头顶、四周
 */
record TrapWalls(List<Cell> cells) {

    /** 没读到现场（不在世界里）或身边没有实心格：说明里不补这一句。 */
    static final TrapWalls NONE = new TrapWalls(List.of());

    TrapWalls {
        cells = List.copyOf(cells);
    }

    /**
     * 挡着的一格。
     *
     * @param placedBy 归属记录上放它的人（在线写名字，不在线写编号）；只有 {@link Wall#PLACED_BY_OTHER} 才有，别的为 null
     */
    record Cell(BlockPos at, String block, Wall wall, String placedBy) {}

    /** 一格挡着的方块，走路为什么没挖开它；说明按这个先后各说一次。 */
    enum Wall {
        /** 归属记录写着是别人放的，受保护（功能方块、记住的区域或地点旁边的，或这一趟挖别人的方块到了上限）。 */
        PLACED_BY_OTHER,
        /** 受保护但没有别人的放置记录：在记住的区域里、地标旁、归属拿不准、像玩家放的门，或这次任务额外保护的格。 */
        PROTECTED,
        /** 不受保护，但不是天然地形、树木草木，也不在实例额外列的方块里：寻路无论许可放到哪一档都不自己挖它。 */
        NOT_TERRAIN,
        /** 天然方块或放开给脱困的别人的普通方块，寻路这次能挖：挡路的不是它，是算不出从这里出去的路。 */
        MAY_DIG
    }

    /**
     * 读角色身边的坑壁：脚下、头顶、四周身体两格高，有碰撞箱的才算挡着；没加载的格不读。
     * 和寻路判断能不能挖用同一份保护与清障名单：先问是不是放开给脱困的别人的方块，再问受不受保护，
     * 最后问算不算天然地形。放的人在线写名字，不在线写编号。
     */
    static TrapWalls around(PlayerContext context, BlockPos feet) {
        ClientLevel level = context == null ? null : context.level();
        if (level == null || feet == null) return NONE;
        List<BlockPos> spots = new ArrayList<>(List.of(feet.below(), feet.above(2)));
        for (Direction side : Direction.Plane.HORIZONTAL) {
            spots.add(feet.relative(side));
            spots.add(feet.above().relative(side));
        }
        List<Cell> found = new ArrayList<>();
        Map<BlockPos, Boolean> trunks = new HashMap<>();
        for (BlockPos spot : spots) {
            if (!level.isLoaded(spot)) continue;
            BlockState state = level.getBlockState(spot);
            if (state.getCollisionShape(level, spot).isEmpty()) continue;
            String block = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            Optional<String> other = Optional.empty();
            Wall wall;
            if (NavigationProtection.escapeOwner(spot, state).isPresent()) {
                wall = Wall.MAY_DIG;
            } else if (NavigationProtection.protects(spot, state)) {
                other = NavigationProtection.placedByOther(spot);
                wall = other.isPresent() ? Wall.PLACED_BY_OTHER : Wall.PROTECTED;
            } else {
                wall = ClearanceWhitelist.allowsAt(state, spot, level, level::isLoaded, trunks)
                        ? Wall.MAY_DIG : Wall.NOT_TERRAIN;
            }
            found.add(new Cell(spot.immutable(), block, wall,
                    other.map(owner -> TrapEscape.nameOf(owner, context)).orElse(null)));
        }
        return new TrapWalls(found);
    }

    /**
     * 一句话说清身边挡着什么、为什么没挖，以及还剩哪条出路，例如
     * "脚下和四周的 biomesoplenty:black_sandstone 不算天然地形，寻路不会自己挖，可以单独下令挖掉：(1, 64, 0)、(1, 65, 0)"。
     * 几类各说一次，同一类的方块列在一起；最多十格，长度有界。没有挡着的给空。
     *
     * @param tier            这次任务的 change_blocks：能不能单独下令挖一格，和许可检查按同一条档位规矩说
     * @param carriesScaffold 手边有没有能垫的料（走不通的现场里已经列出是哪些料，这里不重列）
     */
    String describe(Permissions.BlockChanges tier, boolean carriesScaffold) {
        if (cells.isEmpty()) return "";
        List<String> groups = new ArrayList<>();
        for (Wall wall : Wall.values()) {
            List<Cell> these = cells.stream().filter(cell -> cell.wall() == wall).toList();
            if (these.isEmpty()) continue;
            String blocks = these.stream().map(Cell::block).distinct().collect(Collectors.joining("、"));
            String why = switch (wall) {
                // 别人放的、受保护的：单独下令挖也要过保护，点名同意了才动，和许可检查的说法一致。
                case PLACED_BY_OTHER -> " 是 " + these.stream().map(Cell::placedBy).distinct()
                        .collect(Collectors.joining("、")) + " 放的，要挖得先同意";
                case PROTECTED -> " 受保护，要挖得先同意";
                case NOT_TERRAIN -> " 不算天然地形，寻路不会自己挖" + wayOut(these, tier, carriesScaffold);
                case MAY_DIG -> " 这次能挖，重新算路还是没有路";
            };
            groups.add(blocks + why);
        }
        return "脚下和四周的 " + String.join("；", groups);
    }

    // 不算天然地形又不受保护的：许可能挖别的方块，就单独下令挖掉那几格（写出坐标）；只能收回自己放的，就只能垫上去，
    // 手边连能垫的料都没有也照实说。
    private static String wayOut(List<Cell> cells, Permissions.BlockChanges tier, boolean carriesScaffold) {
        if (tier.digsBeyondOwn()) return "，可以单独下令挖掉：" + where(cells);
        return "；这次许可只能垫方块上去" + (carriesScaffold ? "" : "，手边没有能垫的料");
    }

    // 只有一种方块时只写坐标；几种方块时每个坐标后面跟上是什么方块。
    private static String where(List<Cell> cells) {
        boolean oneKind = cells.stream().map(Cell::block).distinct().count() == 1;
        return cells.stream().map(cell -> "(" + cell.at().getX() + ", " + cell.at().getY() + ", " + cell.at().getZ() + ")"
                + (oneKind ? "" : " " + cell.block())).collect(Collectors.joining("、"));
    }
}
