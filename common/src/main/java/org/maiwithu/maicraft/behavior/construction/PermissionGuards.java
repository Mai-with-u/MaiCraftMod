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
 *
 * <p>只有 {@code build} 按图施工那一份是角色亲手点的格：在自己家里，别人放的方块也能改（主播说过自己家可以随便改），
 * 回报写明是谁放的。机器施工、照明放灯、出行建门这些共用施工引擎的，用不放开的那一份，家里别人放的照旧不碰。
 */
public final class PermissionGuards implements ConstructionSeams.Guards {

    private final Supplier<PermissionCheck> checks;
    private final Permissions permissions;
    private final Supplier<String> dimension;
    /** 这一份是不是按图施工用的：是才在角色自己的家里放开别人放的方块。 */
    private final boolean liftAtHome;

    /** 不放开的那一份：机器施工、照明、出行建门这些自己挑格子的施工用它。 */
    public PermissionGuards(Supplier<PermissionCheck> checks, Permissions permissions, Supplier<String> dimension) {
        this(checks, permissions, dimension, false);
    }

    /** @param liftAtHome 按图施工（{@code build}）传真：家里别人放的方块也能拆换，回报写明是谁放的 */
    public PermissionGuards(Supplier<PermissionCheck> checks, Permissions permissions, Supplier<String> dimension,
            boolean liftAtHome) {
        this.checks = Objects.requireNonNull(checks, "checks");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.liftAtHome = liftAtHome;
    }

    @Override
    public Optional<Problem> allows(PermissionCheck.WorldAction action, BlockPos pos, String blockType) {
        PermissionCheck check = checks.get();
        if (check == null) return Optional.empty();
        // 按图施工在家里放开别人放的；别的施工照旧，家里的也当别人的。
        return liftAtHome ? check.blockAllowedUnlessHome(permissions, action, at(pos), blockType)
                : check.blockAllowed(permissions, action, at(pos), blockType);
    }

    @Override
    public String othersAtHomeNote(BlockPos pos) {
        PermissionCheck check = checks.get();
        return check == null || !liftAtHome ? "" : check.othersAtHomeNote(at(pos));
    }

    // 施工的格子换成带维度的世界位置：维度跟着这一刻角色所在的世界，认家、问归属都按它比。
    private WorldPosition at(BlockPos pos) {
        return new WorldPosition(pos.getX(), pos.getY(), pos.getZ(), dimension.get());
    }
}
