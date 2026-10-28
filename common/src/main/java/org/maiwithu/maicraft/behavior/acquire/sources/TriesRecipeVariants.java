// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.maiwithu.maicraft.behavior.acquire.cook.WorkstationRecipe;
import org.maiwithu.maicraft.behavior.acquire.engine.CarriedItems;
import org.maiwithu.maicraft.behavior.acquire.engine.OffhandContents;
import org.maiwithu.maicraft.behavior.acquire.engine.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 几种一样缺料的做法挨个试：这一种做砸了、要的东西一件没多，就换下一种；做出过东西就照常收场，由引擎清点。
 * 都不成时把试过的几种和原因一并交代。
 */
final class TriesRecipeVariants implements Action {
    private final ItemRequest request;
    private final List<WorkstationRecipe> variants;
    /** 照一种做法做的一整串：由做出来的来源按这次请求与现场给出。 */
    private final Function<WorkstationRecipe, Action> attempt;
    private final BackpackView backpack;
    private final OffhandContents offhand;
    private final ReadsItemTags tags;
    private final List<String> failures = new ArrayList<>();
    private int index;
    private Action current;
    private int carriedBefore;

    TriesRecipeVariants(ItemRequest request, List<WorkstationRecipe> variants,
            Function<WorkstationRecipe, Action> attempt, BackpackView backpack, OffhandContents offhand,
            ReadsItemTags tags) {
        this.request = request;
        this.variants = variants;
        this.attempt = attempt;
        this.backpack = backpack;
        this.offhand = offhand;
        this.tags = tags;
    }

    @Override public ActionStatus tick(TickContext tick) {
        if (current == null) {
            carriedBefore = CarriedItems.matching(backpack, offhand, request.wanted(), tags);
            current = attempt.apply(variants.get(index));
        }
        ActionStatus status = current.tick(tick);
        if (!(status instanceof ActionStatus.Failed failed)) return status;
        boolean delivered = CarriedItems.matching(backpack, offhand, request.wanted(), tags) > carriedBefore;
        // 每种做法只记它自己那一句结论（缺哪几样原料）；原料为什么弄不来由拿东西的引擎收着，最外层讲一次。
        failures.add(variants.get(index).id() + "：" + failed.problem().headline());
        if (!delivered && index + 1 < variants.size()) {
            current.close();
            current = null;
            index++;
            return ActionStatus.progressed();
        }
        if (failures.size() == 1) return status;
        return ActionStatus.failed(Problem.of(failed.problem().kind(), "试了 " + failures.size() + " 种做法都没做成；"
                + String.join("；", failures), failed.problem().suggestion()));
    }

    @Override public void pause() {
        if (current != null) current.pause();
    }

    @Override public void close() {
        if (current != null) current.close();
    }

    @Override public Interruptibility interruptibility() {
        return current == null ? Interruptibility.WORKING : current.interruptibility();
    }

    @Override public String describe() {
        return current == null ? "做" + request.wanted().describe()
                : current.describe() + "（第 " + (index + 1) + "/" + variants.size() + " 种做法）";
    }
}
