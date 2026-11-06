// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.bodylanguage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.maiwithu.maicraft.behavior.inventory.drops.ThrowsItems;
import org.maiwithu.maicraft.behavior.perception.looking.Aim;
import org.maiwithu.maicraft.behavior.perception.looking.TurnsHead;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 做肢体动作的现场替身：镜头一刻就转到要的方向，潜行键按下去下一刻就蹲下，跳一下离地 6 刻，
 * 准星碰不碰得到东西由测试定。记下按过的键和转过的方向。
 */
final class FakeNonverbalMoves implements NonverbalMoves {

    static final UUID ALEX = UUID.randomUUID();
    static final Partner ALEX_PARTNER = new Partner(7, ALEX, "Alex");

    /** 他的眼睛在角色正南方（偏航 0、俯仰 0）。 */
    Aim face = new Aim(0, 0);
    boolean present = true;
    boolean riding;
    /** 视线抬到这个俯仰以上（更小的数）准星才什么都碰不到。 */
    float clearAbove = -10;
    boolean ceiling;
    int selected = 2;
    final List<String> pressed = new ArrayList<>();
    final List<UUID> answered = new ArrayList<>();
    int attacks;
    private boolean sneakHeld;
    private boolean crouchedNow;
    private int airTicks;
    private boolean jumpHeld;
    private final Head head = new Head();

    /** 镜头一刻转到位的转头替身。 */
    final class Head implements TurnsHead {
        float yaw;
        float pitch;
        boolean holding;

        @Override public float yaw() {
            return yaw;
        }

        @Override public float pitch() {
            return pitch;
        }

        @Override public float standingYaw() {
            return 0;
        }

        @Override public void hold() {
            holding = true;
        }

        @Override public void letGo() {
            holding = false;
        }

        @Override public void turnToward(float yaw, float pitch) {
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    Head fakeHead() {
        return head;
    }

    /** 过一刻：上一刻的按键生效。 */
    void advance() {
        crouchedNow = sneakHeld;
        if (airTicks > 0) {
            airTicks--;
        } else if (jumpHeld && !ceiling) {
            airTicks = 6;
        }
    }

    @Override public Partner partner(TickContext context, Target target) {
        return present ? ALEX_PARTNER : null;
    }

    @Override public double distanceTo(TickContext context, int entityId) {
        return present ? 2 : Double.POSITIVE_INFINITY;
    }

    @Override public boolean riding(TickContext context) {
        return riding;
    }

    @Override public Optional<String> carried(TickContext context, String itemOrTag) {
        return Optional.of(itemOrTag);
    }

    @Override public int selectedSlot(TickContext context) {
        return selected;
    }

    @Override public boolean selectSlot(TickContext context, int slot) {
        selected = slot;
        return true;
    }

    @Override public Optional<Action> takeOut(TickContext context, String itemId) {
        return Optional.empty();
    }

    @Override public Optional<Action> emptyHand() {
        return Optional.empty();
    }

    @Override public Action walkNear(int entityId, double within) {
        throw new UnsupportedOperationException("替身不走路");
    }

    @Override public ThrowsItems toss(String itemId, int count) {
        throw new UnsupportedOperationException("替身不扔东西");
    }

    @Override public Set<Integer> pickable(TickContext context, Target target) {
        return Set.of();
    }

    /** 扔过来的东西已经自己进了包时进的是几个什么；测试定。 */
    Optional<String> alreadyPicked = Optional.empty();

    @Override public Optional<String> alreadyPickedUp(TickContext context, Target target) {
        return alreadyPicked;
    }

    /** 捡东西收场记了几回。 */
    int pickUpSettled;

    @Override public void pickUpSettled() {
        pickUpSettled++;
    }

    @Override public Action pickUp(Set<Integer> items) {
        throw new UnsupportedOperationException("替身不捡东西");
    }

    @Override public void answered(UUID who) {
        answered.add(who);
    }

    @Override public TurnsHead head(TickContext context) {
        return head;
    }

    @Override public Aim aimAt(TickContext context, int entityId, AimPoint point) {
        return present ? face : null;
    }

    @Override public void press(TickContext context, boolean sneak, boolean jump) {
        sneakHeld = sneak;
        jumpHeld = jump;
        if (sneak) pressed.add("sneak");
        if (jump) pressed.add("jump");
    }

    @Override public boolean crouched(TickContext context) {
        return crouchedNow;
    }

    @Override public boolean onGround(TickContext context) {
        return airTicks == 0;
    }

    @Override public boolean aimsAtNothing() {
        return head.pitch <= clearAbove;
    }

    @Override public int aimedEntity() {
        return ALEX_PARTNER.entityId();
    }

    @Override public boolean pressAttack(TickContext context) {
        attacks++;
        return true;
    }
}
