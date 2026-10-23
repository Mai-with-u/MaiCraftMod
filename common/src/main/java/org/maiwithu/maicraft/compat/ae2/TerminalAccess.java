// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.List;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.inventory.AbstractContainerMenu;

import org.maiwithu.maicraft.behavior.menu.MenuOpening;

/**
 * 到 ME 网络跟前的办法：走到一台装着的终端点在面板上打开，或拿出身上的无线终端右键打开。
 * 从终端取货、往网络里存东西都用它，打开之后的步骤两者一样；去哪一台、用不用身上的那件由 {@link NearbyTerminals} 定。
 */
interface TerminalAccess {

    /** 怎么打开终端界面；每次给一个新的打开动作（下单合成后要重新打开看成品）。 */
    MenuOpening opening();

    /** 点开的这份界面是不是这一台的。 */
    boolean belongs(AbstractContainerMenu raw, ClientLevel level);

    /** 给结果与日志看的一句话，例如"ae2:terminal（12,64,-3 的北面）"或"身上的ae2:wireless_terminal"。 */
    String describe();

    /** 记下这次看到的网络连没连上、网络存货，下次取货报价、挑终端用。 */
    void remember(boolean linked, List<Ae2TerminalMenu.StockEntry> stock, boolean complete);
}
