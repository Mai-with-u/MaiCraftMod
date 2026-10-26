// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.Objects;

import org.maiwithu.maicraft.behavior.lighting.LightingWorld;
import org.maiwithu.maicraft.behavior.perception.FacilityKinds;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.game.world.ReadsLightLevels;

/**
 * 世界与观察的读端：感知场景、世界记忆、方块扫描、设施分类、亮度与照明现场——
 * 角色周围是什么、记得什么，都从这一组读。新加一个读世界事实的读端，加在这里并传入所在组即可。
 */
record WorldReads(
        Scene scene,
        WorldMemory memory,
        BlockScanService blockScans,
        /** 设施分类：容器与工作设施按这个世界同步过来的方块标签认。 */
        FacilityKinds facilities,
        ReadsLightLevels lightLevels,
        LightingWorld lightingWorld) {

    WorldReads {
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(memory, "memory");
        Objects.requireNonNull(blockScans, "blockScans");
        Objects.requireNonNull(facilities, "facilities");
        Objects.requireNonNull(lightLevels, "lightLevels");
        Objects.requireNonNull(lightingWorld, "lightingWorld");
    }
}
