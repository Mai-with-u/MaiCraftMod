// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.maiwithu.maicraft.game.ModIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 角色账号名单：服务器认哪些玩家账号是 MaiCraft 角色，写在服务端配置 config/maicraft-server.json 的
 * characterAccounts 里（玩家名或 UUID）。
 *
 * <p>只有角色账号能和服务端 MaiCraft 握手、经服务端链路问方块归属与模组读数。普通玩家照常游玩，只是服务端一个操作
 * 也不对他们开放：公开服上有人装了 MaiCraft 或改了客户端，也不能远程查遍全图谁在哪盖了房子、谁家的网存了多少东西。
 * 名单外还放行两种人：单人游戏与局域网的主机（开世界的人自己的号就是角色），服务器管理员（权限 2 级及以上，服主自己的号）。
 *
 * <p>服务器启动时读一次。文件不存在就生成一份空名单让服主照着填；读不出来按空名单收场并记警告，不让坏配置挡住开服。
 */
public final class CharacterAccounts {

    private static final Logger LOG = LoggerFactory.getLogger(CharacterAccounts.class);

    /** 服务端配置文件名，放在加载器给的配置目录（config）下。 */
    static final String FILE_NAME = "maicraft-server.json";
    /** 名单在配置文件里的键。 */
    static final String KEY = "characterAccounts";
    /** 服务器管理员的权限等级：原版 /op 默认给的就是这一级以上。 */
    private static final int OPERATOR_LEVEL = 2;

    /** 名单里写的玩家名，按小写存：原版玩家名不分大小写。 */
    private final Set<String> names = new HashSet<>();
    private final Set<UUID> ids = new HashSet<>();

    /** 按名单里写的一项项建：像 UUID 的按 UUID 认，其余按玩家名认。 */
    CharacterAccounts(List<String> entries) {
        for (String entry : entries) {
            String value = entry.trim();
            if (value.isEmpty()) continue;
            try {
                ids.add(UUID.fromString(value));
            } catch (IllegalArgumentException notUuid) {
                names.add(value.toLowerCase(Locale.ROOT));
            }
        }
    }

    /** 读配置目录（config）下的服务端配置；文件不在就生成一份空名单。 */
    public static CharacterAccounts read(Path configDirectory) {
        Path file = configDirectory.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            writeDefault(file);
            return new CharacterAccounts(List.of());
        }
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            List<String> entries = new ArrayList<>();
            if (json.has(KEY)) {
                for (JsonElement entry : json.getAsJsonArray(KEY)) {
                    // 数字、布尔不当名字收：Gson 会把它们悄悄转成字符串，这里先认清是不是字符串。
                    if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) {
                        throw new IllegalArgumentException("角色账号名单里只能写玩家名或 UUID 字符串：" + entry);
                    }
                    entries.add(entry.getAsString());
                }
            }
            CharacterAccounts accounts = new CharacterAccounts(entries);
            LOG.info("{} 角色账号名单读到 {} 个：{}", ModIdentity.NAME, accounts.names.size() + accounts.ids.size(), file);
            return accounts;
        } catch (Exception exception) {
            // 坏配置不挡开服：按空名单收场（只剩主机与管理员能当角色），并把原因记下来让服主能发现。
            LOG.warn("{} 服务端配置读不出来，角色账号名单按空的算：{}", ModIdentity.NAME, file, exception);
            return new CharacterAccounts(List.of());
        }
    }

    /** 这个玩家能不能用服务端链路：名单里有他，或者他是单人与局域网的主机、服务器管理员。 */
    public boolean admits(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        GameProfile profile = player.getGameProfile();
        boolean host = server != null && server.isSingleplayerOwner(profile);
        return admits(profile.getName(), profile.getId(), host, player.hasPermissions(OPERATOR_LEVEL));
    }

    /** 判断本身：名单按 UUID 或玩家名（不分大小写）对上，或者是主机、管理员。 */
    boolean admits(String name, UUID id, boolean host, boolean operator) {
        if (host || operator) return true;
        if (id != null && ids.contains(id)) return true;
        return name != null && names.contains(name.toLowerCase(Locale.ROOT));
    }

    /** 生成默认配置文件：空名单，让服主有一份可以照着填的样子。 */
    private static void writeDefault(Path file) {
        JsonObject json = new JsonObject();
        json.add(KEY, new JsonArray());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, json + "\n", StandardCharsets.UTF_8);
            LOG.info("{} 服务端配置不存在，已生成空的角色账号名单：{}", ModIdentity.NAME, file);
        } catch (IOException exception) {
            // 写不出默认文件只影响服主以后改配置，不影响这次开服，记警告继续。
            LOG.warn("{} 服务端配置写不出来：{}", ModIdentity.NAME, file, exception);
        }
    }
}
