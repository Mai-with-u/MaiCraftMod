// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import java.util.function.Consumer;

import org.maiwithu.maicraft.kernel.result.Change;

/**
 * 箭的变化记账：把箭账（{@link ArrowsSpent}）补记成结果里的变化。账本每多记一支离开背包的补一支消耗、
 * 每多一支捡回的补一支收获，各种箭合在一起按 minecraft:arrow 记；调用方拿结果对背包的账时对得上。
 * 点名战斗与自卫共用；打完后的那几刻（捡东西、走回）账本还在接着记，这里也照常补。
 */
public final class ArrowChanges {

    /** 已经补记过的：离开背包的、捡回的各几支。 */
    private int takenRecorded;
    private int pickedUpRecorded;

    /** 补记到账本最新一笔；有新账才产生变化。 */
    public void record(int takenTotal, int pickedUpTotal, Consumer<Change> change) {
        int taken = takenTotal - takenRecorded;
        if (taken > 0) {
            change.accept(Change.of(Change.Kind.ITEM_CONSUMED, "minecraft:arrow", taken));
            takenRecorded = takenTotal;
        }
        int pickedUp = pickedUpTotal - pickedUpRecorded;
        if (pickedUp > 0) {
            change.accept(Change.of(Change.Kind.ITEM_GAINED, "minecraft:arrow", pickedUp));
            pickedUpRecorded = pickedUpTotal;
        }
    }
}
