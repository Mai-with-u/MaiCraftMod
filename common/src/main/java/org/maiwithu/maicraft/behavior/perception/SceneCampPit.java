// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.Objects;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 场景里看到的一处营地地面坑：深度不低于两格的竖坑或爆炸弹坑，带观察编号 f#、坑口坐标、深度与占了几格。
 */
public record SceneCampPit(String id, WorldPosition position, int depth, int cells,
                           String direction, String compass, int distance) implements SceneSighting {
    public SceneCampPit {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(compass, "compass");
    }
}
