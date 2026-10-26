// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.game.serverlink.ClientRequest;
import org.maiwithu.maicraft.game.serverlink.ServerReads;

/**
 * 网络读取器向服务端要整网读数的那一段：到点才问，问出去没收回时不重复问，一答没答上在限额内马上重问；
 * 手里的回答过了保质期、或这一问还在路上时，照样交回，同时给出"这是几秒前的数据"那半句。
 * 回答怎么翻成网络汇总、服务端不答这个操作时怎么说，由各网络读取器自己定；这里只管问与收。
 */
final class ServerNetworkReadings {

    /** 一份服务端回答用多久（毫秒）：过了重新问，网络状态会变。 */
    static final long FRESH_MILLIS = 5_000;
    /** 一答没答上后再重问的次数上限：请求被会话轮换退场这类一过性失败重问就答上了，连着几次都失败就如实交"读不到"。 */
    private static final int MAX_RETRIES = 2;

    private final String operationId;
    private final ServerReads reads;
    /** 时刻从外面给：离线测试拨钟用，生产就是系统时钟。 */
    private final LongSupplier now;
    /** 问话账：问出去还没答的、最近一次答的、连着几次没答上，见 {@link AskLedger}。 */
    private final AskLedger asks = new AskLedger();
    /** 每张网问服务端时报的那一格：没答上自动重问时照旧报它。 */
    private final Map<String, BlockPos> askedAt = new LinkedHashMap<>();

    /**
     * 手里最近的一答。
     *
     * @param reply     服务端的回答；没答上的一问写成带 failed 原因的回答；从来没收到过回答为空
     * @param staleness 这一答不是当拍的时候接在说明后面的那半句（几秒前的、正在重读、正在重问）；当拍的为空字符串
     */
    record Latest(JsonObject reply, String staleness) {
    }

    ServerNetworkReadings(String operationId, ServerReads reads, LongSupplier now) {
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.now = Objects.requireNonNull(now, "now");
    }

    /** 服务端此刻答不答这个操作：握手协商到了、所有者没在策略里关掉。 */
    boolean available() {
        return reads.available(operationId);
    }

    /**
     * 这张网手里最近的一答：先收回在路上的回答，没有答案、上一答没答上或过了保质期就报 at 这一格再问一次。
     * 这一问还在路上时手里的上一答就是过时的：照"正在重读"的口径标明数据时刻再交，不当成本刻的实况——
     * 收场时按已经看到的现整理，走的就是这条路。
     */
    Latest latest(String networkId, BlockPos at) {
        askedAt.put(networkId, at.immutable());
        settle(networkId);
        long now = this.now.getAsLong();
        AskLedger.Answer answer = asks.answer(networkId);
        boolean inFlight = asks.pending(networkId);
        boolean refreshing = inFlight || asks.needsAsk(networkId, now, FRESH_MILLIS);
        if (refreshing && !inFlight) ask(networkId);
        if (answer == null) return new Latest(null, "");
        if (!refreshing) return new Latest(answer.reply(), "");
        String when = answer.failed() ? "，正在重问"
                : inFlight ? "；这是 " + secondsOld(answer, now) + " 秒前收到的读数，这一次的读数还在路上"
                : "；这是 " + secondsOld(answer, now) + " 秒前的读数，正在重读";
        return new Latest(answer.reply(), when);
    }

    /** 这张网这一轮的问话还没收回：手里留着上一轮的旧答案不算答了，交付方要等本轮答案。 */
    boolean pending(String networkId) {
        settle(networkId);
        return asks.pending(networkId);
    }

    /** 手里这份回答离现在几秒：至少算 1 秒，不当成刚答的。 */
    private static long secondsOld(AskLedger.Answer answer, long now) {
        return Math.max(1, (now - answer.at()) / 1000);
    }

    private void ask(String networkId) {
        BlockPos at = askedAt.get(networkId);
        JsonObject body = new JsonObject();
        body.addProperty("x", at.getX());
        body.addProperty("y", at.getY());
        body.addProperty("z", at.getZ());
        asks.asked(networkId, reads.ask(operationId, body));
    }

    // 收回答：答了的记下；被拒、失败、请求退场的也收起来留原因，限额内马上重问一轮——
    // 会话轮换会把在途的只读请求退场，重问一次就答上了，不把这种失败当读数交给调用方。
    private void settle(String networkId) {
        UUID request = asks.inFlight(networkId);
        if (request == null) return;
        Optional<ClientRequest.Snapshot> snapshot = reads.answer(request);
        if (asks.settled(networkId, snapshot, now.getAsLong(), MAX_RETRIES)) {
            ask(networkId);
        }
    }
}
