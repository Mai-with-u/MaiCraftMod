// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.maiwithu.maicraft.behavior.combat.CombatSenses;
import org.maiwithu.maicraft.behavior.combat.LiveBattleMoves;
import org.maiwithu.maicraft.behavior.combat.ModRangedWeapons;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.interaction.NativeBlockBreaking;
import org.maiwithu.maicraft.behavior.inventory.ItemsToKeep;
import org.maiwithu.maicraft.behavior.inventory.drops.PicksUpDrops;
import org.maiwithu.maicraft.behavior.inventory.hands.ClientMovesToMainhand;
import org.maiwithu.maicraft.behavior.landing.CatchesFall;
import org.maiwithu.maicraft.behavior.landing.spi.LandingMaterials;
import org.maiwithu.maicraft.behavior.lighting.FollowLighting;
import org.maiwithu.maicraft.behavior.lighting.LiveTorchMoves;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.baritone.BaritoneInternals;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsRememberedPlaces;
import org.maiwithu.maicraft.behavior.survival.DarknessNeed;
import org.maiwithu.maicraft.behavior.survival.LiveDarkView;
import org.maiwithu.maicraft.behavior.survival.LiveNightAndEdgeMoves;
import org.maiwithu.maicraft.behavior.survival.SurvivalSituation;
import org.maiwithu.maicraft.behavior.survival.breath.BreathNeed;
import org.maiwithu.maicraft.behavior.survival.breath.LiveAirRoutes;
import org.maiwithu.maicraft.behavior.survival.breath.LiveCeilingDigs;
import org.maiwithu.maicraft.behavior.survival.defense.SelfDefenseNeed;
import org.maiwithu.maicraft.behavior.survival.digout.DigOutNeed;
import org.maiwithu.maicraft.behavior.survival.fall.EdgeProximityNeed;
import org.maiwithu.maicraft.behavior.survival.fall.FallNeed;
import org.maiwithu.maicraft.behavior.survival.fall.JetpackFlightFreshness;
import org.maiwithu.maicraft.behavior.survival.fall.LiveEdgeView;
import org.maiwithu.maicraft.behavior.survival.hunger.EatSoonTask;
import org.maiwithu.maicraft.behavior.survival.hunger.HungerNeed;
import org.maiwithu.maicraft.behavior.survival.hunger.LiveHungerView;
import org.maiwithu.maicraft.behavior.survival.night.LiveNightView;
import org.maiwithu.maicraft.behavior.survival.night.NightfallNeed;
import org.maiwithu.maicraft.behavior.survival.recover.LostItemsNeed;
import org.maiwithu.maicraft.game.ClientHooks;
import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.menu.LeftoverScreens;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.ReadsFoodValues;
import org.maiwithu.maicraft.game.world.ReadsLightLevels;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.interrupt.ControlLoop;
import org.maiwithu.maicraft.kernel.interrupt.DeathDecisionHost;

/**
 * 生存需求清单：控制循环按急迫程度登记的生存需求，每项需求的读端与动手部件在这里拼好。
 * 新增一项生存需求在清单里加一行，不做类路径扫描。
 */
final class SurvivalNeedCatalog {

    private SurvivalNeedCatalog() {}

    /**
     * 控制循环的生存需求清单：按急迫程度登记，必须立刻处理的先登记，同样急时先插进来。
     * 战斗感观由外面递进来，与战斗能力共用一份；走到已接上，撤离与绕行用真的走路完成；
     * 饿了的临时任务吃随身食物：先换到主手，再原生按住吃完一口；身上没吃的就经拿东西的引擎有预算地弄口粮。
     * 夜晚的"今晚有没有床"与夜间休息由睡觉能力进世界时接上桥，接上前按弄不到床对待。
     * 太暗只在两个动作之间插（LATER）：走到暗处、包里有火把，就在眼前顺手插一支火把。
     */
    static ControlLoop controlLoop(
            TaskEventSink events, DeathDecisionHost deathDecisions,
            InteractionSender interactionSender, MenuActions menuActions, Interactions interactions,
            CombatSenses combatSenses, WalkTo walks,
            EatSoonTask.FoodMoves foodMoves, ReadsFoodValues foods, LiveCeilingDigs ceilings,
            SurvivalWiring survivalWiring, ItemsToKeep keeps, Supplier<Protection> protection,
            FollowLighting followLighting, ReadsLightLevels lightLevels, ClientMovesToMainhand hands,
            ModRangedWeapons ranged, Function<PlayerContext, CatchesFall> fallRescues,
            Supplier<List<LandingMaterials>> landingMaterials, Predicate<UUID> trusted,
            PicksUpDrops lostDrops, Supplier<ReadsRememberedPlaces> places) {
        // 角色死亡这类循环自身的处境变化也从同一条事件流出去；死亡决策从挂载口交给目标运行表。
        ControlLoop loop = new ControlLoop(List.of(
                new DigOutNeed(new SurvivalSituation.FromPlayer(),
                        () -> new NativeBlockBreaking(interactionSender, menuActions)),
                new BreathNeed(new SurvivalSituation.FromPlayer(), ceilings, new LiveAirRoutes()),
                // 走路里的落地救援、喷气背包飞的那一段、寻路"飞上去"那一步自己管着上下时，落地防护让开。
                // 喷气背包飞的那一段只有还在隔三差五说"我管着"才算在管：被打断、出岔子没收尾时让落地防护兜底。
                new FallNeed(new SurvivalSituation.FromPlayer(), fallRescues, landingMaterials,
                        () -> BaritoneInternals.landingInProgress()
                                || JetpackFlightFreshness.stillControlling(
                                        ClientHooks.flightControlledAt(), System.nanoTime())
                                || BaritoneInternals.flyingUp()),
                // 自家人打角色只发事件、不停工；别的玩家打角色退开举盾、停下等 LLM 决定。
                // 打完把这一仗掉出来的东西和射出去的箭捡回来（用回去捡尸体的同一套）；撤离往记过的"家"那一侧跑。
                new SelfDefenseNeed(combatSenses, new LiveBattleMoves(walks, interactions.heldProjection(), ranged), events,
                        trusted).pickingUp(lostDrops).knowingHome(places),
                new HungerNeed(new LiveHungerView(foods, keeps), foodMoves, events),
                new DarknessNeed(new LiveDarkView(lightLevels),
                        new LiveTorchMoves(interactions, hands, protection, Set.of()), followLighting),
                new NightfallNeed(new LiveNightView(combatSenses),
                        survivalWiring, survivalWiring, survivalWiring, survivalWiring, survivalWiring.autoSleep()),
                new EdgeProximityNeed(new LiveEdgeView(),
                        LiveNightAndEdgeMoves.retreat(walks, events)),
                // 刚重生、身上的东西撒在死的地方：五分钟内跑回去捡。
                new LostItemsNeed(LiveNightAndEdgeMoves.players(),
                        LiveNightAndEdgeMoves.lostItems(walks, lostDrops, events), events)), events, deathDecisions);
        // 换一件事做之前先关掉上一件留下的界面（工作台、过滤器），再让新的那件动手。
        loop.onTaskSwitch(context -> LeftoverScreens.close(context.player()));
        return loop;
    }
}
