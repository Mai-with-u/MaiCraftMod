// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.compat.ae2.NetworkStoreSettlement.Held;
import org.maiwithu.maicraft.compat.ae2.NetworkStoreSettlement.Stored;
import org.maiwithu.maicraft.compat.ae2.NetworkStoreSettlement.Verdict;
import org.maiwithu.maicraft.compat.ae2.NetworkStoreSettlement.Waiting;

/**
 * 网络存入的结算判定：以动手前背包加光标的合计为基准，合计少几件就是存进几件；
 * 光标上还拿着的是网络没收下的，不算存进。账要和实物对得上：存 8 记 8、只收下 3 记 3、没收下就报放不下。
 */
class NetworkStoreSettlementTest {

    @Test
    void 整格快速移动存入按背包净减记账() {
        // 动手前背包 64、光标空；服务端收下整格后背包剩 56：存进了 8 件，账记 8。
        Verdict verdict = NetworkStoreSettlement.settle(64, 56, 0);
        assertEquals(8, assertInstanceOf(Stored.class, verdict).moved());
    }

    @Test
    void 凑尾数交回后按合计净减记账() {
        // 凑尾数的全程合计不变：动手前背包 56 加光标 8 合计 64；交回网络后光标空了、背包还是 56。
        // 交回是光标对网络、背包一动不动，只看背包会把已发生的存入漏掉——合计少了 8 就是存进 8。
        Verdict verdict = NetworkStoreSettlement.settle(64, 56, 0);
        assertEquals(8, assertInstanceOf(Stored.class, verdict).moved());
    }

    @Test
    void 光标还拿着时按还没收下算() {
        // 光标上还拿着 8 件：交回是光标对网络，拿着的这件还没被收下，账先记 0；
        // 服务端可能正在收，动手方按刻数等它，这里只按此刻的实况给结论。
        Held held = assertInstanceOf(Held.class, NetworkStoreSettlement.settle(64, 64, 8));
        assertEquals(0, held.moved());
        assertEquals(8, held.leftover());
    }

    @Test
    void 网络装不下整批时按实收记账() {
        // 交出去 8 件网络只收下 3 件，剩 5 件留在光标上：账记 3，剩下 5 件是要点回背包的那份。
        Held held = assertInstanceOf(Held.class, NetworkStoreSettlement.settle(64, 56, 5));
        assertEquals(3, held.moved());
        assertEquals(5, held.leftover());
    }

    @Test
    void 网络不收时光标整批退回报失败() {
        // 光标上的 8 件一件都没被收下：账记 0，照实报放不下，不把挂在光标上的当成存进。
        Held held = assertInstanceOf(Held.class, NetworkStoreSettlement.settle(64, 56, 8));
        assertEquals(0, held.moved());
        assertEquals(8, held.leftover());
    }

    @Test
    void 快速移动没动静时报放不下() {
        // 光标空着背包没少：网络放不下（存储满了或不收这种东西），账不能动。
        assertInstanceOf(Waiting.class, NetworkStoreSettlement.settle(64, 64, 0));
    }
}
