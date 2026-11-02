// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.player.ReadsEquipment;

/**
 * 拿东西清点身上几件用的背包视图：背包主格之外，再算上身上穿着的四格护甲。
 *
 * <p>锻造台上升级的那件升级好会穿回身上，清点要数得到它，不然会当成没拿到、再去做一件。
 * 拿到几件按前后相差算，原来就穿着的不会算成这次拿到的；脱下来放进背包也不会算成多了一件。
 * 只给拿东西的引擎与 obtain 结算清点用：还空几格、快捷栏照旧只看背包主格。
 */
public final class BackpackWithArmor implements BackpackView {

    private final BackpackView backpack;
    private final ReadsEquipment equipment;

    public BackpackWithArmor(BackpackView backpack, ReadsEquipment equipment) {
        this.backpack = Objects.requireNonNull(backpack, "backpack");
        this.equipment = Objects.requireNonNull(equipment, "equipment");
    }

    /** 背包主格里的物品堆，后面接着身上从头到脚穿着的护甲。 */
    @Override public List<BackpackStack> stacks() {
        List<BackpackStack> stacks = new ArrayList<>(backpack.stacks());
        for (GearSlotName slot : GearSlotName.armor()) equipment.slot(slot).ifPresent(stacks::add);
        return stacks;
    }

    @Override public int usedSlots() {
        return backpack.usedSlots();
    }

    @Override public int totalSlots() {
        return backpack.totalSlots();
    }

    @Override public OptionalInt hotbarSlotOf(String itemId) {
        return backpack.hotbarSlotOf(itemId);
    }

    @Override public int selectedHotbarSlot() {
        return backpack.selectedHotbarSlot();
    }
}
