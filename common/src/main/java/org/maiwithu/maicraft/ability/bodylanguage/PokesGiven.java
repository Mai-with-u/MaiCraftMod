// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.bodylanguage;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 角色拍过谁、哪一刻拍的：同一个人 10 秒内最多拍 2 下，别把"喂"拍成连打。只在客户端线程读写。
 */
final class PokesGiven {

    /** 多久（刻）内算一轮：10 秒。 */
    static final int WINDOW_TICKS = 200;
    /** 一轮里最多拍几下。 */
    static final int MAX_POKES = 2;

    private final Map<UUID, Deque<Long>> given = new HashMap<>();

    /** 这会儿还能不能再拍他一下。 */
    boolean allowed(UUID who, long now) {
        Deque<Long> ticks = given.get(who);
        if (ticks == null) return true;
        while (!ticks.isEmpty() && now - ticks.peekFirst() >= WINDOW_TICKS) ticks.removeFirst();
        return ticks.size() < MAX_POKES;
    }

    /** 拍了他一下。 */
    void gave(UUID who, long now) {
        given.computeIfAbsent(who, key -> new ArrayDeque<>()).addLast(now);
    }
}
