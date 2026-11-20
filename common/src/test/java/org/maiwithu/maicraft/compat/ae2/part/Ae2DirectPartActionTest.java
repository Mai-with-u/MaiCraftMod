// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.part;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.OptionalInt;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 监视器与 6 种 P2P 通道直接交互前置判断离线测试：
 * 核对 P2P 配对强制要求主手内存卡（副手明确报 NEED_ITEM）、输出端要求内存卡已存频率、
 * 6 种 P2P 调谐类型映射，以及监视器锁定解锁与流体容器二次右键判定。
 */
class Ae2DirectPartActionTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 配对强制要求主手内存卡副手持有明确报错() {
        ItemStack card = new ItemStack(Items.PAPER);
        Ae2PartControl control = new PartTestFakes.ScriptedSession("") {
            @Override public boolean isMemoryCard(ItemStack stack) {
                return !stack.isEmpty() && stack.is(Items.PAPER);
            }
            @Override public Optional<String> memoryCardP2pType(ItemStack stack) {
                return isMemoryCard(stack) ? Optional.of("ae2:me_p2p_tunnel") : Optional.empty();
            }
            @Override public OptionalInt memoryCardP2pFrequency(ItemStack stack) {
                return isMemoryCard(stack) ? OptionalInt.of(0x1234) : OptionalInt.empty();
            }
        };

        // 仅副手拿着内存卡，主手和主背包没有：必须报 NEED_ITEM 并点明副手会被忽略。
        Ae2DirectPartAction.P2pRolePlan offhandOnly = Ae2DirectPartAction.decideP2pRole(
                control, "input", ItemStack.EMPTY, card, ItemStack.EMPTY, null);
        assertNotNull(offhandOnly.problem());
        assertEquals(Problem.Kind.NEED_ITEM, offhandOnly.problem().kind());
        assertTrue(offhandOnly.problem().message().contains("副手"));

        // 全身没有内存卡：报 NEED_ITEM。
        Ae2DirectPartAction.P2pRolePlan none = Ae2DirectPartAction.decideP2pRole(
                control, "input", ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, null);
        assertNotNull(none.problem());
        assertEquals(Problem.Kind.NEED_ITEM, none.problem().kind());

        // 主手已持内存卡：input 用潜行右键，output 用普通右键。
        Ae2DirectPartAction.P2pRolePlan inputPlan = Ae2DirectPartAction.decideP2pRole(
                control, "input", card, ItemStack.EMPTY, ItemStack.EMPTY, null);
        assertNull(inputPlan.problem());
        assertNull(inputPlan.itemToHold());
        assertTrue(inputPlan.sneakClick());

        Ae2DirectPartAction.P2pRolePlan outputPlan = Ae2DirectPartAction.decideP2pRole(
                control, "output", card, ItemStack.EMPTY, ItemStack.EMPTY, null);
        assertNull(outputPlan.problem());
        assertFalse(outputPlan.sneakClick());

        // 输出端但内存卡未绑定频率：报 NOT_POSSIBLE_HERE。
        Ae2PartControl unboundCard = new PartTestFakes.ScriptedSession("") {
            @Override public boolean isMemoryCard(ItemStack stack) {
                return !stack.isEmpty() && stack.is(Items.PAPER);
            }
        };
        Ae2DirectPartAction.P2pRolePlan unbound = Ae2DirectPartAction.decideP2pRole(
                unboundCard, "output", card, ItemStack.EMPTY, ItemStack.EMPTY, null);
        assertNotNull(unbound.problem());
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, unbound.problem().kind());
    }

    @Test
    void 全部六种点对点调谐类型双向映射完整() {
        for (String attunement : Ae2PartKeys.P2P_ATTUNEMENTS) {
            String partId = Ae2PartKeys.p2pItemOf(attunement).orElseThrow();
            assertEquals(Optional.of(Ae2PartKeys.PartKind.P2P_TUNNEL), Ae2PartKeys.partKindOf(partId));
            assertEquals(Optional.of(attunement), Ae2PartKeys.attunementOfPart(partId));
        }
        assertEquals("unlinked", Ae2PartKeys.formatFrequency(0));
        assertEquals("00FF", Ae2PartKeys.formatFrequency(255));
    }

    @Test
    void 监视器展示物判定覆盖解锁与流体容器二次右键与清空拒收() {
        Ae2PartControl.ClientPartReading lockedIron = Ae2PartControl.ClientPartReading.monitor(
                "minecraft:iron_ingot", 64L, false, true);

        // 监视器已锁定时改换新物品：先解锁再持样本点一次。
        Ae2DirectPartAction.MonitorDisplayPlan switchItem = Ae2DirectPartAction.decideMonitorDisplay(
                lockedIron, "minecraft:diamond", Optional.of("minecraft:diamond"), Optional.empty());
        assertNull(switchItem.problem());
        assertTrue(switchItem.needUnlock());
        assertEquals("minecraft:diamond", switchItem.itemToHold());
        assertFalse(switchItem.needSecondFluidClick());

        // 设置流体展示且背包持有水桶：需点两次（第一次设桶，第二次切内含水）。
        Ae2DirectPartAction.MonitorDisplayPlan switchFluid = Ae2DirectPartAction.decideMonitorDisplay(
                lockedIron, "minecraft:water", Optional.empty(), Optional.of("minecraft:water_bucket"));
        assertNull(switchFluid.problem());
        assertTrue(switchFluid.needUnlock());
        assertEquals("minecraft:water_bucket", switchFluid.itemToHold());
        assertTrue(switchFluid.needSecondFluidClick());

        // 监视器已有展示物时请求 empty：AE2 原生不支持交互清空，如实报 NOT_POSSIBLE_HERE。
        Ae2DirectPartAction.MonitorDisplayPlan clearShown = Ae2DirectPartAction.decideMonitorDisplay(
                lockedIron, "empty", Optional.empty(), Optional.empty());
        assertNotNull(clearShown.problem());
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, clearShown.problem().kind());

        // 缺样本也缺流体容器：报 NEED_ITEM。
        Ae2DirectPartAction.MonitorDisplayPlan missing = Ae2DirectPartAction.decideMonitorDisplay(
                lockedIron, "minecraft:netherite_ingot", Optional.empty(), Optional.empty());
        assertNotNull(missing.problem());
        assertEquals(Problem.Kind.NEED_ITEM, missing.problem().kind());
    }
}
