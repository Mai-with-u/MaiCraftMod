// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.touhoulittlemaid;

import java.util.List;
import java.util.Objects;

import net.minecraft.core.BlockPos;

/**
 * 一只女仆此刻在客户端看得到的状态：车万女仆把这些同步给周围的玩家，Jade 与她的界面显示的也是它们。
 * 驯服关系、坐没坐、血量、手持与盔甲是原版生物事实，不在这里。
 *
 * @param entityId     游戏实体编号
 * @param name         她此刻在本机的显示名：起了名用名字，没起名是皮肤名
 * @param skinId       皮肤（模型）ID，例如 geckolib:winefox
 * @param skinName     本机模型表里这个皮肤的显示名；本机模型表里没有为 null
 * @param workId       工作 ID，例如 touhou_little_maid:farm
 * @param workName     工作的显示名
 * @param shift        班次
 * @param shiftNow     按班次与世界时刻算好的此刻活动
 * @param stayInArea   驻守（待在活动范围里）为 true，跟着主人为 false
 * @param areaCenter   活动范围的圆心
 * @param areaRadius   活动范围的半径
 * @param pickup       开着拾取
 * @param rideable     让不让人骑（抱）
 * @param affection    好感点数
 * @param experience   经验点
 * @param begging      看见主人手里的蛋糕在乞食
 * @param invulnerable 不会受伤
 * @param backpackId   背包种类 ID
 * @param bubbles      她头上此刻的气泡，按出现先后
 */
public record MaidState(int entityId, String name, String skinId, String skinName, String workId, String workName,
                        Shift shift, ShiftNow shiftNow, boolean stayInArea, BlockPos areaCenter, float areaRadius,
                        boolean pickup, boolean rideable, int affection, int experience, boolean begging,
                        boolean invulnerable, String backpackId, List<Bubble> bubbles) {

    public MaidState {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(skinId, "skinId");
        Objects.requireNonNull(workId, "workId");
        Objects.requireNonNull(shift, "shift");
        Objects.requireNonNull(shiftNow, "shiftNow");
        bubbles = bubbles == null ? List.of() : List.copyOf(bubbles);
    }

    /** 班次：车万女仆界面里的日程。 */
    public enum Shift {
        DAY("day"), NIGHT("night"), ALL("all");

        private final String word;

        Shift(String word) {
            this.word = word;
        }

        /** 写进观察与参数的取值。 */
        public String word() {
            return word;
        }
    }

    /** 此刻活动：工作中、休息、睡觉。 */
    public enum ShiftNow {
        WORK("work"), REST("rest"), SLEEP("sleep");

        private final String word;

        ShiftNow(String word) {
            this.word = word;
        }

        /** 写进观察的取值。 */
        public String word() {
            return word;
        }
    }

    /**
     * 她头上的一个气泡。
     *
     * @param key  车万女仆给这个气泡的编号（到期时刻），新冒出来的气泡编号没见过
     * @param kind 文字、在想，还是别的（表情、图、进度）
     * @param text 文字气泡的正文；在想的气泡是它的副文本（她在调哪个工具），没有为 null
     */
    public record Bubble(long key, Kind kind, String text) {

        /** 气泡种类。 */
        public enum Kind { TEXT, THINKING, OTHER }
    }

    /** 她在想（头上有在想的气泡）。 */
    public boolean thinking() {
        return bubbles.stream().anyMatch(bubble -> bubble.kind() == Bubble.Kind.THINKING);
    }

    /** 在想的气泡的副文本（她在调哪个工具）；不在想或没有副文本为 null。 */
    public String thinkingNote() {
        return bubbles.stream().filter(bubble -> bubble.kind() == Bubble.Kind.THINKING)
                .map(Bubble::text).filter(Objects::nonNull).findFirst().orElse(null);
    }

    /** 文字气泡的正文，按出现先后。 */
    public List<String> texts() {
        return bubbles.stream().filter(bubble -> bubble.kind() == Bubble.Kind.TEXT && bubble.text() != null)
                .map(Bubble::text).toList();
    }
}
