// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** /mai 的写法与门槛：加、撤、看三种写法认得出；要 3 级权限，普通玩家和 2 级的管理员都敲不了。 */
class CharacterAccountCommandTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static CommandSourceStack source(int permission) {
        return new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, permission, "test",
                Component.literal("test"), null, null);
    }

    private static boolean parses(CommandDispatcher<CommandSourceStack> dispatcher, String command, int permission) {
        ParseResults<CommandSourceStack> parsed = dispatcher.parse(command, source(permission));
        return parsed.getExceptions().isEmpty() && !parsed.getReader().canRead()
                && parsed.getContext().getCommand() != null;
    }

    @Test
    void 三级权限能敲加撤看_两级以下敲不了() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        CharacterAccountCommand.register(dispatcher, new CharacterAccounts(List.of()), player -> { });
        for (String command : List.of("mai Character01", "mai remove Character01", "mai list")) {
            assertTrue(parses(dispatcher, command, 3), command);
            assertFalse(parses(dispatcher, command, 2), "两级管理员不能改名单：" + command);
            assertFalse(parses(dispatcher, command, 0), "普通玩家不能改名单：" + command);
        }
        assertFalse(parses(dispatcher, "mai remove", 3), "撤要说撤谁");
    }
}
