// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.ae2;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.gson.Gson;
import appeng.api.config.Setting;
import appeng.api.config.Settings;
import appeng.core.network.serverbound.ConfigButtonPacket;
import appeng.core.network.serverbound.GuiActionPacket;
import appeng.core.network.serverbound.InventoryActionPacket;
import appeng.core.network.serverbound.SwitchGuisPacket;
import appeng.helpers.InventoryAction;
import appeng.menu.AEBaseMenu;
import appeng.menu.ISubMenu;
import appeng.menu.SlotSemantic;
import appeng.menu.SlotSemantics;
import appeng.menu.slot.FakeSlot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import org.maiwithu.maicraft.compat.ae2.Ae2MenuSlots;

/**
 * AE2 界面槽位语义查询与通用交互发包的 NeoForge 读写端：全部调用 AE2 公开接口与公开网络包，
 * 按 SlotSemantics 读当前打开菜单的真实槽位编号，并经 NeoForge 发包口发送配置、过滤、界面动作与子界面切换包。
 */
public final class AppliedEnergisticsMenuSlots implements Ae2MenuSlots {

    private static final Gson GSON = new Gson();
    /** AE2 界面动作 JSON 载荷的长度上限，与 AEBaseMenu 服务端校验及网络字节流 UTF 编码上限一致。F 游戏事实。 */
    private static final int MAX_ACTION_PAYLOAD_LENGTH = 32767;

    @Override public boolean isAe2MenuOpen() {
        return openMenu() != null;
    }

    // 按 AE2 公开语义表查当前打开菜单的槽位，自动避开动态插入的网络工具箱槽位偏移。
    @Override public List<Integer> slotsOf(String semanticId) {
        if (semanticId == null || semanticId.isEmpty()) return List.of();
        AEBaseMenu menu = openMenu();
        if (menu == null) return List.of();
        SlotSemantic semantic = SlotSemantics.get(semanticId);
        if (semantic == null) return List.of();
        List<Slot> slots = menu.getSlots(semantic);
        if (slots.isEmpty()) return List.of();
        List<Integer> indices = new ArrayList<>(slots.size());
        for (Slot slot : slots) indices.add(slot.index);
        return List.copyOf(indices);
    }

    // 枚举设置按 AE2 注册名解析，找不到对应设置项时静默拒绝而不抛异常中断任务。
    @Override public boolean sendConfigButton(String settingName, boolean backwards) {
        if (settingName == null || settingName.isEmpty() || openMenu() == null) return false;
        Setting<?> setting;
        try {
            setting = Settings.getOrThrow(settingName);
        } catch (IllegalArgumentException unknown) {
            return false;
        }
        PacketDistributor.sendToServer(new ConfigButtonPacket(setting, backwards));
        return true;
    }

    // 幽灵过滤槽设过滤走 AE2 原生 SET_FILTER 包：发包前先确认目标槽是已启用的 FakeSlot，避免对非过滤槽或未插容量卡锁住的格误报成功。
    @Override public boolean sendSetFilter(int slotIndex, ItemStack stack) {
        AEBaseMenu menu = openMenu();
        if (menu == null || slotIndex < 0 || slotIndex >= menu.slots.size()) return false;
        if (!(menu.getSlot(slotIndex) instanceof FakeSlot fakeSlot) || !fakeSlot.isSlotEnabled()) return false;
        ItemStack filter = stack == null ? ItemStack.EMPTY : stack;
        PacketDistributor.sendToServer(new InventoryActionPacket(InventoryAction.SET_FILTER, slotIndex, filter));
        return true;
    }

    @Override public boolean sendNoArgAction(String actionName) {
        return sendRawJsonAction(actionName, null);
    }

    @Override public boolean sendIntAction(String actionName, int value) {
        return sendRawJsonAction(actionName, Integer.toString(value));
    }

    @Override public boolean sendLongAction(String actionName, long value) {
        return sendRawJsonAction(actionName, Long.toString(value));
    }

    @Override public boolean sendBooleanAction(String actionName, boolean value) {
        return sendRawJsonAction(actionName, Boolean.toString(value));
    }

    @Override public boolean sendStringAction(String actionName, String value) {
        if (value == null) return false;
        return sendRawJsonAction(actionName, GSON.toJson(value));
    }

    // GuiActionPacket 绑定当前打开的 containerId，并对齐 AEBaseMenu 载荷长度上限，避免超长载荷触发网络包编码异常。
    @Override public boolean sendRawJsonAction(String actionName, String jsonPayload) {
        AEBaseMenu menu = openMenu();
        if (menu == null || actionName == null || actionName.isEmpty()) return false;
        if (jsonPayload != null && jsonPayload.length() > MAX_ACTION_PAYLOAD_LENGTH) return false;
        PacketDistributor.sendToServer(new GuiActionPacket(menu.containerId, actionName, jsonPayload));
        return true;
    }

    // 切换子界面前先从菜单注册表确认该 ID 存在，再发 SwitchGuisPacket 让服务端沿当前菜单定位器打开。
    @Override public boolean openSubMenu(String menuTypeId) {
        if (menuTypeId == null || menuTypeId.isEmpty() || openMenu() == null) return false;
        ResourceLocation key = ResourceLocation.tryParse(menuTypeId);
        if (key == null) return false;
        Optional<MenuType<?>> found = BuiltInRegistries.MENU.getOptional(key);
        if (found.isEmpty()) return false;
        @SuppressWarnings("unchecked")
        MenuType<? extends ISubMenu> subMenu = (MenuType<? extends ISubMenu>) found.get();
        PacketDistributor.sendToServer(SwitchGuisPacket.openSubMenu(subMenu));
        return true;
    }

    // 只有当前确实处于 AE2 子界面（ISubMenu）时才发返回包。
    @Override public boolean returnToParentMenu() {
        AEBaseMenu menu = openMenu();
        if (!(menu instanceof ISubMenu)) return false;
        PacketDistributor.sendToServer(SwitchGuisPacket.returnToParentMenu());
        return true;
    }

    private static AEBaseMenu openMenu() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && player.containerMenu instanceof AEBaseMenu menu ? menu : null;
    }
}
