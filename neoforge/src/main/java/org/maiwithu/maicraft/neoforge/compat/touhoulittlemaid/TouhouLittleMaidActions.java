// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.touhoulittlemaid;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.AIChatScreen;
import com.github.tartaricacid.touhoulittlemaid.network.message.ai.OpenMaidAIChatPacket;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import org.maiwithu.maicraft.compat.touhoulittlemaid.MaidActions;

/**
 * 车万女仆的写端：发车万女仆自己的开框包，往眼前的对话框里按字符事件打字、按回车、关框——
 * 和真人按聊天键、敲键盘、按 Esc 走同一条路。只翻译，不判断。
 */
public final class TouhouLittleMaidActions implements MaidActions {

    @Override public void openDialog(int entityId) {
        PacketDistributor.sendToServer(new OpenMaidAIChatPacket(entityId));
    }

    // 一个字符一个字符交给对话框，和键盘输入的字符事件一样；对话框开框后 5 刻内吞字，调用方先等过。
    @Override public void typeIntoDialog(String text) {
        if (!(Minecraft.getInstance().screen instanceof AIChatScreen screen)) return;
        for (int i = 0; i < text.length(); i++) {
            screen.charTyped(text.charAt(i), 0);
        }
    }

    // 回车：对话框自己把输入框里的话发给服务端、在本机回显一行，再关上。
    @Override public void pressEnterInDialog() {
        if (Minecraft.getInstance().screen instanceof AIChatScreen screen) {
            screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
        }
    }

    @Override public void closeDialog() {
        if (Minecraft.getInstance().screen instanceof AIChatScreen screen) screen.onClose();
    }
}
