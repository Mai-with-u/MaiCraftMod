// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import java.util.Optional;
import java.util.UUID;

/**
 * 正在和谁切磋：fight(spar=true) 开打时登记，收场时清掉，全仓只有这一份。
 * 切磋中这名对手打中角色的不算拍一下也不算连打、不发挨打事件、自卫不为他插进来，记进切磋自己的结果。
 * 只在客户端线程读写。
 */
public final class SparPartner {

    private UUID partner;

    /** 开打：从这一刻起他打中角色的都算切磋里的。 */
    public void begin(UUID who) {
        partner = who;
    }

    /** 收场：切磋结束，他再打中角色就从零按拍一下、连打记。 */
    public void end() {
        partner = null;
    }

    /** 此刻是不是在和他切磋。 */
    public boolean with(UUID who) {
        return who != null && who.equals(partner);
    }

    /** 此刻在和谁切磋；没在切磋为空。 */
    public Optional<UUID> current() {
        return Optional.ofNullable(partner);
    }
}
