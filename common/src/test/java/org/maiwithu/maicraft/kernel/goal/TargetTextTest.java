// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * 共享目标文本解析器的单元测试：覆盖原地关键字、四类观察编号、整格坐标、地标名以及残缺方括号语法拦截。
 */
class TargetTextTest {

    @Test
    void parsesHereSeenCoordinatesAndLandmark() {
        assertEquals(Optional.of(new Target.Here()), TargetText.parse("here"));
        assertEquals(Optional.of(new Target.Here()), TargetText.parse("  HERE "));

        assertEquals(Optional.of(new Target.Seen("b5")), TargetText.parse("B5"));
        assertEquals(Optional.of(new Target.Seen("e12")), TargetText.parse(" e12 "));
        assertEquals(Optional.of(new Target.Seen("f3")), TargetText.parse("f3"));
        assertEquals(Optional.of(new Target.Seen("s2")), TargetText.parse("S2"));

        assertEquals(Optional.of(new Target.Position(12, 64, -8, null)), TargetText.parse("[12, 64, -8]"));
        assertEquals(Optional.of(new Target.Landmark("家")), TargetText.parse(" 家 "));
    }

    @Test
    void rejectsBlankAndMalformedBracketCoordinates() {
        assertTrue(TargetText.parse(null).isEmpty());
        assertTrue(TargetText.parse("").isEmpty());
        assertTrue(TargetText.parse("   ").isEmpty());
        assertTrue(TargetText.parse("[1,2]").isEmpty());
        assertTrue(TargetText.parse("[1,2,3").isEmpty());
        assertTrue(TargetText.parse("1,2,3]").isEmpty());
        assertTrue(TargetText.parse("[1,a,3]").isEmpty());
        assertTrue(TargetText.parse("[1,2,3,4]").isEmpty());
    }
}
