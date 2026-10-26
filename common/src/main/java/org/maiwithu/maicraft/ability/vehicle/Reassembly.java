// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.maiwithu.maicraft.ability.vehicle.api.VehicleControls;

/**
 * 拆回再组装时档案怎么跟（纯函数）：车头换成世界里的朝向、认重新组装出来的是不是拆回的那一艘、
 * 控制回路哪里变了、操纵表哪些行带过去。
 *
 * <p>拆回时结构先摆正到最近的整 90° 再落回世界，重新组装出来的结构自己的轴就是组装那一刻的世界轴，
 * 所以车头在拆回时换成世界朝向存着，组装后原样就是新结构自己的轴。改船就是拆回的目的，
 * 方块清单只要一半以上对得上就认作同一艘，多了少了的照实列出来；回路变了的输入，试过的角色不带过去（要重新试按），
 * LLM 定的照带（以它为准），输入没了的都不带。
 */
final class Reassembly {

    /** 方块清单至少有这么多对得上才算同一艘：两边共有的格数占多的那边的比例。 */
    static final double SAME_SHARE = 0.5;

    private Reassembly() {}

    /**
     * 结构自己的一根水平轴此刻落在世界的哪根轴上，就近取整 90°（拆回时就是按这个摆正的）。
     *
     * @param localAxis 结构自己的轴：+x / -x / +z / -z；不是这四个原样返回
     * @param heading   结构自己的 +z 此刻朝哪：0 南、90 西、180 北、270 东
     */
    static String worldAxis(String localAxis, double heading) {
        Integer own = switch (localAxis) {
            case "+z" -> 0;
            case "-x" -> 90;
            case "-z" -> 180;
            case "+x" -> 270;
            default -> null;
        };
        if (own == null) return localAxis;
        long turns = Math.round(heading / 90.0);
        int world = (int) Math.floorMod(own + turns * 90, 360L);
        return switch (world) {
            case 0 -> "+z";
            case 90 -> "-x";
            case 180 -> "-z";
            default -> "+x";
        };
    }

    /**
     * 认不认得：是不是同一艘、两边共有几格、拆前与组装后各几格、方块清单的增减。
     *
     * @param same    认作同一艘
     * @param shared  两边共有的格数（每种方块取少的那边）
     * @param before  拆前的格数
     * @param after   组装后的格数
     * @param changes 方块清单的增减，一种一句（多了 minecraft:iron_block×4、少了 …），按方块 ID 排
     * @param note    为什么这么认，一句话
     */
    record Verdict(boolean same, int shared, int before, int after, List<String> changes, String note) {
        Verdict {
            changes = List.copyOf(changes);
        }
    }

    /** 拿拆前与组装后的方块清单认是不是同一艘；哪边清单是空的（没读全）就认不出。 */
    static Verdict match(Map<String, Integer> before, Map<String, Integer> after) {
        int beforeTotal = before.values().stream().mapToInt(Integer::intValue).sum();
        int afterTotal = after.values().stream().mapToInt(Integer::intValue).sum();
        Set<String> ids = new TreeSet<>(before.keySet());
        ids.addAll(after.keySet());
        int shared = 0;
        List<String> changes = new ArrayList<>();
        for (String id : ids) {
            int was = before.getOrDefault(id, 0);
            int now = after.getOrDefault(id, 0);
            shared += Math.min(was, now);
            if (now > was) changes.add("多了 " + id + "×" + (now - was));
            if (now < was) changes.add("少了 " + id + "×" + (was - now));
        }
        if (beforeTotal == 0 || afterTotal == 0) {
            return new Verdict(false, shared, beforeTotal, afterTotal, List.of(),
                    (beforeTotal == 0 ? "拆前" : "组装后") + "的方块清单没读到，认不出是不是同一艘");
        }
        double share = shared / (double) Math.max(beforeTotal, afterTotal);
        boolean same = share >= SAME_SHARE;
        String note = "拆前 " + beforeTotal + " 格、组装后 " + afterTotal + " 格，对得上 " + shared + " 格"
                + (same ? "" : "，不到一半，当成另一艘");
        return new Verdict(same, shared, beforeTotal, afterTotal, same ? changes : List.of(), note);
    }

    /**
     * 一路控制回路：输入、它发的频率、收端旁边接着的执行件。
     */
    record Circuit(String control, String frequency, List<String> drives) {
        Circuit {
            Objects.requireNonNull(control, "control");
            frequency = frequency == null ? "" : frequency;
            drives = List.copyOf(drives);
        }
    }

    /**
     * 回路的变化。
     *
     * @param lines   一处一句：新多了的输入、没了的输入、接线变了的输入
     * @param changed 接线变了的输入
     * @param gone    没了的输入
     */
    record CircuitChange(List<String> lines, Set<String> changed, Set<String> gone) {
        CircuitChange {
            lines = List.copyOf(lines);
            changed = Set.copyOf(changed);
            gone = Set.copyOf(gone);
        }
    }

    /** 拆前与组装后的回路逐个输入比：频率或接着的执行件不一样就是接线变了。 */
    static CircuitChange circuits(List<Circuit> before, List<Circuit> after) {
        Map<String, Circuit> was = byControl(before);
        Map<String, Circuit> now = byControl(after);
        List<String> lines = new ArrayList<>();
        Set<String> changed = new LinkedHashSet<>();
        Set<String> gone = new LinkedHashSet<>();
        for (Circuit old : was.values()) {
            Circuit fresh = now.get(old.control());
            if (fresh == null) {
                gone.add(old.control());
                lines.add("没了 " + old.control());
            } else if (!old.frequency().equals(fresh.frequency())) {
                changed.add(old.control());
                lines.add(old.control() + " 的频率从 " + old.frequency() + " 换成 " + fresh.frequency());
            } else if (!new TreeSet<>(old.drives()).equals(new TreeSet<>(fresh.drives()))) {
                changed.add(old.control());
                lines.add(old.control() + " 接着的从 " + text(old.drives()) + " 换成 " + text(fresh.drives()));
            }
        }
        for (Circuit fresh : now.values()) {
            if (!was.containsKey(fresh.control())) {
                lines.add("新多了 " + fresh.control() + "（接着 " + text(fresh.drives()) + "）");
            }
        }
        return new CircuitChange(lines, changed, gone);
    }

    /**
     * 带过去的操纵表。
     *
     * @param stored  搬到新结构上的部分
     * @param dropped 没带的行，一行一句为什么
     */
    record Carried(VehicleControls.Stored stored, List<String> dropped) {
        Carried {
            dropped = List.copyOf(dropped);
        }
    }

    /** 操纵表带过去：输入没了的不带；接线变了的，试过的不带（要重新试按），LLM 定的照带；车头照带。 */
    static Carried carry(VehicleControls.Stored stored, CircuitChange change) {
        List<VehicleControls.Entry> kept = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (VehicleControls.Entry entry : stored.entries()) {
            if (change.gone().contains(entry.control())) {
                dropped.add(entry.control() + " 的 " + entry.role() + "（这个输入没了）");
            } else if (change.changed().contains(entry.control()) && !entry.source().equals(VehicleControls.ASSIGNED)) {
                dropped.add(entry.control() + " 试过的 " + entry.role() + "（接线变了，要重新试按）");
            } else {
                kept.add(entry);
            }
        }
        return new Carried(new VehicleControls.Stored(kept, stored.forward(), stored.forwardSource()), dropped);
    }

    private static Map<String, Circuit> byControl(List<Circuit> circuits) {
        Map<String, Circuit> found = new LinkedHashMap<>();
        for (Circuit circuit : circuits) found.putIfAbsent(circuit.control(), circuit);
        return found;
    }

    private static String text(List<String> drives) {
        return drives.isEmpty() ? "没有认得的执行件" : String.join("、", drives);
    }
}
