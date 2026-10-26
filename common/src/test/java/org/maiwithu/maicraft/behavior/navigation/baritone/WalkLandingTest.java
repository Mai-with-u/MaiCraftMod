// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.behavior.landing.CatchesFall;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 落地救援的收场记账：救援做完要把经过写进走路的结果，同一格先放后收算收回、
 * 只放没收的记进留在原地的方块；接不住的如实写"没成"，收过一次的账不再写第二遍。
 * 有计划的一跳认出发点按寻路的脚位，站在矮方块上也认得出。
 */
class WalkLandingTest {

    private static final BlockState 空气;
    private static final BlockState 石头;

    static {
        // 方块常量要在引导原版注册表之后再取：单独跑这一个测试类时，没有别的测试先替它引导过。
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        空气 = Blocks.AIR.defaultBlockState();
        石头 = Blocks.STONE.defaultBlockState();
    }

    /** 救援替身：按脚本回答成败，记下改过的格子与收手时收到的话。 */
    private static class FakeRescue implements CatchesFall {
        final List<Change> changes = new ArrayList<>();
        final boolean failed;
        final List<String> stoppedWith = new ArrayList<>();

        FakeRescue(boolean failed) {
            this.failed = failed;
        }

        void placed(BlockPos cell) {
            changes.add(new Change(cell, 空气, 石头));
        }

        void collected(BlockPos cell) {
            changes.add(new Change(cell, 石头, 空气));
        }

        @Override public boolean prepare(PlayerContext context) { return true; }
        @Override public void tick(PlayerContext context) {}
        @Override public boolean complete() { return true; }
        @Override public boolean failed() { return failed; }
        @Override public String detail() { return "测试接住"; }
        @Override public String itemId() { return "minecraft:water_bucket"; }
        @Override public BlockPos landingFeet() { return new BlockPos(1, 63, 2); }
        @Override public boolean touchedDown(PlayerContext context) { return true; }
        @Override public PlayerInput.Movement steering(PlayerContext context, float yaw) {
            return PlayerInput.Movement.STOPPED;
        }
        @Override public void stop(PlayerContext context, String reason) { stoppedWith.add(reason); }
        @Override public List<Change> drainChanges() {
            List<Change> drained = new ArrayList<>(changes);
            changes.clear();
            return drained;
        }
    }

    @Test
    void 收场时写下经过_同一格先放后收算收回() {
        WalkLanding landing = new WalkLanding();
        FakeRescue rescue = new FakeRescue(false);
        BlockPos 放了又收 = new BlockPos(1, 63, 2);
        BlockPos 只放没收 = new BlockPos(4, 63, 5);
        rescue.placed(放了又收);
        rescue.collected(放了又收);
        rescue.placed(只放没收);
        landing.attach(rescue);
        assertFalse(landing.inProgress(), "替身救援已收尾，不算还在进行");
        landing.stop(null, "走路结束");

        assertEquals(List.of(只放没收), landing.leftInPlace(), "放收各一次的格子不算留在原地");
        assertEquals(1, landing.notes().size());
        assertTrue(landing.notes().getFirst().contains("落地救援（minecraft:water_bucket，落点 1, 63, 2）：测试接住"),
                landing.notes().getFirst());
        assertFalse(landing.inProgress(), "收过场就不再算进行中");
        // 再停一次不写第二遍账。
        landing.stop(null, "又停一次");
        assertEquals(1, landing.notes().size());
    }

    @Test
    void 没接住的救援如实记成没成() {
        WalkLanding landing = new WalkLanding();
        FakeRescue rescue = new FakeRescue(true);
        rescue.placed(new BlockPos(2, 63, 3));
        landing.attach(rescue);
        landing.stop(null, "走路结束");
        assertTrue(landing.notes().getFirst().startsWith("落地救援没成"), landing.notes().getFirst());
        assertEquals(List.of(new BlockPos(2, 63, 3)), landing.leftInPlace());
    }

    @Test
    void 还没收场的救援算在推进_不能把走路超时丢下它() {
        WalkLanding landing = new WalkLanding();
        FakeRescue rescue = new FakeRescue(false) {
            @Override public boolean complete() { return false; }
        };
        assertFalse(landing.inProgress(), "还没挂上救援");
        landing.attach(rescue);
        assertTrue(landing.inProgress());
    }

    @Test
    void 站在灵魂沙半砖耕地上也认得出站在下落出发格上() {
        // 下落这一步从 (x, 65, 0) 出发：人陷在灵魂沙里、站在下半砖或耕地上时 y 比 65 低一点，照样算站在出发格上，
        // 先站定备好落地的东西再跳；向下取整会读成脚下那块方块，备料这一步就被跳过了。
        World world = new World();
        world.put(new BlockPos(0, 64, 0), Blocks.SOUL_SAND.defaultBlockState());
        world.put(new BlockPos(2, 64, 0), Blocks.STONE_SLAB.defaultBlockState());
        world.put(new BlockPos(4, 64, 0), Blocks.FARMLAND.defaultBlockState());
        world.put(new BlockPos(6, 64, 0), 石头);
        assertTrue(WalkLanding.standsAt(world, new Vec3(0.5, 64.875, 0.5), new BlockPos(0, 65, 0)), "灵魂沙");
        assertTrue(WalkLanding.standsAt(world, new Vec3(2.5, 64.5, 0.5), new BlockPos(2, 65, 0)), "下半砖");
        assertTrue(WalkLanding.standsAt(world, new Vec3(4.5, 64.9375, 0.5), new BlockPos(4, 65, 0)), "耕地");
        assertTrue(WalkLanding.standsAt(world, new Vec3(6.5, 65.0, 0.5), new BlockPos(6, 65, 0)), "石头");
        assertFalse(WalkLanding.standsAt(world, new Vec3(6.5, 65.0, 0.5), new BlockPos(7, 65, 0)), "站在旁边一格不算");
    }

    /** 替身世界：没摆的格子都是空气。 */
    private static final class World implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        void put(BlockPos pos, BlockState state) {
            blocks.put(pos.immutable(), state);
        }

        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, 空气);
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
    }

    @Test
    void 没有救援时停手是空操作() {
        WalkLanding landing = new WalkLanding();
        landing.stop(null, "走路结束");
        assertEquals(List.of(), landing.notes());
        assertEquals(List.of(), landing.leftInPlace());
    }
}
