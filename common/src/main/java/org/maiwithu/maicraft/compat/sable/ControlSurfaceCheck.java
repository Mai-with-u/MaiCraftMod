// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.EnumMap;
import java.util.Map;

/**
 * 固定翼的舵飞着核对方向（纯函数的累加器）：舵在地上试不出来（没有气流，舵面转了机身不动），只能飞着看。
 * 滑跑和飞行中第一次按某个舵（升降舵管俯仰、副翼管横滚、方向舵管偏航）时，看机身真实往哪转：
 * 按着要的方向连着 10 刻都往反方向转（每秒 2° 以上），这一对键就是反的，这一趟反着用；连着 10 刻转对了就算核对过。
 * 转得太慢看不出的不算，接着看；核对过、反过的不再看。
 */
final class ControlSurfaceCheck {

    /** 三个舵：俯仰（正的抬头）、横滚（正的右压）、偏航（正的右转）。 */
    enum Axis { PITCH, ROLL, YAW }

    /** 一个舵核对到哪了：还没看出来 / 方向对 / 方向反。 */
    enum Verdict { UNCHECKED, RIGHT, REVERSED }

    /** 连着几刻同一个结论才算数。C */
    static final int TICKS = 10;
    /** 转得快过每秒几度才看得出方向。C */
    static final double RATE = 2;
    /** 按到多少才算在用这个舵。C */
    static final double PRESS = 0.5;

    private final Map<Axis, Verdict> verdicts = new EnumMap<>(Axis.class);
    private final Map<Axis, Integer> agree = new EnumMap<>(Axis.class);
    private final Map<Axis, Integer> disagree = new EnumMap<>(Axis.class);

    /**
     * 这一刻按了多少、机身真实转得多快：按的方向与转的方向连着一致或连着相反就下结论。
     *
     * @param command 这一刻要的方向与大小（-1～1，按上面的正负）
     * @param rate    机身绕这根轴每秒转几度（同样的正负）
     */
    void observe(Axis axis, double command, double rate) {
        if (verdict(axis) != Verdict.UNCHECKED) return;
        if (Math.abs(command) < PRESS) {
            agree.put(axis, 0);
            disagree.put(axis, 0);
            return;
        }
        if (Math.abs(rate) < RATE) return;
        if (command * rate > 0) {
            disagree.put(axis, 0);
            int count = agree.merge(axis, 1, Integer::sum);
            if (count >= TICKS) verdicts.put(axis, Verdict.RIGHT);
        } else {
            agree.put(axis, 0);
            int count = disagree.merge(axis, 1, Integer::sum);
            if (count >= TICKS) verdicts.put(axis, Verdict.REVERSED);
        }
    }

    Verdict verdict(Axis axis) {
        return verdicts.getOrDefault(axis, Verdict.UNCHECKED);
    }

    /** 这个舵是反的：这一趟按它时把正负反过来。 */
    boolean reversed(Axis axis) {
        return verdict(axis) == Verdict.REVERSED;
    }
}
