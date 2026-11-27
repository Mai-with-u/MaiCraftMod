// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.combat.CombatSenses;
import org.maiwithu.maicraft.behavior.combat.Footwork;
import org.maiwithu.maicraft.behavior.combat.RangedCombat;
import org.maiwithu.maicraft.behavior.combat.ThreatAssessment;
import org.maiwithu.maicraft.behavior.combat.WeaponChoice;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 不点名的清扫：只清开打那一刻看得见的这一批，途中冲着角色来的一并处理；
 * 墙后看不见的、之后新刷出来的不追，这一批打完就收手。
 */
class FightAreaClearTest {

    private record Tick(long gameTick) implements TickContext {
        @Override public PlayerContext player() {
            throw new IllegalStateException("战斗任务测试不碰角色对象");
        }
    }

    /** 战场替身：哪些敌人在、哪些看得见、谁刚打过角色，由测试摆；出手一下对面就倒。 */
    private static final class Field implements CombatSenses, SeenTargets, FightMoves {
        @Override public String mainHand(TickContext context) {
            return "minecraft:iron_sword";
        }

        final Map<Integer, Threat> alive = new HashMap<>();
        final Set<Integer> dead = new HashSet<>();
        final List<Attacker> attackers = new ArrayList<>();
        final List<Integer> struck = new ArrayList<>();

        void zombie(int id, double distance, boolean visible) {
            alive.put(id, new Threat(id, new UUID(0, id), "minecraft:zombie", distance, 64, 0, distance,
                    ThreatAssessment.Kind.MELEE, false, visible, false));
        }

        @Override public List<Threat> threats(TickContext context, double radius) {
            return alive.values().stream().filter(threat -> threat.distance() <= radius).toList();
        }

        @Override public List<Attacker> recentAttackers(TickContext context) { return attackers; }

        @Override public CombatProfile profile(TickContext context) {
            return new CombatProfile(20, 10,
                    Optional.of(new WeaponChoice.Picked("minecraft:iron_sword", WeaponChoice.Weapon.MELEE)), 4);
        }

        @Override public Entity entityById(TickContext context, int entityId) { return null; }

        @Override public Locked lock(TickContext context, String observedId) { return null; }

        @Override public Observed observe(TickContext context, int entityId) {
            if (dead.contains(entityId)) return new Observed(0, 64, 0, 1, true);
            Threat threat = alive.get(entityId);
            return threat == null ? null : new Observed(threat.x(), threat.y(), threat.z(), threat.distance(), false);
        }

        /** 每次近战出手需几刻确认完成；默认 1 刻，设为 2 可模拟真实一刀秒杀时的两刻确认窗口。 */
        int strikeConfirmTicks = 1;

        @Override public Action strike(TickContext context, int entityId) {
            struck.add(entityId);
            // 一刀下去就倒：下一刻观察得到死亡，威胁列表里也没了它。
            alive.remove(entityId);
            dead.add(entityId);
            if (strikeConfirmTicks <= 1) return done();
            int needed = strikeConfirmTicks;
            return new Action() {
                private int ticks;
                @Override public ActionStatus tick(TickContext context) {
                    return ++ticks < needed ? ActionStatus.running() : ActionStatus.done();
                }
                @Override public String describe() { return "多刻确认一刀"; }
            };
        }

        @Override public Action walk(GoalCompiler.Compiled goal) { return done(); }
        boolean canBow;
        final List<Integer> shot = new ArrayList<>();

        @Override public Action shoot(TickContext context, int entityId) {
            shot.add(entityId);
            alive.remove(entityId);
            dead.add(entityId);
            return new FiredAction();
        }
        @Override public boolean canShoot(TickContext context) { return canBow; }
        @Override public boolean hasMeleeWeapon(TickContext context) { return !canBow; }
        @Override public int ticksToReady(TickContext context) { return 0; }
        @Override public void guard(TickContext context, double distance, boolean aboutToStrike) {}
        @Override public void lowerGuard() {}
        @Override public Footwork.Ground ground(TickContext context) { return (dx, dz) -> Footwork.Footing.FLAT; }
        @Override public void step(TickContext context, int faceEntityId, Footwork.Step step) {}
        @Override public boolean standable(TickContext context, int x, int y, int z) { return true; }

        @Override public double[] selfPosition(TickContext context) { return new double[] {0, 64, 0}; }

        @Override public long dropCursor(TickContext context) { return 0; }

        @Override public Set<Integer> dropsLyingBefore(TickContext context, long cursor) { return Set.of(); }

        @Override public Action pickUpNewSince(Set<Integer> before) { return done(); }

        @Override public List<Gained> pickedUpSince(TickContext context, long cursor) { return List.of(); }

        private static Action done() {
            return new Action() {
                @Override public ActionStatus tick(TickContext context) { return ActionStatus.done(); }

                @Override public String describe() { return "一下"; }
            };
        }

        private static final class FiredAction implements Action, RangedCombat.Fires {
            @Override public ActionStatus tick(TickContext context) { return ActionStatus.done(); }
            @Override public boolean fired() { return true; }
            @Override public String describe() { return "射出一箭"; }
        }
    }

    private final Field field = new Field();
    private final FightTask task = new FightTask(
            new FightInput(List.of(), null, FightInput.DEFAULT_RADIUS, null, Permissions.DEFAULT),
            field, field, field, (target, permissions) -> Optional.empty());

    private long tick;

    private TaskResult run(int ticks, Runnable afterFirstTicks) {
        task.start(new Tick(tick));
        for (int i = 0; i < ticks; i++) {
            if (i == 3) afterFirstTicks.run();
            if (task.tick(new Tick(tick++)) instanceof TickResult.Finished finished) {
                return finished.result();
            }
        }
        return null;
    }

    @Test
    void zombieBehindAWallIsNotChased() {
        // 身边一只看得见、墙后一只看不见：只清看得见的那只，清完就收手。
        field.zombie(1, 2, true);
        field.zombie(2, 2, false);

        TaskResult result = run(40, () -> {});

        assertNotNull(result);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(List.of(1), field.struck);
    }

    @Test
    void mobsSpawningAfterTheFightStartsAreLeftAlone() {
        // 开打后才冒出来的不算这一批：夜里怪不停刷，任务也收得了尾。
        field.zombie(1, 2, true);

        TaskResult result = run(40, () -> field.zombie(3, 2, true));

        assertNotNull(result);
        assertEquals(List.of(1), field.struck);
    }

    @Test
    void aPlayerWhoHitsMeMidwayIsNotHitBack() {
        // 清扫途中被路过的玩家打了一下：没点名，不自动还手，打不打他由 LLM 定。
        field.zombie(1, 2, true);

        run(40, () -> {
            field.alive.put(6, new CombatSenses.Threat(6, new UUID(0, 6), "minecraft:player", 2, 64, 0, 2,
                    ThreatAssessment.Kind.PLAYER, false, true, false, 0.3, 1.7, true, 3.3, false));
            field.attackers.add(new CombatSenses.Attacker(new UUID(0, 6), "minecraft:player", tick, "player"));
        });

        // 他还在打的这段时间里只退开、举盾，直到他停手十秒后这一仗才收；这里只看有没有还手。
        assertFalse(field.struck.contains(6), field.struck.toString());
        assertTrue(field.struck.contains(1), "这一批照样清");
    }

    @Test
    void somethingThatAttacksMidwayIsDealtWithToo() {
        // 途中冲着角色来、真打了一下的：不在这一批里也一并处理。
        field.zombie(1, 2, true);

        TaskResult result = run(40, () -> {
            field.zombie(4, 2, true);
            field.attackers.add(new CombatSenses.Attacker(new UUID(0, 4), "minecraft:zombie", tick, "mob_attack"));
        });

        assertNotNull(result);
        assertTrue(field.struck.contains(4), field.struck.toString());
    }

    @Test
    void onlyHitTargetsCountAsDefeatedWhenCompanionsKillOthers() {
        // 区域清扫视野内 4 只僵尸：近处实体 1 被角色亲手命中击败，远处实体 2、3、4 未被角色命中却被同伴击杀。
        field.zombie(1, 2, true);
        field.zombie(2, 12, true);
        field.zombie(3, 14, true);
        field.zombie(4, 16, true);

        TaskResult result = run(40, () -> {
            field.alive.remove(2);
            field.alive.remove(3);
            field.alive.remove(4);
            field.dead.add(2);
            field.dead.add(3);
            field.dead.add(4);
        });

        assertNotNull(result);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        FightDetails details = (FightDetails) result.details();
        assertEquals(List.of("minecraft:zombie（实体 1）"), details.defeated(), "只有亲手命中的 1 只记入 defeated");
        assertEquals(3, details.diedOtherwise().size(), "同伴击杀的 3 只应单列在 diedOtherwise：" + details.diedOtherwise());
        int affectedCount = result.changes().stream()
                .filter(change -> change.kind() == Change.Kind.ENTITY_AFFECTED)
                .mapToInt(Change::count).sum();
        assertEquals(1, affectedCount, "ENTITY_AFFECTED 仅统计自己命中的目标：" + result.changes());
        assertTrue(result.summary().contains("击败了 1 个目标") && result.summary().contains("另有 3 个目标因其他原因倒下"),
                "摘要不得虚报四杀：" + result.summary());
    }

    @Test
    void zeroHitsWhileCompanionsClearAreaReportsNoSelfDefeats() {
        // 视野内 3 只怪都在远处，角色零命中前同伴已把它们全部清完：defeated 为空、不虚报击败、任务正常收工不卡住。
        field.zombie(10, 12, true);
        field.zombie(11, 14, true);
        field.zombie(12, 16, true);

        TaskResult result = run(40, () -> {
            field.alive.clear();
            field.dead.addAll(Set.of(10, 11, 12));
        });

        assertNotNull(result);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        FightDetails details = (FightDetails) result.details();
        assertTrue(details.defeated().isEmpty(), "角色零命中，defeated 必须为空：" + details.defeated());
        assertEquals(3, details.diedOtherwise().size(), "三只被同伴清掉的怪应记进 diedOtherwise");
        assertTrue(result.changes().stream().noneMatch(change -> change.kind() == Change.Kind.ENTITY_AFFECTED),
                "零命中不应记 ENTITY_AFFECTED：" + result.changes());
        assertTrue(result.summary().contains("没有确认由自己击败") && result.summary().contains("另有 3 个目标因其他原因倒下"),
                result.summary());
    }

    @Test
    void rangedShotTargetCountsAsDefeatedWhileUntouchedCompanionKillDoesNot() {
        // 持弓远程射击实体 20 确认放箭后其倒下，应记入 defeated；同时另一只未射击的实体 21 倒下，应记入 diedOtherwise。
        field.canBow = true;
        field.zombie(20, 10, true);
        field.zombie(21, 18, true);

        TaskResult result = run(40, () -> {
            field.alive.remove(21);
            field.dead.add(21);
        });

        assertNotNull(result);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        FightDetails details = (FightDetails) result.details();
        assertEquals(List.of("minecraft:zombie（实体 20）"), details.defeated(), "远程确认放箭击杀的怪应进 defeated");
        assertEquals(List.of("minecraft:zombie（实体 21）"), details.diedOtherwise(), "未射击的怪倒下应进 diedOtherwise");
    }

    @Test
    void multiTickOneShotKillDuringAreaClearStillCountsAsDefeatedWhileCompanionKillDoesNot() {
        // 区域清扫 3 只怪：1、2 号在近处被角色依次两刻确认窗口一刀秒杀，3 号在远处被同伴击杀。
        // 切换目标到 2 号前，1 号的在途确认窗口必须走完并归入 defeated，3 号归入 diedOtherwise。
        field.strikeConfirmTicks = 2;
        field.zombie(1, 2, true);
        field.zombie(2, 2.5, true);
        field.zombie(3, 15, true);

        TaskResult result = run(50, () -> {
            field.alive.remove(3);
            field.dead.add(3);
        });

        assertNotNull(result);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        FightDetails details = (FightDetails) result.details();
        assertEquals(2, details.defeated().size(), "两只一刀秒杀的怪都应归入 defeated：" + details.defeated());
        assertTrue(details.defeated().contains("minecraft:zombie（实体 1）"));
        assertTrue(details.defeated().contains("minecraft:zombie（实体 2）"));
        assertEquals(List.of("minecraft:zombie（实体 3）"), details.diedOtherwise(), "同伴击杀的 3 号应进 diedOtherwise");
        assertEquals(2, result.changes().stream().filter(c -> c.kind() == Change.Kind.ENTITY_AFFECTED)
                .mapToInt(Change::count).sum(), "ENTITY_AFFECTED 应准确记为 2");
    }
}
