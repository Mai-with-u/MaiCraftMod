// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.bodylanguage;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 角色和别的玩家之间肢体动作的你来我往：角色做肢体动作时要知道最近谁冲它做过（不点名就对着他）、
 * 谁扔了东西到它身边（捡起来），做完要告诉回头看"回过他了"（不再晾着他）。
 */
public interface NonverbalExchange {

    /** 什么都不知道的一份：离线清单、还没进世界时用。 */
    NonverbalExchange NONE = new NonverbalExchange() {
        @Override public Optional<UUID> lastTowardMe(long now, long withinTicks) {
            return Optional.empty();
        }

        @Override public Set<Integer> tossedToMe(long now, long withinTicks) {
            return Set.of();
        }

        @Override public void answered(UUID who) {}
    };

    /** 最近 withinTicks 刻内最后一个冲角色做过肢体动作的玩家；没有为空。 */
    Optional<UUID> lastTowardMe(long now, long withinTicks);

    /** 最近 withinTicks 刻内别的玩家扔过来、落在角色身边的东西（掉落物的实体编号）。 */
    Set<Integer> tossedToMe(long now, long withinTicks);

    /** 角色冲他做了肢体动作：回过他了，回头看不再因为没人理他而先不理他。 */
    void answered(UUID who);

    /**
     * 生产的一份：看肢体动作的那一份在伤害包接好时才建，用的时候再取；还没建好时什么都不知道。
     *
     * @param lookBack 被拍了回头看的那一项生存需求
     */
    static NonverbalExchange live(Supplier<NonverbalWatch> watch, LookBackNeed lookBack) {
        return new NonverbalExchange() {
            @Override public Optional<UUID> lastTowardMe(long now, long withinTicks) {
                NonverbalWatch current = watch.get();
                return current == null ? Optional.empty() : current.lastTowardMe(now, withinTicks);
            }

            @Override public Set<Integer> tossedToMe(long now, long withinTicks) {
                NonverbalWatch current = watch.get();
                return current == null ? Set.of() : current.tossedToMe(now, withinTicks);
            }

            @Override public void answered(UUID who) {
                if (lookBack != null) lookBack.answered(who);
            }
        };
    }
}
