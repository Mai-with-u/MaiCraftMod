// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server;

import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.network.CharacterReachTolerance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务端角色距离容差：白名单里的角色账号、单人主机与管理员在原版 1.0 格余量上再加 1.0 格兜底，
 * 名单外的普通玩家一丝不放宽，边界与原版严格小于判定一致。
 */
class CharacterReachTest {

    private static final UUID 角色编号 = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");
    private static final UUID 普通编号 = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void 白名单角色与主机管理员加一格容差_普通玩家保持原版余量() {
        CharacterAccounts accounts = new CharacterAccounts(List.of("玩家甲"));
        boolean 准入甲 = accounts.admits("玩家甲", 角色编号, false, false);
        boolean 准入主机 = accounts.admits("玩家乙", 普通编号, true, false);
        boolean 准入路人 = accounts.admits("玩家乙", 普通编号, false, false);

        assertEquals(2.0, CharacterReach.effectivePadding(CharacterReachTolerance.VANILLA_PADDING, 准入甲), 1.0e-6);
        assertEquals(2.0, CharacterReach.effectivePadding(CharacterReachTolerance.VANILLA_PADDING, 准入主机), 1.0e-6);
        assertEquals(1.0, CharacterReach.effectivePadding(CharacterReachTolerance.VANILLA_PADDING, 准入路人), 1.0e-6);
    }

    @Test
    void 方块交互距离对角色账号放宽到加二格且边界严格小于() {
        Vec3 eye = new Vec3(0.5, 65.62, 0.5);
        // 眼睛到 x=6 方块西面 (6.0, 65.62, 0.5) 的距离是 5.5 格：原版 4.5+1.0=5.5 严格小于不过，角色 4.5+2.0=6.5 放行。
        BlockPos 五点五格远 = new BlockPos(6, 65, 0);
        assertFalse(CharacterReach.canInteractWithBlock(eye, 4.5,五点五格远, 1.0, false),
                "普通玩家在 5.5 格处按原版严格小于 5.5 被拒");
        assertTrue(CharacterReach.canInteractWithBlock(eye, 4.5, 五点五格远, 1.0, true),
                "角色账号放宽到 6.5 格上限，停偏在 5.5 格处照常放行");

        // 眼睛到 x=7 方块西面距离正好 6.5 格：严格小于 6.5 不过，角色账号也不越过 2.0 格容差上限。
        BlockPos 六点五格远 = new BlockPos(7, 65, 0);
        assertFalse(CharacterReach.canInteractWithBlock(eye, 4.5, 六点五格远, 1.0, true),
                "满 6.5 格整超出严格小于边界，角色账号同样被拒");
    }

    @Test
    void 实体交互距离只对角色账号放宽一格() {
        Vec3 eye = new Vec3(0.5, 65.62, 0.5);
        // 实体西侧在 x=4.8，离眼睛 4.3 格：普通玩家 3.0+1.0=4.0 够不着，角色账号 3.0+2.0=5.0 放行。
        AABB 实体框 = new AABB(4.8, 64.0, 0.0, 5.6, 66.0, 1.0);
        assertFalse(CharacterReach.canInteractWithEntity(eye, 3.0, 实体框, 1.0, false));
        assertTrue(CharacterReach.canInteractWithEntity(eye, 3.0, 实体框, 1.0, true));

        // 离眼睛满 5.0 格整：角色账号也够不着。
        AABB 过远实体 = new AABB(5.5, 64.0, 0.0, 6.3, 66.0, 1.0);
        assertFalse(CharacterReach.canInteractWithEntity(eye, 3.0, 过远实体, 1.0, true));
    }
}
