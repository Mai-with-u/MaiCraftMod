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
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.FeedPlan;
import org.maiwithu.maicraft.ability.machine.spi.MachineFeeding;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineSettings;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.behavior.inventory.containers.MenuFeed;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuOutputLook;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 电路切片器（{@code extendedae:circuit_cutter}）设备委托：
 * 除相对正面与背面外的四面连 ME 网与受电；客户端直读 {@code working}、输入槽与渲染产物；
 * 投料走 {@link MenuFeed}，收产物走 {@link MenuOutputLook}（兼容 {@code wait=false}）；
 * 配置支持 {@code auto_export}、{@code output_side.<dir>} 与 {@code upgrade.0..3}。
 */
final class CircuitCutterOps implements ExtendedAeDeviceOps {

    static final int UPGRADE_SLOTS = 4;
    static final int MAX_PROGRESS = 200;
    private static final List<String> YES_NO = List.of("yes", "no");
    private static final List<String> TRUE_FALSE = List.of("true", "false");
    private static final List<String> UPGRADE_CHOICES = List.of("ae2:speed_card", "empty");

    private final ExtendedAeCompat compat;
    private final ExtendedAeDeviceReadings readings;
    private final BiFunction<BlockPos, Permissions, MenuOpening> menuOpenings;

    CircuitCutterOps(ExtendedAeCompat compat, ExtendedAeDeviceReadings readings) {
        this(compat, readings, null);
    }

    CircuitCutterOps(ExtendedAeCompat compat, ExtendedAeDeviceReadings readings,
            BiFunction<BlockPos, Permissions, MenuOpening> menuOpenings) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.readings = Objects.requireNonNull(readings, "readings");
        this.menuOpenings = menuOpenings;
    }

    // 电路切片器除相对正面与背面外的四面可连 ME 网与受电。
    @Override public Optional<List<ExchangePoint>> exchangePoints(BlockState state, BlockPos at, ClientLevel level) {
        Optional<ExtendedAeReads.CircuitCutterView> view = level == null
                ? Optional.empty() : compat.circuitCutterAt(level, at);
        Direction front = view.map(ExtendedAeReads.CircuitCutterView::front).orElse(Direction.NORTH);
        Direction back = view.map(ExtendedAeReads.CircuitCutterView::back).orElse(front.getOpposite());
        List<ExchangePoint> points = new ArrayList<>(4);
        for (Direction side : Direction.values()) {
            if (side == front || side == back) continue;
            points.add(new ExchangePoint(at, side, null, ME_NETWORK, ExchangePoint.Flow.BOTH,
                    "电路切片器侧面：除前后外四面可连 ME 网与受电"));
        }
        return Optional.of(List.copyOf(points));
    }

    @Override public Optional<MachineState> state(BlockPos at, ClientLevel level) {
        if (level == null) return Optional.of(MachineState.unknown("角色不在世界里，读不到电路切片器"));
        Optional<ExtendedAeReads.CircuitCutterView> found = compat.circuitCutterAt(level, at);
        if (found.isEmpty()) return Optional.empty();
        return Optional.of(buildState(found.get(), readings.circuitCutter(at), readings.latest(at)));
    }

    /** 纯函数：整合客户端同步态、开窗缓存与服务端读数构造电路切片器状态。 */
    static MachineState buildState(ExtendedAeReads.CircuitCutterView view,
            Optional<ExtendedAeReads.CircuitCutterMenuView> cached, Optional<JsonObject> serverReply) {
        Map<String, Object> readingsMap = new LinkedHashMap<>();
        readingsMap.put("working", view.working());
        readingsMap.put("powered", view.powered());
        readingsMap.put("active", view.active());
        readingsMap.put("max_processing_time", (long) MAX_PROGRESS);
        List<MachineState.Shown> shown = new ArrayList<>();
        if (view.input() != null) {
            readingsMap.put("input", view.input().itemId() + " ×" + view.input().count());
            shown.add(view.input());
        } else {
            readingsMap.put("input", "empty");
        }
        if (view.renderOutput() != null) {
            readingsMap.put("render_output", view.renderOutput().itemId() + " ×" + view.renderOutput().count());
            shown.add(view.renderOutput());
        } else {
            readingsMap.put("render_output", "empty");
        }
        cached.ifPresent(menu -> applyMenuCache(readingsMap, menu));
        serverReply.ifPresent(reply -> ExtendedAeDeviceReadings.mergeServerReply(readingsMap, reply));
        MachineState.Activity activity = !view.powered() ? MachineState.Activity.OFFLINE
                : view.working() ? MachineState.Activity.RUNNING : MachineState.Activity.IDLE;
        return new MachineState(activity, null, null, List.copyOf(shown), readingsMap,
                "除前后外四面可连 ME 网受电；投入整块原料可一次切出多张电路板");
    }

    private static void applyMenuCache(Map<String, Object> target, ExtendedAeReads.CircuitCutterMenuView menu) {
        target.put("processing_time", (long) menu.processingTime());
        target.put("max_processing_time", (long) menu.maxProcessingTime());
        target.put("auto_export", menu.autoExport());
        target.put("output_sides", menu.outputSides());
        for (int i = 0; i < UPGRADE_SLOTS; i++) {
            target.put("upgrade." + i, i < menu.upgrades().size() ? menu.upgrades().get(i) : "empty");
        }
    }

    // 输出槽实际堆叠数只在打开界面或服务端同步，投完料走界面回头看或 wait=false 直接返回。
    @Override public boolean outputVisible(BlockPos at) {
        return false;
    }

    @Override public Optional<MachineFeeding> feeding(BlockPos pos, PlayerServices services) {
        return Optional.of(new MachineFeeding() {
            @Override public Optional<Action> feed(BlockPos at, FeedPlan.Entry item, Permissions permissions) {
                return Optional.of(new MenuFeed(services, "电路切片器", at,
                        ExtendedAeMachineCatalog.CIRCUIT_CUTTER, item.item(), item.count(), permissions, null));
            }

            @Override public Optional<Action> take(BlockPos at, String itemId, int count, Permissions permissions) {
                return Optional.of(new MenuOutputLook(itemId, true, openingFor(at, permissions, services),
                        services.context()));
            }
        });
    }

    @Override public Optional<MachineSettings> settings(BlockPos pos, PlayerServices services) {
        return Optional.of(new MachineSettings() {
            @Override public List<MachineSetting> settings(BlockPos at) {
                return cutterSettings(readings.circuitCutter(at), readings.latest(at));
            }

            @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
                return changeSetting(at.immutable(), key, value, permissions, services);
            }
        });
    }

    /** 纯函数：构造电路切片器设置项清单。 */
    static List<MachineSetting> cutterSettings(Optional<ExtendedAeReads.CircuitCutterMenuView> cached,
            Optional<JsonObject> serverReply) {
        List<MachineSetting> list = new ArrayList<>(1 + Direction.values().length + UPGRADE_SLOTS);
        String autoExport = cached.map(ExtendedAeReads.CircuitCutterMenuView::autoExport)
                .or(() -> serverReply.flatMap(r -> ExtendedAeDeviceReadings.readSetting(r, "auto_export")))
                .orElse("unknown");
        list.add(new MachineSetting("auto_export", autoExport, YES_NO,
                "自动输出：yes 时成品自动推送至已勾选输出面"));
        List<String> enabledSides = cached.map(ExtendedAeReads.CircuitCutterMenuView::outputSides)
                .or(() -> serverReply.flatMap(r -> ExtendedAeDeviceReadings.readStringList(r, "output_sides")))
                .orElse(null);
        for (Direction dir : Direction.values()) {
            String dirName = dir.getSerializedName();
            String cur = enabledSides == null ? "unknown"
                    : enabledSides.contains(dirName) ? "true" : "false";
            list.add(new MachineSetting("output_side." + dirName, cur, TRUE_FALSE,
                    "自动输出方向 " + dirName + " 开关（true/false）"));
        }
        List<String> upgrades = cached.map(ExtendedAeReads.CircuitCutterMenuView::upgrades)
                .orElseGet(() -> serverReply.map(ExtendedAeDeviceReadings::readUpgrades).orElse(List.of()));
        for (int i = 0; i < UPGRADE_SLOTS; i++) {
            String card = i < upgrades.size() ? upgrades.get(i) : (cached.isPresent() ? "empty" : "unknown");
            list.add(new MachineSetting("upgrade." + i, card, UPGRADE_CHOICES,
                    "电路切片器第 " + i + " 号升级槽（ae2:speed_card / empty）"));
        }
        return List.copyOf(list);
    }

    private Optional<Action> changeSetting(BlockPos target, String key, String value,
            Permissions permissions, PlayerServices services) {
        if ("auto_export".equals(key)) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (!YES_NO.contains(normalized)) return Optional.empty();
            return Optional.of(new ExtendedAeMenuActionSupport.ConfigCycleAction(
                    openingFor(target, permissions, services), compat, "auto_export", normalized, 3,
                    () -> compat.openCircuitCutterMenu().map(ExtendedAeReads.CircuitCutterMenuView::autoExport),
                    () -> compat.openCircuitCutterMenu().ifPresent(v -> readings.putCircuitCutter(target, v)),
                    "电路切片器自动输出"));
        }
        if (key.startsWith("output_side.")) {
            Direction dir = Direction.byName(key.substring("output_side.".length()).toLowerCase(Locale.ROOT));
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (dir == null || !TRUE_FALSE.contains(normalized)) return Optional.empty();
            boolean enabled = "true".equals(normalized);
            String dirName = dir.getSerializedName();
            return Optional.of(new ExtendedAeMenuActionSupport.GenericPacketAction(
                    openingFor(target, permissions, services),
                    () -> compat.openCircuitCutterMenu().isPresent(),
                    () -> compat.openCircuitCutterMenu()
                            .map(v -> v.outputSides().contains(dirName) == enabled).orElse(false),
                    () -> compat.sendGenericAction("set_side", dirName, enabled),
                    () -> compat.openCircuitCutterMenu()
                            .map(v -> v.outputSides().contains(dirName) == enabled).orElse(false),
                    () -> compat.openCircuitCutterMenu().ifPresent(v -> readings.putCircuitCutter(target, v)),
                    "设置电路切片器输出面 " + dirName + "=" + enabled));
        }
        int slot = ExtendedAeMenuActionSupport.parseUpgradeSlot(key, UPGRADE_SLOTS);
        if (slot < 0) return Optional.empty();
        boolean remove = "empty".equalsIgnoreCase(value) || "out".equalsIgnoreCase(value);
        if (!remove && !"ae2:speed_card".equals(value)) return Optional.empty();
        return Optional.of(new ExtendedAeMenuActionSupport.SemanticSlotSwapAction(
                openingFor(target, permissions, services), compat, "UPGRADE", slot,
                remove ? null : snap -> "ae2:speed_card".equals(snap.key()),
                remove ? "空位" : "ae2:speed_card",
                () -> compat.openCircuitCutterMenu().ifPresent(v -> readings.putCircuitCutter(target, v)),
                "电路切片器升级槽 " + slot));
    }

    private MenuOpening openingFor(BlockPos target, Permissions permissions, PlayerServices services) {
        return menuOpenings != null ? menuOpenings.apply(target, permissions)
                : ExtendedAeMenuActionSupport.openBlock(target, services, permissions);
    }
}
