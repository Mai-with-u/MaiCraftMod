// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.Objects;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.acquire.engine.OffhandContents;
import org.maiwithu.maicraft.behavior.acquire.engine.ReadsCharacterPosition;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.ReadsEffects;
import org.maiwithu.maicraft.game.player.ReadsEquipment;
import org.maiwithu.maicraft.game.player.ReadsFoodValues;
import org.maiwithu.maicraft.game.player.ReadsGearFit;
import org.maiwithu.maicraft.game.player.ReadsHunger;

/**
 * 当刻角色的读端：能力构造一次、用到整局，读的却是每一刻的角色——
 * 各读端接在角色上下文的供给者上，上下文不在手（不在世界、人接管中）时如实给空，按"读不到"处理。
 * 新加一个读角色的读端，加在这里并传入所在组即可。
 */
record CharacterReads(
        /** 当刻的角色上下文：感知、靠近、换手与联动的玩家行为都从它拿这一刻的角色。 */
        Supplier<PlayerContext> context,
        BackpackView backpack,
        OffhandContents offhand,
        ReadsHunger hunger,
        ReadsFoodValues foods,
        ReadsEquipment equipment,
        ReadsEffects effects,
        ReadsGearFit gearFit,
        /** 角色此刻站在哪一格：丢弃落点、挖楼梯、记地点、施工锚点与拿到物品都从这里读。 */
        ReadsCharacterPosition position) {

    CharacterReads {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(backpack, "backpack");
        Objects.requireNonNull(offhand, "offhand");
        Objects.requireNonNull(hunger, "hunger");
        Objects.requireNonNull(foods, "foods");
        Objects.requireNonNull(equipment, "equipment");
        Objects.requireNonNull(effects, "effects");
        Objects.requireNonNull(gearFit, "gearFit");
        Objects.requireNonNull(position, "position");
    }
}
