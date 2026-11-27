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
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineSettings;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * ExtendedAE 设施与多方块委托集合：
 * ME 原料缓存器（36 格通用缓存）、ME 装罐机（仅顶底两面连网与 fill/empty 模式切换）、
 * ME 无线连接器与无线集线器（服务端读频率/对端/能耗、升级卡、集线器通道断开）以及
 * 组装矩阵多方块 6 种方块（成型/供电/集群边界/核心统计/取消全部合成作业）。
 */
final class ExtendedAeFacilityOps {

    private static final List<String> CANER_MODES = List.of("fill", "empty");
    private static final List<String> WIRELESS_UPGRADE_CHOICES = List.of("ae2:energy_card", "empty");
    private static final List<String> HUB_LINK_INDEX_CHOICES = List.of("0", "1", "2", "3", "4", "5", "6", "7");

    private ExtendedAeFacilityOps() {}

    /** ME 原料缓存器（{@code extendedae:ingredient_buffer}，36 格物品与流体缓存）。 */
    static final class IngredientBufferOps implements ExtendedAeDeviceOps {

        private final ExtendedAeDeviceReadings readings;

        IngredientBufferOps(ExtendedAeDeviceReadings readings) {
            this.readings = Objects.requireNonNull(readings, "readings");
        }

        @Override public Optional<List<ExchangePoint>> exchangePoints(BlockState state, BlockPos at,
                ClientLevel level) {
            return Optional.of(List.of(new ExchangePoint(at, null, ExchangePoint.Medium.ITEM,
                    ExchangePoint.Flow.BOTH, "36 格通用物品与流体缓存方块，可通过界面或管道存取")));
        }

        @Override public Optional<MachineState> state(BlockPos at, ClientLevel level) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("total_slots", 36);
            readings.latest(at).ifPresent(reply -> ExtendedAeDeviceReadings.mergeServerReply(map, reply));
            return Optional.of(new MachineState(MachineState.Activity.IDLE, null, null, List.of(), map,
                    "36 格 ME 原料缓存器，同时容纳物品与流体"));
        }
    }

    /** ME 装罐机（{@code extendedae:caner}，仅顶底两面连网，支持 {@code mode=fill|empty}）。 */
    static final class CanerOps implements ExtendedAeDeviceOps {

        private final ExtendedAeCompat compat;
        private final ExtendedAeDeviceReadings readings;
        private final BiFunction<BlockPos, Permissions, MenuOpening> menuOpenings;

        CanerOps(ExtendedAeCompat compat, ExtendedAeDeviceReadings readings,
                BiFunction<BlockPos, Permissions, MenuOpening> menuOpenings) {
            this.compat = Objects.requireNonNull(compat, "compat");
            this.readings = Objects.requireNonNull(readings, "readings");
            this.menuOpenings = menuOpenings;
        }

        // ME 装罐机仅相对顶面与底面两面可接入 ME 网络与受电。
        @Override public Optional<List<ExchangePoint>> exchangePoints(BlockState state, BlockPos at,
                ClientLevel level) {
            Optional<ExtendedAeReads.CanerView> view = level == null ? Optional.empty() : compat.canerAt(level, at);
            Direction top = view.map(ExtendedAeReads.CanerView::topFace).orElse(Direction.UP);
            Direction bottom = view.map(ExtendedAeReads.CanerView::bottomFace).orElse(top.getOpposite());
            return Optional.of(List.of(
                    new ExchangePoint(at, top, null, ME_NETWORK, ExchangePoint.Flow.BOTH,
                            "装罐机顶面：可连 ME 网与受电"),
                    new ExchangePoint(at, bottom, null, ME_NETWORK, ExchangePoint.Flow.BOTH,
                            "装罐机底面：可连 ME 网与受电")));
        }

        @Override public Optional<MachineState> state(BlockPos at, ClientLevel level) {
            if (level == null) return Optional.of(MachineState.unknown("角色不在世界里，读不到 ME 装罐机"));
            Optional<ExtendedAeReads.CanerView> found = compat.canerAt(level, at);
            if (found.isEmpty()) return Optional.empty();
            ExtendedAeReads.CanerView view = found.get();
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("powered", view.powered());
            map.put("active", view.active());
            if (!view.mode().isEmpty()) map.put("mode", view.mode());
            readings.caner(at).ifPresent(c -> map.put("mode", c.mode()));
            readings.latest(at).ifPresent(reply -> ExtendedAeDeviceReadings.mergeServerReply(map, reply));
            MachineState.Activity activity = !view.powered() ? MachineState.Activity.OFFLINE
                    : view.active() ? MachineState.Activity.RUNNING : MachineState.Activity.IDLE;
            return Optional.of(new MachineState(activity, null, null, List.of(), map,
                    "仅顶面与底面两面连 ME 网受电；支持 fill（灌装）与 empty（抽空）两种模式"));
        }

        @Override public Optional<MachineSettings> settings(BlockPos pos, PlayerServices services) {
            return Optional.of(new MachineSettings() {
                @Override public List<MachineSetting> settings(BlockPos at) {
                    String mode = readings.caner(at).map(ExtendedAeReads.CanerMenuView::mode)
                            .or(() -> readings.latest(at)
                                    .flatMap(r -> ExtendedAeDeviceReadings.readString(r, "mode")))
                            .orElse("unknown");
                    return List.of(new MachineSetting("mode", mode, CANER_MODES,
                            "装罐模式：fill（向容器灌入）或 empty（从容器抽出）"));
                }

                @Override public Optional<Action> change(BlockPos at, String key, String value,
                        Permissions permissions) {
                    if (!"mode".equals(key)) return Optional.empty();
                    String normalized = value.trim().toLowerCase(Locale.ROOT);
                    int ordinal = CANER_MODES.indexOf(normalized);
                    if (ordinal < 0) return Optional.empty();
                    BlockPos target = at.immutable();
                    return Optional.of(new ExtendedAeMenuActionSupport.GenericPacketAction(
                            openingFor(target, permissions, services, menuOpenings),
                            () -> compat.openCanerMenu().isPresent(),
                            () -> compat.openCanerMenu().map(v -> normalized.equals(v.mode())).orElse(false),
                            () -> compat.sendGenericAction("set", ordinal),
                            () -> compat.openCanerMenu().map(v -> normalized.equals(v.mode())).orElse(false),
                            () -> compat.openCanerMenu().ifPresent(v -> readings.putCaner(target, v)),
                            "切换 ME 装罐机模式为 " + normalized));
                }
            });
        }
    }

    /** ME 无线连接器与无线集线器（{@code extendedae:wireless_connect} / {@code extendedae:wireless_hub}）。 */
    static final class WirelessOps implements ExtendedAeDeviceOps {

        static final int UPGRADE_SLOTS = 4;

        private final ExtendedAeCompat compat;
        private final ExtendedAeDeviceReadings readings;
        private final boolean hub;
        private final BiFunction<BlockPos, Permissions, MenuOpening> menuOpenings;

        WirelessOps(ExtendedAeCompat compat, ExtendedAeDeviceReadings readings, boolean hub,
                BiFunction<BlockPos, Permissions, MenuOpening> menuOpenings) {
            this.compat = Objects.requireNonNull(compat, "compat");
            this.readings = Objects.requireNonNull(readings, "readings");
            this.hub = hub;
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
            if (hub) {
                readings.wirelessHub(at).ifPresent(menu -> {
                    map.put("power_use", menu.powerUse());
                    map.put("used_channels", menu.usedChannel());
                    map.put("max_channels", menu.maxChannel());
                });
            }
            Optional<JsonObject> reply = readings.latest(at);
            reply.ifPresent(r -> ExtendedAeDeviceReadings.mergeServerReply(map, r));
            MachineState.Activity activity = ExtendedAeNetworkOps.activityOf(net);
            String note = hub
                    ? "ME 无线集线器（最多 8 条无线配对通道，拿 extendedae:wireless_tool 依次右键绑定）"
                    : "ME 无线连接器（点对点无线传输频道，拿 extendedae:wireless_tool 依次右键两端配对）";
            return Optional.of(new MachineState(activity, null, null, List.of(), map, note));
        }

        @Override public Optional<MachineSettings> settings(BlockPos pos, PlayerServices services) {
            return Optional.of(new MachineSettings() {
                @Override public List<MachineSetting> settings(BlockPos at) {
                    List<String> upgrades = readings.latest(at)
                            .map(ExtendedAeDeviceReadings::readUpgrades).orElse(List.of());
                    List<MachineSetting> list = new ArrayList<>(UPGRADE_SLOTS + (hub ? 1 : 0));
                    for (int i = 0; i < UPGRADE_SLOTS; i++) {
                        String cur = i < upgrades.size() ? upgrades.get(i) : "unknown";
                        list.add(new MachineSetting("upgrade." + i, cur, WIRELESS_UPGRADE_CHOICES,
                                "无线设备第 " + i + " 号升级槽（ae2:energy_card / empty）"));
                    }
                    if (hub) {
                        list.add(new MachineSetting("disconnect_port", "none", HUB_LINK_INDEX_CHOICES,
                                "断开无线集线器指定编号（0..7）的无线连接"));
                    }
                    return List.copyOf(list);
                }

                @Override public Optional<Action> change(BlockPos at, String key, String value,
                        Permissions permissions) {
                    BlockPos target = at.immutable();
                    if (hub && "disconnect_port".equals(key)) {
                        Integer linkIndex = parseHubIndex(value);
                        if (linkIndex == null) return Optional.empty();
                        int index = linkIndex;
                        return Optional.of(new ExtendedAeMenuActionSupport.GenericPacketAction(
                                openingFor(target, permissions, services, menuOpenings),
                                () -> compat.openWirelessHubMenu().isPresent(),
                                () -> isHubLinkUnconnected(compat.openWirelessHubMenu(), index),
                                () -> compat.sendGenericAction("disconnect", index),
                                () -> isHubLinkUnconnected(compat.openWirelessHubMenu(), index),
                                () -> compat.openWirelessHubMenu().ifPresent(v -> readings.putWirelessHub(target, v)),
                                "断开无线集线器 " + index + " 号通道"));
                    }
                    int slot = ExtendedAeMenuActionSupport.parseUpgradeSlot(key, UPGRADE_SLOTS);
                    if (slot < 0) return Optional.empty();
                    boolean remove = "empty".equalsIgnoreCase(value) || "out".equalsIgnoreCase(value);
                    if (!remove && !"ae2:energy_card".equals(value)) return Optional.empty();
                    return Optional.of(new ExtendedAeMenuActionSupport.SemanticSlotSwapAction(
                            openingFor(target, permissions, services, menuOpenings), compat, "UPGRADE", slot,
                            remove ? null : snap -> "ae2:energy_card".equals(snap.key()),
                            remove ? "空位" : "ae2:energy_card",
                            () -> {}, "无线设备升级槽 " + slot));
                }
            });
        }

        private static boolean isHubLinkUnconnected(Optional<ExtendedAeReads.WirelessHubMenuView> view, int index) {
            if (view.isEmpty() || index < 0 || index >= view.get().links().size()) return false;
            return "unconnected".equalsIgnoreCase(view.get().links().get(index).status());
        }

        private static Integer parseHubIndex(String raw) {
            if (raw == null) return null;
            try {
                int idx = Integer.parseInt(raw.trim());
                return idx >= 0 && idx < ExtendedAeReads.WIRELESS_HUB_SLOTS ? idx : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }

    /** 组装矩阵多方块（{@code extendedae:assembler_matrix_*} 6 种方块统一认领）。 */
    static final class AssemblerMatrixOps implements ExtendedAeDeviceOps {

        private static final List<String> CANCEL_VALUES = List.of("true", "yes");

        private final ExtendedAeCompat compat;
        private final ExtendedAeDeviceReadings readings;
        private final BiFunction<BlockPos, Permissions, MenuOpening> menuOpenings;

        AssemblerMatrixOps(ExtendedAeCompat compat, ExtendedAeDeviceReadings readings,
                BiFunction<BlockPos, Permissions, MenuOpening> menuOpenings) {
            this.compat = Objects.requireNonNull(compat, "compat");
            this.readings = Objects.requireNonNull(readings, "readings");
            this.menuOpenings = menuOpenings;
        }

        @Override public Optional<MachineState> state(BlockPos at, ClientLevel level) {
            if (level == null) return Optional.of(MachineState.unknown("角色不在世界里，读不到组装矩阵"));
            Optional<ExtendedAeReads.MatrixView> found = compat.matrixBlockAt(level, at);
            if (found.isEmpty()) return Optional.empty();
            return Optional.of(buildMatrixState(found.get(), readings.latest(at)));
        }

        /** 纯函数：整合组装矩阵客户端成型/供电态与服务端集群边界、核心与样板统计。 */
        static MachineState buildMatrixState(ExtendedAeReads.MatrixView view, Optional<JsonObject> serverReply) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("formed", view.formed());
            map.put("powered", view.powered());
            map.put("active", view.active());
            serverReply.ifPresent(reply -> ExtendedAeDeviceReadings.mergeServerReply(map, reply));
            MachineState.Activity activity;
            if (!view.formed() || !view.powered()) {
                activity = MachineState.Activity.OFFLINE;
            } else if (view.active()) {
                activity = MachineState.Activity.RUNNING;
            } else {
                activity = MachineState.Activity.IDLE;
            }
            String note = view.formed()
                    ? "组装矩阵已成型；配置 cancel_all=true 可取消全部在途合成作业"
                    : "组装矩阵尚未成型（需用框架/墙壁/玻璃围成至少 3×3×3 完整长方体外壳，内部填样板/合成/速度核心）";
            return new MachineState(activity, null, null, List.of(), map, note);
        }

        @Override public Optional<MachineSettings> settings(BlockPos pos, PlayerServices services) {
            return Optional.of(new MachineSettings() {
                @Override public List<MachineSetting> settings(BlockPos at) {
                    return List.of(new MachineSetting("cancel_all", "idle", CANCEL_VALUES,
                            "设为 true 打开组装矩阵界面并取消全部在途合成任务"));
                }

                @Override public Optional<Action> change(BlockPos at, String key, String value,
                        Permissions permissions) {
                    if (!"cancel_all".equals(key)) return Optional.empty();
                    if (!CANCEL_VALUES.contains(value.trim().toLowerCase(Locale.ROOT))) return Optional.empty();
                    boolean[] sent = {false};
                    return Optional.of(new ExtendedAeMenuActionSupport.GenericPacketAction(
                            openingFor(at.immutable(), permissions, services, menuOpenings),
                            compat::isAssemblerMatrixMenuOpen,
                            () -> false,
                            () -> {
                                boolean ok = compat.sendGenericAction("cancel");
                                if (ok) sent[0] = true;
                                return ok;
                            },
                            () -> sent[0],
                            () -> {},
                            "取消组装矩阵全部在途合成作业"));
                }
            });
        }
    }

    private static MenuOpening openingFor(BlockPos target, Permissions permissions, PlayerServices services,
            BiFunction<BlockPos, Permissions, MenuOpening> override) {
        return override != null ? override.apply(target, permissions)
                : ExtendedAeMenuActionSupport.openBlock(target, services, permissions);
    }
}
