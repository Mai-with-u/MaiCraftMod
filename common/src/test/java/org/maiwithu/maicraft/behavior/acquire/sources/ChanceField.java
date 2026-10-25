// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.acquire.FakeBackpack;
import org.maiwithu.maicraft.behavior.acquire.field.DropsFrom;
import org.maiwithu.maicraft.behavior.acquire.mining.CollectsBlocks;
import org.maiwithu.maicraft.behavior.acquire.mining.DropOdds;
import org.maiwithu.maicraft.behavior.acquire.mining.MinableSpot;
import org.maiwithu.maicraft.behavior.acquire.mining.PlacesBlockBeside;
import org.maiwithu.maicraft.behavior.acquire.mining.ScansMinables;
import org.maiwithu.maicraft.behavior.acquire.mining.VanillaBlockDrops;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 测试替身现场：沿 x 排开一行砾石（挨着，一批连得起来），挖掉就没了；挖到第几块出燧石由场景定，其余掉砾石。
 * 身上的砾石放下时落在 (100+n, 64, 0)，挖它和挖天然的一样按第几块算掉什么。记下走的是哪个收的入口。
 */
class ChanceField implements ScansMinables, CollectsBlocks, PlacesBlockBeside {

    static final String GRAVEL = "minecraft:gravel";
    static final String FLINT = "minecraft:flint";
    static final WantedItem WANT_FLINT = WantedItem.ofItem(FLINT);
    /** 不带时运挖砾石：每块 10%。 */
    static final DropOdds PLAIN = VanillaBlockDrops.chanceSources(FLINT).getFirst().odds(0);

    final FakeBackpack backpack;
    final List<MinableSpot> spots = new ArrayList<>();
    final Set<Integer> flintOnDig;
    final List<String> log = new ArrayList<>();
    DropOdds odds = PLAIN;
    int digs;
    int placed;

    ChanceField(FakeBackpack backpack, int gravel, Set<Integer> flintOnDig) {
        this.backpack = backpack;
        this.flintOnDig = flintOnDig;
        for (int x = 1; x <= gravel; x++) spots.add(new MinableSpot(new BlockPos(x, 64, 0), GRAVEL, FLINT, odds));
    }

    /** 换成拿着时运工具挖的机会。 */
    ChanceField withOdds(DropOdds odds) {
        this.odds = odds;
        spots.replaceAll(spot -> new MinableSpot(spot.pos(), spot.blockType(), spot.droppedItem(), odds));
        return this;
    }

    /** 身上这种东西有几件。 */
    int carried(String itemId) {
        return backpack.stacks().stream().filter(stack -> stack.itemId().equals(itemId))
                .mapToInt(stack -> stack.count()).sum();
    }

    @Override public List<MinableSpot> minable(WantedItem wanted, DropsFrom dropsFrom, WorldPosition center,
            int radiusBlocks) {
        return List.copyOf(spots);
    }

    @Override public boolean anyBlockDrops(WantedItem wanted, DropsFrom dropsFrom) {
        return true;
    }

    @Override public boolean scanComplete() {
        return true;
    }

    @Override public boolean buriedUnderfoot(WantedItem wanted) {
        return false;
    }

    @Override public boolean dropsWanted(WantedItem wanted, DropsFrom dropsFrom, String blockTypeId) {
        return false;
    }

    // 只有要燧石时砾石能放下再挖。
    @Override public List<PlaceAndDig> placeAndDig(WantedItem wanted) {
        return wanted.equals(WANT_FLINT) ? List.of(new PlaceAndDig(GRAVEL, FLINT, odds)) : List.of();
    }

    @Override public Optional<Action> collect(BlockPos cell, Permissions permissions) {
        return collectBatch(List.of(cell), permissions);
    }

    @Override public Optional<Action> collectBatch(List<BlockPos> cells, Permissions permissions) {
        log.add("普通收 " + cells.size() + " 格");
        return Optional.of(digging(cells));
    }

    @Override public Optional<Action> collectForChanceDrops(List<BlockPos> cells, Permissions permissions,
            Consumer<BlockPos> givenUp) {
        log.add("碰运气收 " + cells.size() + " 格");
        return Optional.of(digging(cells));
    }

    // 一格一格挖掉、捡起：挖第 n 块时 n 在出燧石的名单里就进一个燧石，不然进一块砾石。
    private Action digging(List<BlockPos> cells) {
        return new Action() {
            @Override public ActionStatus tick(TickContext context) {
                for (BlockPos cell : cells) {
                    spots.removeIf(spot -> spot.pos().equals(cell));
                    backpack.add(flintOnDig.contains(++digs) ? FLINT : GRAVEL, 1);
                }
                return ActionStatus.done();
            }

            @Override public String describe() {
                return "挖 " + cells.size() + " 格";
            }
        };
    }

    // 放一块：身上没有就失败；放下了落在 (100+n, 64, 0)。
    @Override public Optional<Action> placeBeside(String blockType, Consumer<BlockPos> placedAt) {
        return Optional.of(new Action() {
            @Override public ActionStatus tick(TickContext context) {
                if (!backpack.take(blockType, 1)) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, "身上没有砾石"));
                }
                placed++;
                log.add("放下一块");
                placedAt.accept(new BlockPos(100 + placed, 64, 0));
                return ActionStatus.done();
            }

            @Override public String describe() {
                return "在身边放下砾石";
            }
        });
    }
}
