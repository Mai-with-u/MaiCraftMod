// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import java.util.Optional;

/**
 * 界面会话从哪来：关闭界面与放回光标上的东西用眼下这份会话，补试时作废重领。
 * 会话由打开者认领并留着，关闭的一方不另认领，免得光标来源的登记对不上。
 */
public interface MenuSessionSource {

    /** 眼下这份会话；还没认领或认领不了（光标上挂着不是自己拿的东西）时为空。 */
    Optional<MenuSession> current();

    /** 作废眼下这份会话，下一次 {@link #current()} 重新认领。 */
    void renew();
}
