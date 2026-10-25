// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonObject;

import org.maiwithu.maicraft.game.serverlink.ClientRequest;

/**
 * 一张网的问话账：哪些网问出去还没收回答、最近一次收到的回答与时刻、连续几次没答上。
 * 只记账与下判断，不碰游戏：要不要再问、读数在不在路上，都从这里出，离线可测。
 *
 * <p>判定口径：手里留着上一轮的旧答案不算"已经答了"——只要这一轮的问话还没收回，读数就还在路上，
 * 交付方要等本轮答案到账，不能拿旧答案交差；一答没答上（请求被会话轮换退场、被拒）也不能当现状缓存，
 * 到点就重问。
 */
final class AskLedger {

    /** 最近一次收到的回答与收到时刻；failed 表示这一问没答上，回答里写的是原因。 */
    record Answer(JsonObject reply, long at, boolean failed) {
    }

    /** 问出去还没收回答的网 → 请求编号。 */
    private final Map<String, UUID> asking = new LinkedHashMap<>();
    /** 网 → 最近一次回答。 */
    private final Map<String, Answer> answers = new LinkedHashMap<>();
    /** 网 → 这一轮问话已经重问过的次数；答上了就清零。 */
    private final Map<String, Integer> retries = new LinkedHashMap<>();

    /** 这一网此刻有没有没收回的问话。 */
    boolean pending(String networkId) {
        return asking.containsKey(networkId);
    }

    /** 这一网还没收回回答时的请求编号；没有给空。 */
    UUID inFlight(String networkId) {
        return asking.get(networkId);
    }

    /** 这一网最近一次收到的回答；从来没答上过给空。 */
    Answer answer(String networkId) {
        return answers.get(networkId);
    }

    /** 这一网现在要不要问：手里没有答、上一答没答上、或答的时刻过了保质期；正在问的不重复问。 */
    boolean needsAsk(String networkId, long now, long freshMillis) {
        if (asking.containsKey(networkId)) return false;
        Answer answer = answers.get(networkId);
        return answer == null || answer.failed() || now - answer.at() > freshMillis;
    }

    /** 记下问出去的请求；没问成（拿不到请求编号）不记，下次还按"要问"判。 */
    void asked(String networkId, Optional<UUID> request) {
        request.ifPresent(id -> asking.put(networkId, id));
    }

    /**
     * 收回答：问话的请求结算了（答了、被拒、退场）就记下来并腾出问话位。
     * 没答上且重问次数没到上限时不算完——返回 true 让读端马上重问，没答上的原因照留作交代。
     */
    boolean settled(String networkId, Optional<ClientRequest.Snapshot> snapshot, long now, int maxRetries) {
        UUID request = asking.get(networkId);
        if (request == null) return false;
        if (snapshot.isPresent() && !snapshot.get().settled()) return false;
        asking.remove(networkId);
        boolean failed = snapshot.isEmpty() || snapshot.get().status() != ClientRequest.Status.SUCCEEDED;
        answers.put(networkId, new Answer(replyOf(snapshot), now, failed));
        if (!failed) {
            retries.remove(networkId);
            return false;
        }
        int tried = retries.getOrDefault(networkId, 0);
        if (tried >= maxRetries) {
            retries.remove(networkId);
            return false;
        }
        retries.put(networkId, tried + 1);
        return true;
    }

    // 答上了抄结果，没答上把原因写成回答，读端照实转述。
    private static JsonObject replyOf(Optional<ClientRequest.Snapshot> snapshot) {
        if (snapshot.isPresent() && snapshot.get().status() == ClientRequest.Status.SUCCEEDED) {
            return snapshot.get().result();
        }
        JsonObject reply = new JsonObject();
        reply.addProperty("failed", snapshot.map(ClientRequest.Snapshot::message).orElse("请求已经退场"));
        return reply;
    }
}
