// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.machine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineSettings;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.ae2.Ae2DeviceOps;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * ME 箱子（ae2:chest）的设备操作委托：
 * 客户端直接同步读取 online、cell_item、cell_status、color 与相对顶面 top_face；
 * 明确标注相对顶面（getTop，并非固定 UP）右键开存储界面 ae2:basic_cell_chest，
 * 其余 5 面右键开元件插槽界面 ae2:me_chest，配置键 cell=in:&lt;item_id&gt;|out 避开顶面换盘。
 */
final class MeChestOps implements Ae2DeviceOps {

    private final Ae2Compat compat;
    private final Ae2MachineReads reads;

    MeChestOps(Ae2Compat compat, Ae2MachineReads reads) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.reads = Objects.requireNonNull(reads, "reads");
    }

    @Override public Optional<MachineState> state(BlockPos pos, ClientLevel level) {
        if (level == null) return Optional.of(MachineState.unknown("角色不在世界里，读不到 ME 箱子"));
        Optional<Ae2MachineReads.MeChestView> found = reads.meChestAt(level, pos);
        if (found.isEmpty()) return Optional.empty();
        return Optional.of(buildState(found.get()));
    }

    /** 纯函数：将 ME 箱子客户端同步视图转为机器状态。 */
    static MachineState buildState(Ae2MachineReads.MeChestView view) {
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("online", view.online());
        String cell = view.cellItemId() == null || view.cellItemId().isEmpty() ? "empty" : view.cellItemId();
        readings.put("cell_item", cell);
        readings.put("cell_status", view.cellStatus());
        readings.put("color", view.color());
        String topName = view.topFace() == null ? "up" : view.topFace().getSerializedName();
        readings.put("top_face", topName);
        List<MachineState.Shown> shown = new ArrayList<>();
        if (!"empty".equals(cell)) {
            shown.add(new MachineState.Shown(cell, 1));
        }
        MachineState.Activity activity = !view.online() ? MachineState.Activity.OFFLINE
                : "empty".equals(cell) ? MachineState.Activity.IDLE : MachineState.Activity.RUNNING;
        String note = "相对顶面（" + topName + "）右键打开存储终端界面（ae2:basic_cell_chest）存取盘内物品，"
                + "其余 5 面右键打开元件插槽界面（ae2:me_chest）换盘";
        return new MachineState(activity, null, null, List.copyOf(shown), readings, note);
    }

    @Override public Optional<MachineSettings> settings(BlockPos pos, PlayerServices services) {
        return Optional.of(new MachineSettings() {
            @Override public List<MachineSetting> settings(BlockPos at) {
                PlayerContext ctx = services.context().get();
                String current = ctx == null || ctx.level() == null ? "unknown"
                        : reads.meChestAt(ctx.level(), at)
                                .map(v -> v.cellItemId() == null || v.cellItemId().isEmpty()
                                        ? "out" : "in:" + v.cellItemId())
                                .orElse("unknown");
                return List.of(new MachineSetting("cell", current, List.of("in:<item_id>", "out"),
                        "ME 箱子元件槽：in:<item_id> 避开相对顶面打开换盘界面放入存储元件，out 取出元件"));
            }

            // 换盘必须点击非相对顶面的其余 5 面，才能打开 ae2:me_chest 而不是存储终端界面。
            @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
                if (!"cell".equals(key)) return Optional.empty();
                boolean out = "out".equalsIgnoreCase(value) || "empty".equalsIgnoreCase(value);
                String wanted = out ? null : (value.startsWith("in:") ? value.substring(3) : value);
                if (!out && (wanted == null || wanted.isBlank())) return Optional.empty();
                PlayerContext ctx = services.context().get();
                Direction top = ctx == null || ctx.level() == null ? Direction.UP
                        : reads.meChestAt(ctx.level(), at).map(Ae2MachineReads.MeChestView::topFace).orElse(Direction.UP);
                List<Direction> sideFaces = FaceMenuOpening.facesExcept(top);
                FaceMenuOpening opening = new FaceMenuOpening(at, sideFaces, permissions, services, "ME 箱子换盘界面");
                return Optional.of(new Ae2MenuActionSupport.SemanticSlotSwapAction(
                        opening, compat, "STORAGE_CELL", 0,
                        out ? null : (snap, player) -> wanted.equals(snap.key()),
                        out ? "空位" : wanted,
                        () -> {},
                        "ME 箱子元件槽"));
            }
        });
    }
}
