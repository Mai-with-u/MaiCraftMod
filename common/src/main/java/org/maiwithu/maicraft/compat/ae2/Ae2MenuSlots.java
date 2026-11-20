// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.List;

import net.minecraft.world.item.ItemStack;

/**
 * AE2 界面的槽位语义查询与通用发包接缝：按 AE2 的 SlotSemantic 名称查当前打开菜单的槽位编号
 * （彻底规避携带网络工具箱时动态插入 9 个 TOOLBOX 槽造成的格号偏移），并统一发送配置按钮、
 * 幽灵过滤槽、界面动作与子界面切换包。实现类在 NeoForge 读写端，直接调 AE2 公开接口。
 */
public interface Ae2MenuSlots {

    /** 没接读写端时的空实现：查不到任何语义槽位，也不向服务端发包。 */
    Ae2MenuSlots NONE = new Ae2MenuSlots() {
        @Override public boolean isAe2MenuOpen() { return false; }
        @Override public List<Integer> slotsOf(String semanticId) { return List.of(); }
        @Override public boolean sendConfigButton(String settingName, boolean backwards) { return false; }
        @Override public boolean sendSetFilter(int slotIndex, ItemStack stack) { return false; }
        @Override public boolean sendNoArgAction(String actionName) { return false; }
        @Override public boolean sendIntAction(String actionName, int value) { return false; }
        @Override public boolean sendLongAction(String actionName, long value) { return false; }
        @Override public boolean sendBooleanAction(String actionName, boolean value) { return false; }
        @Override public boolean sendStringAction(String actionName, String value) { return false; }
        @Override public boolean sendRawJsonAction(String actionName, String jsonPayload) { return false; }
        @Override public boolean openSubMenu(String menuTypeId) { return false; }
        @Override public boolean returnToParentMenu() { return false; }
    };

    /** 当前角色打开的界面是不是 AE2 的菜单。 */
    boolean isAe2MenuOpen();

    /**
     * 查询当前打开的 AE2 菜单里指定语义名称（例如 UPGRADE、CONFIG、STORAGE、STORAGE_CELL、MACHINE_INPUT、
     * MACHINE_OUTPUT、MACHINE_CRAFTING_GRID、ENCODED_PATTERN、BLANK_PATTERN、CRAFTING_GRID、CRAFTING_RESULT、
     * PROCESSING_INPUTS、PROCESSING_OUTPUTS、STONECUTTING_INPUT、SMITHING_TABLE_TEMPLATE、SMITHING_TABLE_BASE、
     * SMITHING_TABLE_ADDITION、INSCRIBER_PLATE_TOP、INSCRIBER_PLATE_BOTTOM、TOOLBOX）对应的槽位编号列表，
     * 保持菜单里的注册顺序；未打开 AE2 菜单或语义不存在时返回空列表。
     */
    List<Integer> slotsOf(String semanticId);

    /** 发送配置按钮包（循环切换当前界面目标对象的枚举设置）；未打开 AE2 菜单或设置名未知返回 false。 */
    boolean sendConfigButton(String settingName, boolean backwards);

    /** 给指定幽灵过滤槽写入过滤物品（传空堆叠即清空）；未打开 AE2 菜单或槽位越界返回 false。 */
    boolean sendSetFilter(int slotIndex, ItemStack stack);

    /** 向当前打开的 AE2 菜单发送无参界面动作（例如 clear、partition、encode、cancelCrafting）。 */
    boolean sendNoArgAction(String actionName);

    /** 向当前打开的 AE2 菜单发送整数参数的界面动作（例如 setPriority、setAmount、selectCpu）。 */
    boolean sendIntAction(String actionName, int value);

    /** 向当前打开的 AE2 菜单发送长整型参数的界面动作（例如 setReportingValue）。 */
    boolean sendLongAction(String actionName, long value);

    /** 向当前打开的 AE2 菜单发送布尔参数的界面动作（例如 setSubstitution、setFluidSubstitution）。 */
    boolean sendBooleanAction(String actionName, boolean value);

    /** 向当前打开的 AE2 菜单发送字符串参数的界面动作（例如 setName、setStonecuttingRecipeId）。 */
    boolean sendStringAction(String actionName, String value);

    /** 向当前打开的 AE2 菜单发送已序列化 JSON 载荷的界面动作（用于枚举等参数，null 表示无参）。 */
    boolean sendRawJsonAction(String actionName, String jsonPayload);

    /** 从当前打开的 AE2 菜单切到指定注册 ID 的子界面（例如 ae2:priority、ae2:craftingstatus）。 */
    boolean openSubMenu(String menuTypeId);

    /** 从当前打开的 AE2 子界面返回上一级主界面。 */
    boolean returnToParentMenu();
}
