// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.night;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.kernel.ability.GoalEstimate;
import org.maiwithu.maicraft.kernel.ability.GoalEstimate.Effort;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 夜里先备武器：身上没近战武器、床要出门找或弄不到、手边做得出一把剑或斧时先备那一把；
 * 挑法是武器分高的先问、不用离开的先于知道在哪的；备武器的任务按引擎的结果收场。
 */
class WeaponBeforeBedTest {

    private static final String STONE_SWORD = "minecraft:stone_sword";
    private static final String WOODEN_SWORD = "minecraft:wooden_sword";
    private static final String DIAMOND_SWORD = "minecraft:diamond_sword";
    private static final List<String> BY_SCORE = List.of(DIAMOND_SWORD, STONE_SWORD, WOODEN_SWORD);

    private static GoalEstimate estimate(Effort effort) {
        return new GoalEstimate(effort, "x", "", null, null, List.of(), false);
    }

    /** 替身：按物品给估算的档位，动手时按给定的结局收场；记下问过哪几样。 */
    private static final class FakeNeeds implements ItemNeeds {
        final Map<String, Effort> efforts;
        final List<String> asked = new ArrayList<>();
        ActionStatus ending = ActionStatus.done();

        FakeNeeds(Map<String, Effort> efforts) {
            this.efforts = efforts;
        }

        @Override public GoalEstimate estimate(ItemRequest request, Permissions permissions) {
            asked.add(request.wanted().specifier());
            return WeaponBeforeBedTest.estimate(efforts.getOrDefault(request.wanted().specifier(), Effort.BLOCKED));
        }

        @Override public Action actionFor(ItemRequest request, Permissions permissions) {
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    return ending;
                }

                @Override public String describe() {
                    return "做" + request.wanted().specifier();
                }
            };
        }
    }

    private record Weapons(boolean carrying) implements ReadsMeleeWeapons {
        @Override public boolean carryingMelee(TickContext context) {
            return carrying;
        }

        @Override public List<String> swordsAndAxesByScore() {
            return BY_SCORE;
        }
    }

    private static Task prepare(FakeNeeds needs, Effort bed, boolean carrying) {
        return new WeaponBeforeBed(needs, () -> estimate(bed), new Weapons(carrying), Permissions.DEFAULT)
                .prepareWeapon(TickContext.of(0, null));
    }

    @Test
    void 分高的先问_问到第一把不用离开的就停() {
        FakeNeeds needs = new FakeNeeds(Map.of(STONE_SWORD, Effort.READY, WOODEN_SWORD, Effort.READY));
        assertEquals(Optional.of(STONE_SWORD), WeaponBeforeBed.choose(BY_SCORE,
                item -> needs.estimate(WeaponBeforeBed.request(item), Permissions.DEFAULT).effort()));
        assertEquals(List.of(DIAMOND_SWORD, STONE_SWORD), needs.asked, "石剑就做得出，木剑不用再问");
    }

    @Test
    void 没有不用离开的_取第一把知道在哪的_要出门找的不挑() {
        assertEquals(Optional.of(STONE_SWORD), WeaponBeforeBed.choose(BY_SCORE, item -> switch (item) {
            case DIAMOND_SWORD -> Effort.SEARCH;
            case STONE_SWORD, WOODEN_SWORD -> Effort.NEARBY;
            default -> Effort.BLOCKED;
        }));
        assertEquals(Optional.empty(), WeaponBeforeBed.choose(BY_SCORE, item -> Effort.SEARCH));
    }

    @Test
    void 空手_床弄不到_身上有木头_先备木剑() {
        FakeNeeds needs = new FakeNeeds(Map.of(WOODEN_SWORD, Effort.READY));
        Task task = prepare(needs, Effort.BLOCKED, false);
        assertInstanceOf(PrepareWeaponTask.class, task);
    }

    @Test
    void 身上有近战武器_或床在附近_或手边做不出_都不备() {
        FakeNeeds needs = new FakeNeeds(Map.of(WOODEN_SWORD, Effort.READY));
        assertNull(prepare(needs, Effort.BLOCKED, true), "身上有剑斧：照旧备床");
        assertNull(prepare(needs, Effort.NEARBY, false), "看得见羊，床在附近：照旧备床");
        assertNull(prepare(new FakeNeeds(Map.of()), Effort.BLOCKED, false), "身上没木头、附近没树：照旧备床");
    }

    @Test
    void 备武器的任务_引擎做完就备好了_弄不到就带着原因结束() {
        FakeNeeds needs = new FakeNeeds(Map.of(WOODEN_SWORD, Effort.READY));
        assertEquals(TaskResult.Status.DONE, runToEnd(prepare(needs, Effort.BLOCKED, false)).status());

        needs.ending = ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, "附近没有树", null));
        TaskResult failed = runToEnd(prepare(needs, Effort.BLOCKED, false));
        assertEquals(TaskResult.Status.FAILED, failed.status());
        assertTrue(failed.problem().message().contains("附近没有树"));
    }

    private static TaskResult runToEnd(Task task) {
        TickContext tick = TickContext.of(0, null);
        task.start(tick);
        for (int i = 0; i < 20; i++) {
            if (task.tick(TickContext.of(i, null)) instanceof TickResult.Finished finished) return finished.result();
        }
        throw new AssertionError("二十刻还没收场");
    }
}
