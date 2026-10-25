// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
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
 */
class WalkLandingTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final BlockState 空气 = Blocks.AIR.defaultBlockState();
    private static final BlockState 石头 = Blocks.STONE.defaultBlockState();

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
    void 没有救援时停手是空操作() {
        WalkLanding landing = new WalkLanding();
        landing.stop(null, "走路结束");
        assertEquals(List.of(), landing.notes());
        assertEquals(List.of(), landing.leftInPlace());
    }
}
