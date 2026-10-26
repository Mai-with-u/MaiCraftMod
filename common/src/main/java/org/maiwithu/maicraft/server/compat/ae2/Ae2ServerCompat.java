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
 * 应用能源2（AE2）的服务端联动入口：登记只读操作 ae2.network——客户端问某一格所在那张 ME 网怎样，
 * 回答控制器、频道、能量、存储总量、合成 CPU 的汇总。只答角色所在维度、角色附近已加载的格子（{@link AskedCell}）：
 * 看得见那台设备的玩家才问得着，远处别人家的网不回答。不替角色动手，也不列存了哪些东西。
 */
public final class Ae2ServerCompat extends ServerCompatModule {

    public static final String MOD_ID = "ae2";
    /** 只读操作的名字：客户端的 ME 网络读取器经它问。 */
    public static final String OPERATION = "ae2.network";

    private final Ae2GridReads reads;

    public Ae2ServerCompat(Ae2GridReads reads) {
        super(MOD_ID, "应用能源2");
        this.reads = Objects.requireNonNull(reads, "reads");
    }

    @Override public void contribute(ServerCompatRegistry registry) {
        registry.readOperation(this, OPERATION, 1, this::answer);
    }

    // 问哪一格就答哪一格所在的网：先核对这一格在角色附近、已加载，再读网格服务的汇总。
    private JsonObject answer(ServerPlayer player, JsonObject body) {
        AskedCell cell = AskedCell.nearPlayer(player, body);
        Optional<Ae2GridReads.GridSummary> summary = call("读 ME 网络摘要",
                () -> reads.summarize(cell.level(), cell.at()));
        return summary.map(Ae2ServerCompat::json).orElseGet(() -> {
            JsonObject none = new JsonObject();
            none.addProperty("found", false);
            return none;
        });
    }

    /** 汇总写成回答：字段名就是客户端读的名字。 */
    static JsonObject json(Ae2GridReads.GridSummary summary) {
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
        return out;
    }
}
