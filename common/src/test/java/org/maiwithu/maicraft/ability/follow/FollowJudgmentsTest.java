// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.follow;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 跟随的几样纯判断：他脚下站得住的那一格、我挡没挡他的道、让到哪、他走的门通到哪。 */
class FollowJudgmentsTest {

    /** 地面在 y=63（站在 y=64），其余是空气；water 里的格子穿不过也站不住。 */
    private static GroundBelow.Cells flatGround(Set<BlockPos> water) {
        return new GroundBelow.Cells() {
            @Override public boolean standsOn(BlockPos cell) {
                return cell.getY() == 63 && !water.contains(cell);
            }

            @Override public boolean passes(BlockPos cell) {
                return cell.getY() > 63 && !water.contains(cell);
            }
        };
    }

    @Test
    void groundIsUnderFeetOrBelowAnAirborneTarget() {
        BlockPos feet = new BlockPos(5, 64, 5);
        assertEquals(feet, GroundBelow.of(feet, true, flatGround(Set.of())), "站在地上就是脚下");
        // 他飞在 20 格高：往下找到地面上那一格。
        assertEquals(feet, GroundBelow.of(new BlockPos(5, 84, 5), false, flatGround(Set.of())));
        // 下面是水面：停在水面上，不往水里找。
        BlockPos water = new BlockPos(5, 70, 5);
        assertEquals(new BlockPos(5, 71, 5), GroundBelow.of(new BlockPos(5, 75, 5), false, flatGround(Set.of(water))));
    }

    @Test
    void groundScanStopsAfterSixtyFourCells() {
        BlockPos high = new BlockPos(0, 300, 0);
        assertEquals(high.below(GroundBelow.SCAN), GroundBelow.of(high, false, flatGround(Set.of())));
    }

    @Test
    void inTheWayOnlyWhenHeIsComingOrSqueezing() {
        Vec3 him = new Vec3(0.5, 64, 0.5);
        // 他站着、我在 1.2 格外：不让（走过来说话、递东西）。
        assertFalse(AsideSpot.inTheWay(new Vec3(1.7, 64, 0.5), him, 0, 0));
        // 贴到 0.5 格：站着也让。
        assertTrue(AsideSpot.inTheWay(new Vec3(1.0, 64, 0.5), him, 0, 0));
        // 他朝东走、我在他正前方 2 格：挡道。
        assertTrue(AsideSpot.inTheWay(new Vec3(2.5, 64, 0.5), him, 0.2, 0));
        // 他朝东走、我在他身后：不挡。
        assertFalse(AsideSpot.inTheWay(new Vec3(-1.5, 64, 0.5), him, 0.2, 0));
        // 他朝东走、我在路线旁边 2 格：不挡。
        assertFalse(AsideSpot.inTheWay(new Vec3(2.5, 64, 2.5), him, 0.2, 0));
        // 隔着一层楼：不让。
        assertFalse(AsideSpot.inTheWay(new Vec3(0.8, 68, 0.5), him, 0.2, 0));
    }

    @Test
    void asideSpotPrefersMySideThenTheOtherThenBehind() {
        Vec3 him = new Vec3(0.5, 64, 0.5);
        // 他朝东（+x）走，我在他前方偏南（+z）：先挑南边那一侧。
        Vec3 me = new Vec3(2.5, 64, 1.0);
        Optional<BlockPos> south = AsideSpot.choose(me, him, 1, 0, 3, cell -> true);
        assertTrue(south.isPresent());
        assertTrue(south.get().getZ() > 0, "先让到我已经在的那一侧");
        // 南边全站不住：挑北边。
        Optional<BlockPos> north = AsideSpot.choose(me, him, 1, 0, 3, cell -> cell.getZ() < 0);
        assertTrue(north.get().getZ() < 0);
        // 两侧都站不住：挑他身后。
        Optional<BlockPos> behind = AsideSpot.choose(me, him, 1, 0, 3, cell -> cell.getZ() == 0 && cell.getX() < 0);
        assertTrue(behind.get().getX() < 0, "两侧都没有就让到他身后");
        // 都站不住：原地不动。
        assertTrue(AsideSpot.choose(me, him, 1, 0, 3, cell -> false).isEmpty());
    }

    @Test
    void portalLeadsWhereHeWent() {
        assertEquals("minecraft:the_nether", PortalTrail.destination("minecraft:overworld", "minecraft:nether_portal"));
        assertEquals("minecraft:overworld", PortalTrail.destination("minecraft:the_nether", "minecraft:nether_portal"));
        assertEquals("minecraft:the_end", PortalTrail.destination("minecraft:overworld", "minecraft:end_portal"));
        assertNull(PortalTrail.destination("minecraft:the_end", "minecraft:end_portal"), "出口门送人回各自的重生点，跟不过去");
    }

    @Test
    void dangerousWaterBlocksDrownedPoolAndRemembersRecentCasualty() {
        DangerousWater water = new DangerousWater();
        Vec3 shore = new Vec3(-70, 63, -380);
        Vec3 pool = new Vec3(-82, 61, -392);
        // 目标泡在有溺尸的水里：判定为危险水域，留在岸上不跟下水。
        Optional<DangerousWater.Hazard> liveDrowned = water.evaluate("minecraft:overworld", 100,
                shore, false, pool, true,
                List.of(new DangerousWater.WaterThreat("minecraft:drowned", new Vec3(-85, 61, -390))));
        assertTrue(liveDrowned.isPresent());
        assertTrue(liveDrowned.get().reason().contains("minecraft:drowned"));
        assertFalse(liveDrowned.get().meInWater());

        // 两人都在陆地上、水怪在远侧不挡连线：照常陆路跟，不误拦。
        assertTrue(water.evaluate("minecraft:overworld", 100,
                shore, false, new Vec3(-65, 63, -375), false,
                List.of(new DangerousWater.WaterThreat("minecraft:drowned", new Vec3(-95, 61, -405)))).isEmpty());

        // 刚在这片水里死过一次：重生后 44 秒（880 刻）回来，哪怕还没刷出溺尸视野，目标又下同一片水也不再跟着跳进去。
        water.recordCasualty("minecraft:overworld", pool, 200);
        Optional<DangerousWater.Hazard> remembered = water.evaluate("minecraft:overworld", 200 + 880,
                shore, false, new Vec3(-87, 62, -387), true, List.of());
        assertTrue(remembered.isPresent());
        assertTrue(remembered.get().reason().contains("刚在这片水里遇害过"));

        // 过了 3 分钟警戒期后自动解除。
        assertTrue(water.evaluate("minecraft:overworld", 200 + DangerousWater.CASUALTY_MEMORY_TICKS + 1,
                shore, false, new Vec3(-87, 62, -387), true, List.of()).isEmpty());
    }

    @Test
    void shoreSpotPicksDryLandAwayFromHazard() {
        DangerousWater.Hazard hazard = new DangerousWater.Hazard(
                new Vec3(-82, 61, -392), "水里有敌对生物 minecraft:drowned", true);
        Vec3 meInPool = new Vec3(-78.5, 62, -388.5);
        Vec3 himInPool = new Vec3(-84.5, 61, -392.5);
        // x >= -75 是干燥河岸，其余是水：应挑出离自己最近且离开危险中心的干地岸格。
        Optional<BlockPos> spot = DangerousWater.chooseShoreSpot(meInPool, himInPool, hazard,
                cell -> cell.getY() == 63 && cell.getX() >= -75);
        assertTrue(spot.isPresent());
        assertEquals(63, spot.get().getY());
        assertTrue(spot.get().getX() >= -75);
    }
}
