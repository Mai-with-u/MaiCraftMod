// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.settle;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;

/**
 * 绕路进展：出行离目的地没近，但按路线站上、挖开、垫下这一趟里没碰过的格，也算还在往前走。
 * 只能从地下钻进去的屋子，要先往下挖、在墙底下打洞，这几步离目的地反而更远，只按距离算会被当成原地打转判卡住。
 *
 * <p>只认这一趟里没碰过的格：来回走、原地打转、同一格挖了又塌又挖都攒不出来。绕路也得有个头：
 * 离上次变近满 2 分钟就不再认，只认距离；比最好时远出 8 格以外站上新格不算，越走越远的乱逛攒不了进展。
 * 一趟出行一份，逐刻喂。
 */
public final class DetourProgress {

    /** 离上次变近多久以内认绕路进展：2 分钟，徒手也够挖十五六格石头。 */
    static final long ALLOWANCE_TICKS = 20L * 120;
    /** 比最好时远出这么多格以外，站上新格不算进展。 */
    static final double SLACK_BLOCKS = 8.0;

    private final Set<BlockPos> stoodOn = new HashSet<>();
    private final Set<String> dug = new HashSet<>();
    private final Set<BlockPos> placed = new HashSet<>();
    /** 离上次变近推进了多少刻。 */
    private long ticksSinceCloser;
    /** 离上次变近以来挖开的新格。 */
    private int dugSinceCloser;
    /** 离上次变近以来站上的新格（远出范围的不算）。 */
    private int stoodSinceCloser;
    /** 这一刻攒下的绕路进展说法；没有为空。 */
    private String pending;

    /** 离目的地近了一格以上：额度从头算，这一刻挖开、垫下的格不另记绕路进展。 */
    public void closer(BlockPos feet) {
        if (feet != null) stoodOn.add(feet.immutable());
        ticksSinceCloser = 0;
        dugSinceCloser = 0;
        stoodSinceCloser = 0;
        pending = null;
    }

    /** 开路挖开了一格（挖掉方块账里的一条："x, y, z 的 方块ID"）；这一趟里挖过的同一条不算新格。 */
    public void dug(String broken) {
        if (dug.add(broken)) {
            dugSinceCloser++;
            pending = "按路线挖开 " + broken;
        }
    }

    /** 垫下了一格；这一趟里垫过的不算新格。 */
    public void placed(BlockPos cell) {
        if (placed.add(cell.immutable()) && pending == null) {
            pending = "按路线垫下 " + cell.toShortString();
        }
    }

    /**
     * 离目的地没近的一刻：脚站在 feet（还没报到为空），此刻剩余 gap、最好时 best（同一种量法）。
     * 额度没用完、这一刻站上或挖开、垫下了新格时给进展说法，不然为空。
     */
    public Optional<String> advanced(BlockPos feet, double gap, double best) {
        ticksSinceCloser++;
        // 站过就记下；远出范围的新格记下但不算进展，回到范围里也不再算一次。
        if (feet != null && stoodOn.add(feet.immutable()) && gap <= best + SLACK_BLOCKS) {
            stoodSinceCloser++;
            if (pending == null) pending = "按路线走到 " + feet.toShortString();
        }
        String what = pending;
        pending = null;
        return what == null || ticksSinceCloser > ALLOWANCE_TICKS ? Optional.empty() : Optional.of(what);
    }

    /** 额度用完以后收的场：一句"离上次变近多久、这期间挖开几格、站上几个新格"；额度没用完为空。 */
    public Optional<String> summary() {
        if (ticksSinceCloser <= ALLOWANCE_TICKS) return Optional.empty();
        return Optional.of("离目的地 " + ticksSinceCloser / 20 + " 秒没近：这期间按路线挖开 " + dugSinceCloser
                + " 格、站上 " + stoodSinceCloser + " 个新格，绕路额度 " + ALLOWANCE_TICKS / 20 + " 秒用完了");
    }
}
