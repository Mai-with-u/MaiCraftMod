// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.landing;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.game.player.FallDamage;

/**
 * 真实游戏里的落地救援现场：全部读自当前本地玩家与世界，不做任何判断。
 * 射线按原版准星伸到触及距离尽头，摔落取样沿用游戏接口层的取样。
 */
public final class LiveLandingScene implements LandingScene {

    private final LocalPlayer player;

    public LiveLandingScene(LocalPlayer player) {
        this.player = player;
    }

    @Override public Vec3 eyePosition() {
        return player.getEyePosition();
    }

    @Override public double blockReach() {
        return player.blockInteractionRange();
    }

    @Override public double placementHeight(LandingPlan plan) {
        return plan.placementHeight(player.level());
    }

    @Override public double feetY() {
        return player.getY();
    }

    @Override public boolean onGround() {
        return player.onGround();
    }

    @Override public boolean sneaking() {
        return player.isShiftKeyDown();
    }

    @Override public FallDamage fallSample() {
        // 只在客户端线程读取游戏状态；线程由调用方保证。
        return FallDamage.capture(player);
    }

    @Override public BlockHitResult sightHit() {
        return clip(ClipContext.Fluid.NONE);
    }

    @Override public BlockHitResult sightHitThroughFluids() {
        return clip(ClipContext.Fluid.ANY);
    }

    @Override public boolean canPlaceAid(LandingAid aid, BlockPos cell) {
        return LandingOptions.canPlace(aid, player.level(), cell);
    }

    @Override public boolean carries(Item item) {
        if (player.getOffhandItem().is(item)) return true;
        for (int slot = 0; slot < 36; slot++) {
            if (player.getInventory().getItem(slot).is(item)) return true;
        }
        return false;
    }

    // 沿视线的射线：从眼睛伸到触及距离尽头，按方块外形找第一格；放船那一下把液体也算成命中。
    private BlockHitResult clip(ClipContext.Fluid fluids) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1).scale(player.blockInteractionRange()));
        return player.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, fluids, player));
    }
}
