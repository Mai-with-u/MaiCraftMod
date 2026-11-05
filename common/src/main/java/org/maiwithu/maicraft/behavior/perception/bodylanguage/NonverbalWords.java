// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.bodylanguage;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 一段肢体动作怎么说成一句话：谁、按先后做了什么、几下、对着谁或哪儿、拿着什么，最后说他在角色哪个方位多远、
 * 看没看着角色。纯函数。连着做的同一种合在一起写几下，不逐下列。
 */
final class NonverbalWords {

    private NonverbalWords() {}

    /**
     * 一段的一句话。
     *
     * @param who         "玩家 Alex（e4）"这样的称呼
     * @param acts        这一段的肢体动作，按先后
     * @param where       "他在你前方 3 格"这样的方位
     * @param lookingNow  他此刻看着角色
     */
    static String sentence(String who, List<NonverbalAct> acts, String where, boolean lookingNow) {
        List<String> phrases = new ArrayList<>();
        boolean saidLooking = false;
        int i = 0;
        while (i < acts.size()) {
            NonverbalAct first = acts.get(i);
            int count = 0;
            boolean toward = false;
            int j = i;
            // 连着做的同一种、同一个细节合成一句：蹲起 4 下、对着你挥了 2 下。扔东西每次单说。
            while (j < acts.size() && sameRun(first, acts.get(j))) {
                count += acts.get(j).count();
                toward |= acts.get(j).towardMe();
                j++;
            }
            String phrase = phrase(first, count);
            // 开头那一下是看着角色做的：句子开头说"看着你"，后面不再重复。
            if (phrases.isEmpty() && toward && looksFirst(first.kind())) {
                phrase = "看着你" + phrase;
                saidLooking = true;
            }
            phrases.add(phrase);
            i = j;
        }
        String tail = where + (lookingNow && !saidLooking ? "，看着你" : "");
        return who + String.join("，", phrases) + "；" + tail;
    }

    /** 刷屏合并的一句：这一分钟剩下的按种类写总数。 */
    static String overflow(String who, Map<NonverbalAct.Kind, Integer> counts) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<NonverbalAct.Kind, Integer> entry : new EnumMap<>(counts).entrySet()) {
            parts.add(total(entry.getKey(), entry.getValue()));
        }
        return who + "这一分钟又" + String.join("、", parts);
    }

    private static boolean sameRun(NonverbalAct first, NonverbalAct next) {
        return first.kind() == next.kind() && first.detail().equals(next.detail())
                && first.kind() != NonverbalAct.Kind.TOSS && first.kind() != NonverbalAct.Kind.WALK;
    }

    // 只有原地做的那几种要补一句"看着你"；挥向你、拍你、扔给你的句子里已经说了冲着谁。
    private static boolean looksFirst(NonverbalAct.Kind kind) {
        return switch (kind) {
            case CROUCH, NOD, SHAKE_HEAD, JUMP, HOLD -> true;
            default -> false;
        };
    }

    private static String phrase(NonverbalAct act, int count) {
        return switch (act.kind()) {
            case CROUCH -> "蹲起 " + count + " 下";
            case NOD -> "点了 " + count + " 下头";
            case SHAKE_HEAD -> "摇了 " + count + " 下头";
            case JUMP -> "原地跳了 " + count + " 下";
            case HOLD -> act.detail().isEmpty() ? "空出手" : "拿出" + act.detail();
            case SWING -> act.detail() + "挥了 " + count + " 下";
            case POKE -> (count == 1 ? "拍了你一下" : "拍了你 " + count + " 下")
                    + (act.detail().isEmpty() ? "" : "：" + act.detail());
            case TOSS, WALK -> act.detail();
        };
    }

    private static String total(NonverbalAct.Kind kind, int count) {
        return switch (kind) {
            case CROUCH -> "蹲起 " + count + " 下";
            case NOD -> "点头 " + count + " 下";
            case SHAKE_HEAD -> "摇头 " + count + " 下";
            case JUMP -> "原地跳 " + count + " 下";
            case HOLD -> "换手 " + count + " 次";
            case SWING -> "挥手 " + count + " 下";
            case POKE -> "拍你 " + count + " 下";
            case TOSS -> "扔东西 " + count + " 次";
            case WALK -> "走开又回头 " + count + " 次";
        };
    }
}
