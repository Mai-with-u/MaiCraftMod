// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine.spi;

import java.util.List;

import org.maiwithu.maicraft.kernel.result.Change;

/**
 * 装安装段的动作交出的、确认过的附带变化：装的过程中顺手做成的事，例如清掉了手上锁链记着的接线起点。
 * 机器能力把它们并进这次施工的变化；这一段装没装成仍按最终结构核对，不看这里。
 */
public interface InstallReport {

    /** 至今确认过的附带变化。 */
    List<Change> changes();
}
