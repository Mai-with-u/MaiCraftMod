// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.landing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.game.player.FallDamage;

/**
 * 放船那一下的瞄准核对：准星要正落在落点下面那一格的顶面上、点中的位置就在落点那一格里、
 * 高度对得上放船的那一点；点到别的格、别的面、偏出格子或高度差太多都算没指对。
 */
class BoatAimCheckTest {

    /** 落点在 (0,64,0)，船放在落点下面一格顶面上的那一点。 */
    private static final BlockPos LANDING = new BlockPos(0, 64, 0);
    private static final Vec3 SPAWN = new Vec3(0.5, 63.56, 0.5);
    /** 射线正点在放船的那一点上。 */
    private static final BlockHitResult ON_TARGET = new BlockHitResult(SPAWN, Direction.UP, LANDING.below(), false);

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static BoatPlan plan() {
        return new BoatPlan(LANDING, SPAWN, null, null, true);
    }

    /** 替身现场：射线指到哪由测试说了算。 */
    private static LandingScene scene(BlockHitResult hit) {
        return new LandingScene() {
            @Override public Vec3 eyePosition() { return Vec3.ZERO; }
            @Override public double blockReach() { return 4.5; }
            @Override public double placementHeight(LandingPlan plan) { return 0; }
            @Override public double feetY() { return 0; }
            @Override public boolean onGround() { return false; }
            @Override public boolean sneaking() { return false; }
            @Override public FallDamage fallSample() { return null; }
            @Override public BlockHitResult sightHit() { return hit; }
            @Override public BlockHitResult sightHitThroughFluids() { return hit; }
            @Override public boolean canPlaceAid(LandingAid aid, BlockPos cell) { return false; }
            @Override public boolean carries(Item item) { return false; }
        };
    }

    @Test
    void aimOnTheFloorTopFaceInsideTheLandingCellIsAimed() {
        var verdict = BoatAimCheck.judge(scene(ON_TARGET), plan());
        assertTrue(verdict.aimed());
        assertEquals(SPAWN, verdict.at());
    }

    @Test
    void anotherCellAnotherFaceOrOutsideTheCellIsMisaligned() {
        // 点到旁边那一格的顶面：不是放船的那一格。
        var otherCell = new BlockHitResult(SPAWN.add(1, 0, 0), Direction.UP, new BlockPos(1, 63, 0), false);
        assertFalse(BoatAimCheck.judge(scene(otherCell), plan()).aimed());
        // 点到落点那一格的侧面：船放不上去。
        var sideFace = new BlockHitResult(new Vec3(1.0, 63.5, 0.5), Direction.EAST, LANDING.below(), false);
        assertFalse(BoatAimCheck.judge(scene(sideFace), plan()).aimed());
        // 点中的位置偏出落点那一格。
        var outside = new BlockHitResult(new Vec3(1.5, SPAWN.y, 0.5), Direction.UP, new BlockPos(1, 63, 0), false);
        assertFalse(BoatAimCheck.judge(scene(outside), plan()).aimed());
        // 什么都没点到。
        var miss = BlockHitResult.miss(Vec3.ZERO, Direction.UP, LANDING);
        assertFalse(BoatAimCheck.judge(scene(miss), plan()).aimed());
    }

    @Test
    void heightOffByMoreThanAHairIsMisaligned() {
        // 点的位置比放船的那一点高出半格：虽然都在落点那一格的顶面方向上，放的点不对。
        var tooHigh = new BlockHitResult(new Vec3(0.5, SPAWN.y + 0.5, 0.5), Direction.UP, LANDING.below(), false);
        var verdict = BoatAimCheck.judge(scene(tooHigh), plan());
        assertFalse(verdict.aimed());
        assertNull(verdict.at());
    }
}
