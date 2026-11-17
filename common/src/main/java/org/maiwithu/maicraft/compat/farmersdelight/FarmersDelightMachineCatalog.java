// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.farmersdelight;

import java.util.List;

import org.maiwithu.maicraft.ability.machine.spi.MachineRole;

/**
 * 农夫乐事里认得的机器：编号是方块的注册 ID。目前只有厨锅一种。
 */
final class FarmersDelightMachineCatalog {

    /** 一种机器。 */
    record Kind(String id, String name, MachineRole role, List<String> blocks) {
    }

    private FarmersDelightMachineCatalog() {
    }

    /** 认得的全部机器。 */
    static List<Kind> kinds() {
        return List.of(new Kind("farmersdelight:cooking_pot", "农夫乐事厨锅", MachineRole.PROCESSING,
                List.of("farmersdelight:cooking_pot")));
    }
}
