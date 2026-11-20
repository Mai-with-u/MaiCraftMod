// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.machine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.behavior.menu.ClientMenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 按指定候选面顺序打开方块界面的打开器（例如 ME 箱子避开相对顶面、点击其余五面打开换盘界面）：
 * 为每个候选面构造贴合该面的瞄准薄盒并直接委托给 {@link ClientMenuOpening}，
 * 遇到某一面被遮挡或够不着时自动尝试下一个允许的面，复用玩家行为层全部同步与认领逻辑。
 */
final class FaceMenuOpening implements MenuOpening {

    private static final double SLICE_THICKNESS = 0.04;
    private static final double INSET = 0.08;

    private final BlockPos at;
    private final List<Direction> allowedFaces;
    private final Permissions permissions;
    private final PlayerServices services;
    private final String label;

    private int faceIndex;
    private ClientMenuOpening delegate;

    FaceMenuOpening(BlockPos at, List<Direction> allowedFaces, Permissions permissions,
            PlayerServices services, String label) {
        this.at = at.immutable();
        this.allowedFaces = List.copyOf(Objects.requireNonNull(allowedFaces, "allowedFaces"));
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.services = Objects.requireNonNull(services, "services");
        this.label = Objects.requireNonNull(label, "label");
    }

    /** 构造除排除面之外的全部可达候选面列表（优先水平四面，再下、上）。 */
    static List<Direction> facesExcept(Direction excluded) {
        List<Direction> order = List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST,
                Direction.DOWN, Direction.UP);
        List<Direction> result = new ArrayList<>(5);
        for (Direction dir : order) {
            if (dir != excluded) result.add(dir);
        }
        return List.copyOf(result);
    }

    /** 构造方块表面朝向指定面的薄瞄准盒，保证准星仅落在该合法面上。 */
    static AABB faceSlice(BlockPos pos, Direction face) {
        double[] min = {INSET, INSET, INSET};
        double[] max = {1.0 - INSET, 1.0 - INSET, 1.0 - INSET};
        int axis = face.getAxis().ordinal();
        if (face.getAxisDirection() == Direction.AxisDirection.POSITIVE) {
            min[axis] = 1.0 - SLICE_THICKNESS;
            max[axis] = 1.0;
        } else {
            min[axis] = 0.0;
            max[axis] = SLICE_THICKNESS;
        }
        return new AABB(min[0], min[1], min[2], max[0], max[1], max[2]).move(pos);
    }

    @Override public Optional<OpenedMenu> opened() {
        return delegate == null ? Optional.empty() : delegate.opened();
    }

    @Override public ActionStatus tick(TickContext tick) {
        if (allowedFaces.isEmpty()) {
            return ActionStatus.failed(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, label + "没有可供点击的合法面"));
        }
        if (delegate == null) {
            AABB slice = faceSlice(at, allowedFaces.get(faceIndex));
            delegate = new ClientMenuOpening(at, slice, permissions, services.menuLayouts(),
                    services.approach(), services.interactions(), services.toMainhand(), services.context());
        }
        ActionStatus status = delegate.tick(tick);
        if (status instanceof ActionStatus.Failed failed && faceIndex + 1 < allowedFaces.size()
                && failed.problem().kind() == Problem.Kind.UNREACHABLE) {
            delegate.close();
            delegate = null;
            faceIndex++;
            return ActionStatus.progressed();
        }
        return status;
    }

    @Override public void pause() {
        if (delegate != null) delegate.pause();
    }

    @Override public void close() {
        if (delegate != null) {
            delegate.close();
            delegate = null;
        }
    }

    @Override public Interruptibility interruptibility() {
        return delegate != null ? delegate.interruptibility() : Interruptibility.WORKING;
    }

    @Override public String describe() {
        return "按合法面打开 " + label;
    }
}
