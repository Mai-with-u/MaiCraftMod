// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.result;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 原因合并计数：同样的原因合在一起数、件数多的排前面、种类跟着件数最多的原因走、说明长短有界。 */
class ReasonCountsTest {

    @Test
    void 只有一种原因_直接接在开头后面() {
        ReasonCounts reasons = new ReasonCounts();
        reasons.add(Problem.Kind.UNREACHABLE, "要贴的那一面站到跟前也被挡住或看不见", 1);
        assertEquals("1 格没做成：要贴的那一面站到跟前也被挡住或看不见", reasons.message("1 格没做成", "格"));
        assertEquals(Problem.Kind.UNREACHABLE, reasons.kind());
    }

    @Test
    void 同样的原因合在一起数_件数多的排前面_种类跟着它() {
        ReasonCounts reasons = new ReasonCounts();
        reasons.add(Problem.Kind.STUCK, "点下去没生效", 1);
        reasons.add(Problem.Kind.UNREACHABLE, "走不到能点着要贴那一面的站位", 2);
        reasons.add(Problem.Kind.STUCK, "点下去没生效", 2);
        assertEquals("5 格没做成：3 格点下去没生效、2 格走不到能点着要贴那一面的站位", reasons.message("5 格没做成", "格"));
        assertEquals(Problem.Kind.STUCK, reasons.kind());
    }

    @Test
    void 件数一样时先记下的排前面() {
        ReasonCounts reasons = new ReasonCounts();
        reasons.add(Problem.Kind.UNREACHABLE, "走不到能挖这一格的位置", 2);
        reasons.add(Problem.Kind.STUCK, "倒不进去", 2);
        assertEquals(Problem.Kind.UNREACHABLE, reasons.kind());
        assertTrue(reasons.message("4 格没做成", "格").startsWith("4 格没做成：2 格走不到能挖这一格的位置、"));
    }

    @Test
    void 原因再多也只点名两种_其余给总数_说明不随件数和种数变长() {
        String names = "甲乙丙丁戊己庚辛壬癸子丑";
        ReasonCounts few = new ReasonCounts();
        ReasonCounts many = new ReasonCounts();
        for (int i = 0; i < names.length(); i++) {
            few.add(Problem.Kind.STUCK, "原因" + names.charAt(i), 1);
            many.add(Problem.Kind.STUCK, "原因" + names.charAt(i), 1000 - i);
        }
        String message = many.message("11934 格没做成", "格");
        assertEquals("11934 格没做成：1000 格原因甲、999 格原因乙，另有 10 种原因共 9935 格", message);
        assertFalse(message.contains("原因丙"), "第三种起不点名：" + message);
        // 件数从 1 涨到上千只多几位数字，原因种数从 3 种涨到 12 种说明不变长。
        assertTrue(message.length() - few.message("12 格没做成", "格").length() <= 12, message);
        ReasonCounts three = new ReasonCounts();
        for (int i = 0; i < 3; i++) three.add(Problem.Kind.STUCK, "原因" + names.charAt(i), 1000 - i);
        assertTrue(message.length() <= three.message("11934 格没做成", "格").length() + 2, message);
    }

    @Test
    void 一件都没记_没有种类_说明只是开头() {
        ReasonCounts reasons = new ReasonCounts();
        assertTrue(reasons.isEmpty());
        assertNull(reasons.kind());
        assertEquals("没做成", reasons.message("没做成", "件"));
    }
}
