// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.hunger;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.inventory.ItemsToKeep;
import org.maiwithu.maicraft.behavior.interaction.FirstPersonScene;
import org.maiwithu.maicraft.behavior.interaction.SustainedUse;
import org.maiwithu.maicraft.behavior.interaction.UseKeyProjection;
import org.maiwithu.maicraft.behavior.inventory.hands.MovesToMainhand;
import org.maiwithu.maicraft.behavior.survival.SuicideWayOut;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.ReadsFoodValues;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 吃随身食物的动作来源：从背包里挑一件能直接吃的，换到主手后原生按住吃完一口。
 *
 * <p>吃饭的临时任务只管什么时候吃，怎么吃组合进食的原生动作：先像真人一样把食物换到
 * 主手（背包界面的交换），再按住使用键等游戏自己把这一口吃完。挑哪件按 {@link FoodPicker#forHunger}；
 * 身上没有这会儿该吃的东西时给不出动作，由吃饭任务去弄或如实报告"没吃的"。
 */
public final class EatsCarriedFood implements EatSoonTask.FoodMoves {

    private final BackpackView backpack;
    private final ReadsFoodValues foods;
    private final MovesToMainhand toMainhand;
    private final UseKeyProjection projection;
    /** 正要用的东西：趁空当垫一口时不吃它们；伤得重、饿到不能疾跑时照吃。 */
    private final ItemsToKeep keeps;
    /** 拿东西的引擎：进世界、能力清单建好后才有；没有时弄不了吃的。 */
    private final Supplier<Optional<ItemNeeds>> sources;
    /** 问遍各处弄不到吃的时提的那句寻死出路。 */
    private final SuicideWayOut wayOut;

    public EatsCarriedFood(BackpackView backpack, ReadsFoodValues foods,
            MovesToMainhand toMainhand, UseKeyProjection projection, ItemsToKeep keeps) {
        this(backpack, foods, toMainhand, projection, keeps, Optional::empty);
    }

    /** @param sources 拿东西的引擎（进世界后接上）；饿了身上没吃的时经它去弄 */
    public EatsCarriedFood(BackpackView backpack, ReadsFoodValues foods,
            MovesToMainhand toMainhand, UseKeyProjection projection, ItemsToKeep keeps,
            Supplier<Optional<ItemNeeds>> sources) {
        this(backpack, foods, toMainhand, projection, keeps, sources, SuicideWayOut.NONE);
    }

    /** @param wayOut 问遍各处弄不到吃的时提的那句寻死出路 */
    public EatsCarriedFood(BackpackView backpack, ReadsFoodValues foods,
            MovesToMainhand toMainhand, UseKeyProjection projection, ItemsToKeep keeps,
            Supplier<Optional<ItemNeeds>> sources, SuicideWayOut wayOut) {
        this.wayOut = Objects.requireNonNull(wayOut, "wayOut");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.backpack = Objects.requireNonNull(backpack, "backpack");
        this.foods = Objects.requireNonNull(foods, "foods");
        this.toMainhand = Objects.requireNonNull(toMainhand, "toMainhand");
        this.projection = projection;
        this.keeps = Objects.requireNonNull(keeps, "keeps");
    }

    @Override
    public Action eating(HungerNeed.Facts hunger) {
        return pick(hunger).map(food -> (Action) new BiteAction(food)).orElse(null);
    }

    @Override
    public boolean carriesBite(HungerNeed.Facts hunger) {
        // 弄吃的路上每刻问一句：只挑不动手，挑得出来就回去吃这一口。
        return pick(hunger).isPresent();
    }

    // 按饥饿处境挑：平时只吃补得刚好的普通食物（没坏效果的生肉也是），饱食度见底在掉血才什么都吃。
    // 预留的、正要用的那几件趁空当垫一口时不算口粮；伤得重、饿到不能疾跑就先顾身体：先吃不留的，没有才动留着的。
    // 伤得重又没普通食物：腐肉、生鸡肉这类只带饥饿效果的先吃，一口口往回血线垫；会中毒的、珍贵的照旧不动。
    private Optional<String> pick(HungerNeed.Facts hunger) {
        if (!hunger.eatsKeptFood()) return choose(FoodPicker.carried(backpack.stacks(), foods, keeps), hunger);
        return FoodPicker.keptLast(backpack.stacks(), foods, keeps, carried -> choose(carried, hunger));
    }

    private static Optional<String> choose(List<FoodPicker.Carried> carried, HungerNeed.Facts hunger) {
        Optional<String> pick = FoodPicker.forHunger(carried, hunger.food(), hunger.losingHealth());
        if (pick.isEmpty() && hunger.eatsToHeal() && hunger.carryingJunk()) pick = FoodPicker.autoPick(carried);
        return pick;
    }

    @Override
    public Action fetching(long budgetTicks) {
        // 饿了身上没吃的：经拿东西的引擎有预算地弄几件现成口粮（网络、箱子、做、打猎），弄到第一件能吃的吃饭任务就叫停先吃；
        // 引擎还没接上给 null，任务如实说。
        return sources.get().map(needs -> (Action) new FetchesFood(needs, budgetTicks)).orElse(null);
    }

    @Override
    public SuicideWayOut suicideWayOut() {
        return wayOut;
    }

    @Override
    public Action fetching(long budgetTicks, HungerNeed.Facts hunger, StarvingTake.Told told) {
        // 饿到危险线：这一次弄吃的带上饿急通报，从不是自己放的箱子里拿到现成口粮时告诉宿主；没到线照平常的路拿。
        return sources.get().map(needs -> (Action) new FetchesFood(needs, budgetTicks,
                StarvingTake.when(hunger, foods, told))).orElse(null);
    }

    /** 吃一口的动作：换到主手 → 按住使用键；食物已经在主手就直接按住。 */
    private final class BiteAction implements Action {

        private final String food;
        /** 换手与按住两个子动作，先后接续；正在推进的那一个非空。 */
        private Action moving;
        private SustainedUse biting;
        private ItemStack heldBefore;
        /** 这一口已经换过一次手：换完主手还不是它就不反复赌，如实失败。 */
        private boolean movedOnce;
        private Problem failure;

        BiteAction(String food) {
            this.food = food;
        }

        @Override
        public ActionStatus tick(TickContext context) {
            if (failure != null) return ActionStatus.failed(failure);
            if (moving != null) {
                return advanceMove(context);
            }
            if (biting == null) {
                return startBite(context);
            }
            return biting.tick(context);
        }

        // 换手推进：做成了接着按住；给不出换手动作时看主手是不是已经是它。
        private ActionStatus advanceMove(TickContext context) {
            ActionStatus status = moving.tick(context);
            if (status instanceof ActionStatus.Running) return status;
            if (status instanceof ActionStatus.Failed failed) {
                failure = failed.problem();
                return status;
            }
            moving = null;
            return startBite(context);
        }

        private ActionStatus startBite(TickContext context) {
            PlayerContext player = context.player();
            if (player == null || player.localPlayer() == null) {
                return ActionStatus.failed(Problem.of(Problem.Kind.WRONG_TIME,
                        "这一刻掌握不到角色，吃不了一口", null));
            }
            ItemStack held = player.localPlayer().getMainHandItem();
            String heldId = held.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
            // 主手已经是这件食物就直接按住；换过一次手还不是它就不再反复赌，如实失败。
            if (!heldId.equals(food)) {
                if (movedOnce) return failAgain();
                Optional<Action> move = toMainhand.moveToMainhand(food);
                if (move.isEmpty()) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM,
                            "身上没有能直接吃的 " + food + "，吃不了一口", null));
                }
                movedOnce = true;
                moving = move.get();
                return ActionStatus.running();
            }
            heldBefore = held.copy();
            biting = new SustainedUse(InteractionHand.MAIN_HAND,
                    InteractionConfirmation.heldItemChanged(InteractionHand.MAIN_HAND, heldBefore),
                    projection, holdTicks(), FirstPersonScene::of);
            return ActionStatus.running();
        }

        // 按食物组件的进食秒数加两秒余量定按住上限；读不到组件时保底三秒，不传无上限让角色无限按住空转。
        private int holdTicks() {
            int eatTicks = foods.of(food)
                    .map(value -> (int) Math.ceil(value.eatSeconds() * 20) + 40)
                    .orElse(60);
            return Math.max(60, eatTicks);
        }

        // 被自卫等更急的临时任务打断：把换手或按住一并暂停，交还按住投影，恢复后再按现场续吃或重挑。
        @Override
        public void pause() {
            if (moving != null) moving.pause();
            if (biting != null) {
                if (projection != null) projection.release(biting);
                biting.pause();
            }
        }

        // 任务收尾：子动作一并收尾，交还投影并松开使用键，不留悬挂确认。
        @Override
        public void close() {
            if (moving != null) moving.close();
            if (biting != null) biting.close();
        }

        // 换手做成了主手却还不是它：可能被别的流程动了手，这一口如实失败，由吃饭任务决定要不要再试。
        private ActionStatus failAgain() {
            failure = Problem.of(Problem.Kind.STUCK,
                    "把 " + food + " 换到主手后主手却不是它，吃不了一口", null);
            return ActionStatus.failed(failure);
        }

        @Override
        public String describe() {
            return "吃一件随身食物";
        }
    }
}
