// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.landing;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.attributes.Attributes;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 在空中现取落地料：推进一次取物动作，并卡住两个截止——取物本身最多二百四十刻，
 * 离落点够得着只剩十来刻时不再取（取到了也来不及拿在手上、对准放下去）。
 *
 * <p>落点用正下方最先碰到的支撑面估：坠落中只看这一柱，换柱子是救援接手之后的事。
 * 取物动作由联动模组实现（拿出无线终端、打开、取一件、关上），这里只负责在掉光之前喊停。
 */
public final class LandingFetch implements Action {

    /** 取物最多花多久（刻）：坠落的时间比这短得多，真正的截止是离落点还剩几刻。 */
    private static final int MAX_FETCH_TICKS = 240;
    /** 离够得着落点只剩这么多刻就不再取：留给拿在手上、对准、放下去。 */
    private static final int ACTION_RESERVE_TICKS = 12;

    private final Action supply;
    private final String source;
    private final String itemName;
    private boolean failed;
    private String detail;
    private long startedTick = Long.MIN_VALUE;

    public LandingFetch(Action supply, String source, String itemName) {
        this.supply = supply;
        this.source = source;
        this.itemName = itemName;
        this.detail = "正在从" + source + "取" + itemName;
    }

    @Override
    public ActionStatus tick(TickContext tick) {
        if (failed) return ActionStatus.failed(Problem.of(Problem.Kind.DANGER, detail));
        PlayerContext context = tick.player();
        LocalPlayer player = context == null ? null : context.localPlayer();
        long now = context == null ? tick.gameTick() : context.clientTick();
        if (startedTick == Long.MIN_VALUE) startedTick = now;
        // 已经落地或落进水里：这一掉结束了，取到了也没机会放。
        if (player != null && (player.onGround() || player.isInWater())) {
            return fail("落地了，来不及从" + source + "取" + itemName);
        }
        if (now - startedTick > MAX_FETCH_TICKS) {
            return fail("从" + source + "取" + itemName + "超过十二秒还没取到");
        }
        ActionStatus status = supply.tick(tick);
        if (status instanceof ActionStatus.Failed failure) {
            return fail(failure.problem().message());
        }
        if (status instanceof ActionStatus.Done) return status;
        if (player != null && ticksToImpact(context, player) <= ACTION_RESERVE_TICKS) {
            return fail("离落点太近，来不及从" + source + "取" + itemName + "了，落地伤害由游戏结算");
        }
        return status;
    }

    // 脚到“够得着落点那一面”的高度还要几刻：按原版重力和当前下落速度往前推，最多推两百刻。
    private static int ticksToImpact(PlayerContext context, LocalPlayer player) {
        BlockPos support = EmergencyLanding.groundBelow(player);
        if (support == null || context.level() == null) return Integer.MAX_VALUE;
        double surface = support.getY() + AirSteering.supportHeight(context.level(), support);
        if (!Double.isFinite(surface)) surface = support.getY() + 1;
        double reach = player.blockInteractionRange();
        double actionHeight = surface + Math.max(0, Math.sqrt(Math.max(0, reach * reach - .5)) - player.getEyeHeight());
        double y = player.getY();
        double velocity = player.getDeltaMovement().y;
        double gravity = player.getAttributeValue(Attributes.GRAVITY);
        for (int ticks = 0; ticks < 200; ticks++) {
            if (y <= actionHeight) return ticks;
            y += velocity;
            velocity = (velocity - gravity) * .98;
        }
        return 200;
    }

    private ActionStatus fail(String reason) {
        failed = true;
        detail = reason;
        return ActionStatus.failed(Problem.of(Problem.Kind.DANGER, reason));
    }

    @Override public void pause() { supply.pause(); }

    @Override public void close() { supply.close(); }

    @Override public Interruptibility interruptibility() { return Interruptibility.UNSAFE_TO_STOP; }

    @Override public String describe() { return detail; }
}
