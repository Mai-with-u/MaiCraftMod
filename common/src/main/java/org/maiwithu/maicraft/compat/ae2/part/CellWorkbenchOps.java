// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.part;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineSettings;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.ae2.Ae2DeviceOps;
import org.maiwithu.maicraft.game.serverlink.ServerReads;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 元件工作台（{@code ae2:cell_workbench}）设备委托。
 *
 * <p>支持元件槽（{@code cell=in:<id>|out}）、复制模式（{@code nextCopyMode}）、
 * 模糊模式（经 {@code setFuzzyMode} 动作写入元件物品组件，绝不走 {@code ConfigButtonPacket}）、
 * 自动分区/清空（{@code partition}/{@code clear}）、63 格分区过滤槽与 8 格动态升级槽放取。
 */
public final class CellWorkbenchOps implements Ae2DeviceOps {

    private static final String LABEL = "元件工作台";

    private final Ae2Compat compat;
    private final Ae2PartControl control;
    private final Ae2PartCache cache;

    public CellWorkbenchOps(Ae2Compat compat, Ae2PartControl control, Ae2PartCache cache) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.control = Objects.requireNonNull(control, "control");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    @Override public Map<String, Object> extraReadings(BlockPos pos, ClientLevel level) {
        Map<String, Object> readings = new LinkedHashMap<>(cache.readingsFor(pos, null, ServerReads.NONE));
        Map<String, String> settings = cache.peekSettings(pos, null, ServerReads.NONE);
        if (settings.containsKey("copy_mode")) readings.put("copy_mode", settings.get("copy_mode"));
        if (settings.containsKey("fuzzy_mode")) readings.put("fuzzy_mode", settings.get("fuzzy_mode"));
        return readings;
    }

    @Override public Optional<MachineSettings> settings(BlockPos pos, PlayerServices services) {
        return Optional.of(new WorkbenchSettings(services));
    }

    Optional<Action> buildChange(BlockPos pos, String key, String rawValue, PlayerServices services,
                                 Permissions permissions) {
        String value = rawValue == null ? "" : rawValue.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (key.equals("cell")) {
            Optional<Ae2PartKeys.CellValue> parsed = Ae2PartKeys.parseCellValue(value);
            if (parsed.isEmpty()) return invalid("cell 写法应为 in:<元件物品 ID> 或 out：" + value);
            String normalized = parsed.get().putIn() ? "in:" + parsed.get().itemId() : "out";
            return Optional.of(action(pos, Ae2MenuConfigAction.Kind.ITEM_SLOT, "cell", normalized, 0, true,
                    services, permissions));
        }
        if (key.equals("copy_mode")) {
            if (!Ae2PartKeys.COPY_MODES.contains(lower)) {
                return invalid("copy_mode 只接受 clear_on_remove 或 keep_on_remove");
            }
            return Optional.of(action(pos, Ae2MenuConfigAction.Kind.WORKBENCH_COPY_MODE, "copy_mode", lower, -1, false,
                    services, permissions));
        }
        if (key.equals("fuzzy_mode")) {
            if (!Ae2PartKeys.FUZZY_MODES.contains(lower)) return invalid("不认识的模糊模式：" + value);
            // 元件工作台的模糊模式写入元件物品组件，固定走 setFuzzyMode 界面动作。
            return Optional.of(action(pos, Ae2MenuConfigAction.Kind.WORKBENCH_FUZZY, "fuzzy_mode", lower, -1, false,
                    services, permissions));
        }
        if (key.equals("action")) {
            if (!Ae2PartKeys.PARTITION_ACTIONS.contains(lower)) return invalid("action 只接受 partition 或 clear");
            return Optional.of(action(pos, Ae2MenuConfigAction.Kind.NO_ARG_ACTION, "action", lower, -1, false,
                    services, permissions));
        }
        int filterIdx = Ae2PartKeys.indexedSlot(key, "filter.", Ae2PartKeys.MAX_FILTER_SLOTS);
        if (filterIdx >= 0) {
            return Optional.of(action(pos, Ae2MenuConfigAction.Kind.FILTER_SLOT, key, value, filterIdx, false,
                    services, permissions));
        }
        int upgradeIdx = Ae2PartKeys.indexedSlot(key, "upgrade.", Ae2PartKeys.WORKBENCH_UPGRADE_SLOTS);
        if (upgradeIdx >= 0) {
            return Optional.of(action(pos, Ae2MenuConfigAction.Kind.ITEM_SLOT, key, value, upgradeIdx, false,
                    services, permissions));
        }
        return Optional.empty();
    }

    private Ae2MenuConfigAction action(BlockPos pos, Ae2MenuConfigAction.Kind kind, String key, String value,
                                       int slotIndex, boolean isCellSlot, PlayerServices services,
                                       Permissions permissions) {
        Ae2MenuConfigAction.Spec spec = new Ae2MenuConfigAction.Spec(pos, null, LABEL,
                Ae2PartKeys.MENU_CELL_WORKBENCH, kind, key, value, null, slotIndex, isCellSlot, null);
        return new Ae2MenuConfigAction(compat, control, cache, services, spec, permissions);
    }

    private static Optional<Action> invalid(String message) {
        return Optional.of(new Action() {
            @Override public ActionStatus tick(TickContext context) {
                return ActionStatus.failed(Problem.of(Problem.Kind.INVALID_PARAMETER, message));
            }
            @Override public String describe() {
                return message;
            }
        });
    }

    private final class WorkbenchSettings implements MachineSettings {

        private final PlayerServices services;

        WorkbenchSettings(PlayerServices services) {
            this.services = services;
        }

        @Override public List<MachineSetting> settings(BlockPos at) {
            Map<String, String> values = cache.settingsFor(at, null, services.serverReads());
            return Ae2PartKeys.cellWorkbenchSettings(values);
        }

        @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
            return buildChange(at, key, value, services, permissions);
        }
    }
}
