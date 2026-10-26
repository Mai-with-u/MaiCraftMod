// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.menu.TransferRun;
import org.maiwithu.maicraft.behavior.menu.TransferSettlement;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.menu.MenuConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 一次从终端取货的会话上下文：「打开与核对」「逐笔搬」「下单等成品」三段共用——此刻开着的界面、
 * 搬运执行、搬运结算与「要什么」。取到多少只记确认过的；没能确认的如实记下，收场时随失败说明交回。
 */
class TakeSession extends TerminalSession {

    /** 在下单那几页上点一下等换页的期限（刻）；换页由下单那边自己看，这里只让这一下的确认按时结清。 */
    private static final int PAGE_CONFIRM_TICKS = 60;

    private final PlayerServices services;
    private final ItemRequest request;
    private final TransferSettlement settlement = new TransferSettlement();
    private TransferRun transport;

    TakeSession(TerminalAccess access, Ae2Compat compat, PlayerServices services, ItemRequest request) {
        super(access, compat);
        this.services = services;
        this.request = request;
    }

    ItemRequest request() {
        return request;
    }

    TransferSettlement settlement() {
        return settlement;
    }

    TransferRun transport() {
        return transport;
    }

    // 点开并认下界面：下单时服务端会把终端换成填数量页、确认页，开工后再退回终端——这些都是这次取货接着用的页，
    // 界面跟着换绑，下单那几页上的操作、关界面都对着此刻那一页；搬运的一笔笔交给共用的执行。
    @Override void bindMenu() {
        super.bindMenu();
        transport = new TransferRun(menu());
        menu().followPageSwaps(this::craftPage);
    }

    // 重开终端：搬运执行跟着界面一起换。
    @Override void reopen() {
        super.reopen();
        transport = null;
    }

    int confirmedTotal() {
        int total = 0;
        for (Map.Entry<String, Integer> entry : settlement.confirmedByItem().entrySet()) {
            total += entry.getValue();
        }
        return total;
    }

    boolean wanted(ItemStack stack) {
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return request.wanted().matches(itemId, services.itemTags().tagsOf(itemId));
    }

    // 网络里已经没有想要的了：这一笔之前一件都没取到就是"网络里没有"，取到过就是"取完了"。
    Problem nothingLeft() {
        return Problem.of(Problem.Kind.NEED_ITEM,
                access().describe() + " 的网络里没有" + request.wanted().describe() + "了", null);
    }

    // 服务端换上来的哪些页算这次取货接着用：填合成数量页、合成确认页、退回来的还是这台终端（或这件无线终端）。
    // 别的界面顶上来不跟，照旧当成终端被关掉。
    private boolean craftPage(AbstractContainerMenu from, AbstractContainerMenu to) {
        PlayerContext player = services.context().get();
        return compat().craftAmountMenu(to) || compat().craftPlanPage(to).isPresent()
                || (player != null && access().belongs(to, player.level()));
    }

    // 在此刻那一页上发模组的包：经已打开的界面发（它跟着下单那几页换绑），和取货同一条就绪规矩——
    // 本刻有交互机会、上一下结清、界面画过一帧才发；这一下按"换页了"确认。发不了返回 false，下一刻再试。
    boolean sendOnPage(String what, Runnable send) {
        PlayerContext player = services.context().get();
        if (menu() == null || player == null) return false;
        AbstractContainerMenu page = rawPage(player);
        return menu().submitModAction(what, send,
                (now, ignored) -> rawPage(now) != page
                        ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.PENDING,
                PAGE_CONFIRM_TICKS).isPresent();
    }

    // 去关界面之前把这台终端此刻的网络存货记下：下次报价用，过五分钟当没看过。
    void rememberStockNow() {
        PlayerContext player = services.context().get();
        if (player == null || menu() == null || menu().reading().isEmpty()) return;
        AbstractContainerMenu raw = rawPage(player);
        Optional<Boolean> linked = compat().networkLinked(raw);
        Optional<List<Ae2TerminalMenu.StockEntry>> stock = compat().networkStock(raw);
        if (linked.isPresent() && stock.isPresent()) {
            access().remember(linked.get(), stock.get(), false);
        }
    }
}
