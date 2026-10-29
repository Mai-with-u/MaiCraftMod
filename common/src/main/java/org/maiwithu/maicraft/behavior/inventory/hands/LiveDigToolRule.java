// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.hands;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.inventory.ItemsToKeep;
import org.maiwithu.maicraft.game.interaction.DigToolRule;
import org.maiwithu.maicraft.game.interaction.ToolSelect;

/**
 * 挑工具的生产实现：把角色此刻背包前 36 格与要挖的方块读成挑工具要的事实，交给 {@link DigToolChoice} 下结论。
 * 速度倍数、够不够格、耐久、附魔都问原版的物品与方块，不维护工具等级表；精准采集、时运会不会改这一格的掉落，
 * 问掉落对照（认不出的模组方块当会改）。挖方块的基础代码、走路挖路的寻路引擎都照它换手，全仓一份。
 *
 * <p>调用方预留着的工具不挑出来用（主手已经握着的照用）；正要用的东西不在这里算——刚拿到的镐就是拿来挖的，
 * 挖东西磨的是耐久，花不掉它。
 */
public final class LiveDigToolRule implements DigToolRule {

    private static final int USABLE_SLOTS = 36;

    private final ItemsToKeep reserved;
    private final Predicate<String> dropSensitive;

    /**
     * @param reserved      调用方预留的东西：预留着的工具不挑
     * @param dropSensitive 方块 ID → 精准采集、时运会不会改它的掉落
     */
    public LiveDigToolRule(ItemsToKeep reserved, Predicate<String> dropSensitive) {
        this.reserved = Objects.requireNonNull(reserved, "reserved");
        this.dropSensitive = Objects.requireNonNull(dropSensitive, "dropSensitive");
    }

    @Override
    public int slotFor(LocalPlayer player, BlockState state, int minimumDurability, int expectedBlocks) {
        DigToolChoice.Block block = block(player, state);
        // 挖不动的（基岩这类）不换工具，换了也挖不动。
        if (block == null) return -1;
        Inventory inventory = player.getInventory();
        DigToolChoice.Verdict verdict = DigToolChoice.choose(candidates(inventory, state), inventory.selected, block,
                expectedBlocks, minimumDurability);
        return verdict instanceof DigToolChoice.Verdict.Swap swap ? swap.slot() : -1;
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

    // 一格东西挖这一格的样子：速度、够不够格、耐久问原版；改掉落的附魔（精准采集、时运、剪刀）与稀有度看物品本身。
    private DigToolChoice.Candidate candidate(int slot, ItemStack stack, BlockState state) {
        if (stack.isEmpty()) return DigToolChoice.Candidate.emptySlot(slot);
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        boolean damageable = stack.isDamageableItem();
        return new DigToolChoice.Candidate(slot, false, stack.getDestroySpeed(state),
                !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state),
                damageable, damageable ? stack.getMaxDamage() : 0,
                damageable ? stack.getMaxDamage() - stack.getDamageValue() : 0,
                ToolSelect.keepsBlockWhole(stack) || ToolSelect.fortuneLevel(stack) > 0,
                stack.isEnchanted() || stack.getRarity() != Rarity.COMMON,
                reserved.keep(itemId) > 0);
    }
}
