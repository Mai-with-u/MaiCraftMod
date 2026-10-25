// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.game.serverlink.ClientRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 问话账的判定：什么时候要问、读数算不算在路上、没答上时重问几次。 */
class AskLedgerTest {

    private static final long NOW = 1_000_000L;
    private static final long FRESH_MILLIS = 5_000;
    private static final int MAX_RETRIES = 2;

    private final AskLedger ledger = new AskLedger();

    @Test
    void 没答案时要问_问出去后到收回答前不再重复问() {
        assertTrue(ledger.needsAsk("me@1", NOW, FRESH_MILLIS));
        ledger.asked("me@1", Optional.of(UUID.randomUUID()));
        assertFalse(ledger.needsAsk("me@1", NOW, FRESH_MILLIS));
        assertTrue(ledger.pending("me@1"));
    }

    @Test
    void 收到回答后新鲜期内不再问_过了保质期要重问() {
        UUID request = UUID.randomUUID();
        ledger.asked("me@1", Optional.of(request));
        ledger.settled("me@1", Optional.of(answered(request, 67)), NOW, MAX_RETRIES);

        assertFalse(ledger.pending("me@1"));
        assertFalse(ledger.needsAsk("me@1", NOW + FRESH_MILLIS, FRESH_MILLIS));
        assertTrue(ledger.needsAsk("me@1", NOW + FRESH_MILLIS + 1, FRESH_MILLIS));
        assertEquals(67, ledger.answer("me@1").reply().get("stored_amount").getAsLong());
        assertFalse(ledger.answer("me@1").failed());
    }

    @Test
    void 没答上时不算有答案_马上要重问() {
        UUID request = UUID.randomUUID();
        ledger.asked("me@1", Optional.of(request));
        boolean retry = ledger.settled("me@1", Optional.of(expired(request)), NOW, MAX_RETRIES);

        assertTrue(retry);
        assertTrue(ledger.answer("me@1").failed());
        // 失败的回答不能当现状用：哪怕刚收到，也按"要问"判。
        assertTrue(ledger.needsAsk("me@1", NOW, FRESH_MILLIS));
    }

    @Test
    void 连着没答上到重试上限后停下_答上后清零() {
        UUID first = UUID.randomUUID();
        ledger.asked("me@1", Optional.of(first));
        assertTrue(ledger.settled("me@1", Optional.of(expired(first)), NOW, MAX_RETRIES));

        UUID second = UUID.randomUUID();
        ledger.asked("me@1", Optional.of(second));
        assertTrue(ledger.settled("me@1", Optional.of(expired(second)), NOW, MAX_RETRIES));

        UUID third = UUID.randomUUID();
        ledger.asked("me@1", Optional.of(third));
        // 到上限的这次失败不再重问：如实把"读不到"交出去，不无限等。
        assertFalse(ledger.settled("me@1", Optional.of(expired(third)), NOW, MAX_RETRIES));

        // 后来答上了：重试清零，之后又没答上还能从头重问。
        UUID fourth = UUID.randomUUID();
        ledger.asked("me@1", Optional.of(fourth));
        assertFalse(ledger.settled("me@1", Optional.of(answered(fourth, 67)), NOW, MAX_RETRIES));

        UUID fifth = UUID.randomUUID();
        ledger.asked("me@1", Optional.of(fifth));
        assertTrue(ledger.settled("me@1", Optional.of(expired(fifth)), NOW, MAX_RETRIES));
    }

    @Test
    void 各张网的问话互不干扰() {
        ledger.asked("me@1", Optional.of(UUID.randomUUID()));
        assertTrue(ledger.pending("me@1"));
        assertFalse(ledger.pending("me@2"));
        assertTrue(ledger.needsAsk("me@2", NOW, FRESH_MILLIS));
    }

    @Test
    void 请求还没结算时算还在等() {
        UUID request = UUID.randomUUID();
        ledger.asked("me@1", Optional.of(request));
        ClientRequest.Snapshot waiting = new ClientRequest.Snapshot(request, "ae2.network", 1, false,
                ClientRequest.Status.PENDING, ClientRequest.Effect.NOT_APPLIED, false, "", "", -1, new JsonObject());
        assertFalse(ledger.settled("me@1", Optional.of(waiting), NOW, MAX_RETRIES));
        assertTrue(ledger.pending("me@1"));
    }

    /** 答上的一次：服务端抄回网络汇总。 */
    private static ClientRequest.Snapshot answered(UUID id, long storedAmount) {
        JsonObject result = new JsonObject();
        result.addProperty("found", true);
        result.addProperty("stored_amount", storedAmount);
        return new ClientRequest.Snapshot(id, "ae2.network", 1, false,
                ClientRequest.Status.SUCCEEDED, ClientRequest.Effect.NOT_APPLIED, false, "", "", -1, result);
    }

    /** 没答上的一次：只读请求被会话轮换退场。 */
    private static ClientRequest.Snapshot expired(UUID id) {
        return new ClientRequest.Snapshot(id, "ae2.network", 1, false,
                ClientRequest.Status.FAILED, ClientRequest.Effect.NOT_APPLIED, true, "session_expired", "会话已经轮换",
                -1, new JsonObject());
    }
}
