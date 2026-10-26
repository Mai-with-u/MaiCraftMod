// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.List;

import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 走到机器出口跟前看一眼、顺手收产物的动作交出的实情：点开界面那一刻输出格里有几件要的东西
 * （掉在地上的那种是动手时这一块地上躺着几件）、搬进背包几件、过程中值得知道的事。
 * 看完界面就关上；收多少以背包前后清点为准，不按点了几下算。
 */
interface OutputLook extends Action {

    /** 看的那一刻出口里有几件要的东西（收走之前）。 */
    int seen();

    /** 这一眼收进背包几件；只看不收时为 0。 */
    int taken();

    /** 点不开、搬不动、捡不到、没能确认的那几下，一句一条。 */
    List<String> notes();
}
