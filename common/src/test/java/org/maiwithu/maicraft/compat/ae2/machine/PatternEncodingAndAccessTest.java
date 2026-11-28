// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.ae2.Ae2MenuSlots;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;

/**
 * 样板编码终端与样板管理终端离线测试：
 * 验证 4 种模式 JSON 解析、处理模式流体与大于 64 数量 GenericStack 包装、
 * 防洗样板纪律（先清旧输出槽 + 无效配方绝不调 encode()）、缺空白样板报 NEED_ITEM、
 * 切石配方走专用发包口，以及样板管理终端 QuickMovePatternPacket 同组筛选与送入确认。
 */
class PatternEncodingAndAccessTest {

    private static final BlockPos HOST = new BlockPos(4, 65, 4);

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 四种模式解析与处理模式流体超六十四数量校验() {
        // PROCESSING：流体 1000 mB + 128 个铁锭 -> 64 个铁块。
        PatternEncodingRequest.ParseResult proc = PatternEncodingRequest.parse("""
                {"mode":"processing",
                 "inputs":[{"fluid":"minecraft:water","amount":1000},{"id":"minecraft:iron_ingot","amount":128}],
                 "outputs":[{"id":"minecraft:iron_block","amount":64}]}
                """);
        assertTrue(proc.ok());
        assertEquals("processing", proc.request().mode());
        assertTrue(proc.request().inputs().get(0).isFluid());
        assertEquals(1000L, proc.request().inputs().get(0).amount());
        assertEquals(128L, proc.request().inputs().get(1).amount());

        // PROCESSING 缺主产物拦截。
        assertFalse(PatternEncodingRequest.parse("""
                {"mode":"processing","inputs":["minecraft:iron_ingot"],"outputs":[]}
                """).ok());

        // SMITHING 需要模板/底材/升级材料三槽齐全。
        assertFalse(PatternEncodingRequest.parse("""
                {"mode":"smithing","inputs":["minecraft:netherite_upgrade_smithing_template","minecraft:diamond_chestplate"]}
                """).ok());
        assertTrue(PatternEncodingRequest.parse("""
                {"mode":"smithing_table","inputs":["minecraft:netherite_upgrade_smithing_template","minecraft:diamond_chestplate","minecraft:netherite_ingot"]}
                """).ok());

        // STONECUTTING 必须提供 stonecutting_recipe。
        assertFalse(PatternEncodingRequest.parse("""
                {"mode":"stonecutting","inputs":["minecraft:stone"]}
                """).ok());
        assertTrue(PatternEncodingRequest.parse("""
                {"mode":"stonecutting","inputs":["minecraft:stone"],"stonecutting_recipe":"minecraft:stone_bricks_from_stone_stonecutting"}
                """).ok());
    }

    @Test
    void 防洗样板纪律_先清输出槽旧样板且配方无效时绝不调用encode() {
        String rawJson = """
                {"mode":"crafting","inputs":["minecraft:stick","minecraft:stick"]}
                """;
        PatternEncodingRequest req = PatternEncodingRequest.parse(rawJson).request();
        List<String> events = new ArrayList<>();
        AtomicBoolean oldOutputOccupied = new AtomicBoolean(true);
        AtomicBoolean encodeCalled = new AtomicBoolean(false);
        Ae2MenuSlots menuSlots = new Ae2MenuSlots() {
            @Override public boolean isAe2MenuOpen() { return true; }
            @Override public List<Integer> slotsOf(String semanticId) {
                return switch (semanticId) {
                    case "ENCODED_PATTERN" -> List.of(50);
                    case "CRAFTING_GRID" -> List.of(10, 11, 12, 13, 14, 15, 16, 17, 18);
                    default -> List.of();
                };
            }
            @Override public boolean sendConfigButton(String settingName, boolean backwards) { return true; }
            @Override public boolean sendSetFilter(int slotIndex, ItemStack stack) {
                events.add("filter:" + slotIndex);
                return true;
            }
            @Override public boolean sendNoArgAction(String actionName) { return true; }
            @Override public boolean sendIntAction(String actionName, int value) { return true; }
            @Override public boolean sendLongAction(String actionName, long value) { return true; }
            @Override public boolean sendBooleanAction(String actionName, boolean value) { return true; }
            @Override public boolean sendStringAction(String actionName, String value) { return true; }
            @Override public boolean sendRawJsonAction(String actionName, String jsonPayload) { return true; }
            @Override public boolean openSubMenu(String menuTypeId) { return true; }
            @Override public boolean returnToParentMenu() { return true; }
        };
        Ae2Compat compat = MachineTestFakes.compatWith(menuSlots, Ae2Patterns.NONE, MachineTestFakes.emptyGrid());
        Ae2MachineReads reads = new Ae2MachineReads() {
            @Override public Optional<PatternEncodingMenuView> openPatternEncodingMenu() {
                // 模拟无效合成格：craftingResultId 为空，绝对不能发 encode()。
                return Optional.of(new PatternEncodingMenuView("crafting", false, true, "", List.of(),
                        "", 2, "", oldOutputOccupied.get(), false, false, false));
            }
            @Override public boolean sendEncodeMode(String mode) { events.add("mode:" + mode); return true; }
            @Override public boolean sendEncodeClear() { events.add("clear"); return true; }
            @Override public boolean sendEncodeCommit() { encodeCalled.set(true); return true; }
        };
        MachineTestFakes.ScriptedMenu menu = new MachineTestFakes.ScriptedMenu(
                () -> Optional.of(MachineTestFakes.readingOf(List.of(), List.of(),
                        List.of(50), List.of(SlotSnapshot.empty()))),
                slot -> {
                    if (slot == 50) oldOutputOccupied.set(false);
                });
        PatternEncodingAction action = new PatternEncodingAction(HOST, Direction.NORTH,
                MachineTestFakes.instantOpening(menu), compat, reads, new Ae2MachineCache(), req, rawJson, null, null);

        ActionStatus finalStatus = ActionStatus.running();
        for (int i = 0; i < 80; i++) {
            finalStatus = action.tick(MachineTestFakes.emptyTick());
            if (!(finalStatus instanceof ActionStatus.Running)) break;
        }
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, finalStatus);
        assertEquals(Problem.Kind.INVALID_PARAMETER, failed.problem().kind());
        assertFalse(encodeCalled.get(), "配方无效时绝不允许调用 encode() 以防 clearPattern() 洗掉样板");
        assertTrue(menu.quickMoved.contains(50), "编码前必须先取出输出槽里已占着的旧编码样板");
        assertTrue(menu.closed);
    }

    @Test
    void 完整处理模式编码走GenericStack包装并缺空白样板报NEED_ITEM() {
        String rawJson = """
                {"mode":"processing",
                 "inputs":[{"fluid":"minecraft:water","amount":1000},{"id":"minecraft:iron_ingot","amount":128}],
                 "outputs":[{"id":"minecraft:iron_block","amount":64}]}
                """;
        PatternEncodingRequest req = PatternEncodingRequest.parse(rawJson).request();
        List<String> wrapped = new ArrayList<>();
        AtomicBoolean procWritten = new AtomicBoolean(false);
        Ae2MenuSlots menuSlots = new Ae2MenuSlots() {
            @Override public boolean isAe2MenuOpen() { return true; }
            @Override public List<Integer> slotsOf(String semanticId) {
                return switch (semanticId) {
                    case "PROCESSING_INPUTS" -> List.of(20, 21);
                    case "PROCESSING_OUTPUTS" -> List.of(30);
                    default -> List.of();
                };
            }
            @Override public boolean sendConfigButton(String s, boolean b) { return true; }
            @Override public boolean sendSetFilter(int slotIndex, ItemStack stack) {
                if (slotIndex == 30 && !stack.isEmpty()) procWritten.set(true);
                return !stack.isEmpty();
            }
            @Override public boolean sendNoArgAction(String a) { return true; }
            @Override public boolean sendIntAction(String a, int v) { return true; }
            @Override public boolean sendLongAction(String a, long v) { return true; }
            @Override public boolean sendBooleanAction(String a, boolean v) { return true; }
            @Override public boolean sendStringAction(String a, String v) { return true; }
            @Override public boolean sendRawJsonAction(String a, String j) { return true; }
            @Override public boolean openSubMenu(String m) { return true; }
            @Override public boolean returnToParentMenu() { return true; }
        };
        Ae2Compat compat = MachineTestFakes.compatWith(menuSlots, Ae2Patterns.NONE, MachineTestFakes.emptyGrid());
        Ae2MachineReads reads = new Ae2MachineReads() {
            @Override public Optional<PatternEncodingMenuView> openPatternEncodingMenu() {
                return Optional.of(new PatternEncodingMenuView("processing", false, true, "", List.of(),
                        "", 0, "", false, procWritten.get(), procWritten.get(), false));
            }
            @Override public boolean sendEncodeMode(String mode) { return true; }
            @Override public boolean sendEncodeClear() { return true; }
            @Override public ItemStack wrapGenericStack(String kind, String id, long amount) {
                wrapped.add(kind + ":" + id + ":" + amount);
                return new ItemStack(Items.PAPER);
            }
        };
        MachineTestFakes.ScriptedMenu menu = new MachineTestFakes.ScriptedMenu(
                () -> Optional.of(MachineTestFakes.readingOf(List.of(0), List.of(SlotSnapshot.empty()),
                        List.of(), List.of())),
                null);
        PatternEncodingAction action = new PatternEncodingAction(HOST, Direction.NORTH,
                MachineTestFakes.instantOpening(menu), compat, reads, new Ae2MachineCache(), req, rawJson, null, null);

        ActionStatus finalStatus = ActionStatus.running();
        for (int i = 0; i < 40; i++) {
            finalStatus = action.tick(MachineTestFakes.emptyTick());
            if (!(finalStatus instanceof ActionStatus.Running)) break;
        }
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, finalStatus);
        assertEquals(Problem.Kind.NEED_ITEM, failed.problem().kind());
        assertEquals(List.of("fluid:minecraft:water:1000", "item:minecraft:iron_ingot:128"), wrapped,
                "流体与超过 64 的物品必须经 GenericStack.wrapInItemStack 包装");
    }

    @Test
    void 样板管理终端按同组筛选serverId发QuickMovePattern并核对背包少一() {
        PatternAccessAction.PutTarget target = PatternAccessAction.PutTarget
                .parse("Furnace:minecraft:iron_ingot").orElseThrow();
        assertEquals("Furnace", target.groupName());
        assertEquals("minecraft:iron_ingot", target.outputItemId());

        List<Ae2MachineReads.VisiblePatternContainer> containers = List.of(
                new Ae2MachineReads.VisiblePatternContainer(101L, "Molecular Assembler", 9, 2),
                new Ae2MachineReads.VisiblePatternContainer(201L, "Furnace", 9, 1),
                new Ae2MachineReads.VisiblePatternContainer(202L, "Furnace", 9, 0));
        assertEquals(List.of(201L, 202L), PatternAccessAction.selectGroupServerIds(containers, "furnace"));

        AtomicBoolean slotStillHasPattern = new AtomicBoolean(true);
        AtomicInteger sentSlot = new AtomicInteger(-1);
        List<Long> sentIds = new ArrayList<>();
        Ae2Patterns patterns = new Ae2Patterns() {
            @Override public Set<Integer> openPatternSlots() { return Set.of(); }
            @Override public Optional<String> patternOutput(ItemStack stack, ClientLevel level) {
                return stack.is(Items.PAPER) ? Optional.of("minecraft:iron_ingot") : Optional.empty();
            }
        };
        Ae2Compat compat = MachineTestFakes.compatWith(Ae2MenuSlots.NONE, patterns, MachineTestFakes.emptyGrid());
        Ae2MachineReads reads = new Ae2MachineReads() {
            @Override public Optional<PatternAccessMenuView> openPatternAccessMenu() {
                return Optional.of(new PatternAccessMenuView(7, "visible", true, containers));
            }
            @Override public boolean sendQuickMovePattern(int containerId, int playerSlotIndex, List<Long> allowedServerIds) {
                sentSlot.set(playerSlotIndex);
                sentIds.addAll(allowedServerIds);
                slotStillHasPattern.set(false);
                return true;
            }
        };
        MachineTestFakes.ScriptedMenu menu = new MachineTestFakes.ScriptedMenu(
                () -> Optional.of(MachineTestFakes.readingOf(
                        List.of(12),
                        List.of(slotStillHasPattern.get() ? SlotSnapshot.of(new ItemStack(Items.PAPER, 1)) : SlotSnapshot.empty()),
                        List.of(), List.of())),
                null);
        Ae2MachineCache cache = new Ae2MachineCache();
        PatternAccessAction action = new PatternAccessAction(HOST, Direction.SOUTH, target,
                "Furnace:minecraft:iron_ingot", MachineTestFakes.instantOpening(menu), compat, reads, cache);

        ActionStatus finalStatus = ActionStatus.running();
        for (int i = 0; i < 20; i++) {
            finalStatus = action.tick(MachineTestFakes.emptyTick());
            if (!(finalStatus instanceof ActionStatus.Running)) break;
        }
        assertInstanceOf(ActionStatus.Done.class, finalStatus);
        assertEquals(12, sentSlot.get());
        assertEquals(List.of(201L, 202L), sentIds);
        assertTrue(menu.closed);
    }

    @Test
    void 切石模式完整编码走专用发包并在关窗后读回精确命中原值() {
        String rawJson = "{\"mode\":\"stonecutting\",\"inputs\":[\"minecraft:stone\"],"
                + "\"stonecutting_recipe\":\"minecraft:stone_bricks_from_stone_stonecutting\"}";
        PatternEncodingRequest req = PatternEncodingRequest.parse(rawJson).request();
        List<String> sentStoneRecipe = new ArrayList<>();
        AtomicBoolean encodedNow = new AtomicBoolean(false);
        Ae2MenuSlots menuSlots = new Ae2MenuSlots() {
            @Override public boolean isAe2MenuOpen() { return true; }
            @Override public List<Integer> slotsOf(String semanticId) {
                return switch (semanticId) {
                    case "STONECUTTING_INPUT" -> List.of(40);
                    case "ENCODED_PATTERN" -> List.of(50);
                    default -> List.of();
                };
            }
            @Override public boolean sendConfigButton(String s, boolean b) { return true; }
            @Override public boolean sendSetFilter(int i, ItemStack st) { return !st.isEmpty(); }
            @Override public boolean sendNoArgAction(String a) { return true; }
            @Override public boolean sendIntAction(String a, int v) { return true; }
            @Override public boolean sendLongAction(String a, long v) { return true; }
            @Override public boolean sendBooleanAction(String a, boolean v) { return true; }
            @Override public boolean sendStringAction(String a, String v) { return true; }
            @Override public boolean sendRawJsonAction(String a, String j) { return true; }
            @Override public boolean openSubMenu(String m) { return true; }
            @Override public boolean returnToParentMenu() { return true; }
        };
        Ae2Compat compat = MachineTestFakes.compatWith(menuSlots, Ae2Patterns.NONE, MachineTestFakes.emptyGrid());
        Ae2MachineReads reads = new Ae2MachineReads() {
            @Override public Optional<PatternEncodingMenuView> openPatternEncodingMenu() {
                String recipeId = sentStoneRecipe.isEmpty() ? "" : sentStoneRecipe.getLast();
                return Optional.of(new PatternEncodingMenuView("stonecutting", false, true, recipeId,
                        List.of("minecraft:stone_bricks_from_stone_stonecutting"),
                        "", 1, encodedNow.get() ? "minecraft:stone_bricks" : "",
                        encodedNow.get(), false, false, false));
            }
            @Override public boolean sendEncodeMode(String mode) { return true; }
            @Override public boolean sendEncodeClear() { return true; }
            @Override public boolean sendEncodeStonecuttingRecipe(String recipeId) {
                sentStoneRecipe.add(recipeId);
                return true;
            }
            @Override public boolean sendEncodeCommit() {
                encodedNow.set(true);
                return true;
            }
        };
        MachineTestFakes.ScriptedMenu menu = new MachineTestFakes.ScriptedMenu(
                () -> Optional.of(MachineTestFakes.readingOf(List.of(), List.of(),
                        List.of(50), List.of(SlotSnapshot.empty()))),
                slot -> {
                    if (slot == 50) encodedNow.set(false);
                });
        Ae2MachineCache cache = new Ae2MachineCache();
        PatternEncodingAction action = new PatternEncodingAction(HOST, Direction.EAST,
                MachineTestFakes.instantOpening(menu), compat, reads, cache, req, rawJson, null, null);

        ActionStatus finalStatus = ActionStatus.running();
        for (int i = 0; i < 40; i++) {
            finalStatus = action.tick(MachineTestFakes.emptyTick());
            if (!(finalStatus instanceof ActionStatus.Running)) break;
        }
        assertInstanceOf(ActionStatus.Done.class, finalStatus);
        assertEquals(List.of("minecraft:stone_bricks_from_stone_stonecutting"), sentStoneRecipe);
        assertEquals(rawJson, cache.encoding(HOST, Direction.EAST).orElseThrow().lastRequest());
        assertEquals("minecraft:stone_bricks", cache.encoding(HOST, Direction.EAST).orElseThrow().lastOutput());
        assertTrue(menu.closed);
    }

    @Test
    void 同模式旧网格残留有效配方时必须先等clear清空_防止绕过防洗样板校验() {
        String rawJson = "{\"mode\":\"crafting\",\"inputs\":[\"minecraft:stick\",\"minecraft:stick\"]}";
        PatternEncodingRequest req = PatternEncodingRequest.parse(rawJson).request();
        AtomicInteger clearDelay = new AtomicInteger(-1);
        AtomicBoolean cleared = new AtomicBoolean(false);
        AtomicBoolean encodeCalled = new AtomicBoolean(false);
        Ae2MenuSlots menuSlots = new Ae2MenuSlots() {
            @Override public boolean isAe2MenuOpen() { return true; }
            @Override public List<Integer> slotsOf(String semanticId) {
                return "CRAFTING_GRID".equals(semanticId)
                        ? List.of(10, 11, 12, 13, 14, 15, 16, 17, 18) : List.of();
            }
            @Override public boolean sendConfigButton(String s, boolean b) { return true; }
            @Override public boolean sendSetFilter(int i, ItemStack st) { return true; }
            @Override public boolean sendNoArgAction(String a) { return true; }
            @Override public boolean sendIntAction(String a, int v) { return true; }
            @Override public boolean sendLongAction(String a, long v) { return true; }
            @Override public boolean sendBooleanAction(String a, boolean v) { return true; }
            @Override public boolean sendStringAction(String a, String v) { return true; }
            @Override public boolean sendRawJsonAction(String a, String j) { return true; }
            @Override public boolean openSubMenu(String m) { return true; }
            @Override public boolean returnToParentMenu() { return true; }
        };
        Ae2Compat compat = MachineTestFakes.compatWith(menuSlots, Ae2Patterns.NONE, MachineTestFakes.emptyGrid());
        Ae2MachineReads reads = new Ae2MachineReads() {
            @Override public Optional<PatternEncodingMenuView> openPatternEncodingMenu() {
                if (clearDelay.get() > 0 && clearDelay.decrementAndGet() == 0) {
                    cleared.set(true);
                }
                // clear 未生效前仍残留旧有效配方 minecraft:crafting_table；清空后新配方无效（空结果）。
                String staleOrNew = cleared.get() ? "" : "minecraft:crafting_table";
                return Optional.of(new PatternEncodingMenuView("crafting", false, true, "", List.of(),
                        staleOrNew, 2, "", false, false, false, false));
            }
            @Override public boolean sendEncodeMode(String mode) { return true; }
            @Override public boolean sendEncodeClear() {
                clearDelay.set(3);
                return true;
            }
            @Override public boolean sendEncodeCommit() {
                encodeCalled.set(true);
                return true;
            }
        };
        MachineTestFakes.ScriptedMenu menu = new MachineTestFakes.ScriptedMenu(
                () -> Optional.of(MachineTestFakes.readingOf(List.of(), List.of(), List.of(), List.of())), null);
        PatternEncodingAction action = new PatternEncodingAction(HOST, Direction.NORTH,
                MachineTestFakes.instantOpening(menu), compat, reads, new Ae2MachineCache(), req, rawJson, null, null);

        ActionStatus status = ActionStatus.running();
        for (int i = 0; i < 80; i++) {
            status = action.tick(MachineTestFakes.emptyTick());
            if (!(status instanceof ActionStatus.Running)) break;
        }
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, status);
        assertEquals(Problem.Kind.INVALID_PARAMETER, failed.problem().kind());
        assertFalse(encodeCalled.get(), "必须先等 clear() 清空旧有效配方，绝不可拿旧残留放行 encode()");
        assertTrue(cleared.get());
    }

    @Test
    void 处理样板支持化学品输入输出并走通用包装接口() {
        String rawJson = """
                {"mode":"processing",
                 "inputs":[{"kind":"chemical","id":"mekanism:hydrogen","amount":1000},{"id":"minecraft:iron_ingot","amount":2}],
                 "outputs":[{"chemical":"mekanism:oxygen","amount":500}]}
                """;
        PatternEncodingRequest.ParseResult parsed = PatternEncodingRequest.parse(rawJson);
        assertTrue(parsed.ok());
        PatternEncodingRequest req = parsed.request();
        assertTrue(req.inputs().get(0).isChemical());
        assertTrue(req.inputs().get(0).requiresGenericWrap());
        assertEquals("mekanism:hydrogen", req.inputs().get(0).id());
        assertEquals(1000L, req.inputs().get(0).amount());
        assertTrue(req.outputs().get(0).isChemical());
        assertEquals("mekanism:oxygen", req.outputs().get(0).id());
        assertEquals(500L, req.outputs().get(0).amount());

        // 简写 {"chemical": ...} 与 {"fluid": ...} 均兼容 count 字段，不再静默回落 1000L。
        PatternEncodingRequest.ParseResult withCount = PatternEncodingRequest.parse("""
                {"mode":"processing",
                 "inputs":[{"chemical":"mekanism:hydrogen","count":500},{"fluid":"minecraft:water","count":250}],
                 "outputs":[{"chemical":"mekanism:oxygen","count":125}]}
                """);
        assertTrue(withCount.ok());
        assertEquals(500L, withCount.request().inputs().get(0).amount());
        assertEquals(250L, withCount.request().inputs().get(1).amount());
        assertEquals(125L, withCount.request().outputs().get(0).amount());

        List<String> wrappedCalls = new ArrayList<>();
        AtomicBoolean encoded = new AtomicBoolean(false);
        Ae2MenuSlots menuSlots = new Ae2MenuSlots() {
            @Override public boolean isAe2MenuOpen() { return true; }
            @Override public List<Integer> slotsOf(String semanticId) {
                return switch (semanticId) {
                    case "PROCESSING_INPUTS" -> List.of(10, 11);
                    case "PROCESSING_OUTPUTS" -> List.of(20);
                    case "ENCODED_PATTERN" -> List.of(30);
                    default -> List.of();
                };
            }
            @Override public boolean sendConfigButton(String s, boolean b) { return true; }
            @Override public boolean sendSetFilter(int i, ItemStack st) { return !st.isEmpty(); }
            @Override public boolean sendNoArgAction(String a) { return true; }
            @Override public boolean sendIntAction(String a, int v) { return true; }
            @Override public boolean sendLongAction(String a, long v) { return true; }
            @Override public boolean sendBooleanAction(String a, boolean v) { return true; }
            @Override public boolean sendStringAction(String a, String v) { return true; }
            @Override public boolean sendRawJsonAction(String a, String j) { return true; }
            @Override public boolean openSubMenu(String m) { return true; }
            @Override public boolean returnToParentMenu() { return true; }
        };
        AtomicBoolean written = new AtomicBoolean(false);
        AtomicBoolean taken = new AtomicBoolean(false);
        Ae2MachineReads reads = new Ae2MachineReads() {
            @Override public Optional<PatternEncodingMenuView> openPatternEncodingMenu() {
                boolean hasOut = encoded.get() && !taken.get();
                return Optional.of(new PatternEncodingMenuView("processing", false, false, "", List.of(),
                        "", 1, hasOut ? "mekanism:oxygen" : "", hasOut,
                        written.get(), written.get(), false));
            }
            @Override public boolean sendEncodeMode(String mode) { return true; }
            @Override public boolean sendEncodeClear() { return true; }
            @Override public boolean sendEncodeCommit() {
                encoded.set(true);
                return true;
            }
            @Override public ItemStack wrapGenericStack(String kind, String id, long amount) {
                wrappedCalls.add(kind + ":" + id + ":" + amount);
                written.set(true);
                return new ItemStack(Items.PAPER);
            }
        };
        MachineTestFakes.ScriptedMenu menu = new MachineTestFakes.ScriptedMenu(
                () -> Optional.of(MachineTestFakes.readingOf(List.of(), List.of(), List.of(), List.of())),
                slotId -> {
                    if (slotId == 30) taken.set(true);
                });
        Ae2Compat compat = MachineTestFakes.compatWith(menuSlots, Ae2Patterns.NONE, MachineTestFakes.emptyGrid());
        PatternEncodingAction action = new PatternEncodingAction(HOST, Direction.NORTH,
                MachineTestFakes.instantOpening(menu), compat, reads, new Ae2MachineCache(), req, rawJson, null, null);

        ActionStatus status = ActionStatus.running();
        for (int i = 0; i < 60; i++) {
            status = action.tick(MachineTestFakes.emptyTick());
            if (!(status instanceof ActionStatus.Running)) break;
        }
        assertInstanceOf(ActionStatus.Done.class, status);
        assertEquals(List.of("chemical:mekanism:hydrogen:1000", "chemical:mekanism:oxygen:500"), wrappedCalls);
    }
}
