// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * 走路时疾跑断开的诊断记录：每刻记下给原版的走路按键和原版跑没跑，疾跑断开时说出是哪一边断的——
 * 我们这一刻没按疾跑（没按前进、引擎这一步不要跑、调用方不让跑、这一刻没给按键），
 * 还是按着疾跑、原版把它停了（撞墙、饿了、在用东西）。疾跑一断视野就缩一下，
 * 实机看到走路时视野一缩一放，拿这份记录分清是谁在断、断多久又接上。
 * 只在断开和接上那一刻给一句，一趟走完给一句总数；纯记账，不读世界。
 */
final class SprintBreaks {

    /** 断开后多少刻内又接上算一断一续（半秒）：视野会明显一缩一放。 */
    static final long SHORT_BREAK_TICKS = 10;

    /**
     * 这一刻给原版的走路按键。
     *
     * @param forward      前进键，正为往前
     * @param sprint       疾跑键
     * @param engineWanted 引擎这一步要不要跑
     * @param step         路线上当前这一步的走法，没有路线为空串
     */
    record Command(float forward, boolean sprint, boolean engineWanted, String step) {
        /** 原版只有按着前进时才跑得起来。 */
        boolean running() {
            return forward > 0 && sprint;
        }
    }

    /**
     * 刻末读回的原版情况：这一刻的按键结算之后，角色跑没跑、撞没撞、饿不饿。
     * 撞没撞是这一刻移动之后的；原版判停跑用的是上一刻移动之后的那份，比它早一拍。
     */
    record Reading(boolean sprinting, boolean horizontalCollision, boolean minorCollision, int food,
                   boolean usingItem, boolean inWater) {
        /** 换成另一刻移动之后的碰撞，其余照这一刻。 */
        Reading collidedAs(Reading other) {
            return new Reading(sprinting, other.horizontalCollision, other.minorCollision, food, usingItem, inWater);
        }
    }

    /** 原版饱食度到这个数及以下就不让疾跑。 */
    private static final int SPRINT_FOOD = 6;

    /** 上一刻给的按键；那一刻没给为 null。 */
    private Command previous;
    /** 上一刻读回的原版情况：原版这一刻判停跑时看的碰撞就是它；还没读过为 null。 */
    private Reading lastReading;
    /** 这一刻给的按键；还没给为 null。 */
    private Command current;
    /** 疾跑断开的那一刻；没断为 -1。 */
    private long brokeAt = -1;
    /** 每种原因断了几次，按第一次出现的先后。 */
    private final Map<String, Integer> causes = new LinkedHashMap<>();
    private int shortBreaks;

    /**
     * 这一刻开始时读回原版，看上一刻给的按键结算成了什么样：按着疾跑、原版没在跑，就是原版停的；
     * 上一刻干脆没给按键而再上一刻在跑，就是没给按键断的。断开或接上时给一句，平常为空。
     */
    Optional<String> read(Reading reading, long tick) {
        Command given = current;
        Command before = previous;
        previous = given;
        current = null;
        // 原版这一刻判停跑时看的是上一刻移动之后撞没撞：拿上一份读回的碰撞来归因，不拿这一刻移动后的。
        Reading judged = lastReading == null ? reading : reading.collidedAs(lastReading);
        lastReading = reading;
        if (given == null) {
            // 上一刻在备料、等前面的东西过去、落地救援占着：再上一刻按着疾跑的，疾跑键在这里松了。
            return before != null && before.running()
                    ? broke("没给按键", "这一刻没给走路按键（备料、等东西过去、落地救援占着）", tick) : Optional.empty();
        }
        if (!given.running()) return Optional.empty();
        if (!reading.sprinting()) {
            String cause = vanillaCause(judged);
            return broke("原版停的：" + cause, "按着疾跑，原版把它停了：" + cause + "（" + facts(judged) + "）", tick);
        }
        return resumed(tick);
    }

    /** 这一刻给的按键：上一刻按着疾跑、这一刻不按了，说出为什么不按。 */
    Optional<String> commanded(Command command, long tick) {
        current = command;
        if (previous == null || !previous.running() || command.running()) return Optional.empty();
        String cause = ourCause(command);
        return broke("没按疾跑：" + cause, "这一刻没按疾跑：" + cause + "（这一步 " + command.step() + "）", tick);
    }

    /** 这一趟的总数：断了几次、各为什么、几次不到半秒又接上；一次没断为空。 */
    Optional<String> summary() {
        int total = causes.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) return Optional.empty();
        StringJoiner parts = new StringJoiner("，");
        causes.forEach((cause, count) -> parts.add(cause + " " + count + " 次"));
        return Optional.of("这一趟疾跑断了 " + total + " 次，其中 " + shortBreaks + " 次不到半秒又接上：" + parts);
    }

    // 断开：已经断着的不重复记（我们不按之前原版已经停了的，只算原版停的那一次）。
    private Optional<String> broke(String cause, String line, long tick) {
        if (brokeAt >= 0) return Optional.empty();
        brokeAt = tick;
        causes.merge(cause, 1, Integer::sum);
        return Optional.of(line);
    }

    // 按着疾跑、原版也在跑：断着的话就是接上了，记下断了几刻，半秒内接上的算一断一续。
    private Optional<String> resumed(long tick) {
        if (brokeAt < 0) return Optional.empty();
        long length = tick - brokeAt;
        brokeAt = -1;
        if (length <= SHORT_BREAK_TICKS) shortBreaks++;
        return Optional.of("疾跑接上了，断了 " + length + " 刻");
    }

    // 原版停疾跑的几种原因：正面撞上（擦边不算）、饿了、在用东西；都不是就照实说没认出来。
    private static String vanillaCause(Reading reading) {
        if (reading.horizontalCollision() && !reading.minorCollision()) return "撞墙";
        if (reading.food() <= SPRINT_FOOD) return "饿了";
        if (reading.usingItem()) return "在用东西";
        return "没认出原因";
    }

    // 我们不按疾跑的几种原因：没按前进（站住、只在跳或侧移）、引擎这一步不要跑、调用方不让跑。
    private static String ourCause(Command command) {
        if (command.forward() <= 0) return "没按前进";
        if (!command.engineWanted()) return "引擎这一步不要跑";
        return "调用方不让跑";
    }

    private static String facts(Reading reading) {
        return "撞着=" + yes(reading.horizontalCollision()) + " 擦边=" + yes(reading.minorCollision())
                + " 饱食=" + reading.food() + " 用东西=" + yes(reading.usingItem()) + " 水里=" + yes(reading.inWater());
    }

    private static String yes(boolean value) {
        return value ? "是" : "否";
    }
}
