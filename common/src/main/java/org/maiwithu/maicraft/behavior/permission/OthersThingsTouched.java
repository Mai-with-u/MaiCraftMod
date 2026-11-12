// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.kernel.event.EventFacts;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 动了别人放的东西的结构化事实：角色从别人放的容器里取放东西、拆掉或改动别人放的方块，
 * 确认发生后挂在 others_things_touched 事件的 others_things 字段上交给宿主知情。
 *
 * @param action    做了什么：从容器拿走、往容器存进、拆掉方块、改动方块
 * @param owner     放它的人：在线写玩家名，不在线写"一位不在线的玩家"
 * @param ownerUuid 放置者的玩家 UUID，宿主按它稳定对人
 * @param block     动的是哪种方块（注册 ID）；连拆多格时是第一格的方块类型
 * @param at        方块所在位置；连拆多格时是第一格的位置
 * @param items     容器两侧确认取走或存进的物品与件数；拆方块与改动方块时为 null
 * @param count     连着拆掉同一个人的好几格方块时的总格数；单格或非拆方块时为 null
 */
public record OthersThingsTouched(Action action, String owner, String ownerUuid, String block,
        WorldPosition at, List<Item> items, Integer count) implements EventFacts {

    /** 动别人放的东西的四种动作。 */
    public enum Action { TOOK, STORED, BROKE, CHANGED }

    /** 容器两侧确认搬运的一样物品与件数。 */
    public record Item(String item, int count) {
        public Item {
            Objects.requireNonNull(item, "item");
        }
    }

    public OthersThingsTouched {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(at, "at");
        items = items == null ? null : List.copyOf(items);
    }

    @Override
    public String field() {
        return "others_things";
    }

    /** 从别人放的容器里确认拿走了几样东西。 */
    public static OthersThingsTouched took(String owner, String ownerUuid, String block,
            WorldPosition at, List<Item> items) {
        return new OthersThingsTouched(Action.TOOK, owner, ownerUuid, block, at, items, null);
    }

    /** 往别人放的容器里确认存进了几种东西。 */
    public static OthersThingsTouched stored(String owner, String ownerUuid, String block,
            WorldPosition at, List<Item> items) {
        return new OthersThingsTouched(Action.STORED, owner, ownerUuid, block, at, items, null);
    }

    /** 确认拆掉了别人放的方块；多格连拆时带上总格数，单格时不带 count。 */
    public static OthersThingsTouched broke(String owner, String ownerUuid, String block,
            WorldPosition at, int brokenBlocks) {
        return new OthersThingsTouched(Action.BROKE, owner, ownerUuid, block, at, null,
                brokenBlocks > 1 ? brokenBlocks : null);
    }

    /** 对别人放的方块用了会改动它的东西并确认生效（耕地、剥皮、倒流体、点火、写告示牌这类）。 */
    public static OthersThingsTouched changed(String owner, String ownerUuid, String block, WorldPosition at) {
        return new OthersThingsTouched(Action.CHANGED, owner, ownerUuid, block, at, null, null);
    }
}
