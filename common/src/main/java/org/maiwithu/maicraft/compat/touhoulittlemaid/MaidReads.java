// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.touhoulittlemaid;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * 车万女仆的读端：只读客户端此刻看得到的（同步给周围玩家的实体数据、本机模型表、眼前的界面），只翻译不判断。
 * 接口只用原版与 Java 类型；实现在 NeoForge 一侧直接调车万女仆的公开读法。
 */
public interface MaidReads {

    /** 这只实体是不是车万女仆的女仆。 */
    boolean isMaid(Entity entity);

    /** 一只女仆此刻的同步状态；调用方先用 {@link #isMaid} 认过。 */
    MaidState state(Entity maid);

    /** 这只实体是女仆墓碑时，墓碑上的女仆名；不是墓碑给空。 */
    Optional<String> tombstoneName(Entity entity);

    /** 本机模型表：皮肤 ID → 显示名，按 ID 排。 */
    Map<String, String> skins();

    /** 本机开着车万女仆的 AI 对话：关着时对着她按聊天键，车万女仆根本不发开对话框的包。 */
    boolean aiChatEnabled();

    /** 眼前开着的是不是这只女仆的对话框。 */
    boolean dialogOpenFor(int entityId);

    /** 眼前对话框输入框里此刻的字；没开着对话框为 null。 */
    String dialogText();

    /** "角色设定文件已经自动生成完毕"这句在本机语言里的样子：和没有预设人设的女仆说第一句时，她只发这句、不回答。 */
    String personaGeneratedText();

    /** 她的界面里列的工作，按界面的顺序：ID、显示名、这会儿按钮点不点得动、界面写的条件。 */
    List<Work> works(Entity maid);

    /** 眼前是不是日程点确认屏：开驻守时日程点离她太远或不在同一维度，车万女仆弹它拒绝。 */
    boolean schedulePosScreenOpen();

    /** 这一格是五子棋盘的一部分时，整个棋盘此刻的样子（按中心那一格读）；不是棋盘、方块实体还没同步下来给空。 */
    Optional<GomokuState> gomoku(Level level, BlockPos at);

    /** 这片区域里客户端已有的五子棋盘，按中心那一格各列一次；没加载的区块不去加载。 */
    List<GomokuState> gomokuBoards(Level level, AABB region);

    /**
     * 她界面里的一个工作。
     *
     * @param id         工作 ID，例如 touhou_little_maid:farm
     * @param name       显示名
     * @param enabled    这会儿按钮点不点得动：灰着的点了也不切
     * @param conditions 界面写的条件，每条"条件：满足"或"条件：不满足"（例如"主手需要锄：不满足"）
     */
    record Work(String id, String name, boolean enabled, List<String> conditions) {

        public Work {
            conditions = List.copyOf(conditions);
        }
    }
}
