// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.List;
import java.util.Objects;

import net.minecraft.world.level.Level;

import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.BlockApplication;
import org.maiwithu.maicraft.behavior.acquire.spi.BlockApplications;

/**
 * 联动模组交来的"放下基底方块、拿材料右键改造"配方表，登记表给它包的一层：
 * 模组停用后一条配方都不给，读配方时碰到模组接口对不上也在这里收住。途径名在登记时定下，照常回答。
 */
final class CompatBlockApplications implements BlockApplications {

    private final CompatModule module;
    private final BlockApplications applications;

    CompatBlockApplications(CompatModule module, BlockApplications applications) {
        this.module = Objects.requireNonNull(module, "module");
        this.applications = Objects.requireNonNull(applications, "applications");
    }

    @Override
    public AcquireVia via() {
        return applications.via();
    }

    @Override
    public List<BlockApplication> recipes(Level level) {
        if (!module.active()) return List.of();
        try {
            return applications.recipes(level);
        } catch (ModApiMismatch broken) {
            return List.of();
        }
    }
}
