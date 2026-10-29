// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import org.maiwithu.maicraft.game.ModIdentity;
import org.maiwithu.maicraft.gametest.ServerLinkGameChecks;

/**
 * 服务端链路游戏内测试的 NeoForge 登记入口：核对动作是两个加载器共用的
 * {@link ServerLinkGameChecks}，这里只负责注册与模板名的加载器写法。
 * NeoForge 会给模板名自动补上 Mod 命名空间，所以这里写裸名。
 */
@GameTestHolder(ModIdentity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ServerLinkGameTests {

    @GameTest(template = "empty9x7x9", timeoutTicks = 100)
    public static void confirmationsReachOnlyActiveSessions(GameTestHelper helper) {
        ServerLinkGameChecks.confirmationsReachOnlyActiveSessions(helper);
    }

    @GameTest(template = "empty9x7x9", timeoutTicks = 100)
    public static void characterAccountsGateHandshakeAndOperations(GameTestHelper helper) {
        ServerLinkGameChecks.characterAccountsGateHandshakeAndOperations(helper);
    }
}
