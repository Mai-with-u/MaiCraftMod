// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine.spi;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.behavior.recipe.ShownIngredient;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 一种机器收料与出货的本事：往机器投料、说清此刻为什么投不了、从出口取货。
 * 只会收料取货的简单机器（厨锅）实现这一条就够，不用回答安装段、部件、设置项的问题。
 *
 * <p>发电机这类烧燃料的机器也在这条上：fuelUse 说明 machine_run 给的 item 是不是燃料；
 * 蓝图桌、蓝图加农炮这种不按配方投料、整段自己做完的机器把做法交在 fullRun。
 */
public interface MachineFeeding {

    /**
     * 往这台机器投料：置物台是手持右键顶面，工作盆、石磨这类扔上去，带界面的点开界面放进去。这种机器不收料时给空。
     * 投法不一定件件都进时，动作再实现 {@link FeedReport} 交出实情。
     */
    Optional<Action> feed(BlockPos at, String itemId, int count, Permissions permissions);

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
     * 再换成配方要的灌注物。能就给投哪样、几件，machine_run 把它排在主料前面经 {@link #feed} 投；
     * 换不了给空，machine_run 照旧写一句"带不进机器，做不做得看机器里还有没有"。
     *
     * @param ingredient 配方里的那一格（查看器认得、但不是物品也不是流体的东西）
     * @param amount     这次要的总量：配方的量乘上批数
     */
    default Optional<IngredientFeed> ingredientFeed(BlockPos at, ShownIngredient ingredient, long amount) {
        return Optional.empty();
    }

    /**
     * 配方里列在 {@link ShownRecipe#catalysts()} 里的东西要不要作为投料放进这台机器：
     * 大多数机器的催化剂只是工作站或加热方块图标，不用投进机器，默认 {@code false}；
     * 压印器的不消耗压印模板被部分查看器列进催化剂，却真的要放进机器模板格，答 {@code true}。
     */
    default boolean feedsCatalysts() {
        return false;
    }

    /**
     * 这格原料在按配方投料时要不要排在普通主料前面先放：
     * 压印器的中间输入格只有先放好顶、底模板或印制电路后才允许塞入主料，顶底格物料答 {@code true}。
     */
    default boolean feedsFirst(ShownRecipe recipe, ShownIngredient ingredient) {
        return false;
    }

    /**
     * 这台机器跑 {@code batches} 批这条配方时，这一格原料实际要从身上备齐并投进几件。
     */
    default long feedCount(BlockPos at, ShownRecipe recipe, ShownIngredient ingredient, int batches) {
        return feedCount(at, recipe, ingredient, batches, false);
    }

    /**
     * 这台机器跑 {@code batches} 批这条配方时，这一格原料（或需投入的催化剂）实际要从身上备齐并投进几件：
     * 默认按配方单批用量乘批数；压印器对不消耗的压印模板不论做几批只投 1 张，机器里原本就放着匹配模板时投 0 张。
     *
     * @param fromCatalysts 这一格是不是来自 {@link ShownRecipe#catalysts()}
     */
    default long feedCount(BlockPos at, ShownRecipe recipe, ShownIngredient ingredient, int batches,
            boolean fromCatalysts) {
        return ingredient.amount() * batches;
    }

    /**
     * 选好配方后、备料与投料前，若机器工作格或输出格里占着与本次目标配方不兼容的遗留模板、半成品或异种产物，
     * 返回打开机器界面把冲突物品取回角色背包的自愈动作；机内没有冲突物或无需清理时给空。
     */
    default Optional<Action> clearConflicts(BlockPos at, ShownRecipe recipe, int batches, Permissions permissions) {
        return Optional.empty();
    }
}
