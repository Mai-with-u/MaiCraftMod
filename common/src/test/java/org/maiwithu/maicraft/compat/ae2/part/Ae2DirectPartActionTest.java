// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.part;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.hands.GearChanges;
import org.maiwithu.maicraft.behavior.inventory.hands.MovesToMainhand;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.ae2.Ae2MenuSlots;
import org.maiwithu.maicraft.game.interaction.DigToolRule;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.serverlink.ServerReads;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

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
                control, "input", ItemStack.EMPTY, card, ItemStack.EMPTY, null, 0, false);
        assertNotNull(offhandOnly.problem());
        assertEquals(Problem.Kind.NEED_ITEM, offhandOnly.problem().kind());
        assertTrue(offhandOnly.problem().message().contains("副手"));

        // 全身没有内存卡：报 NEED_ITEM。
        Ae2DirectPartAction.P2pRolePlan none = Ae2DirectPartAction.decideP2pRole(
                control, "input", ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, null, 0, false);
        assertNotNull(none.problem());
        assertEquals(Problem.Kind.NEED_ITEM, none.problem().kind());

        // 主手已持内存卡：input 用潜行右键，output（不同频）用普通右键。
        Ae2DirectPartAction.P2pRolePlan inputPlan = Ae2DirectPartAction.decideP2pRole(
                control, "input", card, ItemStack.EMPTY, ItemStack.EMPTY, null, 0, false);
        assertNull(inputPlan.problem());
        assertNull(inputPlan.itemToHold());
        assertTrue(inputPlan.sneakClick());
        assertFalse(inputPlan.expectNewFreq());

        Ae2DirectPartAction.P2pRolePlan outputPlan = Ae2DirectPartAction.decideP2pRole(
                control, "output", card, ItemStack.EMPTY, ItemStack.EMPTY, null, 0x0001, false);
        assertNull(outputPlan.problem());
        assertFalse(outputPlan.sneakClick());

        // 同频覆盖为 output：AE2 会静默忽略，必须前置报 NOT_POSSIBLE_HERE 避免假确认。
        Ae2DirectPartAction.P2pRolePlan sameFreqOutput = Ae2DirectPartAction.decideP2pRole(
                control, "output", card, ItemStack.EMPTY, ItemStack.EMPTY, null, 0x1234, false);
        assertNotNull(sameFreqOutput.problem());
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, sameFreqOutput.problem().kind());

        // 原为同频 output 转设 input：必须标记 expectNewFreq，确认时旧频保持 PENDING、新频才 APPLIED。
        Ae2DirectPartAction.P2pRolePlan outputToInput = Ae2DirectPartAction.decideP2pRole(
                control, "input", card, ItemStack.EMPTY, ItemStack.EMPTY, null, 0x1234, true);
        assertNull(outputToInput.problem());
        assertTrue(outputToInput.expectNewFreq());
        assertEquals(InteractionConfirmation.Verdict.PENDING,
                Ae2DirectPartAction.verifyP2pRole(false, 0x1234, true, 0x1234, OptionalInt.of(0x1234)));
        assertEquals(InteractionConfirmation.Verdict.APPLIED,
                Ae2DirectPartAction.verifyP2pRole(false, 0x1234, true, 0x5678, OptionalInt.of(0x5678)));
        assertEquals(InteractionConfirmation.Verdict.PENDING,
                Ae2DirectPartAction.verifyP2pRole(true, 0x1234, false, 0x1234, OptionalInt.of(0x1234)));
        assertEquals(InteractionConfirmation.Verdict.APPLIED,
                Ae2DirectPartAction.verifyP2pRole(true, 0x0001, false, 0x1234, OptionalInt.of(0x1234)));

        // 输出端但内存卡未绑定频率：报 NOT_POSSIBLE_HERE。
        Ae2PartControl unboundCard = new PartTestFakes.ScriptedSession("") {
            @Override public boolean isMemoryCard(ItemStack stack) {
                return !stack.isEmpty() && stack.is(Items.PAPER);
            }
        };
        Ae2DirectPartAction.P2pRolePlan unbound = Ae2DirectPartAction.decideP2pRole(
                unboundCard, "output", card, ItemStack.EMPTY, ItemStack.EMPTY, null, 0, false);
        assertNotNull(unbound.problem());
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, unbound.problem().kind());
    }

    @Test
    void 锁定监视器设展示物先腾空手再靠近部件盒最后才解锁() {
        List<String> steps = new ArrayList<>();
        BlockPos host = new BlockPos(3, 64, 5);
        MovesToMainhand hands = new MovesToMainhand() {
            @Override public Optional<Action> moveToMainhand(String itemId) {
                return Optional.of(recordStep(steps, "hold:" + itemId));
            }
            @Override public Optional<Action> emptyMainhand() {
                return Optional.of(recordStep(steps, "emptyHand"));
            }
        };
        Protection nobodyOwns = new Protection((dim, x, y, z) -> Optional.empty(), List::of,
                name -> Optional.empty(), "self");
        PlayerServices services = new PlayerServices(() -> null,
                (target, permissions) -> recordStep(steps, "approach"),
                new Interactions(null), hands, new GearChanges() {
                    @Override public Optional<Action> wear(String itemId, GearSlotName slot) { return Optional.empty(); }
                    @Override public Optional<Action> takeOff(GearSlotName slot) { return Optional.empty(); }
                }, inner -> inner, (request, permissions) -> null, nobodyOwns, itemId -> Set.of(),
                MenuLayouts.VANILLA, new BlockScanService(), ServerReads.NONE, new BackpackView() {
                    @Override public List<BackpackStack> stacks() { return List.of(); }
                    @Override public int usedSlots() { return 0; }
                    @Override public int totalSlots() { return 36; }
                }, DigToolRule.HELD);
        Ae2Compat compat = PartTestFakes.compat(PartTestFakes.cableGrid(host, Map.of()), Ae2MenuSlots.NONE);
        Ae2DirectPartAction action = Ae2DirectPartAction.forLockedUnlockTest(compat, Ae2PartControl.NONE,
                new Ae2PartCache(), services, host, Direction.NORTH, new AABB(host),
                "minecraft:diamond", "minecraft:diamond", Permissions.DEFAULT);

        // 第一刻：完成空手并转入 APPROACH_UNLOCK（不直接跳过靠近进 UNLOCK）。
        action.tick(PartTestFakes.dummyTick());
        assertEquals("APPROACH_UNLOCK", action.stageName());
        // 第二刻：完成靠近部件盒后再转入 UNLOCK。
        action.tick(PartTestFakes.dummyTick());
        assertEquals("UNLOCK", action.stageName());
        assertEquals(List.of("emptyHand", "approach"), steps);
    }

    private static Action recordStep(List<String> steps, String tag) {
        return new Action() {
            @Override public ActionStatus tick(TickContext context) {
                steps.add(tag);
                return ActionStatus.done();
            }
            @Override public String describe() { return tag; }
        };
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
