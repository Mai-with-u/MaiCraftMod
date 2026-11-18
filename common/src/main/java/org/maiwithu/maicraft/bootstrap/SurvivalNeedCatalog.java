// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.combat.LiveBattleMoves;
import org.maiwithu.maicraft.behavior.inventory.ItemsToKeep;
import org.maiwithu.maicraft.behavior.landing.CatchesFall;
import org.maiwithu.maicraft.behavior.landing.spi.LandingMaterials;
import org.maiwithu.maicraft.behavior.lighting.FollowLighting;
import org.maiwithu.maicraft.behavior.lighting.LiveTorchMoves;
import org.maiwithu.maicraft.behavior.navigation.baritone.BaritoneInternals;
import org.maiwithu.maicraft.behavior.survival.SurvivalSituation;
import org.maiwithu.maicraft.behavior.survival.breath.BreathNeed;
import org.maiwithu.maicraft.behavior.survival.breath.LiveAirRoutes;
import org.maiwithu.maicraft.behavior.survival.breath.LiveCeilingDigs;
import org.maiwithu.maicraft.behavior.survival.burning.BurningNeed;
import org.maiwithu.maicraft.behavior.survival.burning.LiveBurningMoves;
import org.maiwithu.maicraft.behavior.survival.burning.LiveBurningView;
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
import org.maiwithu.maicraft.behavior.survival.DarknessNeed;
import org.maiwithu.maicraft.behavior.survival.LiveDarkView;
import org.maiwithu.maicraft.behavior.survival.LiveNightAndEdgeMoves;
import org.maiwithu.maicraft.behavior.perception.bodylanguage.LookBackNeed;
import org.maiwithu.maicraft.game.ClientHooks;
import org.maiwithu.maicraft.game.menu.LeftoverScreens;
import org.maiwithu.maicraft.game.player.PlayerContext;
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
     * 出手、自卫与处境读端按组传入（{@link SurvivalHands}、{@link SurvivalBattle}、{@link SurvivalReads}），
     * 吃随身食物的搬运、落地防护与各项只会用一次的接缝单独传。
     * 夜晚的"今晚有没有床"与夜间休息由睡觉能力进世界时接上桥，接上前按弄不到床对待。
     * 太暗只在两个动作之间插（LATER）：走到暗处、包里有火把，就在眼前顺手插一支火把。
     * 泡在岩浆里没岸可上时垫一块落脚，按主目标许可的方块档位（mainBlockChanges）定垫不垫，和出行垫临时方块同一份许可。
     */
    static ControlLoop controlLoop(
            TaskEventSink events, DeathDecisionHost deathDecisions,
            SurvivalHands hands, SurvivalBattle battle, SurvivalReads reads,
            EatSoonTask.FoodMoves foodMoves,
            Function<PlayerContext, CatchesFall> fallRescues,
            Supplier<List<LandingMaterials>> landingMaterials,
            ItemsToKeep keeps, SurvivalWiring survivalWiring, FollowLighting followLighting,
            LookBackNeed lookBack) {
        // 角色死亡这类循环自身的处境变化也从同一条事件流出去；死亡决策从挂载口交给目标运行表。
        ControlLoop loop = new ControlLoop(List.of(
                // 被埋挖开头顶：和换气挖顶、挖格同一份原生挖掘，工具照挑工具换。
                new DigOutNeed(new SurvivalSituation.FromPlayer(), hands.digging()),
                new BreathNeed(new SurvivalSituation.FromPlayer(),
                        new LiveCeilingDigs(hands.digging(), reads.protection()), new LiveAirRoutes()),
                // 走路里的落地救援、喷气背包飞的那一段、寻路"飞上去"那一步自己管着上下时，落地防护让开。
                // 喷气背包飞的那一段只有还在隔三差五说"我管着"才算在管：被打断、出岔子没收尾时让落地防护兜底。
                new FallNeed(new SurvivalSituation.FromPlayer(), fallRescues, landingMaterials,
                        () -> BaritoneInternals.landingInProgress()
                                || JetpackFlightFreshness.stillControlling(
                                        ClientHooks.flightControlledAt(), System.nanoTime())
                                || BaritoneInternals.flyingUp()),
                // 岩浆、火排在被攻击前面：岩浆比怪烧得快，两项都立刻时先出岩浆。
                new BurningNeed(new LiveBurningView(),
                        new LiveBurningMoves(hands.interactions(), hands.hands(), keeps, reads.mainBlockChanges()), events),
                // 自家人打角色只发事件、不停工；别的玩家打角色退开举盾、停下等 LLM 决定。
                // 打完把这一仗掉出来的东西和射出去的箭捡回来（用回去捡尸体的同一套）；撤离往记过的"家"那一侧跑。
                new SelfDefenseNeed(battle.senses(),
                        new LiveBattleMoves(battle.walks(), hands.interactions().heldProjection(), battle.ranged(), keeps),
                        events, battle.trusted()).pickingUp(battle.lostDrops()).knowingHome(battle.places()),
                new HungerNeed(new LiveHungerView(reads.foods(), keeps), foodMoves, events),
                new DarknessNeed(new LiveDarkView(reads.lightLevels(), keeps),
                        new LiveTorchMoves(hands.interactions(), hands.hands(), reads.protection(), Set.of()), followLighting),
                new NightfallNeed(new LiveNightView(battle.senses()),
                        survivalWiring, survivalWiring, survivalWiring, survivalWiring, survivalWiring.autoSleep()),
                new EdgeProximityNeed(new LiveEdgeView(),
                        LiveNightAndEdgeMoves.retreat(battle.walks())),
                // 刚重生、身上的东西撒在死的地方：五分钟内跑回去捡。
                new LostItemsNeed(LiveNightAndEdgeMoves.players(),
                        LiveNightAndEdgeMoves.lostItems(battle.walks(), battle.lostDrops())),
                // 被别的玩家拍了一下回头看他几秒：登记在最后，同样尽快的生存需求在跑时不插。
                lookBack), events, deathDecisions);
        // 换一件事做之前先关掉上一件留下的界面（工作台、过滤器），再让新的那件动手。
        loop.onTaskSwitch(context -> LeftoverScreens.close(context.player()));
        return loop;
    }
}
