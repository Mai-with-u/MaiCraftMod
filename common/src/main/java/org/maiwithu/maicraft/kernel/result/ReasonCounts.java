// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.result;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 原因合并计数：一件事里有好几处没做成（几格没砌上、几段没装上）时，按原因合在一起数，拼成交给上一层的一句结论。
 *
 * <p>同一句原因只说一次、带上几件；件数最多的原因排前面，最多点名两种，再多的只说还有几种原因、共几件，
 * 说明的长短不随件数和原因种数变长。问题种类跟着件数最多的那种原因走（并列时取先记下的），
 * 说明里头一句原因与种类对得上：站位够不着的多就是到不了，点了不生效的多就是卡住。
 * 原因要写成不带坐标、不带次数的结论句，同样的事才合得到一起；逐件的坐标留在各能力自己的结果细节里。
 */
public final class ReasonCounts {

    /** 说明里最多点名几种原因。 */
    private static final int NAMED = 2;

    private final Map<String, Problem.Kind> kinds = new LinkedHashMap<>();
    private final Map<String, Integer> counts = new LinkedHashMap<>();

    /** 记下 count 件因为这句原因没做成；同一句原因合在一起数，种类按头一次记的。 */
    public void add(Problem.Kind kind, String reason, int count) {
        if (count <= 0) return;
        kinds.putIfAbsent(reason, kind);
        counts.merge(reason, count, Integer::sum);
    }

    public boolean isEmpty() {
        return counts.isEmpty();
    }

    /** 件数最多的那种原因的问题种类；并列取先记下的。一件都没记时为 null。 */
    public Problem.Kind kind() {
        List<String> ranked = ranked();
        return ranked.isEmpty() ? null : kinds.get(ranked.getFirst());
    }

    /**
     * 拼成一句：先是调用方给的开头（"3 格没做成"），只有一种原因时直接接这句原因；
     * 几种原因时按件数从多到少点名前两种并带件数，再多的说还有几种原因、共几件。
     * 原因之间用顿号接，不用分号：上一层只引分号前的结论句，几种原因都要留在结论里。
     *
     * @param lead 开头的结论，已经写明一共几件
     * @param unit 件数的量词（"格""件"）
     */
    public String message(String lead, String unit) {
        List<String> ranked = ranked();
        if (ranked.isEmpty()) return lead;
        if (ranked.size() == 1) return lead + "：" + ranked.getFirst();
        StringBuilder text = new StringBuilder(lead).append("：");
        for (int i = 0; i < Math.min(NAMED, ranked.size()); i++) {
            if (i > 0) text.append("、");
            String reason = ranked.get(i);
            text.append(counts.get(reason)).append(' ').append(unit).append(reason);
        }
        if (ranked.size() > NAMED) {
            int rest = 0;
            for (String reason : ranked.subList(NAMED, ranked.size())) rest += counts.get(reason);
            text.append("，另有 ").append(ranked.size() - NAMED).append(" 种原因共 ").append(rest).append(' ').append(unit);
        }
        return text.toString();
    }

    // 按件数从多到少排；件数一样时照先记下的先后（排序是稳定的）。
    private List<String> ranked() {
        List<String> ranked = new ArrayList<>(counts.keySet());
        ranked.sort((a, b) -> Integer.compare(counts.get(b), counts.get(a)));
        return ranked;
    }
}
