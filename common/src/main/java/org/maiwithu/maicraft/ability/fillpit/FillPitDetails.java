// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fillpit;

import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 填坑能力的结果细节：填平了几处坑、共放下几块回填方块、每处坑的坐标深度与填平状态，以及多余废料是否中途用尽。
 */
record FillPitDetails(
        int pitsFilled,
        int blocksPlaced,
        List<FilledPit> pits,
        Boolean fillerExhausted) implements ResultDetails {

    /** 处理过的一处地面坑记录。 */
    record FilledPit(WorldPosition position, int depth, int cells, int placed, boolean filled) {
        FilledPit {
            Objects.requireNonNull(position, "position");
        }
    }

    FillPitDetails {
        pits = List.copyOf(pits);
    }
}
