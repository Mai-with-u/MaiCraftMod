// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.Objects;

import org.maiwithu.maicraft.behavior.inventory.containers.RareLootNotice;
import org.maiwithu.maicraft.behavior.permission.OthersThingsNotice;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.behavior.permission.TrustedPlayers;

/**
 * 许可与保护：这个世界的保护判断与生物处境读端——
 * 这格能不能碰、这只生物能不能打，都问这一组的那一份。新加一路保护事实，加在这里并传入所在组即可。
 */
record Guards(
        Protection protection,
        ReadsCreatureSituation creatures,
        TrustedPlayers trusted,
        OthersThingsNotice othersNotice,
        RareLootNotice rareLootNotice) {

    Guards {
        Objects.requireNonNull(protection, "protection");
        Objects.requireNonNull(creatures, "creatures");
        Objects.requireNonNull(trusted, "trusted");
        othersNotice = othersNotice == null ? OthersThingsNotice.NONE : othersNotice;
        rareLootNotice = rareLootNotice == null ? RareLootNotice.NONE : rareLootNotice;
    }
}
