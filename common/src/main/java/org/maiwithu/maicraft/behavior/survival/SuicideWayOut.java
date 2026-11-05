// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.Optional;
import org.maiwithu.maicraft.game.world.KeepInventoryRule;

/**
 * 走投无路时提的那句寻死出路：卡在坑里出不去、饿了问遍各处弄不到吃的、水里从这里到不了、
 * 点名打架撤了又回不了血，这几处没做成时在给 LLM 的话末尾接一句。
 *
 * <p>死亡不掉落开着时死一次回重生点，血和饱食度都是满的，往往比硬撑着省事；位置和手头进度要不要紧由 LLM 判断，
 * 这里只把这条路和它的条件说清楚，Mod 自己不会去寻死。关着、创造、旁观、极限模式、规则还在读时不提。
 */
public interface SuicideWayOut {

    /** 读到死亡不掉落开着时说的。 */
    String WHEN_ON = "死亡不掉落开着，位置不要紧的话可以 suicide 回重生点，血和饱食度都是满的";
    /** 连别人的服务器读不到规则时说的：要调用方确认开着才能寻死。 */
    String WHEN_UNKNOWN = "如果这个服务器开着死亡不掉落，位置不要紧的话可以 suicide（带 keep_inventory_confirmed=true）"
            + "回重生点，血和饱食度都是满的";

    /** 不提：离线场景和没接上读端时用。 */
    SuicideWayOut NONE = Optional::empty;

    /** 此刻该不该提、提哪句；不该提为空。只在客户端线程调用。 */
    Optional<String> line();

    /**
     * 按死亡不掉落与游戏模式定提哪句：开着说开着那句，读不到说"如果开着"那句；
     * 关着、规则还在读不提；创造、旁观死不了，极限死了不能重生，也不提。
     */
    static Optional<String> line(KeepInventoryRule.Reading keepInventory, boolean creative, boolean spectator,
            boolean hardcore) {
        if (creative || spectator || hardcore) return Optional.empty();
        return switch (keepInventory) {
            case ON -> Optional.of(WHEN_ON);
            case UNREADABLE -> Optional.of(WHEN_UNKNOWN);
            case OFF, READING -> Optional.empty();
        };
    }

    /** 接在一句建议末尾：不提时照原样；原来没有建议就只有这一句。 */
    static String appendTo(String suggestion, Optional<String> line) {
        if (line.isEmpty()) return suggestion;
        return suggestion == null || suggestion.isBlank() ? line.get() : suggestion + "；" + line.get();
    }
}
