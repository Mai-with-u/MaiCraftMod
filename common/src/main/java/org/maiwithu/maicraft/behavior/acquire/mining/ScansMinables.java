// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.behavior.acquire.field.DropsFrom;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;

/**
 * 扫可挖方块的只读接缝：以一处为圆心，找挖掉会掉出想要的东西的方块（矿石、地表的石头这类）。
 * 只报原版对照里真的会掉的：铁矿石掉的是粗铁，问铁锭时它不算。LLM 给了掉落线索（drops_from）时，
 * 线索点名的方块也当作会掉想要的东西——模组矿掉什么客户端读不到，靠线索认；掉没掉以挖完进包的为准。
 * 概率掉落（砾石出燧石、草出种子、树叶出树苗）也报，每格带上挖一块掉它的机会（按挖的时候会拿的工具算时运）。
 */
public interface ScansMinables {

    /**
     * 半径内挖掉会掉出想要的东西的方块，由近及远。
     *
     * @param wanted       想要的东西，例如 minecraft:coal
     * @param dropsFrom    掉落线索：点名的方块也算会掉想要的东西；没有为 {@link DropsFrom#NONE}
     * @param center       从哪里找起
     * @param radiusBlocks 半径，单位格
     */
    List<MinableSpot> minable(WantedItem wanted, DropsFrom dropsFrom, WorldPosition center, int radiusBlocks);

    /**
     * 世界上有没有任何方块直接掉出想要的东西（含概率掉落、掉落线索点名、本实例注册了的方块）；
     * 铁锭这类做出来的物品没有，只能合成或烧炼。
     */
    boolean anyBlockDrops(WantedItem wanted, DropsFrom dropsFrom);

    /** 最近一次找可挖方块有没有把附近扫完：方块索引分刻建，没扫完时空结果不等于没有。 */
    boolean scanComplete();

    /** 想要的东西是不是埋在脚下的石头掉的（圆石、石质工具的材料这类）：谁都知道往下挖几格就有，看不见也能去挖。 */
    boolean buriedUnderfoot(WantedItem wanted);

    /** 挖掉这种方块会不会掉想要的东西（掉落线索点名的也算）：往下挖楼梯、长程挖矿时数挖到了几格用。 */
    boolean dropsWanted(WantedItem wanted, DropsFrom dropsFrom, String blockTypeId);

    /**
     * 这一格是不是缺料时能挖的那种、挖了会掉想要的东西：地图上自然长的、不是设施；是的话给方块注册 ID。
     * 连锁挖整条矿脉时逐格核用——连锁的预览是模组画给玩家看的，埋着的那几格也算看见了，这里不再要求露在外面。
     */
    default Optional<String> minableAt(WantedItem wanted, DropsFrom dropsFrom, BlockPos pos) {
        return Optional.empty();
    }

    /** 想要的东西是哪种知道生成高度的矿石掉的（铁矿、钻石矿这类，见 OreHeights）；不是为空。长程挖矿按它往下挖。 */
    default Optional<String> depositOre(WantedItem wanted) {
        return Optional.empty();
    }

    /**
     * 放下再挖还能接着出想要的东西的方块：砾石没出燧石掉的就是砾石自己，身上的砾石放在脚边再挖，
     * 用不着满世界找下一块。连同挖一块出它的机会（按挖的时候会拿的工具算时运）；没有为空。
     */
    default List<PlaceAndDig> placeAndDig(WantedItem wanted) {
        return List.of();
    }

    /**
     * 一种放下再挖的方块。
     *
     * @param blockType   方块的注册 ID，也是身上那件东西的物品 ID，例如 minecraft:gravel
     * @param droppedItem 碰运气要出的东西，例如 minecraft:flint
     * @param odds        挖一块出它的机会
     */
    record PlaceAndDig(String blockType, String droppedItem, DropOdds odds) {}
}
