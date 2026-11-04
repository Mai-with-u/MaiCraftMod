// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/** 疾跑断开的诊断：说得出是原版停的还是我们没按、断了多久又接上，跑着不变的刻不出声。 */
class SprintBreaksTest {

    private static final SprintBreaks.Command RUN = new SprintBreaks.Command(1F, true, true, "MovementTraverse");
    private static final SprintBreaks.Reading SPRINTING = new SprintBreaks.Reading(true, false, false, 20, false, false);
    private static final SprintBreaks.Reading BUMPED = new SprintBreaks.Reading(false, true, false, 20, false, false);
    /** 还在跑、这一刻移动之后撞上了：原版下一刻才按它停跑。 */
    private static final SprintBreaks.Reading RUNNING_INTO = new SprintBreaks.Reading(true, true, false, 20, false, false);
    /** 原版停了跑、这一刻移动之后没撞着（慢下来就没顶上）。 */
    private static final SprintBreaks.Reading STOPPED_CLEAR = new SprintBreaks.Reading(false, false, false, 20, false, false);

    // 一刻：先读回上一刻的结算，再给这一刻的按键；两句里有话的那句交出来。
    private static Optional<String> tick(SprintBreaks breaks, long tick, SprintBreaks.Reading reading, SprintBreaks.Command command) {
        Optional<String> read = breaks.read(reading, tick);
        Optional<String> commanded = command == null ? Optional.empty() : breaks.commanded(command, tick);
        return read.isPresent() ? read : commanded;
    }

    @Test
    void 一路跑着_不出声() {
        var breaks = new SprintBreaks();
        for (long t = 0; t < 40; t++) assertTrue(tick(breaks, t, SPRINTING, RUN).isEmpty());
        assertTrue(breaks.summary().isEmpty(), "一次没断不给总数");
    }

    @Test
    void 按着疾跑原版停了_说是撞墙_接上时说断了几刻() {
        var breaks = new SprintBreaks();
        tick(breaks, 0, SPRINTING, RUN);
        tick(breaks, 1, RUNNING_INTO, RUN);
        String line = tick(breaks, 2, BUMPED, RUN).orElseThrow();
        assertTrue(line.contains("原版") && line.contains("撞墙"), line);
        assertTrue(tick(breaks, 3, BUMPED, RUN).isEmpty(), "断着的时候不重复说");
        assertEquals("疾跑接上了，断了 2 刻", tick(breaks, 4, SPRINTING, RUN).orElseThrow());
        String summary = breaks.summary().orElseThrow();
        assertTrue(summary.contains("断了 1 次") && summary.contains("1 次不到半秒又接上") && summary.contains("原版停的：撞墙 1 次"), summary);
    }

    @Test
    void 擦边不算撞墙() {
        var breaks = new SprintBreaks();
        tick(breaks, 0, new SprintBreaks.Reading(true, true, true, 20, false, false), RUN);
        String line = tick(breaks, 1, new SprintBreaks.Reading(false, true, true, 20, false, false), RUN).orElseThrow();
        assertTrue(line.contains("没认出原因") && line.contains("擦边=是"), line);
    }

    @Test
    void 原版按上一刻的碰撞停跑_停下那一刻没撞着也算撞墙() {
        // 跑着顶上墙角，原版下一刻停跑；停跑后慢下来这一刻没再顶上，读回的碰撞已经是"没撞着"。
        var breaks = new SprintBreaks();
        tick(breaks, 0, SPRINTING, RUN);
        tick(breaks, 1, RUNNING_INTO, RUN);
        String line = tick(breaks, 2, STOPPED_CLEAR, RUN).orElseThrow();
        assertTrue(line.contains("撞墙") && line.contains("撞着=是"), line);
    }

    @Test
    void 上一刻没撞_停下那一刻才撞上_不算撞墙() {
        // 原版判停跑时看的那份碰撞是"没撞着"：停跑另有原因，不冒充成撞墙。
        var breaks = new SprintBreaks();
        tick(breaks, 0, SPRINTING, RUN);
        String line = tick(breaks, 1, BUMPED, RUN).orElseThrow();
        assertTrue(line.contains("没认出原因"), line);
    }

    @Test
    void 饿了原版不让跑() {
        var breaks = new SprintBreaks();
        tick(breaks, 0, SPRINTING, RUN);
        String line = tick(breaks, 1, new SprintBreaks.Reading(false, false, false, 6, false, false), RUN).orElseThrow();
        assertTrue(line.contains("饿了"), line);
    }

    @Test
    void 我们不按疾跑_说出为什么() {
        var breaks = new SprintBreaks();
        tick(breaks, 0, SPRINTING, RUN);
        String line = tick(breaks, 1, SPRINTING, new SprintBreaks.Command(1F, false, false, "MovementAscend")).orElseThrow();
        assertTrue(line.contains("引擎这一步不要跑") && line.contains("MovementAscend"), line);

        breaks = new SprintBreaks();
        tick(breaks, 0, SPRINTING, RUN);
        line = tick(breaks, 1, SPRINTING, new SprintBreaks.Command(0F, true, true, "MovementTraverse")).orElseThrow();
        assertTrue(line.contains("没按前进"), line);

        breaks = new SprintBreaks();
        tick(breaks, 0, SPRINTING, RUN);
        line = tick(breaks, 1, SPRINTING, new SprintBreaks.Command(1F, false, true, "MovementTraverse")).orElseThrow();
        assertTrue(line.contains("调用方不让跑"), line);
    }

    @Test
    void 这一刻没给按键_下一刻说出来() {
        var breaks = new SprintBreaks();
        tick(breaks, 0, SPRINTING, RUN);
        assertTrue(tick(breaks, 1, SPRINTING, null).isEmpty(), "这一刻还不知道给不给");
        String line = tick(breaks, 2, SPRINTING, RUN).orElseThrow();
        assertTrue(line.contains("没给走路按键"), line);
    }

    @Test
    void 一断一续_总数里数得出来() {
        var breaks = new SprintBreaks();
        long t = 0;
        tick(breaks, t++, SPRINTING, RUN);
        for (int i = 0; i < 5; i++) {
            tick(breaks, t++, SPRINTING, RUN);
            tick(breaks, t++, BUMPED, RUN);
            tick(breaks, t++, SPRINTING, RUN);
        }
        String summary = breaks.summary().orElseThrow();
        assertTrue(summary.contains("断了 5 次") && summary.contains("5 次不到半秒又接上"), summary);
    }

    @Test
    void 原版先停_我们再不按_只算一次() {
        var breaks = new SprintBreaks();
        tick(breaks, 0, SPRINTING, RUN);
        tick(breaks, 1, BUMPED, new SprintBreaks.Command(1F, false, true, "MovementTraverse"));
        assertTrue(breaks.summary().orElseThrow().contains("断了 1 次"));
    }
}
