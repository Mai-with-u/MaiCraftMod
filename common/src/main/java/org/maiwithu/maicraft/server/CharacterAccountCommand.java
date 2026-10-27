// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;

/**
 * 角色账号命令：服主在游戏里或服务器控制台用 /mai 管角色账号名单，不用改配置文件、不用重启服务器。
 *
 * <pre>
 * /mai &lt;玩家&gt;          把这个号加进名单
 * /mai remove &lt;玩家&gt;   撤掉
 * /mai list            看名单
 * </pre>
 *
 * <p>要 3 级权限，和原版 /whitelist 一样。改完当场写回服务端配置；这个玩家在线就当场生效：
 * 加进来的客户端自动重新握手，撤掉的服务端链路当场断开。认得出这个号的 UUID（在线或服务器见过）就记 UUID，改名也认；
 * 认不出按名字记。
 * 角色自己发不了 /mai：聊天能力把它和 /maicraft 一起留给人。叫 remove、list 的玩家要按 UUID 加。
 */
public final class CharacterAccountCommand {

    /** 命令名。 */
    public static final String ROOT = "mai";
    /** 要几级权限：和原版 /whitelist 一样，是服主管服务器的那一级。 */
    static final int PERMISSION_LEVEL = 3;

    private CharacterAccountCommand() {}

    /**
     * 登记 /mai。
     *
     * @param accounts         角色账号名单
     * @param admissionChanged 名单改了以后，按这个在线玩家此刻准不准入处理他的连接（通知重新握手或断开）
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CharacterAccounts accounts,
                                Consumer<ServerPlayer> admissionChanged) {
        dispatcher.register(Commands.literal(ROOT)
                .requires(source -> source.hasPermission(PERMISSION_LEVEL))
                .then(Commands.literal("list").executes(context -> list(context.getSource(), accounts)))
                .then(Commands.literal("remove")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        listedNames(context.getSource().getServer(), accounts), builder))
                                .executes(context -> remove(context, accounts, admissionChanged))))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                context.getSource().getServer().getPlayerNames(), builder))
                        .executes(context -> add(context, accounts, admissionChanged))));
    }

    // 加：认得出 UUID 就记 UUID，认不出按名字记；已经在名单里就说一声，不重复加。写回配置后，在线的当场重新握手。
    private static int add(CommandContext<CommandSourceStack> context, CharacterAccounts accounts,
                           Consumer<ServerPlayer> admissionChanged) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "player");
        MinecraftServer server = source.getServer();
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        UUID id = online != null ? online.getUUID()
                : CharacterAccounts.uuid(name).or(() -> knownId(server, name)).orElse(null);
        String shown = online != null ? online.getGameProfile().getName() : name;
        if (accounts.listed(shown, id)) {
            source.sendSuccess(() -> Component.literal(shown + " 已经在角色账号名单里了"), false);
            return 0;
        }
        accounts.add(id != null ? id.toString() : name);
        boolean saved = accounts.save();
        if (online != null) admissionChanged.accept(online);
        String effect = online != null ? "，他在线，装着 MaiCraft 的客户端会当场重新握手" : "，他下次进服就能用 MaiCraft 服务端链路";
        source.sendSuccess(() -> Component.literal("已把 " + shown + " 加进角色账号名单" + effect), true);
        if (!saved) source.sendFailure(Component.literal("名单改了，但写不回 config/maicraft-server.json，重启服务器后会丢；看服务器日志"));
        return 1;
    }

    // 撤：名单里按名字或 UUID 对上的都撤掉；写回配置后，在线的当场断开服务端链路（他是管理员或主机的照样能用，说一声）。
    private static int remove(CommandContext<CommandSourceStack> context, CharacterAccounts accounts,
                              Consumer<ServerPlayer> admissionChanged) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "player");
        MinecraftServer server = source.getServer();
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        UUID id = online != null ? online.getUUID()
                : CharacterAccounts.uuid(name).or(() -> knownId(server, name)).orElse(null);
        List<String> removed = accounts.remove(name, id);
        if (removed.isEmpty()) {
            source.sendFailure(Component.literal("角色账号名单里没有 " + name));
            return 0;
        }
        boolean saved = accounts.save();
        String effect = "";
        if (online != null) {
            admissionChanged.accept(online);
            effect = CharacterAccounts.host(online) || CharacterAccounts.operator(online)
                    ? "；不过他是服务器管理员（或主机），名单外也能用" : "，他的 MaiCraft 服务端链路当场断开";
        }
        String told = effect;
        source.sendSuccess(() -> Component.literal("已把 " + name + " 撤出角色账号名单" + told), true);
        if (!saved) source.sendFailure(Component.literal("名单改了，但写不回 config/maicraft-server.json，重启服务器后会丢；看服务器日志"));
        return removed.size();
    }

    // 看名单：按 UUID 记的认得出名字就写名字；服务器管理员与主机不用写也能用，顺带说一句。
    private static int list(CommandSourceStack source, CharacterAccounts accounts) {
        List<String> shown = listedNames(source.getServer(), accounts);
        String body = shown.isEmpty() ? "角色账号名单是空的" : "角色账号名单（" + shown.size() + " 个）：" + String.join("、", shown);
        source.sendSuccess(() -> Component.literal(body + "；服务器管理员和单人、局域网的主机不用写也能用"), false);
        return shown.size();
    }

    // 名单里每一项给人看的写法：按 UUID 记的，在线或服务器见过就写名字，认不出就写 UUID。
    private static List<String> listedNames(MinecraftServer server, CharacterAccounts accounts) {
        List<String> names = new ArrayList<>();
        for (String entry : accounts.entries()) {
            Optional<UUID> id = CharacterAccounts.uuid(entry);
            names.add(id.flatMap(uuid -> knownName(server, uuid)).orElse(entry));
        }
        return names;
    }

    private static Optional<String> knownName(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) return Optional.of(online.getGameProfile().getName());
        GameProfileCache profiles = server.getProfileCache();
        return profiles == null ? Optional.empty() : profiles.get(id).map(GameProfile::getName);
    }

    private static Optional<UUID> knownId(MinecraftServer server, String name) {
        GameProfileCache profiles = server.getProfileCache();
        return profiles == null ? Optional.empty() : profiles.get(name).map(GameProfile::getId);
    }
}
