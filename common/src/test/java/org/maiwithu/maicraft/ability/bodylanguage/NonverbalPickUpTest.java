// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.bodylanguage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 捡起扔过来的东西：落在脚边的过两秒就自己进了包，再叫她捡时如实说已经在背包里了、按做完收；
 * 地上和包里都没有才报没找到，点了地上那件说"那件不在了"，点了人或不点名说"身边没有刚扔过来的"。
 */
class NonverbalPickUpTest {

    private final FakeNonverbalMoves moves = new FakeNonverbalMoves();

    @Test
    void 扔过来的已经自己进了包_如实说在背包里_按做完收() {
        moves.alreadyPicked = Optional.of("3 个 minecraft:iron_ingot");
        TaskResult result = run(null);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals("已经在背包里了：3 个 minecraft:iron_ingot（落在身边时自己捡起来了）", result.summary());
    }

    @Test
    void 点名扔东西的人_地上包里都没有_说身边没有刚扔过来的() {
        TaskResult result = run(new Target.Player("玩家甲"));
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.NOT_FOUND, result.problem().kind());
        assertEquals("身边 6 格内没有别人刚扔过来的东西", result.problem().message());
    }

    @Test
    void 点名地上那件_不见了也没进包_说那件不在了() {
        TaskResult result = run(new Target.Seen("e12"));
        assertEquals(Problem.Kind.NOT_FOUND, result.problem().kind());
        assertTrue(result.problem().message().startsWith("那件东西不在了"), result.problem().message());
    }

    private TaskResult run(Target target) {
        NonverbalTask task = new NonverbalTask(new NonverbalInput(NonverbalOperation.PICK_UP, 1, null, 40, target),
                moves, new PokesGiven());
        task.start(tick(0));
        for (int i = 1; i < 50; i++) {
            if (task.tick(tick(i)) instanceof TickResult.Finished finished) return finished.result();
        }
        throw new AssertionError("50 刻还没收场：" + task.describe());
    }

    private static TickContext tick(long now) {
        return new TickContext() {
            @Override public long gameTick() {
                return now;
            }

            @Override public PlayerContext player() {
                return null;
            }
        };
    }
}
