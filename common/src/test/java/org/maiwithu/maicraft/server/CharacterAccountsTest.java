// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 角色账号名单：名单里的号、主机、管理员能用服务端链路，普通玩家不能；文件不在生成空名单，坏文件按空名单收场。 */
class CharacterAccountsTest {

    private static final UUID CHARACTER = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");
    private static final UUID FAN = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @TempDir
    Path configDir;

    @Test
    void 名单按玩家名或UUID认_玩家名不分大小写() throws IOException {
        Files.writeString(configDir.resolve(CharacterAccounts.FILE_NAME),
                "{\"characterAccounts\": [\"Character01\", \"" + FAN + "\"]}", StandardCharsets.UTF_8);
        CharacterAccounts accounts = CharacterAccounts.read(configDir);

        assertTrue(accounts.admits("character01", CHARACTER, false, false), "写的是玩家名，大小写不同也认");
        assertTrue(accounts.admits("SomeoneRenamed", FAN, false, false), "写的是 UUID，改了名也认");
        assertFalse(accounts.admits("Passerby", UUID.randomUUID(), false, false), "名单外的普通玩家不给用");
    }

    @Test
    void 名单外的主机与管理员照样能用() {
        CharacterAccounts empty = new CharacterAccounts(List.of());
        assertTrue(empty.admits("Host", CHARACTER, true, false), "单人游戏与局域网的主机自己就是角色");
        assertTrue(empty.admits("Admin", CHARACTER, false, true), "服务器管理员是服主自己的号");
        assertFalse(empty.admits("Fan", FAN, false, false), "空名单时普通玩家一个也不放");
    }

    @Test
    void 文件不在就生成空名单() throws IOException {
        CharacterAccounts accounts = CharacterAccounts.read(configDir);
        Path file = configDir.resolve(CharacterAccounts.FILE_NAME);
        assertTrue(Files.exists(file), "文件不存在时应生成一份空名单给服主照着填");
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains(CharacterAccounts.KEY));
        assertFalse(accounts.admits("Fan", FAN, false, false));
    }

    @Test
    void 坏文件按空名单收场() throws IOException {
        Files.writeString(configDir.resolve(CharacterAccounts.FILE_NAME), "这不是 JSON", StandardCharsets.UTF_8);
        assertFalse(CharacterAccounts.read(configDir).admits("Fan", FAN, false, false));

        // 名单里混了数字：整份按坏配置收场，不悄悄把数字当成名字。
        Files.writeString(configDir.resolve(CharacterAccounts.FILE_NAME),
                "{\"characterAccounts\": [\"Character01\", 42]}", StandardCharsets.UTF_8);
        CharacterAccounts mixed = CharacterAccounts.read(configDir);
        assertFalse(mixed.admits("Character01", CHARACTER, false, false));
        assertFalse(mixed.admits("42", FAN, false, false));
    }
}
