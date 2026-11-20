// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * AE2 陨石罗盘反透视与 3 种指针状态换算离线测试：
 * 验证未携带罗盘时对 CompassManager 零调用，以及携带时按水平距离 > 6 / <= 6 / 无目标
 * 精确换算为 pointing（含八向方位）、nearby_spinning 与 searching_spinning 且绝不透出精确距离与 Y 坐标。
 */
class MeteoriteCompassTest {

    @BeforeAll
    static void 引导注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 未携带陨石罗盘时绝不调用罗盘查询接口() {
        AtomicInteger calls = new AtomicInteger();
        Ae2ToolReads reads = new Ae2ToolReads() {
            @Override public Optional<BlockPos> closestMeteorite(BlockPos playerPos) {
                calls.incrementAndGet();
                return Optional.of(new BlockPos(100, 0, -50));
            }
        };
        MeteoriteCompassSight sight = new MeteoriteCompassSight(ToolTestFakes.compat(), reads);

        Optional<JsonObject> absent = sight.buildSummary(false, new Vec3(0, 64, 0), new BlockPos(0, 64, 0));
        assertTrue(absent.isEmpty());
        assertEquals(0, calls.get(), "未携带陨石罗盘时必须零调用 CompassManager");
    }

    @Test
    void 携带罗盘时三种指针状态严格对齐游戏可见表现且不透出精确距离与高度() {
        AtomicInteger calls = new AtomicInteger();
        BlockPos[] currentTarget = {new BlockPos(0, 0, -40)};
        Ae2ToolReads reads = new Ae2ToolReads() {
            @Override public Optional<BlockPos> closestMeteorite(BlockPos playerPos) {
                calls.incrementAndGet();
                return Optional.ofNullable(currentTarget[0]);
            }
        };
        MeteoriteCompassSight sight = new MeteoriteCompassSight(ToolTestFakes.compat(), reads);
        Vec3 playerPos = new Vec3(0.5, 72.0, 0.5);
        BlockPos blockPos = new BlockPos(0, 72, 0);

        // 水平距离 > 6 格：pointing + 八向方位，绝无 distance / y 字段。
        JsonObject pointing = sight.buildSummary(true, playerPos, blockPos).orElseThrow();
        assertEquals("pointing", pointing.get("status").getAsString());
        assertEquals("north", pointing.get("direction").getAsString());
        assertEquals(2, pointing.size(), "pointing 摘要只含 status 与 direction");
        assertFalse(pointing.has("distance"));
        assertFalse(pointing.has("y"));
        assertEquals(1, calls.get());

        // 水平距离 <= 6 格：nearby_spinning，不输出 direction 与距离。
        currentTarget[0] = new BlockPos(4, 0, 4);
        JsonObject nearby = sight.buildSummary(true, playerPos, blockPos).orElseThrow();
        assertEquals("nearby_spinning", nearby.get("status").getAsString());
        assertFalse(nearby.has("direction"));
        assertEquals(1, nearby.size());

        // 刚好 6 格边界仍为 nearby_spinning（源码为 distanceSq > 6 * 6 才 pointing）。
        currentTarget[0] = new BlockPos(6, 0, 0);
        assertEquals("nearby_spinning",
                CompassReading.from(new Vec3(0, 64, 0), currentTarget[0]).status());

        // 无目标或查询中：searching_spinning，不输出 direction。
        currentTarget[0] = null;
        JsonObject searching = sight.buildSummary(true, playerPos, blockPos).orElseThrow();
        assertEquals("searching_spinning", searching.get("status").getAsString());
        assertFalse(searching.has("direction"));
        assertEquals(1, searching.size());
    }

    @Test
    void 八向水平方位覆盖全部八个方向() {
        Vec3 origin = new Vec3(0, 64, 0);
        assertEquals("north", CompassReading.from(origin, new BlockPos(0, 0, -20)).direction());
        assertEquals("northeast", CompassReading.from(origin, new BlockPos(20, 0, -20)).direction());
        assertEquals("east", CompassReading.from(origin, new BlockPos(20, 0, 0)).direction());
        assertEquals("southeast", CompassReading.from(origin, new BlockPos(20, 0, 20)).direction());
        assertEquals("south", CompassReading.from(origin, new BlockPos(0, 0, 20)).direction());
        assertEquals("southwest", CompassReading.from(origin, new BlockPos(-20, 0, 20)).direction());
        assertEquals("west", CompassReading.from(origin, new BlockPos(-20, 0, 0)).direction());
        assertEquals("northwest", CompassReading.from(origin, new BlockPos(-20, 0, -20)).direction());
    }
}
