// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.inventory.ItemsToKeep;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.behavior.travel.TravelFakes;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 战斗寻路的生产实现：走位交给走到，能不能打断与先到安全处再停都按走路说。 */
class LiveBattleMovesTest {

    @BeforeAll
    static void bootMinecraft() {
        // 战斗的手（举盾、射箭）建起来要碰物品注册表：注册表引导过才能碰。
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 寻路悬在半空停不得_先停稳转给走路_落地才算停稳() {
        TravelFakes.ScriptedWalk leg = new TravelFakes.ScriptedWalk(
                WalkReport.onTheWay(new BlockPos(1, 65, 0), false),
                WalkReport.stopped(new BlockPos(1, 64, 0), false));
        leg.airborne = true;
        Action walk = new LiveBattleMoves((target, permit) -> leg, null, ModRangedWeapons.none(), ItemsToKeep.NOTHING)
                .walk(GoalCompiler.near(new BlockPos(12, 64, 0), 2.0));
        assertFalse(walk.stopSafely(), "还没开走：现在就能停");
        walk.tick(TickContext.of(1, null));
        assertEquals(Interruptibility.UNSAFE_TO_STOP, walk.interruptibility(), "悬在半空停不得");
        assertTrue(walk.stopSafely(), "悬在半空，要先落地");
        assertEquals(1, leg.safeStops, "先停稳转给了走路");
        assertFalse(walk.stoppedSafely(), "还没落地");
        walk.tick(TickContext.of(2, null));
        assertTrue(walk.stoppedSafely(), "落地站稳了");

        TravelFakes.ScriptedWalk ground = new TravelFakes.ScriptedWalk(
                WalkReport.onTheWay(new BlockPos(1, 64, 0), false));
        Action onGround = new LiveBattleMoves((target, permit) -> ground, null, ModRangedWeapons.none(), ItemsToKeep.NOTHING)
                .walk(GoalCompiler.near(new BlockPos(12, 64, 0), 2.0));
        onGround.tick(TickContext.of(1, null));
        assertEquals(Interruptibility.WORKING, onGround.interruptibility(), "站在地上走着是正常干活");
        assertFalse(onGround.stopSafely(), "站在地上走着：现在就能停");
    }
}
