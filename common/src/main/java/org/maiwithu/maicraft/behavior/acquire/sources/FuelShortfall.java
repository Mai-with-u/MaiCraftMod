// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import java.util.Optional;

import org.maiwithu.maicraft.behavior.acquire.cook.ReadsFuels;
import org.maiwithu.maicraft.behavior.acquire.cook.WorkstationRecipe;
import org.maiwithu.maicraft.behavior.acquire.engine.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.game.player.BackpackView;

/**
 * 烧炼要备的燃料：身上能烧的加起来够烧这一炉就不用备；不够时身上有原木就拿原木做木板当燃料，
 * 没有原木才去弄煤。只读背包与燃料表给结论，要备的那份交给拿东西的引擎去弄。
 */
final class FuelShortfall {

    /** 备燃料时的规划数字：一块煤烧 1600 刻，按它算要带几块，实际烧多久以燃料表为准。 */
    private static final int PLANNED_COAL_BURN_TICKS = 1600;
    private static final String COAL = "minecraft:coal";
    private static final String LOGS_TAG = "minecraft:logs";
    private static final String PLANKS_TAG = "minecraft:planks";
    /** 一块木板烧多久（刻）：一块半件的量。 */
    private static final int PLANK_BURN_TICKS = 300;

    private final BackpackView backpack;
    private final ReadsFuels fuels;
    private final ReadsItemTags tags;

    FuelShortfall(BackpackView backpack, ReadsFuels fuels, ReadsItemTags tags) {
        this.backpack = backpack;
        this.fuels = fuels;
        this.tags = tags;
    }

    // 烧炼要的燃料：身上有烧得着的就用身上的（挑烧得最久的），一件都没有才去弄煤。
    Optional<ItemRequest> need(WorkstationRecipe recipe, int times) {
        // 燃料表是熔炉口径；高炉、烟熏炉烧得快、燃料也耗得快，按熔炉口径一件仍是 200 刻。
        int smeltTicks = times * recipe.kind().fuelTicksPerItem();
        // 身上能烧的加起来够不够：十块木板能烤好几块肉，不能只看最耐烧的一件就说要去弄煤。
        // 工具、武器这类一格一件的不算燃料，这次要烧的原料也不算。
        long burnable = 0;
        for (var stack : backpack.stacks()) {
            if (stack.maxStackSize() <= 1 || isIngredientOf(recipe, stack.itemId())) continue;
            burnable += (long) stack.count() * fuels.burnTicks(stack.itemId());
        }
        if (burnable >= smeltTicks) {
            return Optional.empty();
        }
        // 身上有原木（又不全是这次要烧的）：拿原木做木板当燃料，开局就这么烧——要烧木炭时再去弄煤只会转圈
        // （实机做火把缺木炭，揣着原木却因为"没有燃料"烧不了木炭）。没有原木才去弄煤。
        long spareLogs = 0;
        boolean burnsLogs = false;
        for (var stack : backpack.stacks()) {
            if (!tags.tagsOf(stack.itemId()).contains(LOGS_TAG)) continue;
            spareLogs += stack.count();
            burnsLogs |= isIngredientOf(recipe, stack.itemId());
        }
        // 这一炉本来就烧原木（烧木炭）：留够要烧的那几根，剩下的才拿去做木板。
        if (burnsLogs) spareLogs -= times;
        long missingTicks = smeltTicks - burnable;
        if (spareLogs > 0) {
            int planksNeeded = Math.max(1, (int) Math.ceil((double) missingTicks / PLANK_BURN_TICKS));
            return Optional.of(new ItemRequest(WantedItem.ofTag(PLANKS_TAG), planksNeeded,
                    "烧炼" + recipe.result().describe() + "的燃料"));
        }
        int coalNeeded = Math.max(1, (int) Math.ceil((double) missingTicks / PLANNED_COAL_BURN_TICKS));
        return Optional.of(new ItemRequest(WantedItem.ofItem(COAL), coalNeeded,
                "烧炼" + recipe.result().describe() + "的燃料"));
    }

    // 这件东西是不是这条配方要的原料：烧炼时不把要烧的东西当燃料。
    private boolean isIngredientOf(WorkstationRecipe recipe, String itemId) {
        return recipe.ingredients().stream().anyMatch(ingredient -> ingredient.accepts(itemId, tags.tagsOf(itemId)));
    }
}
