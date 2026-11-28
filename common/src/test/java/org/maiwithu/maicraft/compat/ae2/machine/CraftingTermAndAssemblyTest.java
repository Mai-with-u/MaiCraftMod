// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.ae2.Ae2MachineCatalog;
import org.maiwithu.maicraft.compat.ae2.Ae2MenuSlots;
import org.maiwithu.maicraft.compat.ae2.Ae2SemanticsLayout;
import org.maiwithu.maicraft.kernel.task.ActionStatus;

/**
 * 合成终端 3×3 手搓来源与 Ae2Machines 统一汇编入口测试：
 * 验证语义槽定位（兼容无线/有线槽序与工具箱）、craftMissing=false 填格、CRAFT_* 取产物、完工清格，
 * 以及 Ae2Machines 暴露的设备目录、委托映射、布局规则零碰撞与携带网络工具箱的 9 槽免疫。
 */
class CraftingTermAndAssemblyTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 合成终端按语义定位三乘三格_严格不触发缺料下单并用CRAFT包取产物与完工清格() {
        // 模拟无线合成终端槽位排列（TOOLBOX 在前，CRAFTING_GRID 紧随其后，玩家背包在最后）。
        Ae2MenuSlots menuSlots = new Ae2MenuSlots() {
            @Override public boolean isAe2MenuOpen() { return true; }
            @Override public List<Integer> slotsOf(String semanticId) {
                return switch (semanticId) {
                    case "CRAFTING_GRID" -> List.of(10, 11, 12, 13, 14, 15, 16, 17, 18);
                    case "CRAFTING_RESULT" -> List.of(19);
                    default -> List.of();
                };
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
        List<String> sent = new ArrayList<>();
        AtomicBoolean filled = new AtomicBoolean(false);
        AtomicBoolean gridOccupied = new AtomicBoolean(true);
        AtomicReference<SlotSnapshot> cursor = new AtomicReference<>(SlotSnapshot.empty());
        AtomicReference<SlotSnapshot> bagSlot = new AtomicReference<>(SlotSnapshot.empty());
        Ae2MachineReads reads = new Ae2MachineReads() {
            @Override public Optional<CraftingTermMenuView> openCraftingTermMenu() {
                if (!filled.get()) {
                    return Optional.of(new CraftingTermMenuView(true, "", 0, gridOccupied.get()));
                }
                return Optional.of(new CraftingTermMenuView(true, "minecraft:crafting_table", 1, gridOccupied.get()));
            }
            @Override public boolean sendFillCraftingGrid(String recipeId, List<ItemStack> templates9) {
                sent.add("fill:" + recipeId + ":" + templates9.size());
                filled.set(true);
                return true;
            }
            @Override public boolean sendCraftResult(int outputSlotIndex, boolean shift) {
                sent.add("craft:" + outputSlotIndex + ":" + (shift ? "shift" : "item"));
                // 模拟 AE2 真实行为：CRAFT_ITEM 把单次成品放到鼠标光标。
                if (!shift) {
                    cursor.set(SlotSnapshot.of(new ItemStack(Items.CRAFTING_TABLE, 1)));
                }
                return true;
            }
            @Override public boolean sendClearCraftingGrid(boolean toPlayerInventory) {
                sent.add(toPlayerInventory ? "clear_player" : "clear_network");
                if (toPlayerInventory) gridOccupied.set(false);
                return true;
            }
        };
        Ae2Compat compat = MachineTestFakes.compatWith(menuSlots, Ae2Patterns.NONE, MachineTestFakes.emptyGrid());
        MachineTestFakes.ScriptedMenu menu = new MachineTestFakes.ScriptedMenu(
                () -> Optional.of(MachineTestFakes.readingOf(
                        List.of(30), List.of(bagSlot.get()), List.of(), List.of())),
                null)
                .withCursor(cursor::get, slot -> {
                    if (slot == 30 && !cursor.get().isEmpty()) {
                        bagSlot.set(cursor.get());
                        cursor.set(SlotSnapshot.empty());
                    }
                });
        List<ItemStack> templates = List.of(
                new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.OAK_PLANKS), ItemStack.EMPTY,
                new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.OAK_PLANKS), ItemStack.EMPTY,
                ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);
        // 要 2 件也走 CRAFT_ITEM（绝不误用 CRAFT_SHIFT 搓一整组 64 件），两次从光标点入背包后成功 Done。
        CraftingTermAction action = new CraftingTermAction(MachineTestFakes.instantOpening(menu),
                "minecraft:crafting_table", templates, "minecraft:crafting_table", 2, compat, reads);

        ActionStatus status = ActionStatus.running();
        for (int i = 0; i < 60; i++) {
            status = action.tick(MachineTestFakes.emptyTick());
            if (!(status instanceof ActionStatus.Running)) break;
        }
        assertInstanceOf(ActionStatus.Done.class, status, "CRAFT_ITEM 光标入包应完整成功而非超时");
        assertTrue(sent.contains("fill:minecraft:crafting_table:9"), "必须发 FillCraftingGridFromRecipePacket");
        assertEquals(2, sent.stream().filter("craft:19:item"::equals).count(), "要 2 件应发 2 次 CRAFT_ITEM 而非 CRAFT_SHIFT");
        assertEquals(List.of(30, 30), menu.clicked, "两次从光标点进玩家背包槽 30");
        assertTrue(cursor.get().isEmpty(), "关窗前光标必须已清空，绝不掉地上");
        assertTrue(sent.contains("clear_network"), "完工先退回 ME 网络");
        assertTrue(sent.contains("clear_player"), "退网后格内仍有余料时再退回玩家背包");
        assertTrue(menu.closed);

        // 报价防抢单：背包缺料时标 UNKNOWN_COUNT 并带 shortfallBlocks，代价高于 10 格外现货箱子；背包自足则报确定件数且零缺料。
        SourceQuote.Offer uncertain = CraftingTermSource.buildOffer("ME 合成终端 3×3 手搓", 4, 0, 3, 2, "h", "r");
        assertEquals(SourceQuote.Offer.UNKNOWN_COUNT, uncertain.obtainableCount());
        AcquisitionCost nearbyChestSpot = new AcquisitionCost(10, 4, 0);
        assertTrue(uncertain.cost().compareTo(nearbyChestSpot) > 0, "未知网络原料手搓不应压过 10 格外明确现货箱子");

        SourceQuote.Offer selfSufficient = CraftingTermSource.buildOffer("ME 合成终端 3×3 手搓", 4, 0, 3, 0, "h", "r");
        assertEquals(4, selfSufficient.obtainableCount());
        assertEquals(0.0, selfSufficient.cost().shortfallBlocks());

        // 多配方缺料计数：背包有 2 块木板时，2 格木板配方缺 0 格，4 格木板配方缺 2 格。
        List<ItemStack> bag = List.of(new ItemStack(Items.OAK_PLANKS, 2));
        Ingredient planks = Ingredient.of(Items.OAK_PLANKS);
        assertEquals(0, CraftingTermSource.countMissingIngredients(List.of(planks, planks), bag));
        assertEquals(2, CraftingTermSource.countMissingIngredients(List.of(planks, planks, planks, planks), bag));

        // 校验无线与固定终端 hint 编解码。
        String wHint = CraftingTermSource.encodeWirelessHint(
                "ae2:wireless_crafting_terminal", "minecraft:stick", "minecraft:stick");
        CraftingTermSource.ParsedHint parsedW = CraftingTermSource.ParsedHint.parse(wHint).orElseThrow();
        assertEquals("ae2:wireless_crafting_terminal", parsedW.wirelessItem());

        String pHint = CraftingTermSource.encodePlacedHint(
                new BlockPos(1, 2, 3), Direction.NORTH, "minecraft:stick", "minecraft:stick");
        CraftingTermSource.ParsedHint parsedP = CraftingTermSource.ParsedHint.parse(pHint).orElseThrow();
        assertEquals(new BlockPos(1, 2, 3), parsedP.host());
        assertEquals(Direction.NORTH, parsedP.side());
    }

    @Test
    void 统一汇编入口设备清单与独占布局规则无冲突且免疫工具箱九槽() {
        Ae2Compat compat = MachineTestFakes.compatWith(Ae2MenuSlots.NONE, Ae2Patterns.NONE, MachineTestFakes.emptyGrid());
        Ae2Machines machines = new Ae2Machines(compat, Ae2MachineReads.NONE);

        // 3 个新增设备种类。
        List<String> extraIds = machines.extraKinds().stream().map(Ae2MachineCatalog.Kind::id).toList();
        assertEquals(List.of("ae2:crank", "ae2:sky_stone_tank", "ae2:growth_accelerator"), extraIds);

        // 11 个方块设备委托全部到位。
        Set<String> expectedDevices = Set.of("ae2:charger", "ae2:crank", "ae2:vibration_chamber",
                "ae2:condenser", "ae2:chest", "ae2:sky_stone_tank", "ae2:growth_accelerator",
                "ae2:pattern_provider", "ae2:molecular_assembler", "ae2:inscriber", "ae2:crafting_unit");
        assertEquals(expectedDevices, machines.deviceOps().keySet());

        // 放置规则与合成终端手搓来源。
        assertEquals(1, machines.placementRules().size());
        var sources = machines.itemSources(MachineTestFakes.offlineServices());
        assertEquals(1, sources.size());
        assertEquals(AcquireVia.CRAFT, sources.getFirst().via());

        // 独占布局规则：绝不与既有 6 个布局及无 36 格背包子菜单撞 ID。
        Map<String, Ae2SemanticsLayout.Rule> rules = machines.layoutRules();
        Set<String> existingAndSubMenus = Set.of(
                "ae2:item_terminal", "ae2:craftingterm", "ae2:patternterm", "ae2:wirelessterm",
                "ae2:wirelesscraftingterm", "ae2:pattern_provider", "ae2:wireless_access_point",
                "ae2:inscriber", "ae2:drive", "ae2:skychest",
                "ae2:priority", "ae2:craftingcpu", "ae2:craftingstatus", "ae2:set_stock_amount");
        for (String id : rules.keySet()) {
            assertFalse(existingAndSubMenus.contains(id), "布局规则不可与既有布局或无背包子菜单撞 ID：" + id);
        }
        assertEquals(Set.of("ae2:vibrationchamber", "ae2:condenser", "ae2:me_chest",
                "ae2:basic_cell_chest", "ae2:molecular_assembler", "ae2:patternaccessterminal"), rules.keySet());

        // 验证带 9 格 TOOLBOX 的分子组装室布局分类精准跳过工具箱。
        Ae2MenuSlots toolboxSlots = new Ae2MenuSlots() {
            @Override public boolean isAe2MenuOpen() { return true; }
            @Override public List<Integer> slotsOf(String semanticId) {
                return switch (semanticId) {
                    case "TOOLBOX" -> List.of(0, 1, 2, 3, 4, 5, 6, 7, 8);
                    case "UPGRADE" -> List.of(9, 10, 11, 12, 13);
                    case "MACHINE_CRAFTING_GRID" -> List.of(14, 15, 16, 17, 18, 19, 20, 21, 22);
                    case "ENCODED_PATTERN" -> List.of(23);
                    case "MACHINE_OUTPUT" -> List.of(24);
                    default -> List.of();
                };
            }
            @Override public boolean sendConfigButton(String s, boolean b) { return false; }
            @Override public boolean sendSetFilter(int i, ItemStack st) { return false; }
            @Override public boolean sendNoArgAction(String a) { return false; }
            @Override public boolean sendIntAction(String a, int v) { return false; }
            @Override public boolean sendLongAction(String a, long v) { return false; }
            @Override public boolean sendBooleanAction(String a, boolean v) { return false; }
            @Override public boolean sendStringAction(String a, String v) { return false; }
            @Override public boolean sendRawJsonAction(String a, String j) { return false; }
            @Override public boolean openSubMenu(String m) { return false; }
            @Override public boolean returnToParentMenu() { return false; }
        };
        Ae2SemanticsLayout layout = new Ae2SemanticsLayout(toolboxSlots, rules);
        List<Integer> playerSlots = new ArrayList<>();
        for (int i = 25; i < 61; i++) playerSlots.add(i);
        List<Integer> otherSlots = new ArrayList<>();
        for (int i = 0; i < 25; i++) otherSlots.add(i);
        MenuSlots rawMenu = new MenuSlots() {
            @Override public String menuTypeId() { return "ae2:molecular_assembler"; }
            @Override public int slotCount() { return 61; }
            @Override public boolean playerBacked(int slot) { return slot >= 25; }
            @Override public int playerInventoryIndex(int slot) { return slot - 25; }
        };
        MenuLayout.Supported supported = assertInstanceOf(MenuLayout.Supported.class,
                layout.classify(rawMenu, playerSlots, otherSlots));
        assertEquals(16, supported.containerSlots().size(), "9 合成格 + 1 输出 + 1 样板 + 5 升级 = 16，自动排除 9 工具箱槽");
        assertFalse(supported.containerSlots().contains(0), "TOOLBOX 槽绝不可混入容器槽");
        assertEquals(10, supported.machineSlots().size(), "9 合成格 + 1 输出格为机器槽");
    }
}
