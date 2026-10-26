// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.compat.mekanism;

import java.util.Objects;
import java.util.Optional;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;

import org.maiwithu.maicraft.server.compat.AskedCell;
import org.maiwithu.maicraft.server.compat.ServerCompatModule;
import org.maiwithu.maicraft.server.compat.ServerCompatRegistry;

/**
 * 通用机械（Mekanism）的服务端联动入口：登记只读操作 mekanism.network——客户端问某一格管道所在那张管网怎样，
 * 回答几段管道、连着几处机器，以及网里存着多少电、多少流体或化学品（存量只在服务端，客户端读不到）。
 * 只答角色所在维度、角色附近已加载的格子（{@link AskedCell}），不替角色动手。
 */
public final class MekanismServerCompat extends ServerCompatModule {

    public static final String MOD_ID = "mekanism";
    /** 只读操作的名字：客户端的通用机械管网读取器经它问。 */
    public static final String OPERATION = "mekanism.network";

    private final MekanismNetworkReads reads;

    public MekanismServerCompat(MekanismNetworkReads reads) {
        super(MOD_ID, "通用机械");
        this.reads = Objects.requireNonNull(reads, "reads");
    }

    @Override public void contribute(ServerCompatRegistry registry) {
        registry.readOperation(this, OPERATION, 1, this::answer);
    }

    // 问哪一格就答哪一格管道所在的网：先核对这一格在角色附近、已加载，再读那张网的汇总；不是管道答 found=false。
    private JsonObject answer(ServerPlayer player, JsonObject body) {
        AskedCell cell = AskedCell.nearPlayer(player, body);
        Optional<MekanismNetworkReads.PipeNetwork> network = call("读管网存量",
                () -> reads.network(cell.level(), cell.at()));
        return network.map(MekanismServerCompat::json).orElseGet(() -> {
            JsonObject none = new JsonObject();
            none.addProperty("found", false);
            return none;
        });
    }

    /**
     * 汇总写成回答：字段名就是客户端读的名字。存量按介质起名（energy_stored、fluid_stored、chemical_stored……），
     * 流体与化学品再写装的是什么（fluid、chemical）和上一刻送出去多少（_flow）；不存东西的网没有这几项。
     */
    static JsonObject json(MekanismNetworkReads.PipeNetwork network) {
        JsonObject out = new JsonObject();
        out.addProperty("found", true);
        out.addProperty("network", network.networkKey());
        out.addProperty("medium", network.medium());
        out.addProperty("pipes", network.pipes());
        out.addProperty("machines", network.machines());
        network.stock().ifPresent(stock -> {
            String medium = network.medium();
            if (!stock.contents().isEmpty()) out.addProperty(medium, stock.contents());
            out.addProperty(medium + "_stored", stock.stored());
            out.addProperty(medium + "_max", stock.capacity());
            if (stock.flow() >= 0) out.addProperty(medium + "_flow", stock.flow());
            out.addProperty(medium + "_unit", stock.unit());
        });
        return out;
    }
}
