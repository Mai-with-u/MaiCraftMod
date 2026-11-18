// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine.spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.recipe.ShownIngredient;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.behavior.recipe.ShownStack;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 一种机器收料与出货的本事：往机器投料、说清此刻为什么投不了、从出口取货。
 * 只会收料取货的简单机器（厨锅）实现这一条就够，不用回答安装段、部件、设置项的问题。
 *
 * <p>按配方投料前先问一次 {@link #plan}：这条配方在这台机器怎么投，一次解析成一份计划
 * （每格投几件、谁先投、机内有什么冲突），不逐格来回问。发电机这类烧燃料的机器也在这条上：
 * fuelUse 说明 machine_run 给的 item 是不是燃料；蓝图桌、蓝图加农炮这种整段自己做完的机器把做法交在 fullRun。
 */
public interface MachineFeeding {

    /**
     * 往这台机器投料：置物台是手持右键顶面，工作盆、石磨这类扔上去，带界面的点开界面放进去。这种机器不收料时给空。
     * 投的是计划里的一样料：不消耗、收产物时要一并收回的（压印模板）由 {@code item.collectBack()} 说清，
     * 机器据此记下"自己放进机器的"，不再靠旁路记录传话。
     * 投法不一定件件都进时，动作再实现 {@link FeedReport} 交出实情。
     */
    Optional<Action> feed(BlockPos at, FeedPlan.Entry item, Permissions permissions);

    /**
     * 这台机器此刻为什么接不了投料（{@link #feed} 给了空是因为当前朝向不接散料、加工处还没摆置物台或工作盆、
     * 双轮之间没有朝下送料口这类现场摆法原因）：给出一句中文原因，machine_run 按这里做不了如实报，
     * 不误报成机器不收这种物品；机器本身就不收料时给空。
     */
    default Optional<String> whyCannotFeed(BlockPos at, String itemId) {
        return Optional.empty();
    }

    /** 从出口把东西拿进背包；出口没有这种东西或这种机器不出东西时给空。 */
    Optional<Action> take(BlockPos at, String itemId, int count, Permissions permissions);

    /**
     * 这台机器自己从头做到尾的一套做法（蓝图桌写蓝图、蓝图加农炮照蓝图施工）：不按配方投料，整段交给机器类型。
     * 不是这种机器、或这次的请求不归它管给空，machine_run 照配方那一套走。
     */
    default Optional<FullRun> fullRun(BlockPos at, RunRequest request, Permissions permissions) {
        return Optional.empty();
    }

    /**
     * machine_run 给的 item 对这台机器是什么：发电机这类烧燃料的，item 就是燃料，备齐了直接经 {@link #feed} 投，不查配方；
     * 不吃料的发电机说一句它靠什么发电。大多数机器按配方投料，给 {@link FuelUse#RECIPE}。
     */
    default FuelUse fuelUse(BlockPos at) {
        return FuelUse.RECIPE;
    }

    /**
     * 配方里背包带不进机器的那一格原料（化学品这类）能不能用物品投：通用机械的机器从附加格吃煤、红石这些物品，
     * 再换成配方要的灌注物。能就给投哪样、几件，计划把它排在主料前面经 {@link #feed} 投；
     * 换不了给空，计划照旧写一句"带不进机器，做不做得看机器里还有没有"。
     *
     * @param ingredient 配方里的那一格（查看器认得、但不是物品也不是流体的东西）
     * @param amount     这次要的总量：配方的量乘上批数
     */
    default Optional<IngredientFeed> ingredientFeed(BlockPos at, ShownIngredient ingredient, long amount) {
        return Optional.empty();
    }

    /**
     * 这条配方在这台机器怎么投，一次解析成一份计划值：每格投几件、谁先投、机内有什么冲突。
     * 判断与现场读数在这里做，调用方一次拿到，不再按 feedsCatalysts、feedCount 这类问题逐格问。
     * 默认是标准的按配方投料：换料在前，普通主料按配方顺序，每样按配方用量乘批数；催化剂默认不投。
     */
    default FeedPlan plan(BlockPos at, ShownRecipe recipe, int batches, Permissions permissions) {
        return standard(this, at, recipe, batches);
    }

    /**
     * 标准的按配方投料计划：换料排在最前（机器先有灌注物，主料一放就开工），普通主料按配方顺序；
     * 每样按配方单批用量乘批数。没有投料本事（self 为 null）的机器也算得出一份：化学品这类照旧写一句带不进去。
     */
    static FeedPlan standard(MachineFeeding self, BlockPos at, ShownRecipe recipe, int batches) {
        List<FeedPlan.Entry> converted = new ArrayList<>();
        List<FeedPlan.Entry> main = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (ShownIngredient ingredient : recipe.inputs()) {
            Optional<WantedItem> wanted = wantedItemOf(ingredient);
            if (wanted.isEmpty()) {
                Optional<IngredientFeed> byItem = self == null ? Optional.empty()
                        : self.ingredientFeed(at, ingredient, ingredient.amount() * batches);
                if (byItem.isPresent()) {
                    notes.add(byItem.get().note());
                    converted.add(new FeedPlan.Entry(byItem.get().itemId(),
                            (int) Math.min(Integer.MAX_VALUE, byItem.get().count()), false));
                } else {
                    notes.add("配方还要机器自己要的东西（" + describe(ingredient)
                            + "），带不进机器，做不做得看机器里还有没有");
                }
                continue;
            }
            long count = (long) ingredient.amount() * batches;
            if (count <= 0) {
                notes.add("机器里已有匹配的 " + wanted.get().specifier() + "，不用再放");
                continue;
            }
            main.add(new FeedPlan.Entry(wanted.get().specifier(),
                    (int) Math.min(Integer.MAX_VALUE, count), false));
        }
        List<FeedPlan.Entry> entries = new ArrayList<>(converted.size() + main.size());
        entries.addAll(converted);
        entries.addAll(main);
        return new FeedPlan(entries, notes, Optional.empty(), Optional.empty());
    }

    /** 一格原料写成备料与记账用的规格串（带 # 的标签或第一个具体物品 ID）。 */
    static Optional<WantedItem> wantedItemOf(ShownIngredient ingredient) {
        if (ingredient.tag() != null) {
            return Optional.of(WantedItem.ofTag(ingredient.tag()));
        }
        return ingredient.options().stream()
                .filter(option -> option.kind() == ShownStack.Kind.ITEM)
                .findFirst()
                .map(item -> WantedItem.ofItem(item.id()));
    }

    /** 一格原料给人看的一句话：标签名或第一个候选物品 ID。 */
    static String describe(ShownIngredient ingredient) {
        if (ingredient.tag() != null) {
            return ingredient.tag();
        }
        return ingredient.options().stream().findFirst().map(ShownStack::id).orElse("未知的东西");
    }
}
