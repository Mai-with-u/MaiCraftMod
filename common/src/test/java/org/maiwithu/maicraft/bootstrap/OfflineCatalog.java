// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.behavior.acquire.mining.Campground;
import org.maiwithu.maicraft.behavior.approach.UnreachableFromHere;
import org.maiwithu.maicraft.behavior.combat.CombatSenses;
import org.maiwithu.maicraft.behavior.combat.ModRangedWeapons;
import org.maiwithu.maicraft.behavior.combat.SparPartner;
import org.maiwithu.maicraft.behavior.inventory.drops.ClientDropPickup;
import org.maiwithu.maicraft.behavior.inventory.hands.ClientMovesToMainhand;
import org.maiwithu.maicraft.behavior.inventory.hands.LiveDigToolRule;
import org.maiwithu.maicraft.behavior.landing.FallRescues;
import org.maiwithu.maicraft.behavior.perception.bodylanguage.LookBackNeed;
import org.maiwithu.maicraft.behavior.perception.bodylanguage.PokesFelt;
import org.maiwithu.maicraft.behavior.survival.SuicideWayOut;
import org.maiwithu.maicraft.behavior.survival.hunger.EatsCarriedFood;
import org.maiwithu.maicraft.game.interaction.DigToolRule;
import org.maiwithu.maicraft.behavior.lighting.FollowLighting;
import org.maiwithu.maicraft.behavior.lighting.LightingWorld;
import org.maiwithu.maicraft.behavior.perception.ClientCampPitSight;
import org.maiwithu.maicraft.behavior.perception.bodylanguage.NonverbalExchange;
import org.maiwithu.maicraft.behavior.permission.OthersThingsNotice;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.TrustedPlayers;
import org.maiwithu.maicraft.behavior.inventory.containers.RareLootNotice;
import org.maiwithu.maicraft.behavior.acquire.engine.ItemsInUse;
import org.maiwithu.maicraft.behavior.inventory.ItemsToKeep;
import org.maiwithu.maicraft.behavior.inventory.ReservedItems;
import org.maiwithu.maicraft.game.world.DropConfirmations;
import org.maiwithu.maicraft.game.world.GeneratedTerrain;
import org.maiwithu.maicraft.behavior.acquire.engine.LiveCarryReads;
import org.maiwithu.maicraft.behavior.construction.ShowsPreview;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.interaction.UseKeyProjection;
import org.maiwithu.maicraft.behavior.navigation.baritone.BaritoneInternals;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.behavior.combat.CombatMemory;
import org.maiwithu.maicraft.behavior.combat.LiveCombatSenses;
import org.maiwithu.maicraft.behavior.travel.planning.ClientTravelWorldView;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.ChatChannel;
import org.maiwithu.maicraft.game.ChatLog;
import org.maiwithu.maicraft.game.ClientHooks;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.player.InputDriver;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerControlBoundary;
import org.maiwithu.maicraft.game.player.ReadsFoodValues;
import org.maiwithu.maicraft.game.serverlink.LinkTransport;
import org.maiwithu.maicraft.game.serverlink.ServerLinkSession;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.game.world.KeepInventoryRule;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.event.ChatEventLog;
import org.maiwithu.maicraft.compat.CompatRegistry;
import org.maiwithu.maicraft.behavior.perception.FacilityKinds;
import org.maiwithu.maicraft.behavior.perception.FakeBlockTags;
import org.maiwithu.maicraft.ability.timer.Timers;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.interrupt.ControlLoop;
import org.maiwithu.maicraft.kernel.interrupt.DeathDecisionHost;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import org.maiwithu.maicraft.game.world.ReadsLightLevels;
import java.util.function.Supplier;

/**
 * 离线拼一份能力清单的依赖：全部用离线可构造的实例，不碰任何真实游戏对象。
 * 总装测试拿它验证装得上、推得动；接口快照拿它列出生产清单里的全部能力。
 */
final class OfflineCatalog {

    /** 测试专用的世界身份编号：任意一个合法的 SHA-256 形状。 */
    static final String WORLD_KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    /** 一条空的发送通道：离线不连服务器，会话构造需要它存在而已。 */
    private static final LinkTransport NO_TRANSPORT = new LinkTransport() {
        @Override public boolean available() {
            return false;
        }

        @Override public void send(JsonObject envelope) {
            throw new IllegalStateException("离线总装不发信封");
        }
    };

    /** 按住使用键投影的替身：进食的续期一律不续，任务按"按住到期"如实收场。 */
    private static final UseKeyProjection NO_HOLD = new UseKeyProjection() {
        @Override public boolean renew(Object owner, PlayerContext context, PendingInteraction pending,
                InteractionHand hand, ItemStack before) {
            return false;
        }

        @Override public void release(Object owner) {
        }
    };

    /** 离线角色的玩家编号。 */
    private static final String SELF_ID = "00000000-0000-0000-0000-000000000000";

    /** 只读亮度替身：永远"读不到"。能力清单照常登记，真量光在实机验收。 */
    private static final ReadsLightLevels NO_LIGHT = new ReadsLightLevels() {
        @Override public OptionalInt blockLight(BlockPos pos) { return OptionalInt.empty(); }
        @Override public OptionalInt skyLight(BlockPos pos) { return OptionalInt.empty(); }
    };

    private OfflineCatalog() {}

    /** 照明现场替身：永远没加载、没有结实支撑，量光与选灯位都会如实回答做不了。 */
    private static final class UnloadedWorld implements LightingWorld {
        @Override public boolean loaded(BlockPos pos) {
            return false;
        }

        @Override public BlockState state(BlockPos pos) {
            return Blocks.AIR.defaultBlockState();
        }

        @Override public boolean sturdy(BlockPos pos, Direction face) {
            return false;
        }
    }

    /** 一份依赖，聊天逐字打字按默认开着：离线替身测的是逐字路径。 */
    static AbilityCatalog.Deps deps(Path tempDir) {
        return deps(tempDir, true);
    }

    /** 一份依赖；世界记忆的库文件放在给定的临时目录里，聊天逐字打字开关由测试摆。 */
    static AbilityCatalog.Deps deps(Path tempDir, boolean chatTypewriter) {
        Supplier<PlayerContext> nobody = () -> null;
        WorldMemory memory = new WorldMemory(new DocumentStore(tempDir.resolve("state.sqlite")), WORLD_KEY);
        ClientHooks.registerChatLog(new ChatLog());
        ServerLinkSession session = new ServerLinkSession(NO_TRANSPORT);
        FacilityKinds facilities = new FacilityKinds(FakeBlockTags.vanilla());
        // 实例配置按测试要摆逐字打字开关，其余照默认：管理员命令不放、没有自家人、方块上限只有默认那一条。
        InstanceConfig config = new InstanceConfig(false, chatTypewriter, List.of(),
                Map.of("create:chain_conveyor", 1));
        // 交互动作入口允许没有按住使用键投影；生存需求共用的挖掘走原生交互，离线给空壳。
        return new AbilityCatalog.Deps(
                new CharacterReads(nobody,
                        PlayerViews.backpack(nobody),
                        LiveCarryReads.offhand(nobody),
                        PlayerViews.hunger(nobody),
                        PlayerViews.foods(nobody),
                        PlayerViews.equipment(nobody),
                        PlayerViews.effects(nobody),
                        PlayerViews.gearFit(nobody),
                        LiveCarryReads.characterPosition(nobody), SuicideWayOut.NONE),
                new ItemReads(LiveCarryReads.itemTags(), LiveCarryReads.itemRegistry(),
                        LiveCarryReads.toolRequirements(nobody), stack -> 0),
                new WorldReads(new Scene(memory, facilities), memory, new BlockScanService(), facilities,
                        NO_LIGHT,
                        new UnloadedWorld(), new KeepInventoryRule(), NonverbalExchange.NONE, GeneratedTerrain.NONE,
                        // 营地地面坑的扫描读端离线给"哪都没有营地"的空壳：填坑能力照常登记。
                        new ClientCampPitSight(Campground.NOWHERE, pos -> false)),
                new LiveCombatSenses(new CombatMemory(), itemId -> Optional.empty()),
                new Hands(new Interactions(null), NO_HOLD, () -> null, new InputDriver(new PlayerControlBoundary()),
                        new LiveDigToolRule(ItemsToKeep.NOTHING, blockId -> true)),
                new Travel(new BaritoneInternals(ItemsToKeep.NOTHING, DigToolRule.HELD),
                        new BaritoneInternals(ItemsToKeep.NOTHING, DigToolRule.HELD), new ClientTravelWorldView(nobody),
                        progress -> { },
                        // 离线没有目标运行表：靠近什么都不记。
                        UnreachableFromHere.none()),
                new Guards(
                        new Protection((dimension, x, y, z) -> Optional.empty(), memory, memory,
                                SELF_ID),
                        (ReadsCreatureSituation) entityId -> Optional.empty(),
                        // 离线没有自家人，动别人东西的通报与稀罕战利品通报都给空实现。
                        TrustedPlayers.NOBODY, OthersThingsNotice.NONE, RareLootNotice.NONE),
                new Channels(new ChatChannel(nobody), () -> 0,
                        // 任务事件离线没人听，能力照常登记、参数照常校验。
                        (kind, message) -> { },
                        // 计时到点的事件同样没人听。
                        new Timers(TaskEventSink.NONE),
                        session,
                        // 聊天栏收不到东西：交互前后的服务端回音读不到。
                        new ChatEventLog()),
                new Stores(new DocumentStore(tempDir.resolve("state.sqlite")), WORLD_KEY,
                        tempDir.resolve("config")),
                CompatRegistry.empty(),
                // 睡觉能力在离线清单里没有登记方，夜晚生存需求按弄不到床对待即可。
                new SurvivalWiring(),
                new FollowLighting(),
                ShowsPreview.NONE,
                new ItemsInUse(LiveCarryReads.itemTags()),
                // 离线清单里没有预留，身上件数也读不到。
                new ReservedItems(LiveCarryReads.itemTags(), Map::of),
                new DropConfirmations(),
                config, new SparPartner());
    }

    /**
     * 离线的控制循环：生存需求按生产走的同一个清单（SurvivalNeedCatalog）启动时创建，替身按"读不到现场"回答。
     * 总装测试拿它核对每项需求的登记顺序没接错、循环推进不依赖真实客户端；
     * 没有主任务、读不到角色时各需求都按不急处理，控制循环只推进主任务。
     */
    static ControlLoop controlLoop(Path tempDir) {
        Supplier<PlayerContext> nobody = () -> null;
        WorldMemory memory = new WorldMemory(new DocumentStore(tempDir.resolve("state.sqlite")), WORLD_KEY);
        // 这个世界的保护判断：换气挖开头顶、插火把挑位置都问它；离线认不出归属，按没有归属回答。
        Protection protection = new Protection((dimension, x, y, z) -> Optional.empty(), memory, memory, SELF_ID);
        // 饿了挑吃的、数口粮都按游戏的食物组件认：读不到当刻角色时各需求按不急处理。
        ReadsFoodValues foods = PlayerViews.foods(nobody);
        // 走到与寻路同一个内件：自卫退开、跑回去捡都从这里走。
        BaritoneInternals walks = new BaritoneInternals(ItemsToKeep.NOTHING, DigToolRule.HELD);
        // 要留的东西照生产走同一道：预留（离线读不到身上件数）加上正要用的。
        ItemsToKeep kept = new ReservedItems(LiveCarryReads.itemTags(), Map::of)
                .plus(new ItemsInUse(LiveCarryReads.itemTags()));
        // 战斗感观与远程武器照生产的形状拼：联动的远程武器离线一份没有。
        ModRangedWeapons ranged = new ModRangedWeapons(List::of, foods);
        CombatSenses senses = new LiveCombatSenses(new CombatMemory(), foods, ranged, () -> protection);
        return SurvivalNeedCatalog.controlLoop(
                // 任务事件离线没人听；角色死亡这类循环自身的处境变化也没有挂载方。
                (kind, message) -> { }, DeathDecisionHost.NONE,
                new SurvivalHands(new Interactions(null), () -> null, new ClientMovesToMainhand(nobody)),
                new SurvivalBattle(senses, walks, ranged,
                        new ClientDropPickup(nobody, walks, new DropConfirmations()),
                        () -> memory, uuid -> false),
                new SurvivalReads(foods, NO_LIGHT, () -> protection,
                        () -> Permissions.DEFAULT.changeBlocks()),
                // 吃随身食物：读不到角色时挑不出吃的，这项需求按不急处理。
                new EatsCarriedFood(PlayerViews.backpack(nobody), foods,
                        new ClientMovesToMainhand(nobody), NO_HOLD, kept),
                FallRescues.withBrakes(List::of), List::of,
                kept, new SurvivalWiring(), new FollowLighting(),
                // 被拍了回头看：离线没有人拍，读端按没人拍过回答。
                new LookBackNeed(PokesFelt.later(() -> null)));
    }
}
