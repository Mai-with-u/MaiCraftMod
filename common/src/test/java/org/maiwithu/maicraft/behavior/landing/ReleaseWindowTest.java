// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.landing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * 出手窗口：这一刻放不放落地的东西。掉得太高干草也救不了不放、东西没到手上不放、
 * 方块类没按住潜行等一等、落点被占被保护不放；还够不着与对不准分得清，点对了才放；
 * 放水只开窗口，射线原样交出去。前提按顺序核，先拒绝的先说。
 */
class ReleaseWindowTest {

    private static final BlockPos FEET = new BlockPos(0, 64, 0);
    /** 普通落点的摔落取样：满血、没缓冲、已经在掉的那段不算。 */
    private static final FallDamage HEALTHY = new FallDamage(20, 0, 3, 1, 0, 0, 0, 0, 0.08, 0, false);

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 按脚本回答的替身现场：判断读到什么，测试里写得明明白白。 */
    private static final class FakeScene implements LandingScene {
        Vec3 eye = new Vec3(0.5, 66.0, 0.5);
        double reach = 4.5;
        double placementHeight = 63.0;
        double feetY = 68.0;
        boolean onGround = false;
        boolean sneaking = true;
        FallDamage fallSample = HEALTHY;
        BlockHitResult sightHit = miss();
        boolean placeable = true;
        boolean carrying = true;

        @Override public Vec3 eyePosition() { return eye; }
        @Override public double blockReach() { return reach; }
        @Override public double placementHeight(LandingPlan plan) { return placementHeight; }
        @Override public double feetY() { return feetY; }
        @Override public boolean onGround() { return onGround; }
        @Override public boolean sneaking() { return sneaking; }
        @Override public FallDamage fallSample() { return fallSample; }
        @Override public BlockHitResult sightHit() { return sightHit; }
        @Override public BlockHitResult sightHitThroughFluids() { return sightHit; }
        @Override public boolean canPlaceAid(LandingAid aid, BlockPos cell) { return placeable; }
        @Override public boolean carries(Item item) { return carrying; }
    }

    /** 一格蛛网要放的计划：东西放进落点那一格，点落点下面一格的顶面。 */
    private static LandingPlan cobwebPlan() {
        return new LandingPlan(LandingAid.COBWEB, FEET, FEET, FEET.below(), Direction.UP, false);
    }

    private static BlockHitResult miss() {
        return BlockHitResult.miss(Vec3.ZERO, Direction.UP, FEET);
    }

    /** 射线正点在要点的那一格那一面。 */
    private static BlockHitResult hitOnTarget() {
        return new BlockHitResult(new Vec3(0.5, 64.0, 0.5), Direction.UP, FEET.below(), false);
    }

    @Test
    void hayThatWouldStillKillIsRefusedBeforeEverythingElse() {
        FakeScene scene = new FakeScene();
        // 脚下离干草顶 46 格：减到五分之一还有七点伤害，只剩两点血救不成，这一跳不跳；东西在不在手上都轮不到看。
        scene.feetY = 110.0;
        scene.fallSample = new FallDamage(2, 0, 3, 1, 0, 0, 0, 0, 0.08, 0, false);
        LandingPlan hay = new LandingPlan(LandingAid.HAY, FEET, FEET.below(), FEET.below(2), Direction.UP, false);
        var refused = ReleaseWindow.judge(scene, hay, false, pos -> false);
        assertEquals(ReleaseWindow.Step.SURVIVAL_REFUSED, refused.step());
        // 同样的落差满血就活得成。
        scene.fallSample = HEALTHY;
        assertEquals(ReleaseWindow.Step.ITEM_NOT_IN_HAND, ReleaseWindow.judge(scene, hay, false, pos -> false).step());
    }

    @Test
    void aidThatIsDamageFreeNeedsNoHayMath() {
        FakeScene scene = new FakeScene();
        // 落进水里是免伤的，再高的落差也不看干草那一笔，直接轮到看东西在不在手上。
        LandingPlan water = new LandingPlan(LandingAid.WATER, FEET, FEET, FEET.below(), Direction.UP, false);
        assertEquals(ReleaseWindow.Step.ITEM_NOT_IN_HAND,
                ReleaseWindow.judge(scene, water, false, pos -> false).step());
    }

    @Test
    void waterBucketMissingAndPresentInHand() {
        FakeScene scene = new FakeScene();
        LandingPlan water = new LandingPlan(LandingAid.WATER, FEET, FEET, FEET.below(), Direction.UP, false);
        // 手上没水：不放，也不看射线。
        assertEquals(ReleaseWindow.Step.ITEM_NOT_IN_HAND,
                ReleaseWindow.judge(scene, water, false, pos -> false).step());
        // 水到了手上：窗口就开，射线交出去由放水那一步认真实落进哪格。
        scene.sightHit = hitOnTarget();
        var verdict = ReleaseWindow.judge(scene, water, true, pos -> false);
        assertEquals(ReleaseWindow.Step.RELEASE, verdict.step());
        assertEquals(FEET.below(), verdict.hit().getBlockPos());
        assertEquals(Direction.UP, verdict.hit().getDirection());
    }

    @Test
    void blockAidWaitsForSneakSoTheClickDoesNotUseTheBlock() {
        FakeScene scene = new FakeScene();
        LandingPlan cobweb = cobwebPlan();
        scene.sightHit = hitOnTarget();
        scene.sneaking = false;
        assertEquals(ReleaseWindow.Step.WAIT_FOR_SNEAK, ReleaseWindow.judge(scene, cobweb, true, pos -> false).step());
        scene.sneaking = true;
        assertEquals(ReleaseWindow.Step.RELEASE, ReleaseWindow.judge(scene, cobweb, true, pos -> false).step());
    }

    @Test
    void protectedOrBlockedLandingCellRefusesTheRelease() {
        FakeScene scene = new FakeScene();
        scene.sightHit = hitOnTarget();
        // 被划进保护。
        assertEquals(ReleaseWindow.Step.CELL_LOST, ReleaseWindow.judge(scene, cobwebPlan(), true, pos -> true).step());
        // 现场放不下。
        scene.placeable = false;
        assertEquals(ReleaseWindow.Step.CELL_LOST,
                ReleaseWindow.judge(scene, cobwebPlan(), true, pos -> false).step());
    }

    @Test
    void outOfReachIsNotCountedAsMisaligned() {
        FakeScene scene = new FakeScene();
        LandingPlan cobweb = cobwebPlan();
        // 还在高处：眼睛离被点的那一面 37 格，射线点不到落点，是够不着不是对不准。
        scene.eye = new Vec3(0.5, 100.0, 0.5);
        scene.sightHit = miss();
        assertEquals(ReleaseWindow.Step.OUT_OF_REACH, ReleaseWindow.judge(scene, cobweb, true, pos -> false).step());
        // 掉近了：眼睛离被点的那一面 3 格，够得着但准星没点在那一面上，这才算对不准。
        scene.eye = new Vec3(0.5, 66.0, 0.5);
        assertEquals(ReleaseWindow.Step.MISALIGNED, ReleaseWindow.judge(scene, cobweb, true, pos -> false).step());
    }

    @Test
    void exactFaceHitReleasesForBlockAids() {
        FakeScene scene = new FakeScene();
        scene.sightHit = hitOnTarget();
        var verdict = ReleaseWindow.judge(scene, cobwebPlan(), true, pos -> false);
        assertEquals(ReleaseWindow.Step.RELEASE, verdict.step());
        assertEquals(FEET.below(), verdict.hit().getBlockPos());
        assertEquals(Direction.UP, verdict.hit().getDirection());
    }

    @Test
    void inReachFollowsEyeHeightAgainstTheClickedFace() {
        FakeScene scene = new FakeScene();
        // 眼睛离被点的那一面 3 格：够得着。
        assertTrue(ReleaseWindow.inReach(scene, cobwebPlan()));
        // 高过触及距离：够不着。
        scene.eye = new Vec3(0.5, 100.0, 0.5);
        assertFalse(ReleaseWindow.inReach(scene, cobwebPlan()));
    }
}
