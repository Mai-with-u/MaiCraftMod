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

import org.maiwithu.maicraft.ability.machine.spi.FeedPlan;
import org.maiwithu.maicraft.ability.machine.spi.FullRun;
import org.maiwithu.maicraft.ability.machine.spi.MachineFeeding;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineSettings;
import org.maiwithu.maicraft.ability.machine.spi.RunRequest;
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
 * ME IO 传输站（游戏方块 {@code ae2:io_port}）设备委托。
 *
 * <p>提供工作方向（{@code operation_mode}）、触发满度（{@code fullness_mode}）、
 * 红石模式（{@code redstone_mode}，显式附注装红石卡时 {@code ignore} 按低电平生效的 AE2 事实）、
 * 3 格升级卡配置，以及将存储元件或 {@code IBasicCellItem} 工具送入输入槽倒盘并从输出槽收回的完整运行流程。
 */
public final class CellTransferOps implements Ae2DeviceOps {

    private static final String LABEL = "ME IO 传输站";
    /** AE2 19.2.17 中装红石卡时 IGNORE 仍按低电平逻辑生效的事实提示。F 游戏事实。 */
    static final String REDSTONE_IGNORE_NOTE =
            "已装红石卡且 redstone_mode=ignore：AE2 19.2.17 中该组合仍按低电平逻辑生效，有红石信号时会暂停倒盘";

    private final Ae2Compat compat;
    private final Ae2PartControl control;
    private final Ae2PartCache cache;

    public CellTransferOps(Ae2Compat compat, Ae2PartControl control, Ae2PartCache cache) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.control = Objects.requireNonNull(control, "control");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    @Override public Map<String, Object> extraReadings(BlockPos pos, ClientLevel level) {
        Map<String, Object> readings = new LinkedHashMap<>(cache.readingsFor(pos, null, ServerReads.NONE));
        Map<String, String> settings = cache.peekSettings(pos, null, ServerReads.NONE);
        if (settings.containsKey("operation_mode")) readings.put("operation_mode", settings.get("operation_mode"));
        if (settings.containsKey("fullness_mode")) readings.put("fullness_mode", settings.get("fullness_mode"));
        if (settings.containsKey("redstone_mode")) readings.put("redstone_mode", settings.get("redstone_mode"));
        if (redstoneIgnoreCaveatActive(readings, settings)) {
            readings.put("redstone_note", REDSTONE_IGNORE_NOTE);
        }
        return readings;
    }

    /** 判定当前传输站是否处于「装了红石卡但红石模式设为 ignore」的特殊组合。 */
    static boolean redstoneIgnoreCaveatActive(Map<String, Object> readings, Map<String, String> settings) {
        boolean hasCard = Boolean.TRUE.equals(readings.get("has_redstone_card"))
                || isRedstoneCardInUpgrades(settings);
        return hasCard && "ignore".equals(settings.get("redstone_mode"));
    }

    private static boolean isRedstoneCardInUpgrades(Map<String, String> settings) {
        for (int i = 0; i < Ae2PartKeys.TRANSFER_UPGRADE_SLOTS; i++) {
            if ("ae2:redstone_card".equals(settings.get("upgrade." + i))) return true;
        }
        return false;
    }

    @Override public Optional<MachineSettings> settings(BlockPos pos, PlayerServices services) {
        return Optional.of(new TransferSettings(services));
    }

    @Override public Optional<MachineFeeding> feeding(BlockPos pos, PlayerServices services) {
        return Optional.of(new MachineFeeding() {
            @Override public Optional<Action> feed(BlockPos at, FeedPlan.Entry item, Permissions permissions) {
                return Optional.empty();
            }

            @Override public Optional<Action> take(BlockPos at, String itemId, int count, Permissions permissions) {
                return Optional.empty();
            }

            @Override public Optional<FullRun> fullRun(BlockPos at, RunRequest request, Permissions permissions) {
                return Optional.of(new CellTransferRun(control, cache, services, at, request, permissions));
            }
        });
    }

    Optional<Action> buildChange(BlockPos pos, String key, String rawValue, PlayerServices services,
                                 Permissions permissions) {
        String value = rawValue == null ? "" : rawValue.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (key.equals("operation_mode")) {
            if (!Ae2PartKeys.OPERATION_MODES.contains(lower)) return invalid("operation_mode 只接受 empty 或 fill");
            return Optional.of(buttonAction(pos, "operation_mode", lower, "operation_mode", services, permissions));
        }
        if (key.equals("fullness_mode")) {
            if (!Ae2PartKeys.FULLNESS_MODES.contains(lower)) return invalid("fullness_mode 只接受 empty / half / full");
            return Optional.of(buttonAction(pos, "fullness_mode", lower, "fullness_mode", services, permissions));
        }
        if (key.equals("redstone_mode")) {
            if (!Ae2PartKeys.TRANSFER_REDSTONE_MODES.contains(lower)) {
                return invalid("redstone_mode 只接受 ignore / high_signal / low_signal");
            }
            return Optional.of(buttonAction(pos, "redstone_mode", lower, "redstone_controlled", services, permissions));
        }
        int upgradeIdx = Ae2PartKeys.indexedSlot(key, "upgrade.", Ae2PartKeys.TRANSFER_UPGRADE_SLOTS);
        if (upgradeIdx >= 0) {
            Ae2MenuConfigAction.Spec spec = new Ae2MenuConfigAction.Spec(pos, null, LABEL,
                    Ae2PartKeys.MENU_CELL_TRANSFER, Ae2MenuConfigAction.Kind.ITEM_SLOT, key, value, null,
                    upgradeIdx, false, null);
            return Optional.of(new Ae2MenuConfigAction(compat, control, cache, services, spec, permissions));
        }
        return Optional.empty();
    }

    private Ae2MenuConfigAction buttonAction(BlockPos pos, String key, String value, String ae2Setting,
                                             PlayerServices services, Permissions permissions) {
        Ae2MenuConfigAction.Spec spec = new Ae2MenuConfigAction.Spec(pos, null, LABEL,
                Ae2PartKeys.MENU_CELL_TRANSFER, Ae2MenuConfigAction.Kind.CONFIG_BUTTON, key, value, ae2Setting,
                -1, false, null);
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

    private final class TransferSettings implements MachineSettings {

        private final PlayerServices services;

        TransferSettings(PlayerServices services) {
            this.services = services;
        }

        @Override public List<MachineSetting> settings(BlockPos at) {
            Map<String, String> values = cache.settingsFor(at, null, services.serverReads());
            return Ae2PartKeys.cellTransferSettings(values);
        }

        @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
            return buildChange(at, key, value, services, permissions);
        }
    }
}
