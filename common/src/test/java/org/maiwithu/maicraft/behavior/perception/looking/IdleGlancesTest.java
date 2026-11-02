// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.looking;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.maiwithu.maicraft.behavior.perception.looking.LookingDoubles.mob;
import static org.maiwithu.maicraft.behavior.perception.looking.LookingDoubles.player;
import static org.maiwithu.maicraft.behavior.perception.looking.LookingDoubles.sound;

/** 闲看挑什么：说话的人 > 声音 > 玩家 > 生物 > 随便看；范围、不重复、闲久了只剩有反应的几种。 */
class IdleGlancesTest {

    private static final UUID FRIEND = new UUID(0, 1);
    private static final UUID STRANGER = new UUID(0, 2);

    private static Optional<IdleGlances.Glance> pick(List<LookingWorld.Candidate> around, Map<UUID, Long> spoke,
            Map<String, Long> heard, String lastKey, boolean reactiveOnly) {
        return IdleGlances.pick(around, spoke, heard, 0f, lastKey, null, reactiveOnly, new Random(3));
    }

    @Test
    void 先看刚说过话的人_再看声音_再看玩家_再看生物() {
        List<LookingWorld.Candidate> around = List.of(
                mob("e1", 30f, 5, true),
                player("e2", STRANGER, 60f, 6, false),
                sound("sound:脚步:后方", 180f),
                player("e3", FRIEND, -90f, 20, false));
        assertEquals("e3", pick(around, Map.of(FRIEND, 40L), Map.of("sound:脚步:后方", 10L), null, false)
                .orElseThrow().key(), "说过话的人二十四格内都看");
        assertEquals("sound:脚步:后方", pick(around, Map.of(), Map.of("sound:脚步:后方", 10L), null, false)
                .orElseThrow().key(), "没人说话先看声音");
        assertEquals("e2", pick(around, Map.of(), Map.of(), null, false).orElseThrow().key(), "再看玩家");
        assertEquals("e1", pick(List.of(around.get(0)), Map.of(), Map.of(), null, false).orElseThrow().key(), "再看生物");
    }

    @Test
    void 说话超过十秒_声音超过三秒就不算刚发生() {
        List<LookingWorld.Candidate> around = List.of(player("e3", FRIEND, -90f, 20, false),
                sound("sound:爆炸:左侧", -90f));
        IdleGlances.Glance glance = pick(around, Map.of(FRIEND, 201L), Map.of("sound:爆炸:左侧", 61L), null, false)
                .orElseThrow();
        // 二十格外的玩家不在十六格内，说话也过了十秒，声音也过了三秒：随便看（或低头看地面）。
        assertTrue(glance.key().equals("anywhere") || glance.key().equals("ground"), glance.key());
    }

    @Test
    void 太远的玩家和生物不看() {
        List<LookingWorld.Candidate> around = List.of(player("e2", STRANGER, 60f, 17, false), mob("e1", 30f, 13, true));
        String key = pick(around, Map.of(), Map.of(), null, false).orElseThrow().key();
        assertTrue(key.equals("anywhere") || key.equals("ground"), key);
    }

    @Test
    void 连着两回不看同一处_说话的人除外() {
        List<LookingWorld.Candidate> around = List.of(player("e2", STRANGER, 60f, 6, false), mob("e1", 30f, 5, true));
        assertEquals("e1", pick(around, Map.of(), Map.of(), "e2", false).orElseThrow().key(), "上回看过这个玩家，换生物");
        assertEquals("e2", pick(around, Map.of(STRANGER, 5L), Map.of(), "e2", false).orElseThrow().key(),
                "他又说话了，接着看他");
    }

    @Test
    void 动着的生物先看() {
        List<LookingWorld.Candidate> around = List.of(mob("e1", 30f, 3, false), mob("e4", 50f, 10, true));
        assertEquals("e4", pick(around, Map.of(), Map.of(), null, false).orElseThrow().key());
    }

    @Test
    void 闲久了只对说话的人_走近的玩家和声音有反应() {
        List<LookingWorld.Candidate> around = List.of(player("e2", STRANGER, 60f, 6, false), mob("e1", 30f, 5, true));
        assertTrue(pick(around, Map.of(), Map.of(), null, true).isEmpty(), "站着的玩家和生物都不看，也不随便看");
        List<LookingWorld.Candidate> coming = List.of(player("e2", STRANGER, 60f, 6, true));
        assertEquals("e2", pick(coming, Map.of(), Map.of(), null, true).orElseThrow().key(), "走过来的玩家看");
    }

    @Test
    void 随便看不转过一百度_俯仰在范围里_五回里约有一回看地面() {
        Random random = new Random(7);
        int ground = 0;
        Float last = null;
        int closeToLast = 0;
        for (int i = 0; i < 500; i++) {
            IdleGlances.Glance glance = IdleGlances.anywhere(40f, last, random);
            LookTarget.Direction direction = (LookTarget.Direction) glance.step().target();
            float offset = Aim.wrap(direction.yaw() - 40f);
            if (glance.key().equals("ground")) {
                ground++;
                assertTrue(Math.abs(offset) <= 20.01f, "看地面左右不偏多：" + offset);
                assertTrue(direction.pitch() >= 28f && direction.pitch() <= 38f, "看脚前两三格：" + direction.pitch());
                continue;
            }
            assertTrue(Math.abs(offset) >= 24.99f && Math.abs(offset) <= 100.01f, "偏开二十五到一百度：" + offset);
            assertTrue(direction.pitch() >= -15f && direction.pitch() <= 10f, "俯仰：" + direction.pitch());
            if (last != null && Math.abs(glance.randomOffset() - last) < 20f) closeToLast++;
            last = glance.randomOffset();
            int hold = glance.step().holdTicks();
            assertTrue(hold >= 20 && hold <= 40, "一两秒：" + hold);
        }
        assertTrue(ground > 60 && ground < 150, "五回里约一回看地面：" + ground);
        assertTrue(closeToLast < 10, "连着两回随便看几乎不挨着：" + closeToLast);
    }

    @Test
    void 同一份随机数给同样的结果() {
        List<LookingWorld.Candidate> around = List.of();
        IdleGlances.Glance first = IdleGlances.pick(around, Map.of(), Map.of(), 0f, null, null, false, new Random(11))
                .orElseThrow();
        IdleGlances.Glance second = IdleGlances.pick(around, Map.of(), Map.of(), 0f, null, null, false, new Random(11))
                .orElseThrow();
        assertEquals(first, second);
    }
}
