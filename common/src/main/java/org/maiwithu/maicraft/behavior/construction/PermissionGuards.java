// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 施工的许可读端：角色自己挑中的一格能不能挖、能不能放，全部问许可检查点；这里只把格子换成世界位置。
 * 施工是许可检查点的第一个真正使用方，蓝图声明范围内的格默认按许可档位与保护判断逐格放行。
 * 归属（谁放的）不参与：谁的方块都能按图改，动过的回报写明是谁放的。
 */
public final class PermissionGuards implements ConstructionSeams.Guards {

    private final Supplier<PermissionCheck> checks;
    private final Permissions permissions;
    private final Supplier<String> dimension;

    /** 施工共用这一份：机器施工、照明、出行建门与按图施工同权。 */
    public PermissionGuards(Supplier<PermissionCheck> checks, Permissions permissions, Supplier<String> dimension) {
        this.checks = Objects.requireNonNull(checks, "checks");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.dimension = Objects.requireNonNull(dimension, "dimension");
    }

    @Override
    public Optional<Problem> allows(PermissionCheck.WorldAction action, BlockPos pos, String blockType) {
        PermissionCheck check = checks.get();
        if (check == null) return Optional.empty();
        return check.blockAllowed(permissions, action, at(pos), blockType);
    }

    @Override
    public String othersAtHomeNote(BlockPos pos) {
        PermissionCheck check = checks.get();
        return check == null ? "" : check.othersAtHomeNote(at(pos));
    }

    // 施工的格子换成带维度的世界位置：维度跟着这一刻角色所在的世界，认家、问归属都按它比。
    private WorldPosition at(BlockPos pos) {
        return new WorldPosition(pos.getX(), pos.getY(), pos.getZ(), dimension.get());
    }
}
