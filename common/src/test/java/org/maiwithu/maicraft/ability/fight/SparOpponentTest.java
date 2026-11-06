// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fight;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonObject;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.behavior.combat.CombatSenses;
import org.maiwithu.maicraft.behavior.combat.Footwork;
import org.maiwithu.maicraft.behavior.combat.SparPartner;
import org.maiwithu.maicraft.behavior.combat.WeaponChoice;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;
import org.maiwithu.maicraft.mcp.tool.ResultJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 切磋结果写明和谁打的：点名用观察编号时宿主光看目标认不出是谁，结果细节的 spar.opponent 带上他的玩家名和 UUID；
 * 没认出点名的玩家就不写，不拿名字凑。
 */
class SparOpponentTest {

    private static final UUID ALEX = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");

    /** 刻号替身：切磋只经对手视图、感观与走位替身读现场。 */
    private record Tick(long gameTick) implements TickContext {
        @Override public PlayerContext player() {
            throw new IllegalStateException("切磋测试不碰角色对象");
        }
    }

    /** 对手视图替身：e4 是 Alex；开打后第一刻他就不在这个世界里了（走开），切磋当场收手。 */
    private static final class View implements SparView {
        boolean knowsHim = true;

        @Override public Opponent find(TickContext context, Target target) {
            return knowsHim ? new Opponent(42, ALEX, "Alex") : null;
        }

        @Override public Reading read(TickContext context, int entityId) {
            return null;
        }

        @Override public double myHealth(TickContext context) {
            return 20;
        }

        @Override public double myBlow(TickContext context) {
            return 7;
        }
    }

    /** 感观替身：周围没有别的威胁，满血拿剑。 */
    private static final class Senses implements CombatSenses {
        @Override public List<Threat> threats(TickContext context, double radius) { return List.of(); }

        @Override public List<Attacker> recentAttackers(TickContext context) { return List.of(); }

        @Override public CombatProfile profile(TickContext context) {
            return new CombatProfile(20, 10,
                    Optional.of(new WeaponChoice.Picked("minecraft:iron_sword", WeaponChoice.Weapon.MELEE)), 4);
        }

        @Override public Entity entityById(TickContext context, int entityId) { return null; }
    }

    /** 走位替身：这里只走到收手退开，出手、走路都不会真用上。 */
    private static final class Moves implements FightMoves {
        @Override public Action strike(TickContext context, int entityId) { return idle("打"); }
        @Override public Action shoot(TickContext context, int entityId) { return idle("射"); }
        @Override public boolean canShoot(TickContext context) { return false; }
        @Override public boolean hasMeleeWeapon(TickContext context) { return true; }
        @Override public int ticksToReady(TickContext context) { return 0; }
        @Override public void guard(TickContext context, double distance, boolean aboutToStrike) {}
        @Override public void lowerGuard() {}
        @Override public Footwork.Ground ground(TickContext context) { return (dx, dz) -> Footwork.Footing.FLAT; }
        @Override public void step(TickContext context, int faceEntityId, Footwork.Step step) {}
        @Override public Action walk(GoalCompiler.Compiled goal) { return idle("走"); }
        @Override public double[] selfPosition(TickContext context) { return new double[] {0, 64, 0}; }
        @Override public boolean standable(TickContext context, int x, int y, int z) { return true; }
        @Override public String mainHand(TickContext context) { return "minecraft:iron_sword"; }
        @Override public long dropCursor(TickContext context) { return 0; }
        @Override public Set<Integer> dropsLyingBefore(TickContext context, long cursor) { return Set.of(); }
        @Override public Action pickUpNewSince(Set<Integer> before) { return idle("捡"); }
        @Override public List<Gained> pickedUpSince(TickContext context, long cursor) { return List.of(); }

        private static Action idle(String what) {
            return new Action() {
                @Override public ActionStatus tick(TickContext context) { return ActionStatus.done(); }

                @Override public void close() {}

                @Override public String describe() { return what; }
            };
        }
    }

    private final View view = new View();

    // 点名 e4 切磋，推到收场；返回写成 JSON 的整份结果。
    private JsonObject result() {
        SparTask task = new SparTask(new SparInput(new Target.Seen("e4"), 6), new Senses(), new Moves(), view,
                new SparPartner());
        task.start(new Tick(0));
        for (long t = 0; t < 20; t++) {
            if (task.tick(new Tick(t)) instanceof TickResult.Finished finished) {
                TaskResult result = finished.result();
                return ResultJson.result(result);
            }
        }
        throw new AssertionError("切磋 20 刻内应已收场");
    }

    @Test
    void namedBySeenIdTheResultStillSaysWhoItWas() {
        // 点名用的是观察编号 e4：他走开、切磋收场，结果细节照样写出是 Alex 和他的 UUID。
        JsonObject spar = result().getAsJsonObject("details").getAsJsonObject("spar");
        assertEquals("they_left", spar.get("ended_by").getAsString());
        JsonObject opponent = spar.getAsJsonObject("opponent");
        assertEquals("Alex", opponent.get("name").getAsString());
        assertEquals(ALEX.toString(), opponent.get("uuid").getAsString());
    }

    @Test
    void noOpponentWhenTheNamedPlayerWasNotFound() {
        // 点名的不是一位在场的玩家：没开打就收场，结果里不写和谁打的。
        view.knowsHim = false;
        JsonObject result = result();
        assertEquals("failed", result.get("status").getAsString());
        JsonObject spar = result.getAsJsonObject("details").getAsJsonObject("spar");
        assertFalse(spar.has("opponent"), "没认出点名的玩家时不写 opponent");
    }
}
