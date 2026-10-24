// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

/**
 * 往网络里存一批的结算判定：以动手那一刻「背包加光标」里这种东西的合计为基准，
 * 动完以后合计少了几件，就是存进了网络的几件。纯函数，不读界面也不动手。
 *
 * <p>为什么以合计的净减少为证据：凑尾数的一批先整份拿上光标、把多余的件退回背包，再整份交给网络——
 * 只看背包会看到"先少回来、交回那一刻不再少"，交回又是光标对网络、背包一动不动；
 * 背包加光标的合计在凑的全程不变，交回之后合计少几件，恰恰就是网络收下的几件。
 * 网络真满了或不收时，光标上还挂着没交出去的件（或背包根本没少），照实算没存进，不冒充成功。
 */
public final class NetworkStoreSettlement {

    private NetworkStoreSettlement() {}

    /** 一趟存入的结算结论。 */
    public sealed interface Verdict {
    }

    /** 还看不出结果：光标空着而背包没少（可能还在路上，等下一刻再看）。 */
    public record Waiting() implements Verdict {
    }

    /** 这批离手了：正好 moved 件离开背包与光标，就是存进了网络的件数。 */
    public record Stored(int moved) implements Verdict {
    }

    /** 光标上还拿着 leftover 件没交出去，另外 moved 件已经进了网络（网络装不下整批时部分收下）。 */
    public record Held(int moved, int leftover) implements Verdict {
    }

    /**
     * 结一次账：baseline 是动手前背包加光标的合计件数，backpackNow 与 cursorCount 是此刻的实况。
     * 光标还拿着就不下结论——服务端可能还没收完，等到光标空了（全收下）或结算期限到了
     * （光标上拿着的按没存进算）再定；那是动手方的刻数纪律，这里只按此刻的实况给结论。
     */
    public static Verdict settle(int baseline, int backpackNow, int cursorCount) {
        if (cursorCount > 0) {
            return new Held(Math.max(0, baseline - backpackNow - cursorCount), cursorCount);
        }
        if (backpackNow < baseline) return new Stored(baseline - backpackNow);
        return new Waiting();
    }
}
