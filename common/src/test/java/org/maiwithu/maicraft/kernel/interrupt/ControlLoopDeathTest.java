// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.interrupt;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.player.DeathFacts;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.interrupt.ControlLoopTest.FakeNeed;
import org.maiwithu.maicraft.kernel.interrupt.ControlLoopTest.FakeTask;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.Urgency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 角色死亡时控制循环停摆：不再插生存需求的临时任务、不推进任务，插着的临时任务按"角色没了"收尾，
 * 只发一条死亡事件；重生后（同一个上下文替身改回活着）循环照常推进，但要等新身体的背包从服务器发来才开始判。
 */
class ControlLoopDeathTest {

    @Test
    void deadCharacterHaltsTheLoopWithOneDeathEvent() {
        // 被僵尸磨死在死亡界面：饥饿还急也不能再插吃饭任务，事件只报一条，不刷屏。
        FakeTask main = new FakeTask("挖矿", Interruptibility.WORKING);
        FakeNeed hunger = FakeNeed.always("饥饿", Urgency.NOW);
        RecordingEvents events = new RecordingEvents();
        ControlLoop loop = new ControlLoop(List.of(hunger), events);
        loop.setMainTask(main);

        DyingPlayer body = new DyingPlayer();
        body.dead = true;

        assertInstanceOf(ControlLoop.Decision.WaitingRespawn.class, loop.tick(new Tick(body)));
        assertEquals(0, hunger.created, "死了不再插生存需求的临时任务");
        assertEquals(0, main.ticks, "死了不推进主任务");
        assertEquals(1, events.published.size(), "死亡只报一条事件");
        assertTrue(events.published.get(0).startsWith(TaskEvent.Kind.CHARACTER_DIED.name()),
                "发的是角色死亡事件：" + events.published.get(0));

        // 继续死着：不重复报，也不偷偷恢复推进。
        assertInstanceOf(ControlLoop.Decision.WaitingRespawn.class, loop.tick(new Tick(body)));
        assertEquals(1, events.published.size());
        assertEquals(0, hunger.created);
        assertEquals(0, main.ticks);
    }

    @Test
    void tempTasksEndButMainTaskSurvivesTheDeath() {
        // 死亡时插着的临时任务按"角色没了"收尾（已经发生的消耗留在它的结果里），
        // 主任务不收尾，只停手；重生后从原地接着做。
        FakeTask main = new FakeTask("挖矿", Interruptibility.WORKING);
        // 重生后处境不再急（脚本第二次回答 null），轮到的就是主任务。
        FakeNeed breath = FakeNeed.of("换气", Urgency.NOW, null);
        ControlLoop loop = new ControlLoop(List.of(breath), new RecordingEvents());
        loop.setMainTask(main);

        DyingPlayer body = new DyingPlayer();
        loop.tick(new Tick(body));
        FakeTask temp = (FakeTask) loop.currentTask();
        assertFalse(temp.closed, "前置：临时任务已经插进来在推进");

        body.dead = true;
        assertInstanceOf(ControlLoop.Decision.WaitingRespawn.class, loop.tick(new Tick(body)));
        assertTrue(temp.closed, "临时任务按角色没了收尾");
        assertEquals(CloseReason.PLAYER_GONE, temp.closeReason);
        assertFalse(main.closed, "主任务不收尾，等重生接着做");
        assertEquals(2, main.pauses, "插临时任务时停过一次，死亡停摆时再停一次");

        // 重生：同一个身体活了，循环恢复，轮到主任务。
        body.dead = false;
        ControlLoop.Decision.Advanced advanced = assertInstanceOf(
                ControlLoop.Decision.Advanced.class, loop.tick(new Tick(body)));
        assertSame(main, advanced.task());
        assertNull(advanced.interrupting());
    }

    @Test
    void deathEventCarriesTheDeathScreenCause() {
        // 死亡界面上那句死因原话带进事件：LLM 才知道是被什么弄死的。
        RecordingEvents events = new RecordingEvents();
        DyingPlayer body = new DyingPlayer();
        body.facts = new DeathFacts(12, "minecraft:overworld", 1, 64, 2, false, "MaiBot被苦力怕炸死了");
        ControlLoop loop = new ControlLoop(List.of(), events, new RecordingDeathDecisions());
        loop.setMainTask(new FakeTask("挖矿", Interruptibility.WORKING));
        body.dead = true;
        loop.tick(new Tick(body));
        assertTrue(events.published.stream().anyMatch(line -> line.startsWith("CHARACTER_DIED:角色死了（MaiBot被苦力怕炸死了）")),
                "事件带死因：" + events.published);
    }

    @Test
    void deathHandsFactsToTheDecisionHostOnce() {
        // 停摆的第一刻把死亡事实与连接状态交给挂决策的一方，之后每刻停摆不再重复叫。
        RecordingDeathDecisions decisions = new RecordingDeathDecisions();
        DyingPlayer body = new DyingPlayer();
        ControlLoop loop = new ControlLoop(List.of(), new RecordingEvents(), decisions);
        loop.setMainTask(new FakeTask("挖矿", Interruptibility.WORKING));

        body.dead = true;
        assertInstanceOf(ControlLoop.Decision.WaitingRespawn.class, loop.tick(new Tick(body)));
        loop.tick(new Tick(body));
        assertEquals(1, decisions.deaths, "同一个死亡过程只叫一次");
        assertEquals(12, decisions.facts.score(), "死亡事实原样交给挂决策的一方");
        assertFalse(decisions.connectionAlive, "替身没有连接，如实交给挂决策的一方");
        assertEquals(0, decisions.aliveAgain, "还没活过来不算了结");

        // 重生：本轮死亡决策了结，下次死亡再挂新的。
        body.dead = false;
        loop.tick(new Tick(body));
        assertEquals(1, decisions.aliveAgain);
    }

    @Test
    void deathWithoutAHostStillHaltsTheLoop() {
        // 没接挂决策的一方：死亡照常停摆，不挂问题也不出错。
        DyingPlayer body = new DyingPlayer();
        ControlLoop loop = new ControlLoop(List.of(), new RecordingEvents());
        body.dead = true;
        assertInstanceOf(ControlLoop.Decision.WaitingRespawn.class, loop.tick(new Tick(body)));
    }

    @Test
    void immediateRespawnStillEndsTempTasksAndReportsTheDeath() {
        // 服务器开着立即重生：一刻都看不到死掉的身体，只看到重生次数变了。插着的找床照样收尾、报一条死亡，
        // 不挂死亡决策（已经重生了）；下一刻主任务从重生的地方接着做。
        FakeTask main = new FakeTask("挖矿", Interruptibility.WORKING);
        FakeNeed night = FakeNeed.of("夜晚", Urgency.SOON, null);
        RecordingEvents events = new RecordingEvents();
        RecordingDeathDecisions decisions = new RecordingDeathDecisions();
        ControlLoop loop = new ControlLoop(List.of(night), events, decisions);
        loop.setMainTask(main);

        DyingPlayer body = new DyingPlayer();
        loop.tick(new Tick(body));
        FakeTask bedHunt = (FakeTask) loop.currentTask();
        assertFalse(bedHunt.closed, "前置：找床插进来了");

        body.respawns = 1;
        body.facts = new DeathFacts(0, "minecraft:overworld", 600, 78, 80, false, "MaiBot被僵尸杀死了");
        assertInstanceOf(ControlLoop.Decision.WaitingRespawn.class, loop.tick(new Tick(body)));
        assertTrue(bedHunt.closed, "找床按角色没了收尾");
        assertEquals(CloseReason.PLAYER_GONE, bedHunt.closeReason);
        assertEquals(0, decisions.deaths, "已经重生，不挂死亡决策");
        assertTrue(events.published.stream().anyMatch(line -> line.startsWith("CHARACTER_DIED:角色死了（MaiBot被僵尸杀死了），已经在 600, 78, 80 重生")),
                "报一条死亡：" + events.published);

        ControlLoop.Decision.Advanced advanced = assertInstanceOf(
                ControlLoop.Decision.Advanced.class, loop.tick(new Tick(body)));
        assertSame(main, advanced.task(), "主任务接着做");
    }

    @Test
    void respawnedBodyWaitsForItsBackpackBeforeAskingNeeds() {
        // 重生后的新身体背包从空的起，服务器整包发来之前身上看着什么都没有：这一刻问夜晚需求，它会把包里的铁剑当没有，
        // 插进"备武器"；包真到了（铁剑在）就不该插。循环在背包到之前不问需求、不推进主任务，到了才接着判。
        FakeTask main = new FakeTask("挖矿", Interruptibility.WORKING);
        DyingPlayer body = new DyingPlayer();
        // 夜晚需求的替身：只看身上有没有剑——背包没到时读到空背包，判"打不过、备武器"；到了看见铁剑就不插。
        AsksAboutBackpack night = new AsksAboutBackpack(body);
        ControlLoop loop = new ControlLoop(List.of(night), new RecordingEvents());
        loop.setMainTask(main);

        body.dead = true;
        assertInstanceOf(ControlLoop.Decision.WaitingRespawn.class, loop.tick(new Tick(body)));

        // 重生：身体活了，但背包还没发来。
        body.dead = false;
        body.backpackReceived = false;
        ControlLoop.Decision.WaitingBackpack waiting = assertInstanceOf(
                ControlLoop.Decision.WaitingBackpack.class, loop.tick(new Tick(body)));
        assertSame(main, waiting.mainTask(), "等背包时主任务还在手上");
        assertEquals(0, night.asked, "背包没到不问生存需求");
        assertEquals(0, main.ticks, "背包没到不推进主任务");

        // 背包到了（铁剑在包里）：问了需求，它看见剑不插备武器，轮到主任务。
        body.backpackReceived = true;
        ControlLoop.Decision.Advanced advanced = assertInstanceOf(
                ControlLoop.Decision.Advanced.class, loop.tick(new Tick(body)));
        assertSame(main, advanced.task());
        assertEquals(1, night.asked);
        assertEquals(0, night.created, "包里有剑，不插备武器");
    }

    /** 夜晚需求替身：背包没到时读到的是空背包（没剑→备武器），到了就看见剑、不插；记下被问了几次、建了几次任务。 */
    private static final class AsksAboutBackpack implements SurvivalNeed {
        private final DyingPlayer body;
        int asked;
        int created;

        AsksAboutBackpack(DyingPlayer body) {
            this.body = body;
        }

        @Override public String name() { return "夜晚"; }

        @Override public Urgency urgency(TickContext context) {
            asked++;
            return body.backpackReceived ? null : Urgency.SOON;
        }

        @Override public Task createTask(TickContext context) {
            created++;
            return new FakeTask("备武器", Interruptibility.WORKING);
        }
    }

    /** 记下死亡决策挂载口被叫了几次：断言一个死亡过程只挂一次、活过来才了结。 */
    private static final class RecordingDeathDecisions implements DeathDecisionHost {
        int deaths;
        int aliveAgain;
        DeathFacts facts;
        boolean connectionAlive;

        @Override public void characterDied(DeathFacts deathFacts, boolean aliveConnection) {
            deaths++;
            facts = deathFacts;
            connectionAlive = aliveConnection;
        }

        @Override public void characterAliveAgain() {
            aliveAgain++;
        }
    }

    /** 只回答"死没死、背包到没到"的角色上下文替身；其他入口这些测试用不到，如实报缺。 */
    private static final class DyingPlayer implements PlayerContext {
        boolean dead;
        long respawns;
        /** 这具身体的背包服务器发来没有：默认当作到了，只有换身体的测试把它拨回去。 */
        boolean backpackReceived = true;
        DeathFacts facts = new DeathFacts(12, "minecraft:overworld", 1, 64, 2, false, null);

        @Override public LocalPlayer localPlayer() { return null; }
        @Override public ClientLevel level() { return null; }
        @Override public ClientPacketListener connection() { return null; }
        @Override public PlayerInput input() {
            throw new UnsupportedOperationException("死亡测试用不到输入入口");
        }
        @Override public InteractionSender interactionSender() {
            throw new UnsupportedOperationException("死亡测试用不到交互提交入口");
        }
        @Override public MenuActions menuActions() {
            throw new UnsupportedOperationException("死亡测试用不到容器界面入口");
        }
        @Override public long clientTick() { return 0; }
        @Override public boolean isCurrent() { return true; }
        @Override public boolean canInteractThisTick() { return false; }
        @Override public boolean tryClaimInteraction() { return false; }
        @Override public boolean isDeadOrDying() { return dead; }
        @Override public DeathFacts deathFacts() { return facts; }
        @Override public long respawns() { return respawns; }
        @Override public boolean backpackReceived() { return backpackReceived; }
    }

    /** 本刻上下文替身：把身体递给循环。 */
    private record Tick(DyingPlayer body) implements TickContext {
        @Override public long gameTick() { return 0; }
        @Override public PlayerContext player() { return body; }
    }

    /** 记下发过的事件：断言死亡只发一条。 */
    private static final class RecordingEvents implements TaskEventSink {
        final List<String> published = new ArrayList<>();

        @Override public void publish(TaskEvent.Kind kind, String message) {
            published.add(kind + ":" + message);
        }
    }
}
