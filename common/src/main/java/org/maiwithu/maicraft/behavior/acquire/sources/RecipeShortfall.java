// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.behavior.acquire.cook.WorkstationRecipe;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.acquire.engine.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.engine.OffhandContents;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.engine.CarriedItems;

/**
 * 备料弄不来的经过：一样原料弄不来，身上这样原料还够做一份就接着备别的，先把够做的做掉；
 * 连一份的量都没有就当场放弃这个做法——别的原料弄来也做不成，不白跑去弄（没有羊毛就不去砍床板的木头）。
 * 里层为什么弄不来只转述它的结论句，经过由拿东西的引擎收着，最外层讲一次。
 *
 * <p>缺的几样按配方逐项交给拿东西的引擎；每做一样就按身上的重新算，
 * 这一趟还要的其它几样随请求一起带过去，开箱时顺手就能拿到。
 */
public final class RecipeShortfall {

    /** 这一格缺料时去弄哪一样：可以互换的原料由配方来源按身上的与远近挑。 */
    @FunctionalInterface
    interface Picks {
        WantedItem pick(WorkstationRecipe.IngredientStack ingredient, int needed, SourceContext context);
    }

    private final WorkstationRecipe recipe;
    /** 这次动手时的现场：可以互换的原料挑哪一样时按它问代价。 */
    private final SourceContext context;
    private final IngredientChoice choice;
    private final ItemNeeds needs;
    private final BackpackView backpack;
    private final OffhandContents offhand;
    private final ReadsItemTags tags;
    private final Picks picker;
    /** 弄不来的原料：东西×件数。 */
    final List<String> missing = new ArrayList<>();
    /** 里层没弄来的结论句。 */
    final List<String> reasons = new ArrayList<>();
    /** 各句结论原来的问题种类：合并成整场的问题时按它定种类，不把提问压成缺东西。 */
    private final Set<Problem.Kind> reasonKinds = new LinkedHashSet<>();

    RecipeShortfall(WorkstationRecipe recipe, SourceContext context, IngredientChoice choice, ItemNeeds needs,
            BackpackView backpack, OffhandContents offhand, ReadsItemTags tags, Picks picker) {
        this.recipe = recipe;
        this.context = context;
        this.choice = choice;
        this.needs = needs;
        this.backpack = backpack;
        this.offhand = offhand;
        this.tags = tags;
        this.picker = picker;
    }

    // 缺的原料逐项回引擎去弄，身上已有的不再要，用途写清是为谁备的。这一次里已经知道拿不到的那样排最前：
    // 身上一份的量都没有，碰一下就放弃这个做法，不先去弄排在它前面的原料（没有白色羊毛，就不先去采染红色要的花）。
    // 几样都缺时把整张单子交给同一步：一步一步按还缺的来，开箱时顺手把别的也拿了；全都够时给空列表。
    List<Action> ingredients(int times, Permissions permissions) {
        List<Action> steps = new ArrayList<>();
        if (ordered(times).stream().anyMatch(ingredient -> choice.carried(ingredient) < needOf(ingredient, times))) {
            steps.add(new Ingredients(times, permissions));
        }
        return steps;
    }

    // 缺的原料按"这一次里已知拿不到的排最前"排好，其余保持配方里的先后。
    private List<WorkstationRecipe.IngredientStack> ordered(int times) {
        List<WorkstationRecipe.IngredientStack> ordered = new ArrayList<>(recipe.ingredients());
        ordered.sort(Comparator.comparingInt((WorkstationRecipe.IngredientStack ingredient) ->
                ingredient.options().stream().allMatch(needs::knownUnobtainable) ? 0 : 1));
        return ordered;
    }

    private int needOf(WorkstationRecipe.IngredientStack ingredient, int times) {
        return ingredient.count() * times;
    }

    // 一条原料这一刻还缺几件、去弄哪一样：按现场重算，不拿开始备料那一刻的数。
    private Optional<ItemRequest> missingRequest(WorkstationRecipe.IngredientStack ingredient, int times) {
        int needed = needOf(ingredient, times);
        int carried = choice.carried(ingredient);
        if (carried >= needed) return Optional.empty();
        int shortfall = needed - carried;
        return Optional.of(new ItemRequest(picker.pick(ingredient, shortfall, context), shortfall,
                "为「做" + recipe.result().describe() + "」备原料"));
    }

    /**
     * 备料的一整串：按配方逐样把还缺的交给引擎，每样都带着这张单子里其它还缺的（开箱时一起拿）。
     * 每做一样就按身上的重新算，顺手拿到的那几样不再单独去弄；一样弄不来时按原来的规矩收场：
     * 记下缺什么与结论，身上这一样还够做一份就接着备别的，一份的量都没有就整个做法到此为止。
     */
    private final class Ingredients implements Action {
        private final int times;
        private final Permissions permissions;
        private final List<WorkstationRecipe.IngredientStack> order;
        /** 这一趟已经碰过的那几种：一样只试一次，弄不来的不回头再试。 */
        private final Set<WorkstationRecipe.IngredientStack> attempted =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private Action current;
        private WorkstationRecipe.IngredientStack currentIngredient;
        private ItemRequest currentRequest;

        private Ingredients(int times, Permissions permissions) {
            this.times = times;
            this.permissions = permissions;
            this.order = ordered(times);
            // 建出来就把第一样缺的交回引擎，之后每做一样再按现场重算。
            this.current = startNext();
        }

        @Override public ActionStatus tick(TickContext tick) {
            if (current == null) return ActionStatus.done();
            ActionStatus status = current.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            current.close();
            current = null;
            attempted.add(currentIngredient);
            if (status instanceof ActionStatus.Failed failed) {
                // 里层只给一句结论，经过由拿东西的引擎收着，最外层摊平讲一次。
                missing.add(currentRequest.wanted().describe() + "×" + currentRequest.count());
                reasons.add(failed.problem().headline());
                reasonKinds.add(failed.problem().kind());
                int onHand = choice.carried(currentIngredient);
                if (currentIngredient.count() > 0 && onHand < currentIngredient.count()) {
                    return ActionStatus.failed(problem());
                }
            }
            currentIngredient = null;
            currentRequest = null;
            current = startNext();
            return current == null ? ActionStatus.done() : ActionStatus.progressed();
        }

        // 找下一样还缺的：带着别的还缺的当"顺路还要的"，开一次箱就能一起拿。都碰过了给空。
        private Action startNext() {
            for (WorkstationRecipe.IngredientStack ingredient : order) {
                if (attempted.contains(ingredient)) continue;
                Optional<ItemRequest> request = missingRequest(ingredient, times);
                if (request.isEmpty()) {
                    attempted.add(ingredient);
                    continue;
                }
                List<ItemRequest> alsoWanted = new ArrayList<>();
                for (WorkstationRecipe.IngredientStack other : order) {
                    if (other == ingredient || attempted.contains(other)) continue;
                    missingRequest(other, times).ifPresent(alsoWanted::add);
                }
                currentIngredient = ingredient;
                currentRequest = request.get();
                return needs.actionFor(currentRequest, permissions, null, alsoWanted);
            }
            return null;
        }

        @Override public void pause() {
            if (current != null) current.pause();
        }

        @Override public void close() {
            if (current != null) {
                current.close();
                current = null;
            }
        }

        @Override public Interruptibility interruptibility() {
            return current == null ? Interruptibility.BETWEEN_ACTIONS : current.interruptibility();
        }

        @Override public String describe() {
            return current == null ? "备「做" + recipe.result().describe() + "」的原料"
                    : current.describe();
        }
    }

    // 备料的一步：回引擎去弄。perCraft 是做一份要几件，为 0 表示不按份数止损（燃料不够还能烧一部分）；
    // ingredient 是这一步备的哪一格原料（燃料没有为空），身上够不够一份按这一格收的几样一起数。
    Action tolerant(ItemRequest request, WorkstationRecipe.IngredientStack ingredient, int perCraft,
            Permissions permissions) {
        return new Tolerant(needs.actionFor(request, permissions), request, ingredient, perCraft);
    }

    // 原料没备齐、一份都做不了：结论句点名缺哪几样，展开写里层没弄来的结论。
    // 做的是什么由转述的一层写在前面（哪种做法、哪样东西），这里不再重复成品的名字。
    Problem problem() {
        StringBuilder message = new StringBuilder("原料没备齐（缺").append(String.join("、", missing))
                .append("），一份都做不了");
        for (String reason : reasons) message.append("；").append(reason);
        return Problem.of(Problem.combined(reasonKinds), message.toString());
    }

    // 动手那一步：做不下去时（料用完了）在它的结论后面补上缺哪几样，引擎写进结果。
    Action explaining(Action run) {
        return new Action() {
            @Override public ActionStatus tick(TickContext context) {
                ActionStatus status = run.tick(context);
                if (!(status instanceof ActionStatus.Failed failed) || missing.isEmpty()) return status;
                Problem problem = failed.problem();
                String headline = problem.headline();
                StringBuilder message = new StringBuilder(headline).append("（原料没备齐：缺")
                        .append(String.join("、", missing)).append("，身上的料能做的已经做了）")
                        .append(problem.message().substring(headline.length()));
                for (String reason : reasons) message.append("；").append(reason);
                Set<Problem.Kind> kinds = new LinkedHashSet<>(reasonKinds);
                kinds.add(problem.kind());
                return ActionStatus.failed(Problem.of(Problem.combined(kinds), message.toString(),
                        problem.suggestion()));
            }

            @Override public void pause() {
                run.pause();
            }

            @Override public void close() {
                run.close();
            }

            @Override public Interruptibility interruptibility() {
                return run.interruptibility();
            }

            @Override public String describe() {
                return run.describe();
            }
        };
    }

    private final class Tolerant implements Action {
        private final Action step;
        private final ItemRequest request;
        private final WorkstationRecipe.IngredientStack ingredient;
        private final int perCraft;

        private Tolerant(Action step, ItemRequest request, WorkstationRecipe.IngredientStack ingredient,
                int perCraft) {
            this.step = step;
            this.request = request;
            this.ingredient = ingredient;
            this.perCraft = perCraft;
        }

        // 弄不来：记下缺什么和里层的结论句。身上这样原料还够做一份就按做完往下走，先把够做的做掉；
        // 一份的量都没有就整个做法到此为止，别的原料弄来也做不成。
        @Override public ActionStatus tick(TickContext context) {
            ActionStatus status = step.tick(context);
            if (!(status instanceof ActionStatus.Failed failed)) return status;
            missing.add(request.wanted().describe() + "×" + request.count());
            reasons.add(failed.problem().headline());
            reasonKinds.add(failed.problem().kind());
            int onHand = ingredient == null ? CarriedItems.matching(backpack, offhand, request.wanted(), tags)
                    : choice.carried(ingredient);
            if (perCraft > 0 && onHand < perCraft) {
                return ActionStatus.failed(problem());
            }
            return ActionStatus.done();
        }

        @Override public void pause() {
            step.pause();
        }

        @Override public void close() {
            step.close();
        }

        @Override public Interruptibility interruptibility() {
            return step.interruptibility();
        }

        @Override public String describe() {
            return step.describe();
        }
    }
}
