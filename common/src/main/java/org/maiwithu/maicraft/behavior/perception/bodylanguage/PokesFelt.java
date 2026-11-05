// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.bodylanguage;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 被拍了回头看要知道的两件事：最近一次被谁拍了一下，和他最近一下冲角色做肢体动作在哪一刻（他接着做就多看一会儿）。
 * 生产实现是看别的玩家肢体动作的那一份；离线测试换成替身。
 */
public interface PokesFelt {

    /**
     * 被拍的一下。
     *
     * @param entityId 拍的人的实体编号：转头看他用
     * @param name     玩家名
     */
    record Poke(UUID who, int entityId, String name, long tick) {}

    /** 最近一次被拍的那一下；从没被拍过为空。 */
    Optional<Poke> latestPoke();

    /** 他最近一下冲着角色做肢体动作（含拍一下）在哪一刻；没有为 Long.MIN_VALUE。 */
    long lastActTowardMe(UUID who);

    /** 用的时候再取：控制循环先建好、看肢体动作的那一份后建时用；还没建好就当没被拍过。 */
    static PokesFelt later(Supplier<? extends PokesFelt> source) {
        return new PokesFelt() {
            @Override public Optional<Poke> latestPoke() {
                PokesFelt now = source.get();
                return now == null ? Optional.empty() : now.latestPoke();
            }

            @Override public long lastActTowardMe(UUID who) {
                PokesFelt now = source.get();
                return now == null ? Long.MIN_VALUE : now.lastActTowardMe(who);
            }
        };
    }
}
