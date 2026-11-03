// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.chat.spi;

import java.util.List;
import java.util.Optional;

import net.minecraft.world.entity.Entity;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 对一只生物说话的办法：有的模组里生物能听人说话（车万女仆的女仆接着一个 LLM），真人走到她跟前、看着她、
 * 打开她的对话框说。说话能力不认识任何具体模组，点名一只生物时逐个问联动登记的这份办法"它听不听得懂"。
 *
 * <p>分工：说话能力管共用的规矩——认观察编号、问谁会听、话太长让改短、对方还在想上一句时先等它想完；
 * 实现管这只生物那一侧——走近、对准、开它的对话框、逐字打字、交出去、认它的回话。
 */
public interface TalkTo {

    /** 这只生物会不会听人说话：会就给它的听法，不会（不是这个模组的生物）给空。 */
    Optional<Listener> listener(PlayerContext context, Entity creature);

    /** 一只会听人说话的生物。 */
    interface Listener {

        /** 它叫什么：写进结果的 {@code to}。 */
        String name();

        /** 一句话最多几个字（按 Java 字符数）：它的对话框只收这么多。 */
        int maxLength();

        /** 它这会儿为什么听不了角色说话（不是角色的、本机关了对话）；听得了给空。 */
        Optional<Problem> refusal();

        /** 它还在想上一句（头上冒着在想的气泡）：叠着说它会乱，说话能力先等它想完。 */
        boolean thinking();

        /**
         * 对它说这一句：走近、对准、开它的对话框、打字、交出去、等它的回话。
         *
         * @param message     要说的话；长度已经按 {@link #maxLength} 核对过
         * @param maxSeconds  说完以后最多站着等回话几秒；0 表示确认它听到了就走
         * @param typewriter  逐字打字开关，和公屏说话同一个所有者配置
         * @param permissions 这次目标的许可：走过去能动多少地形
         */
        Talk talk(String message, int maxSeconds, boolean typewriter, Permissions permissions);
    }

    /**
     * 说一句话的动作。做完（Done）时它听没听到、回了什么都在 {@link #heard}；中途失败或被取消也照样读得到
     * 已经知道的那部分（例如已经收到的服务器提示）。
     */
    interface Talk extends Action {

        /** 到这一刻为止知道的：交出去没有、它回了什么、聊天栏冒出的别的提示、它自己改了哪些状态。 */
        Heard heard();
    }

    /**
     * 对它说话的结局。
     *
     * @param delivered        它确实听到了：对话框关上，而且它有了反应（开始想、或冒出新的话）
     * @param reply            它的回话原文；没等到为 null
     * @param replyPending     没等到回话：之后的回话照常进聊天事件，不用重说
     * @param personaGenerated 它这次只生成了一份人设、没有回答（车万女仆首次和没有预设人设的女仆说话时）
     * @param notices          这段时间聊天栏收到的其他系统消息原话（对话功能的提示、服务器消息）
     * @param changes          它用这句话改了自己的状态（坐下、跟上、换工作），按说话前后对比列出
     * @param unconfirmed      交出去了却没看到它有反应、或对话框没打开时的说明；看到了反应为 null
     * @param note             需要另外交代的一句（例如两只同名的分不清是谁回的）；没有为 null
     */
    record Heard(boolean delivered, String reply, boolean replyPending, boolean personaGenerated,
                 List<String> notices, List<Change> changes, String unconfirmed, String note) {

        public Heard {
            notices = notices == null ? List.of() : List.copyOf(notices);
            changes = changes == null ? List.of() : List.copyOf(changes);
        }

        /** 还什么都没发生：没交出去、没有回话。 */
        public static Heard nothing() {
            return new Heard(false, null, false, false, List.of(), List.of(), null, null);
        }
    }
}
