// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.fabric.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

import org.maiwithu.maicraft.game.ModIdentity;
import org.maiwithu.maicraft.gametest.ServerLinkGameChecks;

/**
 * 服务端链路游戏内测试的 Fabric 登记入口：核对动作是两个加载器共用的
 * {@link ServerLinkGameChecks}，这里只负责注册与模板名的加载器写法。
 * Fabric 不给模板名补命名空间，所以要写全 maicraft: 前缀；前缀取自 Mod id 不硬编码
 * 两份，注解参数要求编译期常量，用常量拼接。
 */
public final class ServerLinkGameTests {

    private static final String EMPTY_TEMPLATE = ModIdentity.MOD_ID + ":empty9x7x9";

    @GameTest(template = EMPTY_TEMPLATE, timeoutTicks = 100)
    public static void confirmationsReachOnlyActiveSessions(GameTestHelper helper) {
        ServerLinkGameChecks.confirmationsReachOnlyActiveSessions(helper);
    }

    @GameTest(template = EMPTY_TEMPLATE, timeoutTicks = 100)
    public static void characterAccountsGateHandshakeAndOperations(GameTestHelper helper) {
        ServerLinkGameChecks.characterAccountsGateHandshakeAndOperations(helper);
    }
}
