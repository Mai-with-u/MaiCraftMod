// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;

/**
 * 绑定动作里一下点击生效了没有：拿起的生效是那格空了、光标上拿起原来那份；
 * 放进的生效是光标空了、东西落进那格，或者服务端已把它就地结成产出格里的绑定副本。
 * 第六轮实机里「从背包拿起终端」明明已经生效，却因为判定把"光标上是什么"与
 * "点之前那格里是什么"比反了，被当成一直没动静而停手；第七轮起又发现放进绑定格
 * 的那一下被 AE2 当刻结算成副本、绑定格随即空回去，只看绑定格同样误判成没动静。
 */
class WirelessBindingClickTest {

    @BeforeAll static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static SlotSnapshot terminal() {
        return SlotSnapshot.of(new ItemStack(Items.TORCH));
    }

    @Test
    void 从背包拿起终端生效了() {
        // 点之前背包格里有终端、光标是空的；点完那格空了、光标上正是那份终端。
        assertTrue(WirelessBinding.clickApplied(terminal(), SlotSnapshot.empty(),
                SlotSnapshot.empty(), terminal(), false));
    }

    @Test
    void 从背包拿终端被服务端拒了() {
        // 原版会先按本地预测把东西挪到光标上；这里看的是服务端结算后的现场：那格还有东西就没拿走。
        assertFalse(WirelessBinding.clickApplied(terminal(), SlotSnapshot.empty(),
                terminal(), SlotSnapshot.empty(), false));
    }

    @Test
    void 终端放进绑定格生效了() {
        // 点之前绑定格是空的、光标拿着终端；点完光标空了、终端落进了绑定格。
        assertTrue(WirelessBinding.clickApplied(SlotSnapshot.empty(), terminal(),
                terminal(), SlotSnapshot.empty(), false));
    }

    @Test
    void 终端放进绑定格被服务端拒了() {
        // 服务端不收时格子还是空的，光标上还拿着终端。
        assertFalse(WirelessBinding.clickApplied(SlotSnapshot.empty(), terminal(),
                SlotSnapshot.empty(), terminal(), false));
    }

    @Test
    void 从产出格拿起副本生效了() {
        assertTrue(WirelessBinding.clickApplied(terminal(), SlotSnapshot.empty(),
                SlotSnapshot.empty(), terminal(), false));
    }

    @Test
    void 副本放回背包生效了() {
        assertTrue(WirelessBinding.clickApplied(SlotSnapshot.empty(), terminal(),
                terminal(), SlotSnapshot.empty(), false));
    }

    @Test
    void 空手点空格没生效() {
        assertFalse(WirelessBinding.clickApplied(SlotSnapshot.empty(), SlotSnapshot.empty(),
                SlotSnapshot.empty(), SlotSnapshot.empty(), false));
    }

    @Test
    void 终端放进绑定格_服务端就地结成副本_也算生效() {
        // AE2 收下终端的同一刻就结算：绑定格随即又空了，绑定副本出现在产出格，光标也空了。
        // 只看绑定格会把这一下判成"没动静"——绑定明明落上了，动作却停手，副本被关界面的流程丢在地上。
        assertTrue(WirelessBinding.clickApplied(SlotSnapshot.empty(), terminal(),
                SlotSnapshot.empty(), SlotSnapshot.empty(), true));
    }

    @Test
    void 终端放进绑定格_光标还拿着_就算产出格有副本也没生效() {
        // 光标没空说明这一下没被服务端收下；产出格上的副本是更早留下的，不能拿来顶数。
        assertFalse(WirelessBinding.clickApplied(SlotSnapshot.empty(), terminal(),
                SlotSnapshot.empty(), terminal(), true));
    }
}
