// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.touhoulittlemaid;

/**
 * 车万女仆的写端：替角色做真人在客户端做的那一下——发车万女仆自己的包、往她的对话框里打字和回车、关掉对话框。
 * 只翻译，不判断；站位、对准、等确认由调用方负责。实现在 NeoForge 一侧。
 */
public interface MaidActions {

    /** 发打开对话框的包（真人对着自己的女仆按聊天键时车万女仆发的那一个）；服务端核对过才回话开框。 */
    void openDialog(int entityId);

    /** 往眼前的对话框里一个字一个字打进去（照真人按键的字符事件）。 */
    void typeIntoDialog(String text);

    /** 在眼前的对话框里按回车：对话框自己把话发出去并关上。 */
    void pressEnterInDialog();

    /** 关掉眼前的对话框（和按 Esc 一样，里面的字不发）。 */
    void closeDialog();

    /** 发换皮肤的包（真人在她的皮肤界面里点一个皮肤时发的那一个）。 */
    void setSkin(int entityId, String skinId);

    /** 发换工作的包（真人在她界面的工作列表里点一项时发的那一个）。 */
    void setWork(int entityId, String workId);

    /**
     * 发她的设置包（真人在她界面里点驻守、拾取、骑乘、班次按钮时发的那一个）：四项一起发，不改的照她此刻的值带上。
     *
     * @param stayInArea 驻守为 true，跟着主人为 false
     */
    void setConfig(int entityId, boolean stayInArea, boolean pickup, boolean rideable, MaidState.Shift shift);

    /** 关掉日程点确认屏。 */
    void closeSchedulePosScreen();
}
