// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.part;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineSettings;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.ae2.Ae2DeviceOps;
import org.maiwithu.maicraft.compat.ae2.Ae2Grid;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.serverlink.ServerReads;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 线缆部件按面配置委托（挂载于 {@code ae2:cable_bus}）及方块版 ME 接口（{@code ae2:interface}）配置委托。
 *
 * <p>在线缆格上暴露带六向小写前缀 {@code <side>.<key>} 的全量配置项，并经 {@link #partSettings}
 * 提供不带前缀的局部配置列表供查看机器直接写入部件视图。全覆盖输入/输出/存储总线、ME 接口、
 * 成型/破坏面板、标准/能量发信器、存储/转换监视器与 6 种 P2P 通道。
 */
public final class Ae2PartCableOps implements Ae2DeviceOps {

    private final Ae2Compat compat;
    private final Ae2PartControl control;
    private final Ae2PartCache cache;
    private final Supplier<ServerReads> serverReads;
    private final BiFunction<ClientLevel, BlockPos, Optional<Ae2Grid.GridCell>> cellReader;

    public Ae2PartCableOps(Ae2Compat compat, Ae2PartControl control, Ae2PartCache cache) {
        this(compat, control, cache, () -> ServerReads.NONE);
    }

    public Ae2PartCableOps(Ae2Compat compat, Ae2PartControl control, Ae2PartCache cache,
                           Supplier<ServerReads> serverReads) {
        this(compat, control, cache, serverReads,
                (level, pos) -> level == null ? Optional.empty() : compat.gridCell(level, pos));
    }

    /** 离线测试用构造：允许无真实 ClientLevel 时直接提供线缆格读数。 */
    Ae2PartCableOps(Ae2Compat compat, Ae2PartControl control, Ae2PartCache cache,
                    BiFunction<ClientLevel, BlockPos, Optional<Ae2Grid.GridCell>> cellReader) {
        this(compat, control, cache, () -> ServerReads.NONE, cellReader);
    }

    Ae2PartCableOps(Ae2Compat compat, Ae2PartControl control, Ae2PartCache cache,
                    Supplier<ServerReads> serverReads,
                    BiFunction<ClientLevel, BlockPos, Optional<Ae2Grid.GridCell>> cellReader) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.control = Objects.requireNonNull(control, "control");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.serverReads = Objects.requireNonNull(serverReads, "serverReads");
        this.cellReader = Objects.requireNonNull(cellReader, "cellReader");
    }

    /** 构造方块版 ME 接口（ae2:interface，无面名前缀）的设备委托，与线缆部件版共用同一套逻辑。 */
    public Ae2DeviceOps interfaceBlockOps() {
        return new InterfaceBlockOps();
    }

    // 补充各面可见的同步读数与缓存/服务端读数（监视器展示/数量/锁定/可合成、P2P 频率与出入端、存储总线目标、破坏面板附魔）。
    @Override public Map<String, Object> extraReadings(BlockPos pos, ClientLevel level) {
        Optional<Ae2Grid.GridCell> cell = cellReader.apply(level, pos);
        if (cell.isEmpty() || !cell.get().cableBus()) return Map.of();
        ServerReads reads = currentServerReads();
        Map<String, Object> readings = new LinkedHashMap<>();
        for (Direction side : Direction.values()) {
            Ae2Grid.PartView view = cell.get().parts().get(side);
            if (view == null) continue;
            Optional<Ae2PartKeys.PartKind> kind = Ae2PartKeys.partKindOf(view.itemId());
            if (kind.isEmpty()) continue;
            String pre = Ae2PartKeys.sideName(side) + ".";
            addPartReadings(readings, pre, pos, side, kind.get(), level, reads);
        }
        return readings;
    }

    private ServerReads currentServerReads() {
        ServerReads reads = serverReads.get();
        return reads == null ? ServerReads.NONE : reads;
    }

    @Override public Optional<MachineSettings> settings(BlockPos pos, PlayerServices services) {
        return Optional.of(new CableSettings(services));
    }

    @Override public List<MachineSetting> partSettings(BlockPos host, Direction side, PlayerServices services) {
        if (side == null) return List.of();
        ClientLevel level = levelOf(services);
        Optional<Ae2Grid.PartView> part = partAt(level, host, side);
        if (part.isEmpty()) return List.of();
        Optional<Ae2PartKeys.PartKind> kind = Ae2PartKeys.partKindOf(part.get().itemId());
        if (kind.isEmpty()) return List.of();
        Map<String, String> values = cache.settingsFor(host, side, services.serverReads());
        Ae2PartControl.ClientPartReading client = control.readClientPart(level, host, side).orElse(null);
        return Ae2PartKeys.partSettings(kind.get(), part.get().itemId(), values, client);
    }

    /** 供测试或汇总层读取指定面部件的补充读数（整合客户端同步 + 缓存/服务端 ae2.device）。 */
    public Map<String, Object> partReadings(BlockPos host, Direction side, ClientLevel level, ServerReads serverReads) {
        if (side == null) return Map.of();
        Optional<Ae2Grid.PartView> part = partAt(level, host, side);
        if (part.isEmpty()) return Map.of();
        Optional<Ae2PartKeys.PartKind> kind = Ae2PartKeys.partKindOf(part.get().itemId());
        if (kind.isEmpty()) return Map.of();
        Map<String, Object> readings = new LinkedHashMap<>();
        addPartReadings(readings, "", host, side, kind.get(), level, serverReads);
        return readings;
    }

    private void addPartReadings(Map<String, Object> target, String prefix, BlockPos host, Direction side,
                                 Ae2PartKeys.PartKind kind, ClientLevel level, ServerReads serverReads) {
        Optional<Ae2PartControl.ClientPartReading> client = control.readClientPart(level, host, side);
        Map<String, Object> cached = cache.readingsFor(host, side, serverReads);
        switch (kind) {
            case STORAGE_BUS -> {
                if (cached.containsKey("connected_to")) target.put(prefix + "connected_to", cached.get("connected_to"));
            }
            case ANNIHILATION_PLANE -> {
                if (cached.containsKey("enchantments")) target.put(prefix + "enchantments", cached.get("enchantments"));
            }
            case STORAGE_MONITOR, CONVERSION_MONITOR -> {
                if (client.isPresent() && client.get().hasMonitor()) {
                    Ae2PartControl.ClientPartReading m = client.get();
                    target.put(prefix + "displayed", m.displayed() == null ? Ae2PartKeys.EMPTY : m.displayed());
                    target.put(prefix + "amount", m.amount());
                    target.put(prefix + "can_craft", m.canCraft());
                    target.put(prefix + "locked", m.locked());
                }
            }
            case P2P_TUNNEL -> {
                if (client.isPresent() && client.get().hasP2p()) {
                    target.put(prefix + "frequency", Ae2PartKeys.formatFrequency(client.get().p2pFrequency()));
                }
                if (cached.containsKey("p2p_is_output")) {
                    target.put(prefix + "is_output", cached.get("p2p_is_output"));
                }
            }
            default -> {
                if (cached.containsKey("priority")) target.put(prefix + "priority", cached.get("priority"));
                if (cached.containsKey("reporting_value")) {
                    target.put(prefix + "reporting_value", cached.get("reporting_value"));
                }
            }
        }
    }

    // 根据部件种类与局部键名构造对应的改配置动作；不合法键名返回空，不合法取值返回即时失败动作。
    Optional<Action> buildPartChange(BlockPos host, Direction side, Ae2PartKeys.PartKind kind, String localKey,
                                     String rawValue, PlayerServices services, Permissions permissions) {
        String value = rawValue == null ? "" : rawValue.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        String label = side == null ? "ME 接口" : "线缆 " + Ae2PartKeys.sideName(side) + " 面的部件";
        if (kind == Ae2PartKeys.PartKind.STORAGE_MONITOR || kind == Ae2PartKeys.PartKind.CONVERSION_MONITOR) {
            return monitorChange(host, side, localKey, value, lower, services, permissions);
        }
        if (kind == Ae2PartKeys.PartKind.P2P_TUNNEL) {
            return p2pChange(host, side, localKey, lower, services, permissions);
        }
        if (kind == Ae2PartKeys.PartKind.ANNIHILATION_PLANE) return Optional.empty();

        String menuId = menuIdOf(kind);
        if (localKey.equals("key_type.item") || localKey.equals("key_type.fluid")) {
            if (kind != Ae2PartKeys.PartKind.IMPORT_BUS) return Optional.empty();
            if (!Ae2PartKeys.BOOL_CHOICES.contains(lower)) return invalid("导出/导入类型只接受 true 或 false");
            return Optional.of(menuAction(host, side, label, menuId, Ae2MenuConfigAction.Kind.KEY_TYPE, localKey,
                    lower, null, -1, false, null, services, permissions));
        }
        if (localKey.equals("priority")) {
            if (!supportsPriority(kind)) return Optional.empty();
            if (!isInt(value)) return invalid("priority 必须为整数：" + value);
            return Optional.of(menuAction(host, side, label, menuId, Ae2MenuConfigAction.Kind.PRIORITY, localKey,
                    value, null, -1, false, null, services, permissions));
        }
        if (localKey.equals("reporting_value")) {
            if (kind != Ae2PartKeys.PartKind.LEVEL_EMITTER && kind != Ae2PartKeys.PartKind.ENERGY_LEVEL_EMITTER) {
                return Optional.empty();
            }
            if (!isNonNegativeLong(value)) return invalid("reporting_value 必须为非负整数：" + value);
            return Optional.of(menuAction(host, side, label, menuId, Ae2MenuConfigAction.Kind.REPORTING_VALUE, localKey,
                    value, null, -1, false, null, services, permissions));
        }
        if (localKey.equals("action")) {
            if (kind != Ae2PartKeys.PartKind.STORAGE_BUS) return Optional.empty();
            if (!Ae2PartKeys.PARTITION_ACTIONS.contains(lower)) return invalid("action 只接受 partition 或 clear");
            return Optional.of(menuAction(host, side, label, menuId, Ae2MenuConfigAction.Kind.NO_ARG_ACTION, localKey,
                    lower, null, -1, false, null, services, permissions));
        }
        return indexedOrButtonChange(host, side, kind, label, menuId, localKey, value, lower, services, permissions);
    }

    private Optional<Action> indexedOrButtonChange(BlockPos host, Direction side, Ae2PartKeys.PartKind kind,
                                                   String label, String menuId, String localKey, String value,
                                                   String lower, PlayerServices services, Permissions permissions) {
        int maxFilters = maxFilterSlots(kind);
        int filterIdx = Ae2PartKeys.indexedSlot(localKey, "filter.", maxFilters);
        if (filterIdx >= 0) {
            return Optional.of(menuAction(host, side, label, menuId, Ae2MenuConfigAction.Kind.FILTER_SLOT, localKey,
                    value, null, filterIdx, false, null, services, permissions));
        }
        if (kind == Ae2PartKeys.PartKind.INTERFACE) {
            int stockIdx = Ae2PartKeys.indexedSlot(localKey, "stock.", Ae2PartKeys.INTERFACE_STOCK_SLOTS);
            if (stockIdx >= 0) {
                Optional<Ae2PartKeys.StockValue> parsed = Ae2PartKeys.parseStockValue(value);
                if (parsed.isEmpty()) {
                    return invalid("stock 写法应为 <物品或流体 ID> [正整数数量] 或 empty：" + value);
                }
                return Optional.of(menuAction(host, side, label, menuId, Ae2MenuConfigAction.Kind.STOCK_SLOT, localKey,
                        value, null, stockIdx, false, parsed.get(), services, permissions));
            }
        }
        int maxUpgrades = maxUpgradeSlots(kind);
        int upgradeIdx = Ae2PartKeys.indexedSlot(localKey, "upgrade.", maxUpgrades);
        if (upgradeIdx >= 0) {
            return Optional.of(menuAction(host, side, label, menuId, Ae2MenuConfigAction.Kind.ITEM_SLOT, localKey,
                    value, null, upgradeIdx, false, null, services, permissions));
        }
        Optional<String> settingName = Ae2PartKeys.ae2SettingName(kind, localKey);
        if (settingName.isEmpty() || !supportsButtonKey(kind, localKey)) return Optional.empty();
        if (!validButtonValue(kind, localKey, lower)) {
            return invalid(localKey + " 不接受取值：" + value);
        }
        return Optional.of(menuAction(host, side, label, menuId, Ae2MenuConfigAction.Kind.CONFIG_BUTTON, localKey,
                lower, settingName.get(), -1, false, null, services, permissions));
    }

    private Optional<Action> monitorChange(BlockPos host, Direction side, String localKey, String value, String lower,
                                           PlayerServices services, Permissions permissions) {
        if (localKey.equals("locked")) {
            if (!Ae2PartKeys.BOOL_CHOICES.contains(lower)) return invalid("locked 只接受 true 或 false");
            return Optional.of(new Ae2DirectPartAction(compat, control, cache, services, host, side,
                    Ae2DirectPartAction.Kind.MONITOR_LOCK, lower, permissions));
        }
        if (localKey.equals("displayed")) {
            if (value.isEmpty()) return invalid("displayed 不能为空");
            return Optional.of(new Ae2DirectPartAction(compat, control, cache, services, host, side,
                    Ae2DirectPartAction.Kind.MONITOR_DISPLAY, value, permissions));
        }
        return Optional.empty();
    }

    private Optional<Action> p2pChange(BlockPos host, Direction side, String localKey, String lower,
                                       PlayerServices services, Permissions permissions) {
        if (localKey.equals("attunement")) {
            if (!Ae2PartKeys.P2P_ATTUNEMENTS.contains(lower)) {
                return invalid("attunement 只接受 me / redstone / item / fluid / fe / light");
            }
            return Optional.of(new Ae2DirectPartAction(compat, control, cache, services, host, side,
                    Ae2DirectPartAction.Kind.P2P_ATTUNEMENT, lower, permissions));
        }
        if (localKey.equals("p2p_role")) {
            if (!Ae2PartKeys.P2P_ROLES.contains(lower)) return invalid("p2p_role 只接受 input 或 output");
            return Optional.of(new Ae2DirectPartAction(compat, control, cache, services, host, side,
                    Ae2DirectPartAction.Kind.P2P_ROLE, lower, permissions));
        }
        return Optional.empty();
    }

    private Ae2MenuConfigAction menuAction(BlockPos host, Direction side, String label, String menuId,
                                           Ae2MenuConfigAction.Kind kind, String localKey, String wanted,
                                           String ae2SettingName, int slotIndex, boolean isCellSlot,
                                           Ae2PartKeys.StockValue stockValue, PlayerServices services,
                                           Permissions permissions) {
        Ae2MenuConfigAction.Spec spec = new Ae2MenuConfigAction.Spec(host, side, label, menuId, kind, localKey,
                wanted, ae2SettingName, slotIndex, isCellSlot, stockValue);
        return new Ae2MenuConfigAction(compat, control, cache, services, spec, permissions);
    }

    private Optional<Ae2Grid.PartView> partAt(ClientLevel level, BlockPos host, Direction side) {
        return cellReader.apply(level, host)
                .filter(Ae2Grid.GridCell::cableBus)
                .map(cell -> cell.parts().get(side));
    }

    private static ClientLevel levelOf(PlayerServices services) {
        PlayerContext ctx = services.context().get();
        return ctx == null ? null : ctx.level();
    }

    private static String menuIdOf(Ae2PartKeys.PartKind kind) {
        return switch (kind) {
            case IMPORT_BUS -> Ae2PartKeys.MENU_IMPORT_BUS;
            case EXPORT_BUS -> Ae2PartKeys.MENU_EXPORT_BUS;
            case STORAGE_BUS -> Ae2PartKeys.MENU_STORAGE_BUS;
            case INTERFACE -> Ae2PartKeys.MENU_INTERFACE;
            case FORMATION_PLANE -> Ae2PartKeys.MENU_FORMATION_PLANE;
            case LEVEL_EMITTER -> Ae2PartKeys.MENU_STORAGE_LEVEL_EMITTER;
            case ENERGY_LEVEL_EMITTER -> Ae2PartKeys.MENU_ENERGY_LEVEL_EMITTER;
            default -> "";
        };
    }

    private static boolean supportsPriority(Ae2PartKeys.PartKind kind) {
        return kind == Ae2PartKeys.PartKind.STORAGE_BUS
                || kind == Ae2PartKeys.PartKind.INTERFACE
                || kind == Ae2PartKeys.PartKind.FORMATION_PLANE;
    }

    private static int maxFilterSlots(Ae2PartKeys.PartKind kind) {
        return switch (kind) {
            case IMPORT_BUS, EXPORT_BUS, STORAGE_BUS, FORMATION_PLANE -> Ae2PartKeys.MAX_FILTER_SLOTS;
            case LEVEL_EMITTER -> 1;
            default -> 0;
        };
    }

    private static int maxUpgradeSlots(Ae2PartKeys.PartKind kind) {
        return switch (kind) {
            case IMPORT_BUS, EXPORT_BUS, STORAGE_BUS, FORMATION_PLANE -> Ae2PartKeys.BUS_UPGRADE_SLOTS;
            case INTERFACE, LEVEL_EMITTER -> 1;
            default -> 0;
        };
    }

    private static boolean supportsButtonKey(Ae2PartKeys.PartKind kind, String key) {
        return switch (kind) {
            case IMPORT_BUS -> key.equals("redstone_mode") || key.equals("fuzzy_mode");
            case EXPORT_BUS -> key.equals("redstone_mode") || key.equals("fuzzy_mode")
                    || key.equals("craft_only") || key.equals("scheduling_mode");
            case STORAGE_BUS -> key.equals("access") || key.equals("storage_filter")
                    || key.equals("filter_on_extract") || key.equals("fuzzy_mode");
            case INTERFACE -> key.equals("fuzzy_mode");
            case FORMATION_PLANE -> key.equals("place_mode") || key.equals("fuzzy_mode");
            case LEVEL_EMITTER -> key.equals("redstone_mode") || key.equals("fuzzy_mode")
                    || key.equals("craft_via_redstone");
            case ENERGY_LEVEL_EMITTER -> key.equals("redstone_mode");
            default -> false;
        };
    }

    private static boolean validButtonValue(Ae2PartKeys.PartKind kind, String key, String value) {
        return switch (key) {
            case "redstone_mode" -> (kind == Ae2PartKeys.PartKind.LEVEL_EMITTER
                    || kind == Ae2PartKeys.PartKind.ENERGY_LEVEL_EMITTER
                    ? Ae2PartKeys.EMITTER_REDSTONE_MODES : Ae2PartKeys.REDSTONE_MODES).contains(value);
            case "fuzzy_mode" -> Ae2PartKeys.FUZZY_MODES.contains(value);
            case "craft_only", "filter_on_extract", "craft_via_redstone" -> Ae2PartKeys.YES_NO.contains(value);
            case "scheduling_mode" -> Ae2PartKeys.SCHEDULING_MODES.contains(value);
            case "access" -> Ae2PartKeys.ACCESS_MODES.contains(value);
            case "storage_filter" -> Ae2PartKeys.STORAGE_FILTER_MODES.contains(value);
            case "place_mode" -> Ae2PartKeys.PLACE_MODES.contains(value);
            default -> false;
        };
    }

    private static boolean isInt(String text) {
        try {
            Integer.parseInt(text);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean isNonNegativeLong(String text) {
        try {
            return Long.parseLong(text) >= 0L;
        } catch (NumberFormatException ignored) {
            return false;
        }
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

    /** 线缆格（ae2:cable_bus）的按面设置路由。 */
    private final class CableSettings implements MachineSettings {

        private final PlayerServices services;

        CableSettings(PlayerServices services) {
            this.services = services;
        }

        @Override public List<MachineSetting> settings(BlockPos at) {
            ClientLevel level = levelOf(services);
            Optional<Ae2Grid.GridCell> cell = cellReader.apply(level, at);
            if (cell.isEmpty() || !cell.get().cableBus()) return List.of();
            List<MachineSetting> all = new ArrayList<>();
            for (Direction side : Direction.values()) {
                List<MachineSetting> local = partSettings(at, side);
                if (!local.isEmpty()) {
                    all.addAll(Ae2PartKeys.prefix(side, local));
                }
            }
            return List.copyOf(all);
        }

        @Override public List<MachineSetting> partSettings(BlockPos host, Direction side) {
            return Ae2PartCableOps.this.partSettings(host, side, services);
        }

        @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
            ClientLevel level = levelOf(services);
            Optional<Ae2PartKeys.SideKey> parsed = Ae2PartKeys.parseSideKey(key);
            if (parsed.isEmpty()) return Optional.empty();
            Direction side = parsed.get().side();
            Optional<Ae2Grid.PartView> part = partAt(level, at, side);
            if (part.isEmpty()) return Optional.empty();
            Optional<Ae2PartKeys.PartKind> kind = Ae2PartKeys.partKindOf(part.get().itemId());
            if (kind.isEmpty()) return Optional.empty();
            return buildPartChange(at, side, kind.get(), parsed.get().localKey(), value, services, permissions);
        }
    }

    /** 方块版 ME 接口（ae2:interface）：与部件版共用全部设置，不带面名前缀。 */
    private final class InterfaceBlockOps implements Ae2DeviceOps {

        @Override public Map<String, Object> extraReadings(BlockPos pos, ClientLevel level) {
            Map<String, Object> cached = cache.readingsFor(pos, null, currentServerReads());
            return cached.containsKey("priority") ? Map.of("priority", cached.get("priority")) : Map.of();
        }

        @Override public Optional<MachineSettings> settings(BlockPos pos, PlayerServices services) {
            return Optional.of(new MachineSettings() {
                @Override public List<MachineSetting> settings(BlockPos at) {
                    Map<String, String> values = cache.settingsFor(at, null, services.serverReads());
                    return Ae2PartKeys.partSettings(Ae2PartKeys.PartKind.INTERFACE, "ae2:interface", values, null);
                }

                @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
                    return buildPartChange(at, null, Ae2PartKeys.PartKind.INTERFACE, key, value, services, permissions);
                }
            });
        }
    }
}
