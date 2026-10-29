// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.fabric.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

import org.maiwithu.maicraft.game.ModIdentity;
import org.maiwithu.maicraft.gametest.BlockOwnershipGameChecks;

/**
 * 方块归属链路游戏内测试的 Fabric 登记入口：核对动作是两个加载器共用的
 * {@link BlockOwnershipGameChecks}，这里只负责注册与模板名的加载器写法。
 * Fabric 不给模板名补命名空间，所以要写全 maicraft: 前缀；前缀取自 Mod id 不硬编码
 * 两份，注解参数要求编译期常量，用常量拼接。测试入口按 Fabric 惯例在主源集、随 Jar
 * 发布（entrypoint 在生产环境也要能解析），没有被测试运行用到时不做事。
 */
public final class BlockOwnershipGameTests {

    private static final String EMPTY_TEMPLATE = ModIdentity.MOD_ID + ":empty9x7x9";

    @GameTest(template = EMPTY_TEMPLATE, timeoutTicks = 100)
    public static void placeBlockRecordsOwner(GameTestHelper helper) {
        BlockOwnershipGameChecks.placeBlockRecordsOwner(helper);
    }

    @GameTest(template = EMPTY_TEMPLATE, timeoutTicks = 100)
    public static void breakBlockForgetsOwner(GameTestHelper helper) {
        BlockOwnershipGameChecks.breakBlockForgetsOwner(helper);
    }

    @GameTest(template = EMPTY_TEMPLATE, timeoutTicks = 100)
    public static void bedBothHalvesRecorded(GameTestHelper helper) {
        BlockOwnershipGameChecks.bedBothHalvesRecorded(helper);
    }

    @GameTest(template = EMPTY_TEMPLATE, timeoutTicks = 200)
    public static void saveAndReloadKeepsOwners(GameTestHelper helper) {
        BlockOwnershipGameChecks.saveAndReloadKeepsOwners(helper);
    }

    @GameTest(template = EMPTY_TEMPLATE, timeoutTicks = 100)
    public static void brokenOwnershipFileStartsEmpty(GameTestHelper helper) {
        BlockOwnershipGameChecks.brokenOwnershipFileStartsEmpty(helper);
    }

    @GameTest(template = EMPTY_TEMPLATE, timeoutTicks = 100)
    public static void queryRejectsFarChunksAndBadRequests(GameTestHelper helper) {
        BlockOwnershipGameChecks.queryRejectsFarChunksAndBadRequests(helper);
    }
}
