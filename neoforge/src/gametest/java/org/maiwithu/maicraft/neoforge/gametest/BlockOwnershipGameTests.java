// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import org.maiwithu.maicraft.game.ModIdentity;
import org.maiwithu.maicraft.gametest.BlockOwnershipGameChecks;

/**
 * 方块归属链路游戏内测试的 NeoForge 登记入口：核对动作是两个加载器共用的
 * {@link BlockOwnershipGameChecks}，这里只负责注册与模板名的加载器写法。
 * NeoForge 会给模板名自动补上 Mod 命名空间，所以这里写裸名。
 */
@GameTestHolder(ModIdentity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BlockOwnershipGameTests {

    @GameTest(template = "empty9x7x9", timeoutTicks = 100)
    public static void placeBlockRecordsOwner(GameTestHelper helper) {
        BlockOwnershipGameChecks.placeBlockRecordsOwner(helper);
    }

    @GameTest(template = "empty9x7x9", timeoutTicks = 100)
    public static void breakBlockForgetsOwner(GameTestHelper helper) {
        BlockOwnershipGameChecks.breakBlockForgetsOwner(helper);
    }

    @GameTest(template = "empty9x7x9", timeoutTicks = 100)
    public static void bedBothHalvesRecorded(GameTestHelper helper) {
        BlockOwnershipGameChecks.bedBothHalvesRecorded(helper);
    }

    @GameTest(template = "empty9x7x9", timeoutTicks = 200)
    public static void saveAndReloadKeepsOwners(GameTestHelper helper) {
        BlockOwnershipGameChecks.saveAndReloadKeepsOwners(helper);
    }

    @GameTest(template = "empty9x7x9", timeoutTicks = 100)
    public static void brokenOwnershipFileStartsEmpty(GameTestHelper helper) {
        BlockOwnershipGameChecks.brokenOwnershipFileStartsEmpty(helper);
    }

    @GameTest(template = "empty9x7x9", timeoutTicks = 100)
    public static void queryRejectsFarChunksAndBadRequests(GameTestHelper helper) {
        BlockOwnershipGameChecks.queryRejectsFarChunksAndBadRequests(helper);
    }
}
