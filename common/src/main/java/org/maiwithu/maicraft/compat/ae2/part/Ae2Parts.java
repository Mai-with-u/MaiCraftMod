// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.part;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.ae2.Ae2DeviceOps;
import org.maiwithu.maicraft.compat.ae2.Ae2MachineCatalog;
import org.maiwithu.maicraft.compat.ae2.Ae2SemanticsLayout;
import org.maiwithu.maicraft.game.serverlink.ServerReads;

/**
 * 线缆部件全配置、元件工作台、ME IO 传输站与无线访问点补全的对外统一汇总入口。
 *
 * <p>集中交付增补设备目录、设备委托映射、线缆部件委托以及基于 AE2 槽位语义的菜单布局规则，
 * 供 {@code compat.ae2} 顶层一次性接线，不触碰包根既有文件。
 */
public final class Ae2Parts {

    private final Ae2PartCache cache;
    private final Ae2PartCableOps cableOps;
    private final Map<String, Ae2DeviceOps> deviceOps;

    public Ae2Parts(Ae2Compat compat, Ae2PartControl control) {
        this(compat, control, new Ae2PartCache(), () -> ServerReads.NONE);
    }

    public Ae2Parts(Ae2Compat compat, Ae2PartControl control, Supplier<ServerReads> serverReads) {
        this(compat, control, new Ae2PartCache(), serverReads);
    }

    public Ae2Parts(Ae2Compat compat, Ae2PartControl control, Ae2PartCache cache) {
        this(compat, control, cache, () -> ServerReads.NONE);
    }

    public Ae2Parts(Ae2Compat compat, Ae2PartControl control, Ae2PartCache cache, Supplier<ServerReads> serverReads) {
        Objects.requireNonNull(compat, "compat");
        Objects.requireNonNull(control, "control");
        Objects.requireNonNull(serverReads, "serverReads");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.cableOps = new Ae2PartCableOps(compat, control, cache, serverReads);
        Map<String, Ae2DeviceOps> ops = new LinkedHashMap<>();
        ops.put("ae2:interface", cableOps.interfaceBlockOps());
        ops.put("ae2:cell_workbench", new CellWorkbenchOps(compat, control, cache, serverReads));
        ops.put("ae2:io_port", new CellTransferOps(compat, control, cache, serverReads));
        ops.put("ae2:wireless_access_point", new WirelessAccessPointOps(control, cache, serverReads));
        this.deviceOps = Map.copyOf(ops);
    }

    /** 增补与更新的设备目录项：新增元件工作台，并把 ae2:io_port 的显示名统一为「ME IO 传输站」。 */
    public static List<Ae2MachineCatalog.Kind> extraKinds() {
        return List.of(
                Ae2MachineCatalog.of("cell_workbench", "元件工作台", MachineRole.CONTROL, false),
                Ae2MachineCatalog.of("io_port", "ME IO 传输站", MachineRole.LOGISTICS, true));
    }

    /**
     * 经 AE2 19.2.17 源码核实的语义界面布局规则（全部含 36 格角色背包）。
     *
     * <p>刻意排除已有专属证明的 {@code ae2:wireless_access_point}、{@code ae2:pattern_provider}，
     * 以及不含 36 格玩家背包的子菜单 {@code ae2:priority}、{@code ae2:set_stock_amount}。
     */
    public static Map<String, Ae2SemanticsLayout.Rule> layoutRules() {
        Map<String, Ae2SemanticsLayout.Rule> rules = new LinkedHashMap<>();
        Ae2SemanticsLayout.Rule upgradeOnly = new Ae2SemanticsLayout.Rule(List.of("UPGRADE"), Set.of("UPGRADE"));
        rules.put(Ae2PartKeys.MENU_IMPORT_BUS, upgradeOnly);
        rules.put(Ae2PartKeys.MENU_EXPORT_BUS, upgradeOnly);
        rules.put(Ae2PartKeys.MENU_STORAGE_BUS, upgradeOnly);
        rules.put(Ae2PartKeys.MENU_INTERFACE, new Ae2SemanticsLayout.Rule(List.of("STORAGE", "UPGRADE"), Set.of("UPGRADE")));
        rules.put(Ae2PartKeys.MENU_FORMATION_PLANE, upgradeOnly);
        rules.put(Ae2PartKeys.MENU_STORAGE_LEVEL_EMITTER, upgradeOnly);
        rules.put(Ae2PartKeys.MENU_ENERGY_LEVEL_EMITTER, Ae2SemanticsLayout.Rule.configOnly());
        rules.put(Ae2PartKeys.MENU_CELL_WORKBENCH,
                new Ae2SemanticsLayout.Rule(List.of("STORAGE_CELL", "UPGRADE"), Set.of("STORAGE_CELL", "UPGRADE")));
        rules.put(Ae2PartKeys.MENU_CELL_TRANSFER,
                new Ae2SemanticsLayout.Rule(List.of("MACHINE_INPUT", "MACHINE_OUTPUT", "UPGRADE"),
                        Set.of("MACHINE_INPUT", "MACHINE_OUTPUT", "UPGRADE")));
        return Map.copyOf(rules);
    }

    /** 方块设备的委托映射：ME 接口、元件工作台、ME IO 传输站与无线访问点。 */
    public Map<String, Ae2DeviceOps> deviceOps() {
        return deviceOps;
    }

    /** 挂载于 ae2:cable_bus 的全部线缆部件按面配置委托。 */
    public Ae2DeviceOps cablePartOps() {
        return cableOps;
    }

    /** 本模块共享的会话缓存与 ae2.device 服务端权威读数合并器。 */
    public Ae2PartCache cache() {
        return cache;
    }
}
