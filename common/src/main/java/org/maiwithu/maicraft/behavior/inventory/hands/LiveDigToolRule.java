// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.hands;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.inventory.ItemsToKeep;
import org.maiwithu.maicraft.game.interaction.DigToolPlan;
import org.maiwithu.maicraft.game.interaction.DigToolRule;
import org.maiwithu.maicraft.game.interaction.ToolSelect;

/**
 * 挑工具的生产实现：把角色此刻背包前 36 格与要挖的方块读成挑工具要的事实，交给 {@link DigToolChoice} 下结论。
 * 速度倍数、够不够格、耐久、每格磨多少、附魔都问原版的物品与方块，不维护工具等级表；材料价值按物品名里的材料词认；
 * 精准采集、时运会不会改这一格的掉落，问掉落对照（认不出的模组方块当会改）。挖方块的基础代码、走路挖路的寻路引擎
 * 都照它换手，全仓一份。
 *
 * <p>调用方预留着的工具不自动拿来挖（主手已经握着的照用）；正要用的东西不在这里算——刚拿到的镐就是拿来挖的，
 * 挖东西磨的是耐久，花不掉它。
 */
public final class LiveDigToolRule implements DigToolRule {

    private static final int USABLE_SLOTS = 36;

    private final ItemsToKeep reserved;
    private final Predicate<String> dropSensitive;

    /**
     * @param reserved      调用方预留的东西：预留着的工具不自动拿来挖
     * @param dropSensitive 方块 ID → 精准采集、时运会不会改它的掉落
     */
    public LiveDigToolRule(ItemsToKeep reserved, Predicate<String> dropSensitive) {
        this.reserved = Objects.requireNonNull(reserved, "reserved");
        this.dropSensitive = Objects.requireNonNull(dropSensitive, "dropSensitive");
    }

    @Override
    public DigToolPlan plan(LocalPlayer player, BlockState state, int expectedBlocks, boolean wantsDrop) {
        return switch (verdict(player, state, expectedBlocks, wantsDrop)) {
            case DigToolChoice.Verdict.Swap(int slot) -> DigToolPlan.hold(slot);
            case DigToolChoice.Verdict.BareHand ignored -> DigToolPlan.bareHand();
            // 主手已是合适的、身上也没有更划算的：都不动手。
            case DigToolChoice.Verdict.Keep ignored -> DigToolPlan.keep();
            case DigToolChoice.Verdict.None ignored -> DigToolPlan.keep();
        };
    }

    /**
     * 挑工具的结论；挖不动的（基岩这类）当主手合适、不换，换了也挖不动。
     * 现做工具按"身上没有够格的"那一态算账。
     */
    public DigToolChoice.Verdict verdict(LocalPlayer player, BlockState state, int expectedBlocks, boolean wantsDrop) {
        DigToolChoice.Block block = block(player, state);
        if (block == null) return new DigToolChoice.Verdict.Keep();
        Inventory inventory = player.getInventory();
        return DigToolChoice.choose(candidates(inventory, state), inventory.selected, block, expectedBlocks, wantsDrop);
    }

    /** 要挖的这一格的事实：硬度、要不要够格工具、掉落敏感不敏感；挖不动的为 null。 */
    public DigToolChoice.Block block(LocalPlayer player, BlockState state) {
        float hardness = state.getDestroySpeed(player.level(), player.blockPosition());
        if (hardness < 0) return null;
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return new DigToolChoice.Block(hardness, state.requiresCorrectToolForDrops(), dropSensitive.test(blockId));
    }

    /** 背包前 36 格各是什么样子：0~8 快捷栏、9~35 主背包。 */
    public List<DigToolChoice.Candidate> candidates(Inventory inventory, BlockState state) {
        List<DigToolChoice.Candidate> slots = new ArrayList<>();
        int size = Math.min(USABLE_SLOTS, inventory.getContainerSize());
        for (int slot = 0; slot < size; slot++) slots.add(candidate(slot, inventory.getItem(slot), state));
        return slots;
    }

    // 一格东西挖这一格的样子：速度、够不够格、耐久与每格磨损问原版，材料与附魔折成价值，预留问调用方。
    private DigToolChoice.Candidate candidate(int slot, ItemStack stack, BlockState state) {
        if (stack.isEmpty()) return DigToolChoice.Candidate.emptySlot(slot);
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        boolean damageable = stack.isDamageableItem();
        Tool tool = stack.get(DataComponents.TOOL);
        return new DigToolChoice.Candidate(slot, false, speed(stack, state),
                !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state),
                damageable ? stack.getMaxDamage() : 0,
                damageable ? stack.getMaxDamage() - stack.getDamageValue() : 0,
                damageable && tool != null ? tool.damagePerBlock() : 0,
                materialValue(itemId),
                extraValue(stack),
                ToolSelect.keepsBlockWhole(stack) || ToolSelect.fortuneLevel(stack) > 0,
                ToolSelect.mends(stack),
                reserved.keep(itemId) > 0);
    }

    // 挖这一格的有效速度：工具自己的速度之外，效率附魔按等级平方加一加快；原版只在工具比手快时才加这一份。
    private static float speed(ItemStack stack, BlockState state) {
        float speed = stack.getDestroySpeed(state);
        int efficiency = ToolSelect.efficiencyLevel(stack);
        return speed > 1.0f && efficiency > 0 ? speed + efficiency * efficiency + 1 : speed;
    }

    // 材料价值按物品名里的材料词认：木 1、石 1.5、铁 10、金 15、钻石 50、下界合金 100；认不出的模组工具按 5 算。
    static double materialValue(String itemId) {
        String id = itemId.toLowerCase(Locale.ROOT);
        if (id.contains("netherite")) return 100;
        if (id.contains("diamond")) return 50;
        if (id.contains("golden") || id.contains("gold_")) return 15;
        if (id.contains("iron")) return 10;
        if (id.contains("stone")) return 1.5;
        if (id.contains("wooden") || id.contains("wood_")) return 1;
        return DigToolChoice.UNKNOWN_MATERIAL_VALUE;
    }

    // 附魔附加价值：会改掉落的（精准采集、时运）每样按 50 算，别的每样按 5 算。
    private static double extraValue(ItemStack stack) {
        double extra = 0;
        for (var entry : stack.getEnchantments().entrySet()) {
            boolean dropChanging = entry.getKey().is(Enchantments.SILK_TOUCH)
                    || entry.getKey().is(Enchantments.FORTUNE);
            extra += dropChanging ? DigToolChoice.DROP_CHANGING_VALUE : DigToolChoice.OTHER_ENCHANT_VALUE;
        }
        return extra;
    }
}
