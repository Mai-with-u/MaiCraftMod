// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 读本实例注册了哪些 ID 的只读接缝：物品、方块、实体类型、群系、结构各有哪些注册 ID 与标签（含模组和数据包加的）。
 * ID 写错时报错末尾的"你是不是要"从这里挑候选，lookup 的群系与结构目录页也读它；实现在本包，测试用替身摆名单。
 */
public interface ReadsRegisteredIds {

    /** 这一类本实例注册过的全部 ID（命名空间:名字），按 ID 排序；读不到时给空（群系与结构要进了世界才有）。 */
    List<String> ids(IdKind kind);

    /** 这一类本实例里至少挂着一样东西的标签 ID（不带 #），按 ID 排序；读不到时给空。 */
    List<String> tags(IdKind kind);

    /**
     * 写错的 ID 或 # 标签的统一说法："物品 minecraft:diamon 不存在；你是不是要 minecraft:diamond"，
     * 标签说成"物品标签 #… 不存在"；本实例里找不到相近的就只说不存在。
     * 要的这一类里没有、物品方块实体的另一类里有的，直说"touhou_little_maid:smart_slab_init 是物品，不是方块"。
     */
    default String missing(IdKind kind, String idOrTag) {
        String written = idOrTag.trim();
        if (written.startsWith("#")) {
            // 标签只和标签比：#minecraft:log 该提示 #minecraft:logs，不该提示某一种原木。
            return SimilarIds.missing(kind.label() + "标签", written, SimilarIds.closest(tags(kind), written));
        }
        return missing(List.of(kind), written);
    }

    /**
     * 一个写法可以是几类里任何一类的 ID（掉落线索里的"方块或实体"）写错时的统一说法，例如
     * "方块或实体 minecraft:zombi 不存在；你是不是要 minecraft:zombie"；相近名从这几类一起挑，按给的先后排候选。
     * 这几类都没有、物品方块实体的另一类里有的，说"minecraft:diamond 是物品，不是方块或实体"。
     */
    default String missing(List<IdKind> kinds, String id) {
        String written = id.trim();
        String wanted = kinds.stream().map(IdKind::label).collect(Collectors.joining("或"));
        // 照着建方块却写成了物品 ID、挖方块写成了生物这类混了种类的：给同类相近名只会把 LLM 带去一个不相干的
        // 模组方块，直接说它是哪一类，LLM 下一次换成对的参数或对的东西。
        List<String> actually = registeredAsOtherKinds(kinds, written);
        if (!actually.isEmpty()) {
            return written + " 是" + String.join("和", actually) + "，不是" + wanted;
        }
        List<String> candidates = new ArrayList<>();
        kinds.forEach(kind -> candidates.addAll(ids(kind)));
        return SimilarIds.missing(wanted, written, SimilarIds.closest(candidates, written));
    }

    /**
     * 写的 ID 在要的那几类里都没有、却在物品方块实体的另一类里注册过时，给那几类的叫法；否则给空。
     * 物品与方块常常同名（方块物品），只要要的那类里有就不说它是别的。没写命名空间的按原版补上，大小写不分，
     * 和游戏读 ID 的规矩一样。
     */
    private List<String> registeredAsOtherKinds(List<IdKind> kinds, String written) {
        String normalized = written.toLowerCase(Locale.ROOT);
        if (normalized.indexOf(':') < 0) normalized = "minecraft:" + normalized;
        // 只查物品、方块、实体三类：方块和它的物品同名、刷怪蛋和生物、手里的物品和放下去的方块，最常被写混；
        // 群系、结构、药水种类不会被当成一样东西去拿去放，要的是它们时不往别处查。
        Set<IdKind> mixedUp = EnumSet.of(IdKind.ITEM, IdKind.BLOCK, IdKind.ENTITY_TYPE);
        for (IdKind kind : kinds) {
            if (!mixedUp.contains(kind) || ids(kind).contains(normalized)) return List.of();
        }
        List<String> actually = new ArrayList<>();
        for (IdKind other : mixedUp) {
            if (!kinds.contains(other) && ids(other).contains(normalized)) actually.add(other.label());
        }
        return actually;
    }
}
