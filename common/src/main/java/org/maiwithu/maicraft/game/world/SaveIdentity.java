// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.maiwithu.maicraft.game.serverlink.ServerCapabilityState;

/**
 * 当前进度属于哪个存档或服务器：优先认存档自己的世界编号（服务端 MaiCraft 握手时报来，单人游戏的内置服务器也一样），
 * 删了存档用同名文件夹重建、复制出来的存档都是新编号；服务器没报编号时才退回按存档目录或服务器地址算一串编号。
 * 编号里不直接写出地址或路径。还没进世界、或者握手还没回话时不凭外部传来的文字猜，返回空。
 *
 * @param key       世界编号：64 位十六进制，世界记忆按它存，宿主按它分开不同世界里记下的事
 * @param source    编号从哪来
 * @param name      给人看的名字：单人是存档名，多人是服务器列表里的名字；不当键用，读不到时为空串
 * @param legacyKey 旧版本按存档目录或服务器地址算出的那串编号，搬旧数据时按它找；认不出时为 null
 * @param directory 本游戏实例存放 MaiCraft 进度数据的目录
 * @param onlineMode 这个世界所在的服务器开没开正版验证，握手时服务端报的；服务端版本太旧、没报时为 null。
 *                   关着时玩家 UUID 由名字算出，改名就成了另一个人，宿主据此决定按 UUID 认人能信到几分
 */
public record SaveIdentity(String key, Source source, String name, String legacyKey, Path directory,
                           Boolean onlineMode) {

    /** 世界编号从哪来。 */
    public enum Source {
        /** 存档自己的世界编号文件，握手时服务器报来：同一个存档重进不变，同名重建、复制出来的都是新编号。 */
        WORLD_ID_FILE,
        /** 单人存档没拿到世界编号（编号文件写不进去）：按存档目录算，删档后同名重建会认成同一个。 */
        SAVE_FOLDER,
        /** 服务器没报世界编号（服务端 MaiCraft 版本太旧）：按服务器地址算，同一地址换了地图也认成同一个。 */
        SERVER_ADDRESS
    }

    /**
     * 从当前游戏状态与服务端握手认出世界。握手回了欢迎包才认：编号要等服务器报，先认成按地址算的、过一会儿又换成服务器报的，
     * 宿主会把同一个世界当成两个。
     *
     * @param server 与服务端 MaiCraft 的握手现状
     */
    public static Optional<SaveIdentity> current(Minecraft minecraft, ServerCapabilityState server) {
        if (minecraft == null || minecraft.player == null || minecraft.level == null) {
            return Optional.empty();
        }
        if (server == null || server.scope() == null) return Optional.empty();
        MinecraftServer local = minecraft.getSingleplayerServer();
        String raw = local == null ? null : singleplayerIdentity(local);
        boolean singleplayer = raw != null;
        if (raw == null) raw = multiplayerIdentity(minecraft);
        String legacy = raw == null ? null : sha256(raw);
        String name = local != null ? levelName(local) : serverName(minecraft);
        // 进度数据放在 config/maicraft/state 里，与外部工具放在 config 根下的数据文件互不干扰。
        Path directory = minecraft.gameDirectory.toPath().resolve("config").resolve("maicraft").resolve("state");
        String worldId = server.worldId();
        // 正版验证跟世界编号一样取自这次握手：进世界时认一次，这一趟里不再变。
        Boolean onlineMode = server.onlineMode();
        if (worldId != null) {
            return Optional.of(new SaveIdentity(worldId, Source.WORLD_ID_FILE, name, legacy, directory, onlineMode));
        }
        if (legacy == null) return Optional.empty();
        return Optional.of(new SaveIdentity(legacy, singleplayer ? Source.SAVE_FOLDER : Source.SERVER_ADDRESS,
                name, legacy, directory, onlineMode));
    }

    /** 单人世界按存档目录区分；Windows 路径不区分大小写，先统一成小写再算，同一存档才算同一个世界。 */
    private static String singleplayerIdentity(MinecraftServer server) {
        try {
            Path root = server.getWorldPath(LevelResource.ROOT)
                    .toAbsolutePath().normalize();
            String stable = root.toString();
            if (isWindows()) stable = stable.toLowerCase(Locale.ROOT);
            return "singleplayer\n" + stable;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    /** 多人世界按服务器地址区分；同一地址上换地图时仍算同一个身份，地址里也不含玩家账号。 */
    private static String multiplayerIdentity(Minecraft minecraft) {
        ServerData server = minecraft.getCurrentServer();
        if (server == null || server.ip == null || server.ip.isBlank()) return null;
        return "multiplayer\n" + server.ip.strip().toLowerCase(Locale.ROOT);
    }

    /** 单人存档的显示名（存档列表里那一行）；读不到时为空串。 */
    private static String levelName(MinecraftServer server) {
        try {
            String name = server.getWorldData().getLevelName();
            return name == null ? "" : name;
        } catch (RuntimeException unavailable) {
            return "";
        }
    }

    /** 多人服务器在服务器列表里的名字；直连或读不到时为空串。名字是玩家自己起的，不是地址。 */
    private static String serverName(Minecraft minecraft) {
        ServerData server = minecraft.getCurrentServer();
        return server == null || server.name == null ? "" : server.name;
    }

    /** 把路径或地址变成固定长度编号；这只用于区分文件，不代表服务器内容没有变化。 */
    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
