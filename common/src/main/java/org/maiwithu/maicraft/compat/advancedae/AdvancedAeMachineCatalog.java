// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.advancedae;

import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.ability.machine.spi.MachineRole;

/**
 * 高级 AE（AdvancedAE）当前认得的机器目录：编号就是主方块的注册 ID。
 * 当前登记核心批量加工机器反应仓（advanced_ae:reaction_chamber）。
 */
public final class AdvancedAeMachineCatalog {

    /** 反应仓主方块注册 ID。 */
    public static final String REACTION_CHAMBER = "advanced_ae:reaction_chamber";
    /** 反应仓主菜单注册 ID。 */
    public static final String REACTION_CHAMBER_MENU = "advanced_ae:reaction_chamber";

    /**
     * 一种高级 AE 机器。
     *
     * @param id     机器编号，即主方块注册 ID
     * @param name   给 LLM 与玩家看的中文名
     * @param role   机器职责
     * @param blocks 归属该机器的方块注册 ID 列表
     */
    public record Kind(String id, String name, MachineRole role, List<String> blocks) {
        public Kind {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(role, "role");
            blocks = List.copyOf(blocks);
        }
    }

    private AdvancedAeMachineCatalog() {
    }

    /** 认得的全部高级 AE 机器列表。 */
    public static List<Kind> kinds() {
        return List.of(new Kind(REACTION_CHAMBER, "反应仓", MachineRole.PROCESSING, List.of(REACTION_CHAMBER)));
    }
}
