// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.fall;

/**
 * 喷气背包飞的那一段还在不在管上下：最近一小段时间内它还说过"我管着"才算在管。
 * 飞的那一段被打断、出岔子没收尾时，就当它不管了，落地防护照样兜底。
 */
public final class JetpackFlightFreshness {

    /** 多久没再说"我管着上下"就当不在管了。 */
    static final long CONTROL_FRESH_NANOS = 300_000_000L;

    private JetpackFlightFreshness() {}

    /**
     * 判断飞的那一段此刻是不是还在管着上下：只在它说过话、且说的时间还不算久时成立。
     *
     * @param lastClaimNanos 它最近一次说"我管着上下"的时刻（纳秒）；从没说过为 0
     * @param nowNanos       现在的时刻（纳秒）
     */
    public static boolean stillControlling(long lastClaimNanos, long nowNanos) {
        return lastClaimNanos != 0 && nowNanos - lastClaimNanos < CONTROL_FRESH_NANOS;
    }
}
