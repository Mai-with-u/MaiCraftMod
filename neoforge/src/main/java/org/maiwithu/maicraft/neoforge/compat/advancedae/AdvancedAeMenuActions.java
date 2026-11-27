// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.advancedae;

import java.util.Locale;

import appeng.api.config.Settings;
import appeng.api.orientation.RelativeSide;
import appeng.core.network.serverbound.ConfigButtonPacket;
import appeng.core.network.serverbound.SwitchGuisPacket;
import appeng.menu.ISubMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.network.PacketDistributor;
import net.pedroksl.advanced_ae.gui.ReactionChamberMenu;
import net.pedroksl.ae2addonlib.gui.OutputDirectionMenu;

/**
 * 高级 AE 反应仓与输出方向子菜单的客户端发包端：
 * 全部调用 AdvancedAE、ae2addonlib 与 AE2 的公开菜单动作和网络包，零反射。
 */
public final class AdvancedAeMenuActions {

    /** 向当前打开的反应仓主菜单发送 AE2 自动输出配置切换包。 */
    public boolean sendAutoExportToggle() {
        if (!(openMenu() instanceof ReactionChamberMenu)) return false;
        PacketDistributor.sendToServer(new ConfigButtonPacket(Settings.AUTO_EXPORT, false));
        return true;
    }

    /** 在反应仓主菜单调用 clearFluid（发送 flushFluid 清空输入流体槽 1）。 */
    public boolean sendClearFluidIn() {
        if (!(openMenu() instanceof ReactionChamberMenu menu)) return false;
        menu.clearFluid();
        return true;
    }

    /** 在反应仓主菜单调用 clearFluidOut（发送 flushFluidOut 清空输出流体槽 0）。 */
    public boolean sendClearFluidOut() {
        if (!(openMenu() instanceof ReactionChamberMenu menu)) return false;
        menu.clearFluidOut();
        return true;
    }

    /** 在反应仓主菜单调用 configureOutput 打开输出方向子菜单。 */
    public boolean sendOpenOutputDirection() {
        if (!(openMenu() instanceof ReactionChamberMenu menu)) return false;
        menu.configureOutput();
        return true;
    }

    /** 在输出方向子菜单调用 updateSideStatus 切换指定相对输出面。 */
    public boolean sendToggleOutputSide(String relativeSide) {
        if (relativeSide == null || !(openMenu() instanceof OutputDirectionMenu menu)) return false;
        RelativeSide side = parseRelativeSide(relativeSide);
        if (side == null) return false;
        menu.updateSideStatus(side);
        return true;
    }

    /** 当输出方向子菜单打开时发返回上级菜单包。 */
    public boolean sendReturnToParentMenu() {
        AbstractContainerMenu menu = openMenu();
        if (!(menu instanceof OutputDirectionMenu) || !(menu instanceof ISubMenu)) return false;
        PacketDistributor.sendToServer(SwitchGuisPacket.returnToParentMenu());
        return true;
    }

    private static RelativeSide parseRelativeSide(String raw) {
        try {
            return RelativeSide.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static AbstractContainerMenu openMenu() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? null : player.containerMenu;
    }
}
