// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.compat.mekanism;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 在服务端读通用机械一张管网的模组读写接缝：某一格管道所在那张网有几段管道、连着几处机器、网里存着多少（存量）。
 * 存量只在服务端的网络对象里，客户端读不到。只回答网络级的汇总，不替角色动手。
 * 只用 Minecraft 与 Java 的类型；直接调 Mekanism 网络对象的读写端在 NeoForge 模块里，测试里用替身。
 */
public interface MekanismNetworkReads {

    /** 这一格管道所在那张网的汇总；这一格不是通用机械的管道、或管道还没并进任何网给空。 */
    Optional<PipeNetwork> network(ServerLevel level, BlockPos at);

    /**
     * 一张管网的汇总。
     *
     * @param networkKey 这张网的编号（Mekanism 给每张网的编号）：同一张网在这次服务器运行里问几次都一样
     * @param medium     传什么：energy、fluid、chemical、item、heat
     * @param pipes      网里有几段管道
     * @param machines   连着几处机器：一台机器被几段管道接着算几处
     * @param stock      网里存着多少；物品管网、导热管网不在网里存东西，为空
     */
    record PipeNetwork(String networkKey, String medium, int pipes, int machines, Optional<Stock> stock) {

        public PipeNetwork {
            Objects.requireNonNull(networkKey, "networkKey");
            Objects.requireNonNull(medium, "medium");
            Objects.requireNonNull(stock, "stock");
        }
    }

    /**
     * 网里存着的东西（存量）。
     *
     * @param contents 存着的是什么：流体或化学品的注册 ID；能量网、空着的为空字符串
     * @param stored   存着多少：能量按 FE，流体、化学品按毫桶
     * @param capacity 最多能存多少，单位同 stored
     * @param flow     上一刻往机器送出去多少，单位同 stored；读不到为 -1
     * @param unit     stored 的单位：FE 或 mB
     */
    record Stock(String contents, long stored, long capacity, long flow, String unit) {

        public Stock {
            contents = contents == null ? "" : contents;
            Objects.requireNonNull(unit, "unit");
        }
    }
}
