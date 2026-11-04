// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.burning;

import java.util.Objects;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.survival.burning.FireResistancePotions.CarriedPotion;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.interrupt.SurvivalNeed;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 着火这项生存需求：泡在岩浆里、站进火里、身上着火时多急，急了就派着火自救的临时任务。
 *
 * <p>泡在岩浆里、站进火里（往旁边迈得出去）是立刻；在陆地上着火，估着这把火够烧死、手边有水或有防火药水是立刻，
 * 估着烧不死、4 格内走得到水、不在下界是尽快；别的不插，原地熬过去。身上有防火效果（剩 5 秒以上）时火和岩浆都伤不着，
 * 不插。这是眼前危险的反应：主目标关了起居类需求照插，没做成不缓，下一刻按新处境再判。
 */
public final class BurningNeed implements SurvivalNeed {

    /** 防火效果剩不到这么多刻（5 秒）按没有算：转眼就没了，等不到出岩浆。 */
    public static final int RESISTANCE_LEFT_TICKS = 100;
    /** 估着够烧死时，这么多步内有水先跳水，药留着。 */
    public static final int NEAR_WATER_STEPS = 2;
    /** 着火时找水最远找这么多步。 */
    public static final int WATER_STEPS = 4;

    private final View view;
    private final BurningTask.Moves moves;
    private final TaskEventSink events;

    public BurningNeed(View view, BurningTask.Moves moves, TaskEventSink events) {
        this.view = Objects.requireNonNull(view, "view");
        this.moves = Objects.requireNonNull(moves, "moves");
        this.events = Objects.requireNonNull(events, "events");
    }

    /** 每刻读一份着火处境；不在世界里、无敌（创造、旁观）时为空。 */
    @FunctionalInterface
    public interface View {
        Reading read(TickContext context);
    }

    /**
     * 角色此刻的着火处境，都是读到的事实，结论由 {@link #assess} 给。
     *
     * @param feet                脚下这一格
     * @param inLava              身体泡在岩浆里
     * @param fireResistanceTicks 防火效果还剩几刻；没有是 0
     * @param inFire              脚格或头格是火、灵魂火，或点着的营火
     * @param fireStep            站在火里时往哪一格迈出来；不在火里或迈不出去时为 null
     * @param onFire              身上着着火
     * @param burn                估这把火还要烧多久、够不够烧死
     * @param water               几步内走得到的水；在陆地上着火时才找，没有为 null
     * @param waterBoils          这个维度水放不住（下界这类）
     * @param potion              身上挑出来喝的那瓶防火药水；没有为 null
     */
    public record Reading(BlockPos feet, boolean inLava, int fireResistanceTicks, boolean inFire, BlockPos fireStep,
                          boolean onFire, BurnEstimate burn, BurningSteps.Water water, boolean waterBoils,
                          CarriedPotion potion) {

        public Reading {
            Objects.requireNonNull(feet, "feet");
            burn = burn == null ? BurnEstimate.NONE : burn;
        }

        /** 防火效果还够用：火和岩浆都伤不着。 */
        public boolean fireResistant() {
            return fireResistanceTicks >= RESISTANCE_LEFT_TICKS;
        }
    }

    // 火和岩浆是眼前危险：主目标关了起居类需求也照样自救，不干活干到烧死。
    @Override public boolean reactsToImmediateDanger() { return true; }

    // 每一刻的火都是新的处境：没做成不缓，缓着就是站着挨烧。
    @Override public boolean waitsAfterFailure() { return false; }

    @Override public String name() { return "着火"; }

    @Override
    public Urgency urgency(TickContext context) {
        Reading reading = view.read(context);
        return reading == null ? null : assess(reading);
    }

    @Override
    public Task createTask(TickContext context) {
        return new BurningTask(view, moves, events);
    }

    /**
     * 多急，纯函数：岩浆、火里是立刻；陆地着火按估着烧不烧得死、手边有没有水和药分；做不了什么的不报，
     * 免得建出一个马上收场的临时任务每刻空转。
     */
    public static Urgency assess(Reading reading) {
        if (reading.fireResistant()) return null;
        if (reading.inLava()) return Urgency.NOW;
        if (reading.inFire() && reading.fireStep() != null) return Urgency.NOW;
        if (!reading.onFire()) return null;
        if (reading.burn().lethal()) {
            // 估着够烧死：有水跳水、有药喝药，都没有就只能熬着，提醒照挂。
            return reading.water() != null || reading.potion() != null ? Urgency.NOW : null;
        }
        return reading.water() != null && !reading.waterBoils() ? Urgency.SOON : null;
    }
}
