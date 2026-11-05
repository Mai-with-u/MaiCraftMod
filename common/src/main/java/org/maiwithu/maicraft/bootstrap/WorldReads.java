// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.Objects;

import org.maiwithu.maicraft.behavior.lighting.LightingWorld;
import org.maiwithu.maicraft.behavior.perception.FacilityKinds;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.perception.bodylanguage.NonverbalExchange;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.game.world.KeepInventoryRule;
import org.maiwithu.maicraft.game.world.ReadsLightLevels;

/**
 * 世界与观察的读端：感知场景、世界记忆、方块扫描、设施分类、亮度与照明现场、死亡不掉落、别的玩家冲角色做的肢体动作——
 * 角色周围是什么、记得什么，都从这一组读。新加一个读世界事实的读端，加在这里并传入所在组即可。
 */
record WorldReads(
        Scene scene,
        WorldMemory memory,
        BlockScanService blockScans,
        /** 设施分类：容器与工作设施按这个世界同步过来的方块标签认。 */
        FacilityKinds facilities,
        ReadsLightLevels lightLevels,
        LightingWorld lightingWorld,
        /** 死亡不掉落：单人读本机，连服务器读服务端报的；寻死与 observe(self) 读同一份。 */
        KeepInventoryRule keepInventory,
        /** 和别的玩家之间肢体动作的你来我往：最近谁冲角色做过、谁扔了东西过来，回过他了告诉回头看。 */
        NonverbalExchange nonverbal) {

    WorldReads {
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(memory, "memory");
        Objects.requireNonNull(blockScans, "blockScans");
        Objects.requireNonNull(facilities, "facilities");
        Objects.requireNonNull(lightLevels, "lightLevels");
        Objects.requireNonNull(lightingWorld, "lightingWorld");
        Objects.requireNonNull(keepInventory, "keepInventory");
        Objects.requireNonNull(nonverbal, "nonverbal");
    }
}
