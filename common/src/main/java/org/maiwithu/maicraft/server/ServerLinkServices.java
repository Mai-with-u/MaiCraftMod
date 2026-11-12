// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;

/**
 * 按服务器实例登记服务端服务的登记表。Mixin 注入的钩子没有任何构造路径能把服务传进来，
 * 只能到这里按当前服务器取；启动在服务器的第一个刻结束时（或更早收到客户端握手时）按需创建并登记，停服时移除，
 * 因此每个服务器实例（包括单人游戏的每次开局）都有自己的归属记录与世界编号，不跨服残留。
 */
public final class ServerLinkServices {
    /**
     * 一台服务器的服务：方块归属记录、交互确认通道、这个存档的世界编号（写不进编号文件时为 null）、
     * 世界生成往地上铺的实心方块（读不出来时为 null），以及角色账号名单（未挂入时为 null）。
     */
    private record Services(BlockOwnershipRecord ownership, ServerConfirmations confirmations, String worldId,
                            List<String> generatedTerrain, CharacterAccounts accounts) {}

    private static final Map<MinecraftServer, Services> ATTACHED =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private ServerLinkServices() {}

    /** 尚未登记时用给定的工厂创建并登记（不挂角色账号名单的测试重载）；同一服务器重复调用不重建。 */
    public static void attachIfAbsent(MinecraftServer server,
                                      Supplier<BlockOwnershipRecord> ownership,
                                      Supplier<ServerConfirmations> confirmations,
                                      Supplier<String> worldId,
                                      Supplier<List<String>> generatedTerrain) {
        attachIfAbsent(server, ownership, confirmations, worldId, generatedTerrain, () -> null);
    }

    /** 尚未登记时用给定的工厂创建并登记；同一服务器重复调用不重建。 */
    public static void attachIfAbsent(MinecraftServer server,
                                      Supplier<BlockOwnershipRecord> ownership,
                                      Supplier<ServerConfirmations> confirmations,
                                      Supplier<String> worldId,
                                      Supplier<List<String>> generatedTerrain,
                                      Supplier<CharacterAccounts> accounts) {
        ATTACHED.computeIfAbsent(server, ignored -> new Services(ownership.get(), confirmations.get(), worldId.get(),
                generatedTerrain.get(), accounts.get()));
    }

    public static void detach(MinecraftServer server) {
        ATTACHED.remove(server);
    }

    /** 该服务器的方块归属记录；尚未登记（第一个刻之前）返回 null，钩子直接放过。 */
    public static BlockOwnershipRecord ownership(MinecraftServer server) {
        var services = ATTACHED.get(server);
        return services == null ? null : services.ownership();
    }

    /** 该服务器的交互确认通道；尚未登记时返回 null。 */
    public static ServerConfirmations confirmations(MinecraftServer server) {
        var services = ATTACHED.get(server);
        return services == null ? null : services.confirmations();
    }

    /** 该服务器当前存档的世界编号：握手时报给客户端；尚未登记或编号文件写不进去时返回 null。 */
    public static String worldId(MinecraftServer server) {
        var services = ATTACHED.get(server);
        return services == null ? null : services.worldId();
    }

    /** 该服务器的世界生成往地上铺的实心方块 ID：握手时报给客户端；尚未登记或读不出来时返回 null。 */
    public static List<String> generatedTerrain(MinecraftServer server) {
        var services = ATTACHED.get(server);
        return services == null ? null : services.generatedTerrain();
    }

    /** 该服务器的角色账号名单：距离容差钩子按它核对是不是准入的角色；尚未登记时返回 null。 */
    public static CharacterAccounts characterAccounts(MinecraftServer server) {
        var services = ATTACHED.get(server);
        return services == null ? null : services.accounts();
    }
}
