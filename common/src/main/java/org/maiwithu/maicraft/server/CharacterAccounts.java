// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server;

import com.google.gson.GsonBuilder;
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
import java.util.Optional;
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
 * <p>开服时读一次；服主在游戏里或控制台用 /mai 加、撤，改完当场写回同一个文件。文件不存在就生成一份空名单让服主照着填；
 * 读不出来按空名单收场并记警告，不让坏配置挡住开服。只在服务器线程上读写。
 */
public final class CharacterAccounts {

    private static final Logger LOG = LoggerFactory.getLogger(CharacterAccounts.class);

    /** 服务端配置文件名，放在加载器给的配置目录（config）下。 */
    static final String FILE_NAME = "maicraft-server.json";
    /** 名单在配置文件里的键。 */
    static final String KEY = "characterAccounts";
    /** 服务器管理员的权限等级：原版 /op 默认给的就是这一级以上。 */
    private static final int OPERATOR_LEVEL = 2;

    /** 写回的配置文件；离线测试里直接建的名单为 null，不写盘。 */
    private final Path file;
    /** 名单原样，按写进去的先后：写回文件时照这个顺序。 */
    private final List<String> entries = new ArrayList<>();
    /** 名单里写的玩家名，按小写存：原版玩家名不分大小写。 */
    private final Set<String> names = new HashSet<>();
    private final Set<UUID> ids = new HashSet<>();

    /** 按名单里写的一项项建：像 UUID 的按 UUID 认，其余按玩家名认。不写盘。 */
    CharacterAccounts(List<String> entries) {
        this(null, entries);
    }

    private CharacterAccounts(Path file, List<String> entries) {
        this.file = file;
        for (String entry : entries) add(entry);
    }

    /** 读配置目录（config）下的服务端配置；文件不在就生成一份空名单。 */
    public static CharacterAccounts read(Path configDirectory) {
        Path file = configDirectory.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            CharacterAccounts empty = new CharacterAccounts(file, List.of());
            if (empty.save()) LOG.info("{} 服务端配置不存在，已生成空的角色账号名单：{}", ModIdentity.NAME, file);
            return empty;
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
            CharacterAccounts accounts = new CharacterAccounts(file, entries);
            LOG.info("{} 角色账号名单读到 {} 个：{}", ModIdentity.NAME, accounts.entries.size(), file);
            return accounts;
        } catch (Exception exception) {
            // 坏配置不挡开服：按空名单收场（只剩主机与管理员能当角色），并把原因记下来让服主能发现。
            // 不写盘：服主之后用 /mai 改名单时才整份重写，免得开服就把他写坏的文件盖掉。
            LOG.warn("{} 服务端配置读不出来，角色账号名单按空的算：{}", ModIdentity.NAME, file, exception);
            return new CharacterAccounts(file, List.of());
        }
    }

    /** 这个玩家能不能用服务端链路：名单里有他，或者他是单人与局域网的主机、服务器管理员。 */
    public boolean admits(ServerPlayer player) {
        GameProfile profile = player.getGameProfile();
        return admits(profile.getName(), profile.getId(), host(player), operator(player));
    }

    /** 判断本身：名单按 UUID 或玩家名（不分大小写）对上，或者是主机、管理员。 */
    boolean admits(String name, UUID id, boolean host, boolean operator) {
        if (host || operator) return true;
        return listed(name, id);
    }

    /** 名单里写着这个号（按 UUID 或玩家名对上）；主机与管理员不算，他们不在名单里也能用。 */
    public boolean listed(String name, UUID id) {
        if (id != null && ids.contains(id)) return true;
        return name != null && names.contains(name.toLowerCase(Locale.ROOT));
    }

    /** 这个玩家是单人游戏或局域网的主机。 */
    public static boolean host(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        return server != null && server.isSingleplayerOwner(player.getGameProfile());
    }

    /** 这个玩家是服务器管理员（权限 2 级及以上）。 */
    public static boolean operator(ServerPlayer player) {
        return player.hasPermissions(OPERATOR_LEVEL);
    }

    /**
     * 往名单里加一项（玩家名或 UUID）；已经有了（同一个 UUID，或同名不分大小写）就不重复加，返回 false。
     * 只改内存里的名单，写盘调 {@link #save()}。
     */
    public boolean add(String entry) {
        String value = entry == null ? "" : entry.trim();
        if (value.isEmpty()) return false;
        Optional<UUID> id = uuid(value);
        boolean added = id.isPresent() ? ids.add(id.get()) : names.add(value.toLowerCase(Locale.ROOT));
        if (added) entries.add(value);
        return added;
    }

    /**
     * 从名单里撤掉这个号：按玩家名（不分大小写）或 UUID 对上的项都撤掉，返回撤掉的那几项；名单里没有给空列表。
     *
     * @param name 玩家名或 UUID 写法，命令里敲的那个
     * @param id   这个号的 UUID（在线或服务器认得时给），名单里按 UUID 写的那一项一并撤；不知道传 null
     */
    public List<String> remove(String name, UUID id) {
        String typed = name == null ? "" : name.trim();
        Optional<UUID> typedId = uuid(typed);
        List<String> removed = new ArrayList<>();
        for (String entry : List.copyOf(entries)) {
            Optional<UUID> entryId = uuid(entry);
            boolean hit = entryId.isPresent()
                    ? entryId.equals(typedId) || entryId.get().equals(id)
                    : entry.equalsIgnoreCase(typed);
            if (!hit) continue;
            entries.remove(entry);
            if (entryId.isPresent()) ids.remove(entryId.get());
            else names.remove(entry.toLowerCase(Locale.ROOT));
            removed.add(entry);
        }
        return removed;
    }

    /** 名单原样，按写进去的先后。 */
    public List<String> entries() {
        return List.copyOf(entries);
    }

    /** 把名单整份写回服务端配置；写成了返回 true。离线测试里建的名单没有文件，直接算写成。 */
    public boolean save() {
        if (file == null) return true;
        JsonObject json = new JsonObject();
        JsonArray list = new JsonArray();
        entries.forEach(list::add);
        json.add(KEY, list);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(json) + "\n", StandardCharsets.UTF_8);
            return true;
        } catch (IOException exception) {
            LOG.warn("{} 角色账号名单写不回服务端配置：{}", ModIdentity.NAME, file, exception);
            return false;
        }
    }

    /** 名单里写的是不是 UUID：是就给出来，玩家名给空。 */
    static Optional<UUID> uuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException notUuid) {
            return Optional.empty();
        }
    }
}
