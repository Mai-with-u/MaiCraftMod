// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.event.EventFacts;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;

/** 挨打的片段与换维度事件：归并、10 秒分段、死亡丢弃、只看到血掉了的口径、黄心到期不算、换维度。 */
class HurtEpisodesTest {

    private final List<TaskEvent.Kind> kinds = new ArrayList<>();
    private final List<String> messages = new ArrayList<>();
    private final List<EventFacts> facts = new ArrayList<>();
    private final TaskEventSink sink = new TaskEventSink() {
        @Override public void publish(TaskEvent.Kind kind, String message) {
            kinds.add(kind);
            messages.add(message);
        }

        @Override public void publish(TaskEvent.Kind kind, String message, EventFacts attached) {
            publish(kind, message);
            facts.add(attached);
        }
    };
    private final HurtEpisodes hurts = new HurtEpisodes(sink, id -> Optional.empty());

    private static final HurtEpisodes.Hit SKELETON = new HurtEpisodes.Hit("entity:7", "骷髅（e3）", "arrow",
            "minecraft:skeleton", "骷髅", 7);

    @Test
    void 同一个攻击者连着挨的打归成一段_停手五秒才发() {
        hurts.health(20f, false, 0);
        hurts.hit(SKELETON, 10);
        hurts.health(17f, false, 10);
        hurts.hit(SKELETON, 30);
        hurts.health(14f, false, 30);
        hurts.health(14f, false, 120);
        assertTrue(messages.isEmpty(), "还没停够五秒");
        hurts.health(14f, false, 130);
        assertEquals(List.of(TaskEvent.Kind.HURT), kinds);
        assertEquals("被骷髅（e3）射中 2 下，掉了 6 点血，还剩 14 点", messages.getFirst());
    }

    @Test
    void 一直挨着打_每十秒发一条只算这十秒里的() {
        hurts.health(20f, false, 0);
        long tick = 0;
        float health = 20f;
        for (int i = 0; i < 6; i++) {
            tick += 40;
            hurts.hit(SKELETON, tick);
            health -= 1f;
            hurts.health(health, false, tick);
        }
        assertEquals(1, messages.size(), "第 200 刻发了一条");
        assertTrue(messages.getFirst().contains("6 下"), messages.getFirst());
    }

    @Test
    void 死了的时候没发出的段丢掉() {
        hurts.health(20f, false, 0);
        hurts.hit(SKELETON, 10);
        hurts.health(4f, false, 10);
        hurts.health(0f, true, 20);
        hurts.health(20f, false, 400);
        assertTrue(messages.isEmpty(), "角色死了已有自己的事件，这一段不再发");
    }

    @Test
    void 没收到伤害包只看到血掉了的单独一段写明口径() {
        hurts.health(20f, false, 0);
        hurts.health(18.5f, false, 5);
        hurts.health(18.5f, false, 105);
        assertEquals("只看到血掉了 1.5 点（没收到伤害包，说不清是谁、什么伤），还剩 18.5 点", messages.getFirst());
    }

    @Test
    void 黄心到期血量不变不算挨打_摔落按伤害类型说() {
        hurts.health(20f, false, 0);
        hurts.health(20f, false, 300);
        assertTrue(messages.isEmpty());
        hurts.hit(new HurtEpisodes.Hit("type:fall", null, "fall", null, null, null), 310);
        hurts.health(16f, false, 310);
        hurts.health(16f, false, 410);
        assertEquals("摔了一下，掉了 4 点血，还剩 16 点", messages.getFirst());
    }

    @Test
    void 还剩的血写摔完当刻的_不写发出去时回了血的数() {
        hurts.health(20f, false, 0);
        hurts.hit(new HurtEpisodes.Hit("type:fall", null, "fall", null, null, null), 10);
        hurts.health(12f, false, 10);
        // 吃饱了的角色等发事件的这 5 秒里一路回血，发出去时已经回满。
        hurts.health(16f, false, 60);
        hurts.health(20f, false, 110);
        assertEquals("摔了一下，掉了 8 点血，还剩 12 点", messages.getFirst());
    }

    @Test
    void 连着挨几下中间回了血_还剩按最后一下扣完的算() {
        hurts.health(20f, false, 0);
        hurts.hit(SKELETON, 10);
        hurts.health(17f, false, 10);
        hurts.health(19f, false, 50);
        hurts.hit(SKELETON, 60);
        hurts.health(16f, false, 60);
        hurts.health(20f, false, 160);
        assertEquals("被骷髅（e3）射中 2 下，掉了 6 点血，还剩 16 点", messages.getFirst());
    }

    @Test
    void 没掉血的轻摔_还剩写摔那一刻的血() {
        hurts.health(15f, false, 0);
        hurts.hit(new HurtEpisodes.Hit("type:fall", null, "fall", null, null, null), 10);
        hurts.health(15f, false, 10);
        hurts.health(18f, false, 110);
        assertEquals("摔了一下，掉了 0 点血，还剩 15 点", messages.getFirst());
    }

    @Test
    void 换维度发一条_刚进世界不算() {
        DimensionChanges changes = new DimensionChanges(sink);
        changes.observe("minecraft:overworld", new BlockPos(0, 64, 0));
        assertTrue(messages.isEmpty());
        changes.observe("minecraft:the_nether", new BlockPos(12, 70, -8));
        assertEquals(List.of(TaskEvent.Kind.DIMENSION_CHANGED), kinds);
        assertEquals("从主世界到了下界，落在 12, 70, -8", messages.getFirst());
    }

    @Test
    void 别的玩家拍一下掉的血不进片段_也不算只看到血掉了() {
        // 玩家拍了一下：掉的 1 点血留给拍一下的账，挨打事件不发。
        hurts.health(20f, false, 0);
        hurts.byPlayer(10);
        hurts.health(19f, false, 11);
        hurts.health(19f, false, 300);
        assertTrue(messages.isEmpty(), "拍一下只在肢体动作事件里说：" + messages);
    }

    @Test
    void 被玩家连打的那几下照常进片段() {
        hurts.health(20f, false, 0);
        hurts.byPlayer(10);
        hurts.health(17f, false, 11);
        hurts.beaten("玩家 Alex（e4）", 4, "player", 3f, 12);
        hurts.health(17f, false, 120);
        assertEquals(List.of(TaskEvent.Kind.HURT), kinds);
        assertEquals("被玩家 Alex（e4）打了一下，掉了 3 点血，还剩 17 点", messages.getFirst());
    }

    @Test
    void 认得出是谁打的_攻击者事实铺在事件上() {
        // 有攻击者的段：类型 ID、干净名字（不带观察编号后缀）、实体编号，宿主按字段认，不解析句子。
        hurts.health(20f, false, 0);
        hurts.hit(SKELETON, 10);
        hurts.health(17f, false, 10);
        hurts.health(17f, false, 130);
        assertEquals(List.of(TaskEvent.Kind.HURT), kinds);
        assertEquals(new AttackerFacts("minecraft:skeleton", "骷髅", 7), facts.getFirst());

        // 被玩家连打：类型是 minecraft:player，名字不带观察编号后缀。
        facts.clear();
        hurts.health(20f, false, 0);
        hurts.byPlayer(10);
        hurts.health(17f, false, 11);
        hurts.beaten("玩家 Alex（e4）", 4, "player", 3f, 12);
        hurts.health(17f, false, 130);
        assertEquals(new AttackerFacts("minecraft:player", "玩家 Alex", 4), facts.getFirst());
    }

    @Test
    void 没有攻击者的伤不带攻击者事实() {
        hurts.health(20f, false, 0);
        hurts.hit(new HurtEpisodes.Hit("type:fall", null, "fall", null, null, null), 10);
        hurts.health(17f, false, 10);
        hurts.health(17f, false, 130);
        assertEquals(List.of(TaskEvent.Kind.HURT), kinds);
        assertTrue(facts.isEmpty(), "摔落没有攻击者，事件上不铺字段");
    }
}
