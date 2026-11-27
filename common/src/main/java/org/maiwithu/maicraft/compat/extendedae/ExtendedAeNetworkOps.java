// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.extendedae;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineSettings;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * ExtendedAE 五类高频扩展网络设备的委托集合：
 * 扩展样板供应器（36 槽）、ME 扩展驱动器（20 槽磁盘直读）、ME 扩展接口与超大接口（36 槽分页）、
 * 扩展分子组装室（8 线程 + 5 格加速卡）与 ME 扩展 IO 传输站（满度/工作模式 + 升级卡）。
 */
final class ExtendedAeNetworkOps {

    private static final List<String> YES_NO = List.of("yes", "no");
    private static final List<String> LOCK_MODES = List.of("none", "until_pulse", "while_high", "while_low",
            "until_result");
    private static final List<String> FULLNESS_MODES = List.of("empty", "half", "full");
    private static final List<String> OPERATION_MODES = List.of("empty", "fill");
    private static final List<String> SPEED_UPGRADE_CHOICES = List.of("ae2:speed_card", "empty");
    private static final List<String> TRANSFER_UPGRADE_CHOICES = List.of(
            "ae2:speed_card", "ae2:redstone_card", "empty");

    private ExtendedAeNetworkOps() {}

    /** 扩展样板供应器（{@code extendedae:ex_pattern_provider}，36 槽）。 */
    static final class PatternProviderOps implements ExtendedAeDeviceOps {

        private final ExtendedAeCompat compat;
        private final ExtendedAeDeviceReadings readings;
        private final BiFunction<BlockPos, Permissions, MenuOpening> menuOpenings;

        PatternProviderOps(ExtendedAeCompat compat, ExtendedAeDeviceReadings readings,
                BiFunction<BlockPos, Permissions, MenuOpening> menuOpenings) {
            this.compat = Objects.requireNonNull(compat, "compat");
            this.readings = Objects.requireNonNull(readings, "readings");
            this.menuOpenings = menuOpenings;
        }

        @Override public Optional<MachineState> state(BlockPos at, ClientLevel level) {
            Optional<ExtendedAeReads.NetworkDeviceView> net = level == null
                    ? Optional.empty() : compat.networkDeviceAt(level, at);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("pattern_capacity", 36);
            net.ifPresent(v -> {
                map.put("powered", v.powered());
                map.put("active", v.active());
            });
            readings.provider(at).ifPresent(p -> {
                map.put("blocking_mode", p.blockingMode());
                map.put("lock_crafting", p.lockCraftingMode());
                map.put("show_in_access_terminal", p.showInAccessTerminal());
            });
            readings.priority(at).ifPresent(prio -> map.put("priority", prio));
            readings.latest(at).ifPresent(reply -> ExtendedAeDeviceReadings.mergeServerReply(map, reply));
            MachineState.Activity activity = activityOf(net);
            return Optional.of(new MachineState(activity, null, null, List.of(), map,
                    "36 槽扩展样板供应器，六面可连 ME 网"));
        }

        @Override public Optional<MachineSettings> settings(BlockPos pos, PlayerServices services) {
            return Optional.of(new MachineSettings() {
                @Override public List<MachineSetting> settings(BlockPos at) {
                    return providerSettings(readings.provider(at), readings.priority(at), readings.latest(at));
                }

                @Override public Optional<Action> change(BlockPos at, String key, String value,
                        Permissions permissions) {
                    return changeProvider(at.immutable(), key, value, permissions, services);
                }
            });
        }

        static List<MachineSetting> providerSettings(Optional<ExtendedAeReads.PatternProviderMenuView> cached,
                Optional<Integer> cachedPriority, Optional<JsonObject> serverReply) {
            String blocking = cached.map(ExtendedAeReads.PatternProviderMenuView::blockingMode)
                    .or(() -> serverReply.flatMap(r -> ExtendedAeDeviceReadings.readSetting(r, "blocking_mode")))
                    .orElse("unknown");
            String lock = cached.map(ExtendedAeReads.PatternProviderMenuView::lockCraftingMode)
                    .or(() -> serverReply.flatMap(r -> ExtendedAeDeviceReadings.readSetting(r, "lock_crafting_mode")))
                    .orElse("unknown");
            String show = cached.map(ExtendedAeReads.PatternProviderMenuView::showInAccessTerminal)
                    .or(() -> serverReply.flatMap(r -> ExtendedAeDeviceReadings.readSetting(r,
                            "pattern_access_terminal")))
                    .orElse("unknown");
            String prio = cachedPriority.map(String::valueOf)
                    .or(() -> serverReply.flatMap(r -> ExtendedAeDeviceReadings.readLong(r, "priority"))
                            .map(String::valueOf))
                    .orElse("unknown");
            return List.of(
                    new MachineSetting("blocking_mode", blocking, YES_NO,
                            "阻挡模式：yes 时目标容器有材料暂不推送下一批"),
                    new MachineSetting("lock_crafting", lock, LOCK_MODES,
                            "锁合成模式：none / until_pulse / while_high / while_low / until_result"),
                    new MachineSetting("show_in_access_terminal", show, YES_NO,
                            "是否在样板管理终端中显示"),
                    new MachineSetting("priority", prio, List.of(), "ME 优先级（整数）"));
        }

        private Optional<Action> changeProvider(BlockPos target, String key, String value,
                Permissions permissions, PlayerServices services) {
            if ("blocking_mode".equals(key)) {
                String normalized = value.trim().toLowerCase(Locale.ROOT);
                if (!YES_NO.contains(normalized)) return Optional.empty();
                return Optional.of(new ExtendedAeMenuActionSupport.ConfigCycleAction(
                        openingFor(target, permissions, services, menuOpenings), compat, "blocking_mode",
                        normalized, 3,
                        () -> compat.openPatternProviderMenu().map(ExtendedAeReads.PatternProviderMenuView::blockingMode),
                        () -> compat.openPatternProviderMenu().ifPresent(v -> readings.putProvider(target, v)),
                        "扩展样板供应器阻挡模式"));
            }
            if ("lock_crafting".equals(key) || "lock_crafting_mode".equals(key)) {
                String normalized = value.trim().toLowerCase(Locale.ROOT);
                if (!LOCK_MODES.contains(normalized)) return Optional.empty();
                return Optional.of(new ExtendedAeMenuActionSupport.ConfigCycleAction(
                        openingFor(target, permissions, services, menuOpenings), compat, "lock_crafting_mode",
                        normalized, 6,
                        () -> compat.openPatternProviderMenu()
                                .map(ExtendedAeReads.PatternProviderMenuView::lockCraftingMode),
                        () -> compat.openPatternProviderMenu().ifPresent(v -> readings.putProvider(target, v)),
                        "扩展样板供应器锁合成模式"));
            }
            if ("show_in_access_terminal".equals(key)) {
                String normalized = value.trim().toLowerCase(Locale.ROOT);
                if (!YES_NO.contains(normalized)) return Optional.empty();
                return Optional.of(new ExtendedAeMenuActionSupport.ConfigCycleAction(
                        openingFor(target, permissions, services, menuOpenings), compat, "pattern_access_terminal",
                        normalized, 3,
                        () -> compat.openPatternProviderMenu()
                                .map(ExtendedAeReads.PatternProviderMenuView::showInAccessTerminal),
                        () -> compat.openPatternProviderMenu().ifPresent(v -> readings.putProvider(target, v)),
                        "扩展样板供应器终端显示"));
            }
            if ("priority".equals(key)) {
                Integer parsed = parseInt(value);
                if (parsed == null) return Optional.empty();
                return Optional.of(new ExtendedAeMenuActionSupport.PrioritySubMenuAction(
                        openingFor(target, permissions, services, menuOpenings), compat, parsed,
                        saved -> readings.putPriority(target, saved), "扩展样板供应器"));
            }
            return Optional.empty();
        }
    }

    /** ME 扩展驱动器（{@code extendedae:ex_drive}，20 槽磁盘客户端直读）。 */
    static final class DriveOps implements ExtendedAeDeviceOps {

        private final ExtendedAeCompat compat;

        DriveOps(ExtendedAeCompat compat) {
            this.compat = Objects.requireNonNull(compat, "compat");
        }

        @Override public Optional<MachineState> state(BlockPos at, ClientLevel level) {
            if (level == null) return Optional.of(MachineState.unknown("角色不在世界里，读不到 ME 扩展驱动器"));
            Optional<ExtendedAeReads.ExDriveView> found = compat.exDriveAt(level, at);
            if (found.isEmpty()) return Optional.empty();
            return Optional.of(buildDriveState(found.get()));
        }

        static MachineState buildDriveState(ExtendedAeReads.ExDriveView drive) {
            Map<String, Object> map = new LinkedHashMap<>();
            List<MachineState.Shown> shown = new ArrayList<>();
            int occupied = 0;
            for (ExtendedAeReads.DriveCellView slot : drive.slots()) {
                if (!slot.itemId().isEmpty()) {
                    occupied++;
                    map.put("slot_" + slot.slot(), slot.itemId() + " (" + slot.status() + ")");
                    shown.add(new MachineState.Shown(slot.itemId(), 1));
                } else {
                    map.put("slot_" + slot.slot(), "empty");
                }
            }
            int total = drive.slots().size();
            map.put("occupied_slots", occupied);
            map.put("empty_slots", Math.max(0, total - occupied));
            map.put("total_slots", total);
            map.put("online", drive.online());
            if (!drive.online()) {
                return new MachineState(MachineState.Activity.OFFLINE, null, null, shown, map,
                        "没电或未接入 ME 网（已装 " + occupied + "/" + total + " 块存储元件）");
            }
            MachineState.Activity activity = occupied > 0
                    ? MachineState.Activity.RUNNING : MachineState.Activity.IDLE;
            return new MachineState(activity, null, null, shown, map,
                    "ME 扩展驱动器已装 " + occupied + "/" + total + " 块存储元件");
        }
    }

    /** ME 扩展接口与超大接口（{@code extendedae:ex_interface} / {@code extendedae:oversize_interface}）。 */
    static final class InterfaceOps implements ExtendedAeDeviceOps {

        private final ExtendedAeCompat compat;
        private final ExtendedAeDeviceReadings readings;
        private final boolean oversize;

        InterfaceOps(ExtendedAeCompat compat, ExtendedAeDeviceReadings readings, boolean oversize) {
            this.compat = Objects.requireNonNull(compat, "compat");
            this.readings = Objects.requireNonNull(readings, "readings");
            this.oversize = oversize;
        }

        @Override public Optional<MachineState> state(BlockPos at, ClientLevel level) {
            Optional<ExtendedAeReads.NetworkDeviceView> net = level == null
                    ? Optional.empty() : compat.networkDeviceAt(level, at);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("slot_pages", 4);
            map.put("total_slots", 36);
            map.put("oversize", oversize);
            net.ifPresent(v -> {
                map.put("powered", v.powered());
                map.put("active", v.active());
            });
            readings.latest(at).ifPresent(reply -> ExtendedAeDeviceReadings.mergeServerReply(map, reply));
            return Optional.of(new MachineState(activityOf(net), null, null, List.of(), map,
                    oversize ? "36 槽 ME 超大接口（4 页备料与存储，支持超大单槽堆叠）"
                            : "36 槽 ME 扩展接口（4 页备料与存储）"));
        }
    }

    /** 扩展分子组装室（{@code extendedae:ex_molecular_assembler}，8 线程 + 5 格加速卡）。 */
    static final class MolecularAssemblerOps implements ExtendedAeDeviceOps {

        static final int UPGRADE_SLOTS = 5;

        private final ExtendedAeCompat compat;
        private final ExtendedAeDeviceReadings readings;
        private final BiFunction<BlockPos, Permissions, MenuOpening> menuOpenings;

        MolecularAssemblerOps(ExtendedAeCompat compat, ExtendedAeDeviceReadings readings,
                BiFunction<BlockPos, Permissions, MenuOpening> menuOpenings) {
            this.compat = Objects.requireNonNull(compat, "compat");
            this.readings = Objects.requireNonNull(readings, "readings");
            this.menuOpenings = menuOpenings;
        }

        @Override public Optional<MachineState> state(BlockPos at, ClientLevel level) {
            Optional<ExtendedAeReads.NetworkDeviceView> net = level == null
                    ? Optional.empty() : compat.exMolecularAssemblerAt(level, at);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("threads", 8);
            net.ifPresent(v -> {
                map.put("powered", v.powered());
                map.put("active", v.active());
            });
            readings.latest(at).ifPresent(reply -> ExtendedAeDeviceReadings.mergeServerReply(map, reply));
            return Optional.of(new MachineState(activityOf(net), null, null, List.of(), map,
                    "8 线程并行扩展分子组装室，最多装 5 张加速卡"));
        }

        @Override public Optional<MachineSettings> settings(BlockPos pos, PlayerServices services) {
            return Optional.of(new MachineSettings() {
                @Override public List<MachineSetting> settings(BlockPos at) {
                    List<String> upgrades = readings.latest(at)
                            .map(ExtendedAeDeviceReadings::readUpgrades).orElse(List.of());
                    List<MachineSetting> list = new ArrayList<>(UPGRADE_SLOTS);
                    for (int i = 0; i < UPGRADE_SLOTS; i++) {
                        String cur = i < upgrades.size() ? upgrades.get(i) : "unknown";
                        list.add(new MachineSetting("upgrade." + i, cur, SPEED_UPGRADE_CHOICES,
                                "扩展分子组装室第 " + i + " 号升级槽（ae2:speed_card / empty）"));
                    }
                    return List.copyOf(list);
                }

                @Override public Optional<Action> change(BlockPos at, String key, String value,
                        Permissions permissions) {
                    int slot = ExtendedAeMenuActionSupport.parseUpgradeSlot(key, UPGRADE_SLOTS);
                    if (slot < 0) return Optional.empty();
                    boolean remove = "empty".equalsIgnoreCase(value) || "out".equalsIgnoreCase(value);
                    if (!remove && !"ae2:speed_card".equals(value)) return Optional.empty();
                    return Optional.of(new ExtendedAeMenuActionSupport.SemanticSlotSwapAction(
                            openingFor(at.immutable(), permissions, services, menuOpenings), compat, "UPGRADE", slot,
                            remove ? null : snap -> "ae2:speed_card".equals(snap.key()),
                            remove ? "空位" : "ae2:speed_card",
                            () -> {}, "扩展分子组装室升级槽 " + slot));
                }
            });
        }
    }

    /** ME 扩展 IO 传输站（{@code extendedae:ex_io_port}，支持满度/工作模式与 5 格升级卡）。 */
    static final class ExCellTransferOps implements ExtendedAeDeviceOps {

        static final int UPGRADE_SLOTS = 5;

        private final ExtendedAeCompat compat;
        private final ExtendedAeDeviceReadings readings;
        private final BiFunction<BlockPos, Permissions, MenuOpening> menuOpenings;

        ExCellTransferOps(ExtendedAeCompat compat, ExtendedAeDeviceReadings readings,
                BiFunction<BlockPos, Permissions, MenuOpening> menuOpenings) {
            this.compat = Objects.requireNonNull(compat, "compat");
            this.readings = Objects.requireNonNull(readings, "readings");
            this.menuOpenings = menuOpenings;
        }

        @Override public Optional<MachineState> state(BlockPos at, ClientLevel level) {
            Optional<ExtendedAeReads.NetworkDeviceView> net = level == null
                    ? Optional.empty() : compat.networkDeviceAt(level, at);
            Map<String, Object> map = new LinkedHashMap<>();
            net.ifPresent(v -> {
                map.put("powered", v.powered());
                map.put("active", v.active());
            });
            readings.cellTransfer(at).ifPresent(v -> {
                map.put("fullness_mode", v.fullnessMode());
                map.put("operation_mode", v.operationMode());
                for (int i = 0; i < v.upgrades().size(); i++) {
                    map.put("upgrade." + i, v.upgrades().get(i));
                }
            });
            readings.latest(at).ifPresent(reply -> ExtendedAeDeviceReadings.mergeServerReply(map, reply));
            return Optional.of(new MachineState(activityOf(net), null, null, List.of(), map,
                    "高速 ME 扩展 IO 传输站，用于快速倒盘"));
        }

        @Override public Optional<MachineSettings> settings(BlockPos pos, PlayerServices services) {
            return Optional.of(new MachineSettings() {
                @Override public List<MachineSetting> settings(BlockPos at) {
                    return transferSettings(readings.cellTransfer(at), readings.latest(at));
                }

                @Override public Optional<Action> change(BlockPos at, String key, String value,
                        Permissions permissions) {
                    return changeTransfer(at.immutable(), key, value, permissions, services);
                }
            });
        }

        static List<MachineSetting> transferSettings(Optional<ExtendedAeReads.CellTransferMenuView> cached,
                Optional<JsonObject> serverReply) {
            List<MachineSetting> list = new ArrayList<>(2 + UPGRADE_SLOTS);
            String full = cached.map(ExtendedAeReads.CellTransferMenuView::fullnessMode)
                    .or(() -> serverReply.flatMap(r -> ExtendedAeDeviceReadings.readSetting(r, "fullness_mode")))
                    .orElse("unknown");
            String op = cached.map(ExtendedAeReads.CellTransferMenuView::operationMode)
                    .or(() -> serverReply.flatMap(r -> ExtendedAeDeviceReadings.readSetting(r, "operation_mode")))
                    .orElse("unknown");
            list.add(new MachineSetting("fullness_mode", full, FULLNESS_MODES,
                    "移盘触发满度：empty / half / full"));
            list.add(new MachineSetting("operation_mode", op, OPERATION_MODES,
                    "倒盘方向：empty（元件倒进网络）/ fill（网络倒进元件）"));
            List<String> upgrades = cached.map(ExtendedAeReads.CellTransferMenuView::upgrades)
                    .orElseGet(() -> serverReply.map(ExtendedAeDeviceReadings::readUpgrades).orElse(List.of()));
            for (int i = 0; i < UPGRADE_SLOTS; i++) {
                String cur = i < upgrades.size() ? upgrades.get(i) : (cached.isPresent() ? "empty" : "unknown");
                list.add(new MachineSetting("upgrade." + i, cur, TRANSFER_UPGRADE_CHOICES,
                        "ME 扩展 IO 传输站第 " + i + " 号升级槽"));
            }
            return List.copyOf(list);
        }

        private Optional<Action> changeTransfer(BlockPos target, String key, String value,
                Permissions permissions, PlayerServices services) {
            if ("fullness_mode".equals(key)) {
                String normalized = value.trim().toLowerCase(Locale.ROOT);
                if (!FULLNESS_MODES.contains(normalized)) return Optional.empty();
                return Optional.of(new ExtendedAeMenuActionSupport.ConfigCycleAction(
                        openingFor(target, permissions, services, menuOpenings), compat, "fullness_mode",
                        normalized, 4,
                        () -> compat.openExCellTransferMenu().map(ExtendedAeReads.CellTransferMenuView::fullnessMode),
                        () -> compat.openExCellTransferMenu().ifPresent(v -> readings.putCellTransfer(target, v)),
                        "ME 扩展 IO 传输站满度模式"));
            }
            if ("operation_mode".equals(key)) {
                String normalized = value.trim().toLowerCase(Locale.ROOT);
                if (!OPERATION_MODES.contains(normalized)) return Optional.empty();
                return Optional.of(new ExtendedAeMenuActionSupport.ConfigCycleAction(
                        openingFor(target, permissions, services, menuOpenings), compat, "operation_mode",
                        normalized, 3,
                        () -> compat.openExCellTransferMenu().map(ExtendedAeReads.CellTransferMenuView::operationMode),
                        () -> compat.openExCellTransferMenu().ifPresent(v -> readings.putCellTransfer(target, v)),
                        "ME 扩展 IO 传输站工作方向"));
            }
            int slot = ExtendedAeMenuActionSupport.parseUpgradeSlot(key, UPGRADE_SLOTS);
            if (slot < 0) return Optional.empty();
            boolean remove = "empty".equalsIgnoreCase(value) || "out".equalsIgnoreCase(value);
            if (!remove && !TRANSFER_UPGRADE_CHOICES.contains(value)) return Optional.empty();
            return Optional.of(new ExtendedAeMenuActionSupport.SemanticSlotSwapAction(
                    openingFor(target, permissions, services, menuOpenings), compat, "UPGRADE", slot,
                    remove ? null : snap -> value.equals(snap.key()),
                    remove ? "空位" : value,
                    () -> compat.openExCellTransferMenu().ifPresent(v -> readings.putCellTransfer(target, v)),
                    "ME 扩展 IO 传输站升级槽 " + slot));
        }
    }

    static MachineState.Activity activityOf(Optional<ExtendedAeReads.NetworkDeviceView> net) {
        if (net.isEmpty()) return MachineState.Activity.UNKNOWN;
        if (!net.get().powered()) return MachineState.Activity.OFFLINE;
        return net.get().active() ? MachineState.Activity.RUNNING : MachineState.Activity.IDLE;
    }

    private static MenuOpening openingFor(BlockPos target, Permissions permissions, PlayerServices services,
            BiFunction<BlockPos, Permissions, MenuOpening> override) {
        return override != null ? override.apply(target, permissions)
                : ExtendedAeMenuActionSupport.openBlock(target, services, permissions);
    }

    private static Integer parseInt(String raw) {
        if (raw == null) return null;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
