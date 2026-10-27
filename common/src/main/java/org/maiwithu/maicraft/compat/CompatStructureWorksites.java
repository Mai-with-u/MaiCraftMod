// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.ability.machine.spi.StructureWorksites;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.construction.ConstructionServices;
import org.maiwithu.maicraft.behavior.construction.ReadsBlocks;
import org.maiwithu.maicraft.kernel.goal.Permissions;

/**
 * 联动模组交来的物理结构工地，登记表给它包的一层：模组停用后认不出结构，碰到模组接口对不上也按认不出收场；
 * 已经拿到的工地问"在不在、动没动"时模组断了，按结构看不到了回答（施工照"结构没了"停手），不变成任务的内部错误。
 * 施工接缝里的动作经各自的联动入口包过，原样交出去。
 */
final class CompatStructureWorksites implements StructureWorksites {

    private final CompatModule module;
    private final StructureWorksites worksites;

    CompatStructureWorksites(CompatModule module, StructureWorksites worksites) {
        this.module = Objects.requireNonNull(module, "module");
        this.worksites = Objects.requireNonNull(worksites, "worksites");
    }

    @Override public Optional<Worksite> at(String structureId) {
        if (!module.active()) return Optional.empty();
        try {
            return worksites.at(structureId).map(Guarded::new);
        } catch (ModApiMismatch broken) {
            return Optional.empty();
        }
    }

    /** 一个工地：读现场的几样按模组还在不在兜一层。 */
    private final class Guarded implements Worksite {
        private final Worksite worksite;

        Guarded(Worksite worksite) {
            this.worksite = worksite;
        }

        @Override public String structureId() {
            return worksite.structureId();
        }

        @Override public boolean present() {
            if (!module.active()) return false;
            try {
                return worksite.present();
            } catch (ModApiMismatch broken) {
                return false;
            }
        }

        @Override public boolean moving() {
            if (!module.active()) return false;
            try {
                return worksite.moving();
            } catch (ModApiMismatch broken) {
                return false;
            }
        }

        @Override public String name() {
            return worksite.name();
        }

        @Override public Optional<BlockPos> assembler() {
            if (!module.active()) return Optional.empty();
            try {
                return worksite.assembler();
            } catch (ModApiMismatch broken) {
                return Optional.empty();
            }
        }

        @Override public BlockPos plotCenter() {
            return worksite.plotCenter();
        }

        @Override public BlockPos min() {
            return worksite.min();
        }

        @Override public BlockPos max() {
            return worksite.max();
        }

        @Override public BlockPos plotCellAt(Vec3 world) {
            return worksite.plotCellAt(world);
        }

        @Override public ConstructionServices services(ConstructionServices onLand, Permissions permissions) {
            return worksite.services(onLand, permissions);
        }

        @Override public BringsPlayerClose close() {
            return worksite.close();
        }

        @Override public ReadsBlocks blocks() {
            return worksite.blocks();
        }

        @Override public void forgetScan() {
            if (!module.active()) return;
            try {
                worksite.forgetScan();
            } catch (ModApiMismatch broken) {
                // 模组断了也就没有要重读的了。
            }
        }
    }
}
