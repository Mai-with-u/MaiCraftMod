// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine.spi;

import java.util.Objects;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 进出口：一格机器方块和外界交换东西的一面，或它的加工处。
 * 例如"压机的加工处在正下方两格""Mekanism 机器北面按侧面配置是物品输出"。
 *
 * @param at     进出口所在的格：面上的进出口是机器自己那一格，加工处是被加工的那一格（压机下方的置物台）
 * @param side   哪一面；加工处这类不分面的为 null
 * @param medium 交换的是什么
 * @param flow   进、出，还是两样都有
 * @param note   给 LLM 看的一句说明，例如"加工处：放在置物台上或从传送带经过"
 */
public record ExchangePoint(BlockPos at, Direction side, Medium medium, NetworkKind network, Flow flow, String note) {

    /** 交换的是物理介质（物品、流体、能量这类）的进出口；接某张网的用带网络种类的那个构造。 */
    public ExchangePoint(BlockPos at, Direction side, Medium medium, Flow flow, String note) {
        this(at, side, medium, null, flow, note);
    }

    public ExchangePoint {
        at = Objects.requireNonNull(at, "at").immutable();
        if (medium == null && network == null) throw new IllegalArgumentException("进出口要么写介质，要么写接哪种网");
        Objects.requireNonNull(flow, "flow");
        note = note == null ? "" : note;
    }

    /** 交换的东西：物理介质；接某张网的面不答介质，network 说明接哪种网。 */
    public enum Medium {
        ITEM,
        FLUID,
        /** 能量（FE 一类）。 */
        ENERGY,
        /** 机械动力（Create 的转速与应力）。 */
        ROTATION,
        /** Mekanism 的化学品（气体、浆料、注入物）。 */
        CHEMICAL
    }

    /** 方向。 */
    public enum Flow {
        INPUT,
        OUTPUT,
        BOTH
    }
}
