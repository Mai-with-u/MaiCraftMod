// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 舵飞着核对方向：按着要的方向连着 10 刻转反了就是反的；转对了算核对过；转得太慢、没在按都不下结论。 */
class ControlSurfaceCheckTest {

    @Test
    void 连着十刻往反方向转就是反的() {
        ControlSurfaceCheck check = new ControlSurfaceCheck();
        for (int i = 0; i < ControlSurfaceCheck.TICKS - 1; i++) check.observe(ControlSurfaceCheck.Axis.PITCH, 1, -5);
        assertFalse(check.reversed(ControlSurfaceCheck.Axis.PITCH), "九刻还不够");
        check.observe(ControlSurfaceCheck.Axis.PITCH, 1, -5);
        assertTrue(check.reversed(ControlSurfaceCheck.Axis.PITCH));
        for (int i = 0; i < 20; i++) check.observe(ControlSurfaceCheck.Axis.PITCH, 1, 5);
        assertTrue(check.reversed(ControlSurfaceCheck.Axis.PITCH), "下过结论不再改");
    }

    @Test
    void 转对了算核对过_不按或转得慢不下结论() {
        ControlSurfaceCheck check = new ControlSurfaceCheck();
        for (int i = 0; i < 30; i++) check.observe(ControlSurfaceCheck.Axis.YAW, 1, 0.5);
        assertEquals(ControlSurfaceCheck.Verdict.UNCHECKED, check.verdict(ControlSurfaceCheck.Axis.YAW), "转得太慢看不出");
        for (int i = 0; i < 30; i++) check.observe(ControlSurfaceCheck.Axis.YAW, 0.1, -9);
        assertEquals(ControlSurfaceCheck.Verdict.UNCHECKED, check.verdict(ControlSurfaceCheck.Axis.YAW), "没在按不算");
        for (int i = 0; i < 5; i++) check.observe(ControlSurfaceCheck.Axis.ROLL, -1, 6);
        check.observe(ControlSurfaceCheck.Axis.ROLL, 0, 6);
        for (int i = 0; i < 5; i++) check.observe(ControlSurfaceCheck.Axis.ROLL, -1, 6);
        assertFalse(check.reversed(ControlSurfaceCheck.Axis.ROLL), "中间松过手要重新数");
        for (int i = 0; i < ControlSurfaceCheck.TICKS; i++) check.observe(ControlSurfaceCheck.Axis.ROLL, -1, -6);
        assertEquals(ControlSurfaceCheck.Verdict.RIGHT, check.verdict(ControlSurfaceCheck.Axis.ROLL));
    }
}
