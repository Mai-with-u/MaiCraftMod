// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.cook;

import java.util.Arrays;
import java.util.List;

/**
 * 熔炉的燃料怎么挑：身上几堆能烧的东西里挑哪一堆、这一堆放几件才够烧完。
 *
 * <p>只看给进来的刻数，不碰游戏：挑堆与挑件数的规则在这里，角色身上哪一堆能烧由读端照实给。
 */
final class FurnaceFuel {

    /** 零碎燃料的上限：木棍、树苗一件只烧半件，有木板以上的燃料时不拿它们。 */
    static final int SMALL_FUEL_TICKS = 300;

    /** 一格最多放一组。 */
    static final int STACK = 64;

    private FurnaceFuel() {}

    /**
     * 身上的一堆燃料：角色侧第几格、几件、一件烧多少刻。
     *
     * @param index     角色侧第几格
     * @param count     这一堆有几件
     * @param burnTicks 一件烧多少刻
     */
    record Stack(int index, int count, int burnTicks) {}

    /** 挑中的燃料：角色侧第几格、放几件。 */
    record Pick(int index, int count) {}

    /**
     * 挑燃料：要烧 needTicks 刻。先挑一堆就够烧完的；再挑木板以上的（木棍、树苗一件只烧半件，太零碎）；
     * 同样的里挑烧完浪费最少的（烧两件东西不扔一块煤炭块进去），一样少时挑一件烧得久的（少点几下）。
     * 放几件按够烧完算，一组封顶；身上没有能烧的给 null。
     */
    static Pick pick(List<Stack> stacks, long needTicks) {
        Stack best = null;
        long[] bestKey = null;
        long need = Math.max(1, needTicks);
        for (Stack stack : stacks) {
            if (stack.count() <= 0 || stack.burnTicks() <= 0) continue;
            long items = Math.min(stack.count(), ceilDiv(need, stack.burnTicks()));
            long covered = items * stack.burnTicks();
            long[] key = {covered >= need ? 0 : 1, stack.burnTicks() >= SMALL_FUEL_TICKS ? 0 : 1,
                    Math.max(0, covered - need), -stack.burnTicks()};
            if (best == null || Arrays.compare(key, bestKey) < 0) {
                best = stack;
                bestKey = key;
            }
        }
        if (best == null) {
            return null;
        }
        long count = Math.min(Math.min(best.count(), STACK), ceilDiv(need, best.burnTicks()));
        return new Pick(best.index(), (int) count);
    }

    /** 炉子此刻算不算仍有燃料可用：还在燃烧，或者燃料槽里的物品本身具备有效燃烧时间。 */
    static boolean usable(boolean lit, String fuelItemId, int burnTicks) {
        return lit || (fuelItemId != null && !fuelItemId.isEmpty() && burnTicks > 0);
    }

    /**
     * 燃料槽里的不可燃残物是不是角色自己放进去留下的。
     *
     * <p>岩浆桶烧完留下的是空铁桶：那是角色自己放的，该收回背包腾出槽位；别人留下的残物不乱动，换一台炉子。
     */
    static boolean ownResidue(String fuelPlaced, String currentFuelId) {
        if (fuelPlaced == null || currentFuelId == null || currentFuelId.isEmpty()) return false;
        return currentFuelId.equals(fuelPlaced)
                || ("minecraft:lava_bucket".equals(fuelPlaced) && "minecraft:bucket".equals(currentFuelId));
    }

    /** 向上取整的除法：不算得准不会少备一件。 */
    static long ceilDiv(long value, long divisor) {
        return (value + divisor - 1) / divisor;
    }
}
