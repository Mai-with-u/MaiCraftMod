// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.night;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.kernel.ability.GoalEstimate;
import org.maiwithu.maicraft.kernel.ability.GoalEstimate.Effort;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 夜里先备武器：露天打不过、今晚没床时，真人会先看看手边能不能做把剑——有了剑就不怕一两只怪，
 * 空着手摸黑出门找羊才是又费事又危险的那件。
 *
 * <p>按估算比：身上没有近战武器、床要出门找（或弄不到、说不准），而剑或斧里有手边做得出的（不用离开或知道在哪），
 * 就先备那一把；床在手边或附近就照旧备床，做好直接睡，一觉跳过夜晚比拿着剑熬夜省事。
 * 估算只用来挑先后，不拦任何操作：床估出来弄不到，照样可以去备床，由备床如实结束。
 */
public final class WeaponBeforeBed implements NightfallNeed.PreparesNightWeapon {

    private final ItemNeeds needs;
    private final Supplier<GoalEstimate> tonightsBed;
    private final ReadsMeleeWeapons weapons;
    private final Permissions permissions;

    /**
     * @param needs       拿东西的引擎：估武器、备武器都经它
     * @param tonightsBed 备今晚的床此刻多费事（和备床任务同一个请求）
     * @param permissions 备武器用的许可，和备床一样
     */
    public WeaponBeforeBed(ItemNeeds needs, Supplier<GoalEstimate> tonightsBed, ReadsMeleeWeapons weapons,
            Permissions permissions) {
        this.needs = needs;
        this.tonightsBed = tonightsBed;
        this.weapons = weapons;
        this.permissions = permissions;
    }

    // 身上有近战武器 → 不备；床在手边或附近 → 不备；剑、斧里挑一把手边做得出的 → 备它；一把都没有 → 不备（去备床）。
    @Override public Task prepareWeapon(TickContext context) {
        if (weapons.carryingMelee(context)) return null;
        Effort bed = tonightsBed.get().effort();
        if (bed == Effort.READY || bed == Effort.NEARBY) return null;
        return choose(weapons.swordsAndAxesByScore(), item -> needs.estimate(request(item), permissions).effort())
                .map(item -> (Task) new PrepareWeaponTask(needs, request(item), permissions))
                .orElse(null);
    }

    /**
     * 挑哪一把：按武器分从高到低问，问到第一把不用离开就做得出的就停；没有不用离开的，取第一把知道在哪的
     * （同一档里分高的先问到）；要出门找、弄不到、说不准的都不挑。
     */
    static Optional<String> choose(List<String> byScore, Function<String, Effort> effortOf) {
        String nearby = null;
        for (String item : byScore) {
            Effort effort = effortOf.apply(item);
            if (effort == Effort.READY) return Optional.of(item);
            if (effort == Effort.NEARBY && nearby == null) nearby = item;
        }
        return Optional.ofNullable(nearby);
    }

    /** 要一把：用途写清是天黑了先备的。 */
    static ItemRequest request(String item) {
        return new ItemRequest(WantedItem.ofItem(item), 1, "天黑了先备把武器");
    }
}
