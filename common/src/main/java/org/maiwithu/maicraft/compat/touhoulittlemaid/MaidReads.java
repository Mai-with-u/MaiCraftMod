// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.touhoulittlemaid;

import java.util.Map;
import java.util.Optional;

import net.minecraft.world.entity.Entity;

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
}
