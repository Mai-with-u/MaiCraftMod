// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.advanced;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.ae2.Ae2DeviceOps;
import org.maiwithu.maicraft.compat.ae2.Ae2MachineCatalog;
import org.maiwithu.maicraft.compat.ae2.Ae2SemanticsLayout;

/**
 * AE2 高级机制子包的统一对外汇编入口：供最终汇总接线把量子环/量子链接仓、奇点爆炸转化来源、
 * 空间塔、空间传输站与空间锚一次性接入 {@link Ae2Compat}、设备目录与语义界面证明。
 *
 * <p>服务端侧的 {@code ae2.device} 注册与 {@code ae2.network} 扩展写出因归属服务端层，
 * 分别由 {@code server.compat.ae2.Ae2DeviceServerReads#register} 与
 * {@code server.compat.ae2.Ae2NetworkServerExtras#appendTo} 提供，不跨层引入此处。
 */
public final class Ae2Advanced {

    /** 客户端向服务端请求单格权威设备读数使用的操作名，与服务端保持完全一致。 */
    public static final String DEVICE_OPERATION = Ae2DeviceReadings.DEVICE_OPERATION;
    /** 服务端设备只读操作版本号。 */
    public static final int DEVICE_OPERATION_VERSION = Ae2DeviceReadings.DEVICE_OPERATION_VERSION;

    private Ae2Advanced() {
    }

    /** 本组增补的设备种类：空间塔、空间传输站（ae2:spatial_io_port）与空间锚。 */
    public static List<Ae2MachineCatalog.Kind> extraKinds() {
        return List.of(
                Ae2MachineCatalog.of("spatial_pylon", "空间塔", MachineRole.STORAGE, true),
                Ae2MachineCatalog.of("spatial_io_port", "空间传输站", MachineRole.STORAGE, true),
                Ae2MachineCatalog.of("spatial_anchor", "空间锚", MachineRole.CONTROL, true));
    }

    /**
     * 本组设备操作委托映射：覆盖量子环/链接仓、空间塔、空间传输站与空间锚。
     * 共享同一份 {@link Ae2DeviceReadings} 缓存，避免同格重复询问服务端。
     */
    public static Map<String, Ae2DeviceOps> deviceOps(Ae2Compat compat, Ae2AdvancedReads reads,
                                                      PlayerServices services) {
        Objects.requireNonNull(compat, "compat");
        Objects.requireNonNull(reads, "reads");
        Objects.requireNonNull(services, "services");
        Ae2DeviceReadings serverReadings = new Ae2DeviceReadings(services.serverReads());
        Map<String, Ae2DeviceOps> ops = new LinkedHashMap<>();
        ops.put(QuantumRing.RING_BLOCK, new QuantumLink(compat, reads, serverReadings));
        ops.put(SpatialPylons.BLOCK_ID, new SpatialPylons(reads, serverReadings));
        ops.put(SpatialTransfer.BLOCK_ID, new SpatialTransfer(compat, reads, serverReadings));
        ops.put(SpatialAnchor.BLOCK_ID, new SpatialAnchor(compat, reads, serverReadings));
        return Map.copyOf(ops);
    }

    /**
     * 本组独占的含 36 格玩家背包的菜单语义规则：
     * <ul>
     * <li>{@code ae2:qnb}：量子链接仓（STORAGE 槽放取缠绕奇点）</li>
     * <li>{@code ae2:spatialioport}：空间传输站（MACHINE_INPUT / MACHINE_OUTPUT 两格空间元件槽）</li>
     * </ul>
     * 注意：{@code ae2:spatialanchor} 不含 36 格玩家背包，绝不注册进语义容器布局。
     */
    public static Map<String, Ae2SemanticsLayout.Rule> layoutRules() {
        Map<String, Ae2SemanticsLayout.Rule> rules = new LinkedHashMap<>();
        rules.put(QuantumRing.QNB_MENU,
                new Ae2SemanticsLayout.Rule(List.of("STORAGE"), Set.of("STORAGE")));
        rules.put(SpatialTransfer.MENU_ID,
                new Ae2SemanticsLayout.Rule(
                        List.of("MACHINE_INPUT", "MACHINE_OUTPUT"),
                        Set.of("MACHINE_INPUT", "MACHINE_OUTPUT")));
        return Map.copyOf(rules);
    }

    /** 本组提供的物品来源列表：带客户端高级读端的奇点爆炸转化来源。 */
    public static List<ItemSource> itemSources(Ae2AdvancedReads reads, PlayerServices services) {
        Objects.requireNonNull(reads, "reads");
        Objects.requireNonNull(services, "services");
        return List.of(new Ae2ExplosionTransformSource(reads, services));
    }

    /** 仅传入玩家服务时的便捷重载（无客户端高级读端替身时默认使用 {@link Ae2AdvancedReads#NONE}）。 */
    public static List<ItemSource> itemSources(PlayerServices services) {
        return itemSources(Ae2AdvancedReads.NONE, services);
    }
}
