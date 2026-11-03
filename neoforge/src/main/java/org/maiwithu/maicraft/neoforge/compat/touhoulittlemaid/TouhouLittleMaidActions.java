// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.touhoulittlemaid;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.AIChatScreen;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.other.CheckSchedulePosGui;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.network.message.MaidConfigPackage;
import com.github.tartaricacid.touhoulittlemaid.network.message.MaidModelPackage;
import com.github.tartaricacid.touhoulittlemaid.network.message.MaidTaskPackage;
import com.github.tartaricacid.touhoulittlemaid.network.message.ai.OpenMaidAIChatPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import org.maiwithu.maicraft.compat.touhoulittlemaid.MaidActions;
import org.maiwithu.maicraft.compat.touhoulittlemaid.MaidState;

/**
 * 车万女仆的写端：发车万女仆自己的包（开对话框、换皮肤、换工作、设置），往眼前的对话框里按字符事件打字、按回车、关框——
 * 和真人按聊天键、在她界面里点按钮、敲键盘、按 Esc 走同一条路。只翻译，不判断。
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

    @Override public void setSkin(int entityId, String skinId) {
        PacketDistributor.sendToServer(new MaidModelPackage(entityId, ResourceLocation.parse(skinId)));
    }

    @Override public void setWork(int entityId, String workId) {
        PacketDistributor.sendToServer(new MaidTaskPackage(entityId, ResourceLocation.parse(workId)));
    }

    // 四项一起发：车万女仆的设置包就是这样，不改的照她此刻的值带上。
    @Override public void setConfig(int entityId, boolean stayInArea, boolean pickup, boolean rideable,
            MaidState.Shift shift) {
        MaidSchedule schedule = switch (shift) {
            case DAY -> MaidSchedule.DAY;
            case NIGHT -> MaidSchedule.NIGHT;
            case ALL -> MaidSchedule.ALL;
        };
        PacketDistributor.sendToServer(new MaidConfigPackage(entityId, stayInArea, pickup, rideable, schedule));
    }

    @Override public void closeSchedulePosScreen() {
        if (Minecraft.getInstance().screen instanceof CheckSchedulePosGui screen) screen.onClose();
    }
}
