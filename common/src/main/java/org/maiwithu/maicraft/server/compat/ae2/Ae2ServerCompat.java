// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.compat.ae2;

import java.util.Objects;
import java.util.Optional;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;

import org.maiwithu.maicraft.server.compat.AskedCell;
import org.maiwithu.maicraft.server.compat.ServerCompatModule;
import org.maiwithu.maicraft.server.compat.ServerCompatRegistry;

/**
 * 应用能源2（AE2）的服务端联动入口：登记两项只读操作——
 * {@code ae2.network} 回答指定格所在 ME 网的控制器、频道、能量、存储总量、合成 CPU 与样板容器汇总；
 * {@code ae2.device} 按格坐标与可选面名回答单台设备或线缆部件的权威配置、升级卡与专项读数。
 * 两者均只答角色所在维度、角色附近已加载的格子（{@link AskedCell}）：
 * 看得见那台设备的玩家才问得着，远处别人家的网不回答。不替角色动手，也不列仓库存货明细。
 */
public final class Ae2ServerCompat extends ServerCompatModule {

    public static final String MOD_ID = "ae2";
    /** 只读操作的名字：客户端的 ME 网络读取器经它问。 */
    public static final String OPERATION = "ae2.network";
    /** 单格设备与线缆部件的权威只读操作名。 */
    public static final String DEVICE_OPERATION = Ae2DeviceServerReads.OPERATION;

    private final Ae2GridReads reads;
    private final Ae2NetworkServerExtras networkExtras;
    private final Ae2DeviceServerReads.Reader deviceReads;

    /** 没有扩展网络明细与单格设备读端时：只答基础网络摘要，ae2.device 回答未找到。 */
    public Ae2ServerCompat(Ae2GridReads reads) {
        this(reads, Ae2NetworkServerExtras.NONE, Ae2DeviceServerReads.Reader.NONE);
    }

    public Ae2ServerCompat(Ae2GridReads reads, Ae2NetworkServerExtras networkExtras,
            Ae2DeviceServerReads.Reader deviceReads) {
        super(MOD_ID, "应用能源2");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.networkExtras = Objects.requireNonNull(networkExtras, "networkExtras");
        this.deviceReads = Objects.requireNonNull(deviceReads, "deviceReads");
    }

    @Override public void contribute(ServerCompatRegistry registry) {
        registry.readOperation(this, OPERATION, 1, this::answer);
        Ae2DeviceServerReads.register(this, registry, deviceReads);
    }

    // 问哪一格就答哪一格所在的网：先核对这一格在角色附近、已加载，再读网格服务汇总，
    // 有扩展明细时向后兼容地附加供电消耗、设备分组、合成 CPU 作业与样板容器列表。
    private JsonObject answer(ServerPlayer player, JsonObject body) {
        AskedCell cell = AskedCell.nearPlayer(player, body);
        Optional<Ae2GridReads.GridSummary> summary = call("读 ME 网络摘要",
                () -> reads.summarize(cell.level(), cell.at()));
        if (summary.isEmpty()) {
            JsonObject none = new JsonObject();
            none.addProperty("found", false);
            return none;
        }
        Optional<Ae2NetworkServerExtras.NetworkDetail> detail = call("读 ME 网络扩展明细",
                () -> networkExtras.detail(cell.level(), cell.at()));
        return json(summary.get(), detail);
    }

    /** 汇总写成回答：字段名就是客户端读的名字。 */
    static JsonObject json(Ae2GridReads.GridSummary summary) {
        return json(summary, Optional.empty());
    }

    /** 汇总与扩展明细一起写成回答：完全保留基础字段，有扩展明细时追加耗电、CPU 与样板容器字段。 */
    static JsonObject json(Ae2GridReads.GridSummary summary,
            Optional<Ae2NetworkServerExtras.NetworkDetail> detail) {
        JsonObject out = new JsonObject();
        out.addProperty("found", true);
        out.addProperty("grid", summary.gridKey());
        out.addProperty("nodes", summary.nodes());
        out.addProperty("controller", summary.controller());
        out.addProperty("booting", summary.booting());
        out.addProperty("channel_mode", summary.channelMode());
        out.addProperty("channels_used", summary.channelsUsed());
        out.addProperty("ad_hoc_limit", summary.adHocLimit());
        out.addProperty("ad_hoc_problem", summary.adHocProblem());
        out.addProperty("powered", summary.powered());
        out.addProperty("energy_stored", summary.energyStored());
        out.addProperty("energy_max", summary.energyMax());
        out.addProperty("stored_types", summary.storedTypes());
        out.addProperty("stored_amount", summary.storedAmount());
        out.addProperty("crafting_cpus", summary.craftingCpus());
        out.addProperty("crafting_busy", summary.craftingBusy());
        detail.ifPresent(extra -> Ae2NetworkServerExtras.appendTo(out, extra));
        return out;
    }
}
