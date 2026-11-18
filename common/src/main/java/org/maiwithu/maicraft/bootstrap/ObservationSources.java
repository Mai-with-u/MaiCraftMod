// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.Objects;

import org.maiwithu.maicraft.behavior.lighting.LightingWorld;
import org.maiwithu.maicraft.game.SubtitleFeed;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.game.world.ReadsLightLevels;

/**
 * 看世界与量光的读端原料：方块扫描、字幕流、亮度读取与照明现场——
 * 感知场景整理、观察读端与世界读端都从这里拿。新加一路进世界要读的游戏事实，加在这里并传入所在组即可。
 */
record ObservationSources(
        BlockScanService blockScans,
        SubtitleFeed subtitles,
        ReadsLightLevels lightLevels,
        LightingWorld lightingWorld) {

    ObservationSources {
        Objects.requireNonNull(blockScans, "blockScans");
        Objects.requireNonNull(subtitles, "subtitles");
        Objects.requireNonNull(lightLevels, "lightLevels");
        Objects.requireNonNull(lightingWorld, "lightingWorld");
    }
}
