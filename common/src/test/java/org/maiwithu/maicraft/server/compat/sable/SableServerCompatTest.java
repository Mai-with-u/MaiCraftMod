// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.compat.sable;

import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 问物理结构的受力：只答重心离角色不远的，远处的船不回答。 */
class SableServerCompatTest {

    @Test
    void 只答重心离角色一百二十八格以内的结构() {
        Vec3 character = new Vec3(10, 64, 10);
        assertTrue(SableServerCompat.near(character, new Vec3(10, 64, 138)), "正好一百二十八格还答");
        assertTrue(SableServerCompat.near(character, new Vec3(50, 120, -40)), "站在船边、船悬在头顶都答");
        assertFalse(SableServerCompat.near(character, new Vec3(10, 64, 139)), "再远一格就不答");
        assertFalse(SableServerCompat.near(character, new Vec3(5000, 200, -3000)), "远处别人的船不答");
    }
}
