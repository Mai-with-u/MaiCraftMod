// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;

/**
 * 终端界面会话里一段活每刻的走向：「打开与核对」「逐笔搬」「下单等成品」各段拿它回答外层这一刻怎么办，
 * 外层按它换阶段、收场或把动作状态原样交回。
 */
sealed interface TerminalTurn {

    /** 还在本段里做，把动作状态原样交回。 */
    record Stay(ActionStatus status) implements TerminalTurn {
    }

    /**
     * 照常收场：先记下网络存货，再放好光标、关界面。reason 为 null 是拿够了（或只看一眼看完）；
     * evenIfTaken 为真时这次就算已经取到一些也按失败交回：下单没成、等不到成品，引擎要划掉这条路换别的。
     */
    record WrapUp(Problem reason, boolean evenIfTaken) implements TerminalTurn {
    }

    /** 界面已经不在了：不用放光标、不用关，直接结束；取到多少由引擎清点。 */
    record FinishNow(Problem reason) implements TerminalTurn {
    }

    /** 现货不够、网络里有样板：去下单那几页。 */
    record Order() implements TerminalTurn {
    }

    /** 关掉此刻的界面重新打开终端：开工了看成品，或等成品时被打断回来。 */
    record Reopen() implements TerminalTurn {
    }

    /** 核对过了、网络读数同步齐了，这一段做完了。 */
    record Synced() implements TerminalTurn {
    }

    /** 不用放光标了，直接去关界面：放回网络没能确认，或光标一直放不下去。 */
    record CloseOnly() implements TerminalTurn {
    }
}
