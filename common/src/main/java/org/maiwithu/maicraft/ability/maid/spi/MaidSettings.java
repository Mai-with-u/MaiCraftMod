// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.maid.spi;

import java.util.List;
import java.util.Optional;

import net.minecraft.world.entity.Entity;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 改一只女仆设置的办法：车万女仆这类模组的女仆是一种可驯服生物，皮肤、工作、班次、跟随或驻守、拾取、手上身上的装备
 * 都在她的界面里改。改女仆设置的能力不认识具体模组，点名一只生物时逐个问联动登记的这份办法"她是不是你的女仆"。
 *
 * <p>分工：能力管认观察编号、认不出时如实说、把设置写成一串键值；实现管她认哪些键、值对不对、
 * 打开她的界面、逐项改、逐项确认、关界面。
 */
public interface MaidSettings {

    /** 这只生物是不是这个模组的女仆：是就给改她设置的办法，不是给空。 */
    Optional<Maid> maid(PlayerContext context, Entity creature);

    /** 一只女仆。 */
    interface Maid {

        /** 她叫什么：写进结果。 */
        String name();

        /** 她认的设置项键，按说明顺序。 */
        List<String> keys();

        /**
         * 按给的顺序一项项改：已经是目标值的直接算完成，不开界面也不发包；要改的先打开她的界面，每项确认了再改下一项，
         * 改完关上。值不认识、游戏拒绝、等满没变都停在那一项，如实交回。
         */
        Configure configure(List<Setting> settings, Permissions permissions);
    }

    /** 一项设置：键与要改成的值（值统一写字符串）。 */
    record Setting(String key, String value) {}

    /** 改设置的动作；做完、失败、被取消都读得到已经改成了哪些。 */
    interface Configure extends Action {

        /** 到这一刻为止改了什么。 */
        Report report();
    }

    /**
     * 改设置的结局。
     *
     * @param applied     改成了的项：键、原来的值、现在的值
     * @param skipped     本来就是目标值、没动的项
     * @param workHint    切了工作时界面写的启用条件（例如"主手需要锄：不满足"）；没切工作为空
     * @param shiftNow    她此刻在干什么（work 工作中 / rest 休息 / sleep 睡觉）：不在工作时段切了工作，要等班次开始才动
     * @param changes     她身上改了什么，按生物受影响列
     * @param unconfirmed 改了却等满没看到变化的那一项
     */
    record Report(List<Applied> applied, List<String> skipped, List<String> workHint, String shiftNow,
                  List<Change> changes, List<Change> unconfirmed) {

        public Report {
            applied = List.copyOf(applied);
            skipped = List.copyOf(skipped);
            workHint = List.copyOf(workHint);
            changes = List.copyOf(changes);
            unconfirmed = List.copyOf(unconfirmed);
        }

        /** 什么都还没改。 */
        public static Report nothing() {
            return new Report(List.of(), List.of(), List.of(), null, List.of(), List.of());
        }
    }

    /** 改成了的一项。 */
    record Applied(String key, String from, String to) {}
}
