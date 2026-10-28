// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.compat.SupportedMod;
import org.maiwithu.maicraft.game.ModIdentity;
import org.maiwithu.maicraft.game.loader.LoaderEnvironment;
import org.maiwithu.maicraft.network.MaiCraftPayload;
import org.maiwithu.maicraft.server.CharacterAccountCommand;
import org.maiwithu.maicraft.server.CharacterAccounts;
import org.maiwithu.maicraft.server.OwnershipFile;
import org.maiwithu.maicraft.server.OwnershipQuery;
import org.maiwithu.maicraft.server.OwnershipSaver;
import org.maiwithu.maicraft.server.ServerConfirmations;
import org.maiwithu.maicraft.server.ServerLinkNetwork;
import org.maiwithu.maicraft.server.ServerLinkServices;
import org.maiwithu.maicraft.server.ServerOperationRegistry;
import org.maiwithu.maicraft.server.compat.ServerCompatModule;
import org.maiwithu.maicraft.server.compat.ServerCompatRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 启动的服务端一侧（独立服务器与客户端里的内置服务器都会执行）：方块归属记录、交互确认通道、
 * 只读的归属查询与服务端联动读数，服务端链路只对角色账号开放（服主用 /mai 管名单）。
 * 不引用任何仅客户端的类。
 */
final class ServerStartup {
    private static final Logger LOG = LoggerFactory.getLogger(ServerStartup.class);

    /** 方块归属记录有改动时多久存一次盘：五分钟，和原版自动存档的节奏一样。 */
    private static final int OWNERSHIP_SAVE_INTERVAL_TICKS = 20 * 60 * 5;

    private ServerStartup() {}

    /**
     * 建好服务端一侧，返回接加载器服务端事件的接收端：首刻读回归属记录，之后定期存盘，停服再存一次。
     *
     * @param compatCatalog 这个加载器服务端一侧的联动清单；没有联动的加载器传空列表
     */
    static ServerLifecycle start(LoaderEnvironment loader, ServerConfirmations.Push push,
                                 List<SupportedMod<ServerCompatModule>> compatCatalog) {
        LOG.info("{} 通用部分启动（加载器：{}）", ModIdentity.NAME, loader.loaderName());
        ServerOperationRegistry operations = new ServerOperationRegistry();
        // 服务端链路只对角色账号开放：握手时不是角色账号的不给会话；名单改了之后的每个请求也按名单再核一次。
        CharacterAccounts accounts = CharacterAccounts.read(loader.configDirectory());
        operations.setPolicy((player, operation) -> accounts.admits(player));
        ServerLinkNetwork network = new ServerLinkNetwork(operations, accounts::admits);
        // 客户端可查询的只读操作：角色附近一个区块里哪些格是谁放的，格多时分页答。归属记录按服务器实例在第一个刻结束时从存档读回并登记。
        operations.register(OwnershipQuery.OPERATION, OwnershipQuery.VERSION, false, new OwnershipQuery());
        // 服务端一侧的联动清单逐行检查并写日志：通过的联动入口把客户端读不到的模组读数登记成只读操作，握手时协商。
        ServerCompatLoading.load(compatCatalog, loader, new ServerCompatRegistry(operations));
        // 方块归属存盘：主线程只拍快照，压缩写盘在后台，存盘时不卡服。
        OwnershipSaver ownershipSaver = new OwnershipSaver();
        return new ServerLifecycle() {
            @Override public void tickEnd(MinecraftServer server) {
                // 首刻从存档读回本服务器的归属记录并登记确认通道；之后每五分钟把改动存一次盘，停服时再存一次。
                ServerLinkServices.attachIfAbsent(server, () -> OwnershipFile.of(server).load(),
                        () -> new ServerConfirmations(push, network::hasSession));
                if (server.getTickCount() % OWNERSHIP_SAVE_INTERVAL_TICKS == 0) {
                    ownershipSaver.saveLater(server);
                }
            }

            @Override public void stopped(MinecraftServer server) {
                // 停服前存最后一份，并等后台把排着的快照都写完，不丢最后的改动。
                ownershipSaver.saveAndWait(server);
                ServerLinkServices.detach(server);
                network.stopped(server);
                LOG.info("{} 服务端已停止", ModIdentity.NAME);
            }

            @Override public void clientEnvelope(ServerPlayer player, MaiCraftPayload payload,
                                                 Consumer<JsonObject> reply) {
                network.receive(player, payload, reply);
            }

            @Override public void clientDisconnected(ServerPlayer player) {
                network.disconnected(player);
            }

            @Override public void clientWorldChanged(ServerPlayer player) {
                network.worldChanged(player);
            }

            @Override public void playerBrokeBlock(ServerPlayer player, BlockPos position, BlockState state) {
                // 方块被拆了，这一格原来的归属就不再对应任何方块。
                var ownership = ServerLinkServices.ownership(player.serverLevel().getServer());
                if (ownership != null) {
                    ownership.forget(player.serverLevel().dimension().location().toString(), position);
                }
                var confirmations = ServerLinkServices.confirmations(player.serverLevel().getServer());
                if (confirmations != null) confirmations.broke(player, position, state);
            }

            // 服主管角色账号名单的 /mai：改完当场写回配置，在线的玩家当场重新握手或断开服务端链路。
            @Override public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
                CharacterAccountCommand.register(dispatcher, accounts, player -> network.admissionChanged(player, push));
            }
        };
    }
}
