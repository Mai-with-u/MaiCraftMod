// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.behavior.acquire.engine.ReadsCharacterPosition;

/** 测试替身：角色一直站在摆好的位置上，问价的路程都从这里算。 */
public final class FixedSpot implements ReadsCharacterPosition {
    private final WorldPosition position;

    public FixedSpot(int x, int y, int z) {
        this.position = WorldPosition.here(x, y, z);
    }

    @Override public WorldPosition currentPosition() {
        return position;
    }
}
