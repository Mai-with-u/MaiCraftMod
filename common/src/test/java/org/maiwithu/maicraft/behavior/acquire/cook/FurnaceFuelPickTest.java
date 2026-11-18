// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.cook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 熔炉添燃料挑哪一堆、放几件，关着界面等多久回来看。 */
class FurnaceFuelPickTest {

    private static final int COAL = 1600;
    private static final int COAL_BLOCK = 16000;
    private static final int PLANKS = 300;
    private static final int STICK = 100;

    @Test
    void smallBatchDoesNotBurnACoalBlock() {
        // 烧两件（400 刻）：一块煤炭块能烧八十件，扔进去就浪费了；一块煤够烧完，挑煤。
        FurnaceFuel.Pick pick = FurnaceFuel.pick(List.of(
                new FurnaceFuel.Stack(0, 3, COAL_BLOCK),
                new FurnaceFuel.Stack(1, 5, COAL)), 400);
        assertEquals(1, pick.index());
        assertEquals(1, pick.count());
    }

    @Test
    void enoughFuelForTheWholeBatchIsPlacedAtOnce() {
        // 烧十六件（3200 刻）：两块煤正好烧完，一次放两块。
        FurnaceFuel.Pick pick = FurnaceFuel.pick(List.of(new FurnaceFuel.Stack(4, 10, COAL)), 3200);
        assertEquals(4, pick.index());
        assertEquals(2, pick.count());
    }

    @Test
    void sticksOnlyWhenNothingBetterCoversTheBatch() {
        // 木板和木棍都够烧完时不拿零碎的木棍；只有木棍够时才用木棍。
        FurnaceFuel.Pick withPlanks = FurnaceFuel.pick(List.of(
                new FurnaceFuel.Stack(0, 20, STICK),
                new FurnaceFuel.Stack(1, 10, PLANKS)), 600);
        assertEquals(1, withPlanks.index());
        assertEquals(2, withPlanks.count());
        FurnaceFuel.Pick onlySticksCover = FurnaceFuel.pick(List.of(
                new FurnaceFuel.Stack(0, 20, STICK),
                new FurnaceFuel.Stack(1, 1, PLANKS)), 600);
        assertEquals(0, onlySticksCover.index());
        assertEquals(6, onlySticksCover.count());
    }

    @Test
    void fuelIsCappedAtOneStackAndMissingFuelIsNull() {
        FurnaceFuel.Pick pick = FurnaceFuel.pick(List.of(new FurnaceFuel.Stack(2, 64, PLANKS)), 200L * 300);
        assertEquals(64, pick.count());
        assertNull(FurnaceFuel.pick(List.of(), 200));
    }

    @Test
    void waitsUntilTheLoadedItemsShouldBeDoneWithinBounds() {
        // 投入口三件、手上这件烧了一半：两件半的时间再多等一秒回来；八件要七件半的时间，封顶一分钟回来收一次。
        assertEquals(520, FurnaceRun.waitTicks(3, 0.5f, 200));
        assertEquals(20 * 60, FurnaceRun.waitTicks(8, 0.5f, 200), "最多隔一分钟回来看一眼");
        assertEquals(220, FurnaceRun.waitTicks(1, 0f, 200));
        assertTrue(FurnaceRun.waitTicks(1, 1f, 200) >= 40, "刚烧完也至少等一会儿再回来");
    }

    @Test
    void emptyBucketInUnlitFurnaceDoesNotCountAsUsableFuelAndColdBucketResidueCanBeCleared() {
        // 炉火熄灭时，燃料槽里占着的空铁桶燃烧时间为 0，不算还有燃料；炉子仍亮着时照常算在烧。
        assertEquals(false, FurnaceFuel.usable(false, "minecraft:bucket", 0));
        assertTrue(FurnaceFuel.usable(true, "minecraft:bucket", 0));
        assertTrue(FurnaceFuel.usable(false, "minecraft:coal", 1600));

        // 收工整理只认本趟放进去的燃料或本趟岩浆桶烧剩的空铁桶，不把机内原本就有的收走。
        assertTrue(FurnaceFuel.ownResidue("minecraft:coal", "minecraft:coal"));
        assertTrue(FurnaceFuel.ownResidue("minecraft:lava_bucket", "minecraft:bucket"));
        assertEquals(false, FurnaceFuel.ownResidue("minecraft:coal", "minecraft:bucket"));
        assertEquals(false, FurnaceFuel.ownResidue(null, "minecraft:bucket"));

        // 冷炉无外人原料产物时，早先遗留的空铁桶允许清出腾槽，陌生不可燃物仍不乱动。
        assertTrue(FurnaceFuel.removableResidue(null, "minecraft:bucket"));
        assertTrue(FurnaceFuel.removableResidue("minecraft:coal", "minecraft:bucket"));
        assertEquals(false, FurnaceFuel.removableResidue(null, "minecraft:cobblestone"));
        assertEquals(false, FurnaceFuel.removableResidue("minecraft:coal", "minecraft:cobblestone"));
    }
}
