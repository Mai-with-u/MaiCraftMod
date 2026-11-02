// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.behavior.combat.Footwork;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.behavior.travel.TravelFakes;
import org.maiwithu.maicraft.behavior.combat.CombatSenses;
import org.maiwithu.maicraft.behavior.combat.ThreatAssessment;
import org.maiwithu.maicraft.behavior.combat.WeaponChoice;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/** 战斗任务：点名目标朝它本人走过去、逼近不每刻重新寻路；倒地的算击败，跟丢的不算。 */
class FightTaskTest {

    /** 刻号替身：战斗任务只经感观、点名核对与走位替身读现场。 */
    private record Tick(long gameTick) implements TickContext {
        @Override public PlayerContext player() {
            throw new IllegalStateException("战斗任务测试不碰角色对象");
        }
    }

    /** 感观替身：满血拿剑，周围没有别的威胁；点名的僵尸按核对替身此刻的观察给身板。 */
    private final class Senses implements CombatSenses {
        @Override public List<Threat> threats(TickContext context, double radius) { return List.of(); }

        @Override public Optional<Threat> living(TickContext context, int entityId) {
            return namedZombie(entityId);
        }

        @Override public List<Attacker> recentAttackers(TickContext context) { return List.of(); }

        @Override public CombatProfile profile(TickContext context) {
            return new CombatProfile(20, 10,
                    Optional.of(new WeaponChoice.Picked("minecraft:iron_sword", WeaponChoice.Weapon.MELEE)), 4);
        }

        @Override public Entity entityById(TickContext context, int entityId) { return null; }
    }

    /** 点名核对替身：e1 是 7 号僵尸，每刻的观察由测试摆。 */
    private static final class Targets implements SeenTargets {
        SeenTargets.Observed now;

        @Override public Locked lock(TickContext context, String observedId) {
            return "e1".equals(observedId) ? new Locked(7, new UUID(0, 7), "minecraft:zombie") : null;
        }

        @Override public Observed observe(TickContext context, int entityId) { return now; }
    }

    /** 走位替身：走到一直在走，出手一下就完；记下起了哪些动作、收尾了几次。 */
    private static final class Moves implements FightMoves {
        /** 此刻主手里拿着什么；测试摆。 */
        String held = "minecraft:iron_sword";

        @Override public String mainHand(TickContext context) {
            return held;
        }

        final List<String> started = new ArrayList<>();
        int closed;
        /** 此刻人在哪（拾荒回倒下点的测试用它挪人）；默认打点原地。 */
        double[] at = {0, 64, 0};
        /** 下一趟寻路照这段脚本演（长回程，脚位逐刻挪一格）；为 null 时按普通替身回。 */
        TravelFakes.ScriptedWalk nextWalkScript;
        /** 开打前就躺在身边的掉落物；测试摆。 */
        final Set<Integer> lyingBefore = new HashSet<>();
        /** 捡掉落物时交给它的"不碰"名单。 */
        Set<Integer> pickupSkipped;
        /** 服务器说本玩家收进包的（开打后才冒出来的）；测试摆。 */
        final List<Gained> gained = new ArrayList<>();
        /** 此刻背包里箭的只数；测试摆，-1 为读不到。 */
        int arrows = -1;

        @Override public OptionalInt arrowsCarried(TickContext context) {
            return arrows < 0 ? OptionalInt.empty() : OptionalInt.of(arrows);
        }

        /** 出手动作每刻回什么；默认一挥就中。 */
        ActionStatus strikeStatus = ActionStatus.done();

        @Override public Action strike(TickContext context, int entityId) {
            return action("打 " + entityId, strikeStatus);
        }

        /** 普通替身走到每刻回什么；默认一直在走。 */
        ActionStatus walkStatus = ActionStatus.running();

        @Override public Action walk(GoalCompiler.Compiled goal) {
            if (nextWalkScript != null) {
                TravelFakes.ScriptedWalk walk = nextWalkScript;
                nextWalkScript = null;
                return walk;
            }
            BlockPos center = goal.goal().center();
            return action("走向 " + center.getX() + "," + center.getZ(), walkStatus);
        }

        final List<Footwork.Step> steps = new ArrayList<>();

        @Override public Action shoot(TickContext context, int entityId) { return action("射 " + entityId, ActionStatus.running()); }
        @Override public boolean canShoot(TickContext context) { return false; }
        @Override public boolean hasMeleeWeapon(TickContext context) { return true; }
        @Override public int ticksToReady(TickContext context) { return 0; }
        @Override public void guard(TickContext context, double distance, boolean aboutToStrike) {}
        @Override public void lowerGuard() {}
        @Override public Footwork.Ground ground(TickContext context) { return (dx, dz) -> Footwork.Footing.FLAT; }
        @Override public void step(TickContext context, int faceEntityId, Footwork.Step step) { steps.add(step); }
        @Override public boolean standable(TickContext context, int x, int y, int z) { return true; }

        @Override public double[] selfPosition(TickContext context) { return at.clone(); }

        @Override public long dropCursor(TickContext context) { return 0; }

        @Override public Set<Integer> dropsLyingBefore(TickContext context, long cursor) { return lyingBefore; }

        @Override public Action pickUpNewSince(Set<Integer> before) {
            pickupSkipped = before;
            return action("捡掉落物", ActionStatus.done());
        }

        @Override public List<Gained> pickedUpSince(TickContext context, long cursor) { return gained; }

        private Action action(String what, ActionStatus status) {
            started.add(what);
            return new Action() {
                @Override public ActionStatus tick(TickContext context) { return status; }

                @Override public void close() { closed++; }

                @Override public String describe() { return what; }
            };
        }
    }

    private final Targets targets = new Targets();

    // 点名的 7 号僵尸此刻的身板：核对替身说它在哪、看不看得见；倒下或不见了就不在场上。
    private Optional<CombatSenses.Threat> namedZombie(int entityId) {
        SeenTargets.Observed now = targets.now;
        if (entityId != 7 || now == null || now.dead()) return Optional.empty();
        return Optional.of(new CombatSenses.Threat(7, new UUID(0, 7), "minecraft:zombie", now.x(), now.y(), now.z(),
                now.distance(), ThreatAssessment.Kind.MELEE, false, now.visible(), false));
    }
    private final Moves moves = new Moves();
    /** 点名的二次确认：refusal 为空就是能打。 */
    private Optional<Problem> refusal = Optional.empty();
    private final FightTask task = new FightTask(
            new FightInput(List.of("e1"), null, FightInput.DEFAULT_RADIUS, null, Permissions.DEFAULT),
            new Senses(), targets, moves, (target, permissions) -> refusal);

    // 推进若干刻；结束了返回结果，没结束返回 null。
    private TaskResult run(int ticks) {
        task.start(new Tick(0));
        for (long t = 0; t < ticks; t++) {
            if (task.tick(new Tick(t)) instanceof TickResult.Finished finished) {
                return finished.result();
            }
        }
        return null;
    }

    @Test
    void litCreeperNearbyIsEvadedBeforeApproachingTheNamedTarget() {
        // 点名打僵尸，旁边一只点着引信的苦力怕离 4 格：当刻背离它疾跑，不朝僵尸走。
        CombatSenses withCreeper = new CombatSenses() {
            @Override public List<Threat> threats(TickContext context, double radius) {
                return List.of(new Threat(9, new UUID(0, 9), "minecraft:creeper", 4, 64, 0, 4,
                        ThreatAssessment.Kind.EXPLOSIVE, true, true, true));
            }
            @Override public List<Attacker> recentAttackers(TickContext context) { return List.of(); }
            @Override public CombatProfile profile(TickContext context) {
                return new CombatProfile(20, 10,
                        Optional.of(new WeaponChoice.Picked("minecraft:iron_sword", WeaponChoice.Weapon.MELEE)), 4);
            }
            @Override public Entity entityById(TickContext context, int entityId) { return null; }
            @Override public Optional<Threat> living(TickContext context, int entityId) { return namedZombie(entityId); }
        };
        FightTask fight = new FightTask(
                new FightInput(List.of("e1"), null, FightInput.DEFAULT_RADIUS, null, Permissions.DEFAULT),
                withCreeper, targets, moves, (target, permissions) -> refusal);
        targets.now = new SeenTargets.Observed(12, 64, 5, 13, false);
        fight.start(new Tick(0));
        for (long t = 0; t < 10; t++) fight.tick(new Tick(t));

        assertTrue(moves.started.isEmpty(), "先跑后算，不等寻路：" + moves.started);
        assertTrue(moves.steps.get(0).sprint() && moves.steps.get(0).dx() < 0, "背离苦力怕疾跑：" + moves.steps);
    }

    @Test
    void namedTargetOutOfReachIsApproachedWhereItStandsWithOneWalk() {
        // 僵尸站在 (12, 64, 5) 不动：朝它本人走过去，只开一次走到，不每刻重新寻路。
        targets.now = new SeenTargets.Observed(12, 64, 5, 13, false);
        run(10);

        assertEquals(List.of("走向 12,5"), moves.started);
        assertEquals(0, moves.closed);
    }

    @Test
    void namedTargetDefeatedThenItsDropIsPickedUpRightThere() {
        // 实机：点名打倒一只羊后结果写"没捡到掉落物"，羊肉留在地上——点名打完直接收场，没走拾荒这一步。
        targets.now = new SeenTargets.Observed(1, 64, 1, 1.5, false);
        task.start(new Tick(0));
        task.tick(new Tick(0));
        task.tick(new Tick(1));
        task.tick(new Tick(2));
        targets.now = new SeenTargets.Observed(1, 64, 1, 1.5, true);
        // 开打前脚边就躺着一件别人丢的：不是这一仗的，不去碰；羊肉是这一仗掉的，服务器说收进了包。
        moves.lyingBefore.add(41);
        moves.gained.add(new FightMoves.Gained("minecraft:mutton", 1));
        TaskResult result = null;
        for (long t = 3; t < 40 && result == null; t++) {
            if (task.tick(new Tick(t)) instanceof TickResult.Finished finished) result = finished.result();
        }

        assertNotNull(result);
        assertTrue(moves.started.contains("捡掉落物"), "拾荒交给共用的捡掉落物：" + moves.started);
        assertEquals(Set.of(41), moves.pickupSkipped, "开打前就躺着的不碰");
        FightDetails details = (FightDetails) result.details();
        assertEquals(List.of("minecraft:mutton"), details.lootGained(), result.summary());
    }

    @Test
    void namedTargetSeenDyingCountsAsDefeated() {
        // 打到倒地（死亡动画里还查得到、但已不算活着）：这就是击败的证据。
        targets.now = new SeenTargets.Observed(1, 64, 1, 1.5, false);
        task.start(new Tick(0));
        task.tick(new Tick(0));
        task.tick(new Tick(1));
        task.tick(new Tick(2));
        targets.now = new SeenTargets.Observed(1, 64, 1, 1.5, true);
        TaskResult result = run(20);

        assertNotNull(result);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        FightDetails details = (FightDetails) result.details();
        assertEquals(1, details.defeated().size());
        assertTrue(details.lostTrack().isEmpty());
    }

    @Test
    void namedTargetThatCannotBeHitIsReportedOutOfReachWithWhyTheStrikeFailed() {
        // 实机：尸壳立在三格高石柱顶，按距离够得着、看得见，出手的准星却一直落在柱子顶面上——
        // 原生攻击等到上限按打不到收场，连着三次就记成够不着并收场，问题里写清最后一次为什么没打出去。
        targets.now = new SeenTargets.Observed(1, 64, 1, 1.5, false, true);
        moves.strikeStatus = ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                "够得着却打不到：准星打到的是 方块 0, 65, 2，眼睛离瞄点 2.27 格"));
        TaskResult result = run(30);

        assertNotNull(result, "出手连着没打出去就当场收场，不等停滞判定");
        assertEquals(TaskResult.Status.FAILED, result.status(), result.summary());
        assertEquals(Problem.Kind.UNREACHABLE, result.problem().kind());
        assertTrue(result.problem().message().contains("出手连着 3 次没打出去，最后一次：够得着却打不到：准星打到的是 方块 0, 65, 2"),
                result.problem().message());
        assertEquals(List.of("minecraft:zombie（实体 7）"), ((FightDetails) result.details()).reachIssue());
    }

    @Test
    void namedTargetOverheadIsReportedOutOfReachWithTheSceneInsteadOfStalling() {
        // 点名打一只在头顶三格的僵尸：寻路到它正下方就"到站"，人够不着；当场按够不着收场，
        // 问题里写清它在高处、高出几格、还差几格，不空耗半分钟等停滞判定。
        targets.now = new SeenTargets.Observed(0.5, 67, 0.5, 3.6, false);
        moves.walkStatus = ActionStatus.done();
        TaskResult result = run(10);

        assertNotNull(result, "到站了还够不着就当场收场");
        assertEquals(TaskResult.Status.FAILED, result.status(), result.summary());
        assertEquals(Problem.Kind.UNREACHABLE, result.problem().kind());
        assertTrue(result.problem().message().contains("在高处，高出 3.0 格"), result.problem().message());
        assertTrue(result.problem().message().contains("minecraft:zombie（实体 7）"), result.problem().message());
        assertEquals(List.of("minecraft:zombie（实体 7）"), ((FightDetails) result.details()).reachIssue());
    }

    @Test
    void arrowsSpentIsReadFromTheBackpackNotFromTheShotCount() {
        // 实机：结果写"射了 22 箭"，背包却少了 37 支；另两场写 0 箭，27 支箭全没了——射出几箭只数游戏确认了放箭的，
        // 拉弓中途被原版松手放出去的没人报。箭少了几支改按背包里箭的只数开打与打着时的差算，两个数各写各的。
        targets.now = new SeenTargets.Observed(1, 64, 1, 1.5, false);
        moves.arrows = 27;
        task.start(new Tick(0));
        task.tick(new Tick(0));
        task.tick(new Tick(1));
        task.tick(new Tick(2));
        moves.arrows = 5;
        task.tick(new Tick(3));
        targets.now = new SeenTargets.Observed(1, 64, 1, 1.5, true);
        TaskResult result = run(20);

        assertNotNull(result);
        FightDetails details = (FightDetails) result.details();
        assertEquals(22, details.arrowsSpent(), "箭少了几支按背包数");
        assertEquals(0, details.shots(), "没有一箭得到游戏确认就不冒充射出");
    }

    @Test
    void arrowsShotBySelfDefenceWhileTheFightWasPausedAreNotCountedAsTheFights() {
        // 实机：fight 跑到一半自卫插进来射了 7 支，fight 自己一支没射；自卫的箭由自卫的结果交代，
        // fight 回来接着打时从当时的只数重新起算，结果里只有自己的消耗。
        targets.now = new SeenTargets.Observed(1, 64, 1, 1.5, false);
        moves.arrows = 50;
        task.start(new Tick(0));
        for (long t = 0; t < 3; t++) task.tick(new Tick(t));
        task.pause();
        moves.arrows = 43;
        // 自卫收场交代：射了 7 支；目标把这份结果同样转给正在跑的战斗任务。
        task.keepTempTaskFacts(TaskResult.builder(TaskResult.Status.DONE, "自卫结束")
                .change(Change.of(Change.Kind.ITEM_CONSUMED, "minecraft:arrow", 7)).build());
        task.tick(new Tick(3));
        moves.arrows = 41;
        task.tick(new Tick(4));
        targets.now = new SeenTargets.Observed(1, 64, 1, 1.5, true);
        TaskResult result = null;
        for (long t = 5; t < 30 && result == null; t++) {
            if (task.tick(new Tick(t)) instanceof TickResult.Finished finished) result = finished.result();
        }

        assertNotNull(result);
        FightDetails details = (FightDetails) result.details();
        assertEquals(2, details.arrowsSpent(), "只算回来之后自己射的");
        assertTrue(result.changes().stream().anyMatch(change -> change.kind() == Change.Kind.ITEM_CONSUMED
                && change.what().equals("minecraft:arrow") && change.count() == 2), result.changes().toString());
    }

    @Test
    void arrowStillInFlightWhenEatingInterruptsIsCountedAsTheFights() {
        // 实机：fight 射出一箭，扣减还没同步就被吃饭插队；吃饭不射箭、不认箭，回来后那一支要算这一仗的。
        targets.now = new SeenTargets.Observed(1, 64, 1, 1.5, false);
        moves.arrows = 10;
        task.start(new Tick(0));
        for (long t = 0; t < 3; t++) task.tick(new Tick(t));
        task.pause();
        moves.arrows = 9;
        task.keepTempTaskFacts(TaskResult.builder(TaskResult.Status.DONE, "吃了一口").build());
        task.tick(new Tick(3));
        targets.now = new SeenTargets.Observed(1, 64, 1, 1.5, true);
        TaskResult result = null;
        for (long t = 4; t < 30 && result == null; t++) {
            if (task.tick(new Tick(t)) instanceof TickResult.Finished finished) result = finished.result();
        }

        assertNotNull(result);
        assertEquals(1, ((FightDetails) result.details()).arrowsSpent(), "没人认下的那一支归这一仗");
        assertEquals(1, result.changes().stream().filter(change -> change.kind() == Change.Kind.ITEM_CONSUMED
                && change.what().equals("minecraft:arrow")).mapToInt(Change::count).sum());
    }

    @Test
    void arrowSubtractedAfterTheKillIsStillCountedDuringLoot() {
        // 实机：三场里两场 arrows_spent 比背包净少少 1 支——最后一箭射出时游戏只确认了放箭，
        // 背包里那一支的扣减要等服务器隔一两刻同步过来；目标一倒下战斗阶段就换成拾荒，最后一次记数在它入账前。
        // 拾荒那几刻接着记，收场时那一支也算进去。
        targets.now = new SeenTargets.Observed(1, 64, 1, 1.5, false);
        moves.arrows = 27;
        task.start(new Tick(0));
        task.tick(new Tick(0));
        task.tick(new Tick(1));
        moves.arrows = 6;
        task.tick(new Tick(2));
        targets.now = new SeenTargets.Observed(1, 64, 1, 1.5, true);
        // 倒下的同一刻服务器还没把最后一箭扣掉；下一刻（已经在拾荒）才扣。
        task.tick(new Tick(3));
        moves.arrows = 5;
        TaskResult result = null;
        for (long t = 4; t < 30 && result == null; t++) {
            if (task.tick(new Tick(t)) instanceof TickResult.Finished finished) result = finished.result();
        }

        assertNotNull(result);
        assertEquals(22, ((FightDetails) result.details()).arrowsSpent(), "拾荒时同步到的那一支也算");
    }

    @Test
    void arrowsSpentIsLeftOutWhenTheBackpackCannotBeRead() {
        // 没接上角色（替身世界）：读不到背包就不写这一项，不写 0 冒充没少。
        targets.now = new SeenTargets.Observed(1, 64, 1, 1.5, false);
        task.start(new Tick(0));
        task.tick(new Tick(0));
        task.tick(new Tick(1));
        task.tick(new Tick(2));
        targets.now = new SeenTargets.Observed(1, 64, 1, 1.5, true);
        TaskResult result = run(20);

        assertNotNull(result);
        assertNull(((FightDetails) result.details()).arrowsSpent());
    }

    @Test
    void namedTargetLookingTooStrongIsStillFoughtWithAReminder() {
        // 空着手、点名的僵尸会还手：评估打不过也照打（LLM 叫打的），差在哪记一句提醒，不先拒绝。
        CombatSenses bareHanded = new CombatSenses() {
            @Override public List<Threat> threats(TickContext context, double radius) {
                return namedZombie(7).map(List::of).orElse(List.of());
            }
            @Override public List<Attacker> recentAttackers(TickContext context) { return List.of(); }
            @Override public CombatProfile profile(TickContext context) {
                return new CombatProfile(20, 0, Optional.empty(), 0);
            }
            @Override public Entity entityById(TickContext context, int entityId) { return null; }
            @Override public Optional<Threat> living(TickContext context, int entityId) { return namedZombie(entityId); }
        };
        FightTask fight = new FightTask(
                new FightInput(List.of("e1"), null, FightInput.DEFAULT_RADIUS, null, Permissions.DEFAULT),
                bareHanded, targets, moves, (target, permissions) -> refusal);
        targets.now = new SeenTargets.Observed(1, 64, 1, 1.5, false);
        fight.start(new Tick(0));
        TaskResult result = null;
        for (long t = 0; t < 3; t++) {
            if (fight.tick(new Tick(t)) instanceof TickResult.Finished finished) result = finished.result();
        }
        assertTrue(result == null, "打不过也不在开打前收场：" + (result == null ? "" : result.summary()));
        assertTrue(fight.confidentAgainstCurrentThreats(), "没在撤离就由这一仗自己应对，自卫不插进来");
        targets.now = new SeenTargets.Observed(1, 64, 1, 1.5, true);
        for (long t = 3; t < 40 && result == null; t++) {
            if (fight.tick(new Tick(t)) instanceof TickResult.Finished finished) result = finished.result();
        }

        assertNotNull(result);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary() + " / " + result.problem());
        assertEquals("outmatched", ((FightDetails) result.details()).threatVerdict());
        assertTrue(result.attempts().stream().anyMatch(attempt -> attempt.tried().equals("开打前评估")
                && attempt.result().contains("照打") && attempt.result().contains("武器")), result.attempts().toString());
    }

    @Test
    void namedTargetOwnedBySomeoneNeedsAnotherConfirmationBeforeAnyMove() {
        // 点名的是别人的狗：点了名也要再确认一次，许可没开到 any 就不走过去、不出手。
        refusal = Optional.of(Problem.of(Problem.Kind.NEED_APPROVAL, "是有主的", "把 fight 设为 any"));
        targets.now = new SeenTargets.Observed(1, 64, 1, 1.5, false);
        TaskResult result = run(5);

        assertNotNull(result);
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.NEED_APPROVAL, result.problem().kind());
        assertTrue(moves.started.isEmpty(), moves.started.toString());
    }

    @Test
    void longWalkBackToTheKillSpotKeepsProgressWhileItIsMoving() {
        // 倒下的地方离人几十格：走回去是一趟长路。走到在路上不逐刻自报进展（只说「还在走」），
        // 进展要从脚位挪格读——不读的话走得好好的也会半路被停滞判定按卡住收掉，捡回就此作废。
        targets.now = new SeenTargets.Observed(60, 64, 60, 85, false);
        task.start(new Tick(0));
        for (long t = 0; t < 3; t++) task.tick(new Tick(t));
        targets.now = new SeenTargets.Observed(60, 64, 60, 85, true);
        // 回倒下点的路：六百多刻的长路，脚位逐刻挪一格。
        List<WalkReport> leg = new ArrayList<>();
        for (int i = 0; i < 620; i++) leg.add(WalkReport.onTheWay(new BlockPos(i, 64, i), false));
        moves.nextWalkScript = new TravelFakes.ScriptedWalk(leg.toArray(WalkReport[]::new));
        TaskResult result = null;
        for (long t = 3; t < 3 + 620 && result == null; t++) {
            if (task.tick(new Tick(t)) instanceof TickResult.Finished finished) result = finished.result();
        }
        assertNull(result, "回倒下点的长路不半途按卡住收场");
        assertTrue(task.currentProgress().orElseThrow().lastProgress().contains("回到倒下的地方"),
                task.currentProgress().orElseThrow().lastProgress());
        // 走到倒下点跟前：开始捡（替身直接捡完），当场收场。
        moves.at = new double[] {60, 64, 60};
        for (long t = 3 + 620; t < 3 + 640 && result == null; t++) {
            if (task.tick(new Tick(t)) instanceof TickResult.Finished finished) result = finished.result();
        }
        assertNotNull(result);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
    }

    @Test
    void namedTargetThatVanishesIsNotCountedAsDefeated() {
        // 打着打着不见了（走远或被别的东西打死）：不算击败，以没做成收场并说明跟丢了。
        targets.now = new SeenTargets.Observed(1, 64, 1, 1.5, false);
        task.start(new Tick(0));
        task.tick(new Tick(0));
        task.tick(new Tick(1));
        task.tick(new Tick(2));
        targets.now = null;
        TaskResult result = run(20);

        assertNotNull(result);
        assertEquals(TaskResult.Status.FAILED, result.status(), result.summary());
        assertEquals(Problem.Kind.TARGET_GONE, result.problem().kind());
        FightDetails details = (FightDetails) result.details();
        assertTrue(details.defeated().isEmpty(), details.defeated().toString());
        assertEquals(List.of("minecraft:zombie（实体 7）"), details.lostTrack());
    }
}
