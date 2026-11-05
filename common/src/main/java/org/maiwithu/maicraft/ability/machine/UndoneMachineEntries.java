// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.ArrayList;
import java.util.List;

import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ReasonCounts;

/**
 * 建机器没做成的机器条目：安装段、部件、装后设置各几件没做成，各自为什么。收场时拼成部分完成的问题：
 * 先说哪几样还差几件，再把原因合并计数说进同一句结论；种类跟着件数最多的那种原因走——
 * 走不到、宿主没加载是到不了，动作做完了核对没装上是卡住，没有机器类型认、结构上还不会才是不支持，
 * 只有不支持时才提示装上联动再来。
 */
final class UndoneMachineEntries {

    private int segments;
    private int parts;
    private int settings;
    private final ReasonCounts reasons = new ReasonCounts();

    /** 一段安装段没装成：只取这一段问题的结论句和种类，经过留在结果细节与试过的办法里。 */
    void segment(Problem why) {
        segments++;
        reasons.add(why.kind(), why.headline(), 1);
    }

    /** 一个部件没装上。 */
    void part(Problem why) {
        parts++;
        reasons.add(why.kind(), why.headline(), 1);
    }

    /** 一条装后设置没改成。 */
    void setting(Problem why) {
        settings++;
        reasons.add(why.kind(), why.headline(), 1);
    }

    /**
     * 部分完成的问题：整机比对还有几格没对上（施工做全了之后还不对，算卡住）放在前面，
     * 再说机器条目还差几件、为什么。什么都没差（施工只做了一部分却没交出问题）照实说施工没做全。
     *
     * @param unsettledCells 整机比对还没对上的格数（含没加载下不了结论的）
     */
    Problem problem(long unsettledCells) {
        List<String> undone = new ArrayList<>();
        count(segments, "段安装段", undone);
        count(parts, "个部件", undone);
        count(settings, "条设置", undone);
        String cells = unsettledCells > 0 ? "整机比对还有 " + unsettledCells + " 格没对上" : "";
        if (undone.isEmpty()) {
            return Problem.of(Problem.Kind.STUCK, cells.isEmpty() ? "施工没有全部做成" : cells);
        }
        String entries = reasons.message("还有 " + String.join("、", undone) + "没做成", "件");
        Problem.Kind kind = reasons.kind();
        return Problem.of(kind, cells.isEmpty() ? entries : cells + "，" + entries,
                kind == Problem.Kind.UNSUPPORTED ? "缺的联动装上后，再下达一次同样的目标会接着做" : null);
    }

    private static void count(int count, String unit, List<String> into) {
        if (count > 0) into.add(count + " " + unit);
    }
}
