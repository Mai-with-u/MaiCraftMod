// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.beds;

/**
 * 躺下点床时游戏给出的拒绝种类里，能进找床失败话术按原因计数的那两种。
 *
 * <p>扫描时没看出来的拒绝（怪是走近后才到的、占用是躺下那一刻才被原版判的）发生在点床交互上，
 * 把这张床记进排除名单时连原因一起记下，找床失败的话术才能说出"床边有怪几张、被占用几张"，
 * 不至于统统算成"试过睡不上"。
 */
public enum BedRefusal {
    /** 躺下时被原版以"这张床已被占用"拒绝。 */
    OCCUPIED,
    /** 躺下时被原版以"周围有怪物在游荡"拒绝。 */
    MONSTERS_NEARBY,
}
