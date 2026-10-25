// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 绑定无线终端时别把终端丢在地上：AE2 关访问点界面时把绑定格、产出格里的东西直接丢在玩家脚边，不还回背包。
 * 所以终端离开背包到副本回到背包这段停不得；收场前先把格里的东西收回背包；真丢了要在结果里写明（审计 A44）。
 */
class WirelessBindingSafetyTest {

    @BeforeAll static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static SlotSnapshot terminal() {
        return SlotSnapshot.of(new ItemStack(Items.TORCH));
    }

    private static final Optional<Integer> INPUT = Optional.of(1);
    private static final Optional<Integer> OUTPUT = Optional.of(2);

    @Test
    void 收场前先收产出格里的副本_再收绑定格里的原料() {
        assertEquals(OUTPUT, WirelessBinding.recoverSlot(OUTPUT, terminal(), INPUT, terminal()));
        assertEquals(INPUT, WirelessBinding.recoverSlot(OUTPUT, SlotSnapshot.empty(), INPUT, terminal()));
        assertEquals(Optional.empty(), WirelessBinding.recoverSlot(OUTPUT, SlotSnapshot.empty(), INPUT, SlotSnapshot.empty()),
                "两格都空：直接去关界面");
        assertEquals(INPUT, WirelessBinding.recoverSlot(Optional.empty(), terminal(), INPUT, terminal()),
                "认不出产出格就当没有，照样收绑定格");
    }

    @Test
    void 终端不在背包里_或正在往回收_都停不得() {
        assertFalse(WirelessBinding.unsafeToStop(false, false, false), "走近、空手等着时是空当，让给生存需求");
        assertTrue(WirelessBinding.unsafeToStop(true, false, false), "点击在途");
        assertTrue(WirelessBinding.unsafeToStop(false, true, false), "终端在光标上或访问点的格子里：一关界面就掉在脚边");
        assertTrue(WirelessBinding.unsafeToStop(false, false, true), "正在把格里的终端收回背包");
    }

    @Test
    void 关界面时终端还在格里_没做成的原因写明掉在了地上() {
        Problem reason = Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "产出格里一直没出现绑定好的副本，绑不上", "换一台访问点");
        assertSame(reason, WirelessBinding.withDropNote(reason, false), "没丢东西：原因原样交回");
        Problem noted = WirelessBinding.withDropNote(reason, true);
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, noted.kind());
        assertTrue(noted.message().startsWith(reason.message()), noted.message());
        assertTrue(noted.message().contains("丢在访问点跟前的地上"), noted.message());
        assertEquals(reason.suggestion(), noted.suggestion());
    }
}
