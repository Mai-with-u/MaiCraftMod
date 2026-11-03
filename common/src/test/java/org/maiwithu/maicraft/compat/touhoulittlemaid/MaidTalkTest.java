// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.touhoulittlemaid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.chat.spi.TalkTo;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.kernel.event.ChatEventLog;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 对女仆说话：开框、打字、回车、认回话；对话框没弹出、回车后没反应、只回了提示、只生成了人设、等满、
 * 说完就走、打字途中被打断后重开从头打、她自己改了工作，各自如实交代。
 */
class MaidTalkTest {

    private static final int ID = 7;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private final MaidFakes.Reads reads = new MaidFakes.Reads();
    private final MaidFakes.Actions actions = new MaidFakes.Actions(reads);
    private final ChatEventLog chat = new ChatEventLog();
    private final Entity maid = MaidFakes.creature(ID);
    private final TouhouLittleMaidCompat compat = new TouhouLittleMaidCompat(reads, actions);
    /** 回车之后每刻跑一次的"她那边"：测试摆她什么时候开始想、什么时候回话。 */
    private Runnable afterEnter = () -> {};
    private int ticksAfterEnter;

    MaidTalkTest() {
        reads.states.put(ID, MaidFakes.maid(ID, "小雪", List.of()));
        actions.onEnter = () -> think(true);
    }

    private void think(boolean on) {
        reads.states.put(ID, withBubbles(on ? List.of(MaidFakes.thinking(1)) : List.of()));
    }

    private MaidState withBubbles(List<MaidState.Bubble> bubbles) {
        MaidState s = reads.states.get(ID);
        return new MaidState(s.entityId(), s.name(), s.skinId(), s.skinName(), s.workId(), s.workName(), s.shift(),
                s.shiftNow(), s.stayInArea(), s.areaCenter(), s.areaRadius(), s.pickup(), s.rideable(), s.affection(),
                s.experience(), s.begging(), s.invulnerable(), s.backpackId(), bubbles);
    }

    /** 对准就发包、等对话框弹出的替身：弹不出来按游戏没有反应。 */
    private static Action aim(Entity target, String doing, Runnable submission, InteractionConfirmation confirmation,
            int confirmTicks) {
        return new Action() {
            private boolean sent;
            private int waited;

            @Override public ActionStatus tick(TickContext context) {
                if (!sent) {
                    sent = true;
                    submission.run();
                    return ActionStatus.progressed();
                }
                if (confirmation.observe(null) == InteractionConfirmation.Verdict.APPLIED) return ActionStatus.done();
                if (++waited >= confirmTicks) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME, doing + "，游戏没有反应", null));
                }
                return ActionStatus.running();
            }

            @Override public String describe() {
                return doing;
            }
        };
    }

    private TalkTo.Talk talk(String message, int maxSeconds, boolean typewriter) {
        MaidTalk talkTo = new MaidTalk(compat, MaidFakes.services(chat), MaidTalkTest::aim);
        TalkTo.Listener listener = talkTo.listener(null, maid).orElseThrow();
        return listener.talk(message, maxSeconds, typewriter, Permissions.DEFAULT);
    }

    private ActionStatus run(TalkTo.Talk talk, int limit) {
        MaidFakes.Tick tick = new MaidFakes.Tick();
        for (int i = 0; i < limit; i++) {
            ActionStatus status = talk.tick(tick);
            if (status instanceof ActionStatus.Done || status instanceof ActionStatus.Failed) return status;
            if (!actions.entered.isEmpty()) {
                ticksAfterEnter++;
                afterEnter.run();
            }
            tick.gameTick++;
        }
        throw new AssertionError("说话动作没有按时收场");
    }

    @Test
    void 开框打字回车_认到她的回话() {
        afterEnter = () -> {
            if (ticksAfterEnter == 30) {
                think(false);
                MaidFakes.systemMessage(chat, "<小雪> 好的主人");
            }
        };
        TalkTo.Talk talk = talk("你好呀", 30, true);
        assertInstanceOf(ActionStatus.Done.class, run(talk, 2000));
        assertEquals(List.of(ID), actions.opened, "看着她发一次开框的包");
        assertEquals(List.of("你好呀"), actions.entered, "整句打进对话框再回车");
        TalkTo.Heard heard = talk.heard();
        assertTrue(heard.delivered());
        assertEquals("好的主人", heard.reply());
        assertFalse(heard.replyPending());
    }

    @Test
    void 对话框没弹出来_没能确认且这句没打出去() {
        actions.dialogPops = false;
        TalkTo.Talk talk = talk("你好", 30, true);
        assertInstanceOf(ActionStatus.Done.class, run(talk, 500));
        assertTrue(actions.entered.isEmpty());
        assertNotNull(talk.heard().unconfirmed());
        assertTrue(talk.heard().unconfirmed().contains("没打开"));
        assertFalse(talk.heard().delivered());
    }

    @Test
    void 回车后她没任何反应_没能确认不重说() {
        actions.onEnter = () -> {};
        TalkTo.Talk talk = talk("你好", 30, false);
        assertInstanceOf(ActionStatus.Done.class, run(talk, 500));
        assertEquals(1, actions.entered.size());
        assertFalse(talk.heard().delivered());
        assertTrue(talk.heard().unconfirmed().contains("不要重说"));
    }

    @Test
    void 她那边只回了提示_按游戏拒绝附原话() {
        actions.onEnter = () -> MaidFakes.systemMessage(chat, "AI 对话功能已被禁用");
        TalkTo.Talk talk = talk("你好", 30, false);
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, run(talk, 500));
        assertEquals(Problem.Kind.REFUSED_BY_GAME, failed.problem().kind());
        assertTrue(failed.problem().message().contains("AI 对话功能已被禁用"));
        assertEquals(List.of("AI 对话功能已被禁用"), talk.heard().notices());
    }

    @Test
    void 只生成了人设_不算回话() {
        afterEnter = () -> {
            if (ticksAfterEnter == 10) {
                think(false);
                MaidFakes.systemMessage(chat, "角色设定文件已经自动生成完毕");
            }
        };
        TalkTo.Talk talk = talk("你好", 30, false);
        assertInstanceOf(ActionStatus.Done.class, run(talk, 500));
        assertTrue(talk.heard().personaGenerated());
        assertNull(talk.heard().reply());
    }

    @Test
    void 一直在想_等满按没等到回话() {
        TalkTo.Talk talk = talk("你好", 2, false);
        assertInstanceOf(ActionStatus.Done.class, run(talk, 500));
        assertTrue(talk.heard().delivered());
        assertTrue(talk.heard().replyPending());
    }

    @Test
    void 想完了却没回话_再等一会儿按没等到() {
        afterEnter = () -> {
            if (ticksAfterEnter == 5) think(false);
        };
        TalkTo.Talk talk = talk("你好", 30, false);
        assertInstanceOf(ActionStatus.Done.class, run(talk, 200));
        assertTrue(talk.heard().replyPending());
    }

    @Test
    void 说完就走_她开始想就收场() {
        TalkTo.Talk talk = talk("你好", 0, false);
        assertInstanceOf(ActionStatus.Done.class, run(talk, 100));
        assertTrue(talk.heard().delivered());
        assertTrue(talk.heard().replyPending());
    }

    @Test
    void 打字途中被打断_关框后重开从头打() {
        TalkTo.Talk talk = talk("你好呀主人", 30, true);
        MaidFakes.Tick tick = new MaidFakes.Tick();
        // 推到正在打字：对话框开着、框里有字了。
        for (int i = 0; i < 200 && (reads.dialogFor == null || reads.dialog.isEmpty()); i++) {
            talk.tick(tick);
            tick.gameTick++;
        }
        assertFalse(reads.dialog.isEmpty(), "已经打进去几个字");
        talk.pause();
        assertNull(reads.dialogFor, "被打断时关掉对话框");
        afterEnter = () -> {
            if (ticksAfterEnter == 3) {
                think(false);
                MaidFakes.systemMessage(chat, "<小雪> 嗯");
            }
        };
        assertInstanceOf(ActionStatus.Done.class, run(talk, 2000));
        assertEquals(2, actions.opened.size(), "回来重新开框");
        assertEquals(List.of("你好呀主人"), actions.entered, "重开后从头打完整句");
    }

    @Test
    void 她自己换了工作_按生物受影响列出() {
        afterEnter = () -> {
            if (ticksAfterEnter == 5) {
                MaidState s = reads.states.get(ID);
                reads.states.put(ID, new MaidState(s.entityId(), s.name(), s.skinId(), s.skinName(),
                        "touhou_little_maid:farm", "种田", s.shift(), s.shiftNow(), s.stayInArea(), s.areaCenter(),
                        s.areaRadius(), s.pickup(), s.rideable(), s.affection(), s.experience(), s.begging(),
                        s.invulnerable(), s.backpackId(), List.of()));
                MaidFakes.systemMessage(chat, "<小雪> 我去种田啦");
            }
        };
        TalkTo.Talk talk = talk("去种田", 30, false);
        assertInstanceOf(ActionStatus.Done.class, run(talk, 500));
        List<Change> changes = talk.heard().changes();
        assertEquals(1, changes.size());
        assertEquals(Change.Kind.ENTITY_AFFECTED, changes.getFirst().kind());
        assertTrue(changes.getFirst().note().contains("touhou_little_maid:farm"));
    }

    @Test
    void 本机关了AI对话_听不了() {
        reads.aiChat = false;
        MaidTalk talkTo = new MaidTalk(compat, MaidFakes.services(chat), MaidTalkTest::aim);
        TalkTo.Listener listener = talkTo.listener(null, maid).orElseThrow();
        // 不在世界里认不出主人：先报不是你的；主人对上之后才轮到 AI 对话开关。
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, listener.refusal().orElseThrow().kind());
    }

    @Test
    void 不是女仆_不给听法() {
        Entity pig = MaidFakes.creature(99);
        MaidTalk talkTo = new MaidTalk(compat, MaidFakes.services(chat), MaidTalkTest::aim);
        assertTrue(talkTo.listener(null, pig).isEmpty());
    }
}
