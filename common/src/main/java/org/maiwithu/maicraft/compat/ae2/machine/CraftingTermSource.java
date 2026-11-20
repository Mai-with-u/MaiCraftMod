// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.machine;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;

import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.menu.ClientMenuOpening;
import org.maiwithu.maicraft.behavior.menu.HeldItemMenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 合成终端与无线合成终端 3×3 手搓合成来源（ItemSource，途径 AcquireVia.CRAFT）：
 * 角色持有已绑定无线合成终端或身处附近合成终端（ae2:crafting_terminal）旁时，
 * 直接调用 ME 网络与背包库存手搓 3×3 配方，严格以 craftMissing=false 填格并用 CRAFT_* 取产物，
 * 与既有 Ae2TerminalLayout 及网络存取完全解耦共存。
 */
final class CraftingTermSource implements ItemSource {

    static final int SEARCH_RADIUS_BLOCKS = 32;
    private static final int WIRELESS_ACTIONS = 3;
    private static final int PLACED_ACTIONS = 4;

    private final Ae2Compat compat;
    private final Ae2MachineReads reads;
    private final PlayerServices services;

    CraftingTermSource(Ae2Compat compat, Ae2MachineReads reads, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public String describe() {
        return "ME 合成终端 3×3 手搓";
    }

    @Override public AcquireVia via() {
        return AcquireVia.CRAFT;
    }

    @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
        PlayerContext player = services.context().get();
        if (player == null || player.level() == null || player.localPlayer() == null) {
            return new SourceQuote.Unavailable(describe(), "角色不在世界里，无法使用合成终端");
        }
        Optional<RecipeHolder<CraftingRecipe>> recipe = findRecipe(player.level(), request);
        if (recipe.isEmpty()) {
            return new SourceQuote.Unavailable(describe(), "没有产出 " + request.wanted().describe() + " 的 3×3 合成配方");
        }
        String recipeId = recipe.get().id().toString();
        String outputId = BuiltInRegistries.ITEM.getKey(
                recipe.get().value().getResultItem(player.level().registryAccess()).getItem()).toString();

        // 优先使用随身已绑定无线合成终端（零走路开销），其次挑附近最近的一台固定合成终端。
        Optional<String> wireless = reads.carriedLinkedWirelessCraftingTerminal(player.localPlayer());
        if (wireless.isPresent()) {
            String hint = encodeWirelessHint(wireless.get(), recipeId, outputId);
            return new SourceQuote.Offer(describe(), request.count(),
                    new AcquisitionCost(0, WIRELESS_ACTIONS),
                    "用随身无线合成终端 3×3 合成格直接调网络材料手搓（缺料不触发自动合成下单）", hint);
        }
        WorldPosition here = context.characterAt();
        BlockPos feet = new BlockPos(here.x(), here.y(), here.z());
        int radius = context.radiusBlocks() == null ? SEARCH_RADIUS_BLOCKS : Math.min(context.radiusBlocks(), SEARCH_RADIUS_BLOCKS);
        Optional<Ae2MachineReads.CraftingTerminalSpot> nearest = reads.craftingTerminalsNear(player.level(), feet, radius)
                .stream()
                .min(Comparator.comparingDouble(spot -> spot.host().distSqr(feet)));
        if (nearest.isEmpty()) {
            return new SourceQuote.Unavailable(describe(),
                    "身上没有已绑定无线合成终端，附近 " + radius + " 格内也没有 ME 合成终端");
        }
        Ae2MachineReads.CraftingTerminalSpot spot = nearest.get();
        int dist = (int) Math.sqrt(spot.host().distSqr(feet));
        String hint = encodePlacedHint(spot.host(), spot.side(), recipeId, outputId);
        return new SourceQuote.Offer(describe(), request.count(),
                new AcquisitionCost(dist, PLACED_ACTIONS),
                "走到附近 ME 合成终端（" + spot.host().toShortString() + "）直接调网络材料手搓 3×3 配方", hint);
    }

    @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        PlayerContext player = services.context().get();
        if (player == null || player.level() == null || player.localPlayer() == null) return Optional.empty();
        Optional<ParsedHint> parsed = ParsedHint.parse(offer.hint());
        if (parsed.isEmpty()) return Optional.empty();
        ParsedHint hint = parsed.get();
        Optional<RecipeHolder<CraftingRecipe>> holder = resolveRecipe(player.level(), hint.recipeId());
        if (holder.isEmpty()) return Optional.empty();
        List<ItemStack> templates9 = CraftingTermAction.templatesFor(holder.get());
        MenuOpening opening;
        if (hint.wirelessItem() != null) {
            Optional<String> carried = reads.carriedLinkedWirelessCraftingTerminal(player.localPlayer());
            if (carried.isEmpty() || !carried.get().equals(hint.wirelessItem())) return Optional.empty();
            opening = new HeldItemMenuOpening(hint.wirelessItem(), services.menuLayouts(),
                    services.toMainhand(), services.interactions(), services.context());
        } else {
            Optional<Ae2MachineReads.CraftingTerminalSpot> spot = reads.craftingTerminalsNear(
                            player.level(), hint.host(), 2).stream()
                    .filter(s -> s.host().equals(hint.host()) && s.side() == hint.side())
                    .findFirst();
            if (spot.isEmpty()) return Optional.empty();
            opening = new ClientMenuOpening(spot.get().host(), spot.get().panelBox(), context.permissions(),
                    services.menuLayouts(), services.approach(), services.interactions(), services.context());
        }
        return Optional.of(new CraftingTermAction(opening, hint.recipeId(), templates9,
                hint.outputId(), request.count(), compat, reads));
    }

    private Optional<RecipeHolder<CraftingRecipe>> findRecipe(ClientLevel level, ItemRequest request) {
        for (RecipeHolder<CraftingRecipe> holder : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            ItemStack out = holder.value().getResultItem(level.registryAccess());
            if (out.isEmpty()) continue;
            String outId = BuiltInRegistries.ITEM.getKey(out.getItem()).toString();
            if (request.wanted().matches(outId, services.itemTags().tagsOf(outId))) {
                return Optional.of(holder);
            }
        }
        return Optional.empty();
    }

    private static Optional<RecipeHolder<CraftingRecipe>> resolveRecipe(ClientLevel level, String recipeId) {
        ResourceLocation key = ResourceLocation.tryParse(recipeId);
        if (key == null) return Optional.empty();
        return level.getRecipeManager().byKey(key)
                .filter(holder -> holder.value() instanceof CraftingRecipe)
                .map(holder -> {
                    @SuppressWarnings("unchecked")
                    RecipeHolder<CraftingRecipe> cast = (RecipeHolder<CraftingRecipe>) holder;
                    return cast;
                });
    }

    static String encodeWirelessHint(String itemId, String recipeId, String outputId) {
        return "w|" + itemId + "|" + recipeId + "|" + outputId;
    }

    static String encodePlacedHint(BlockPos host, Direction side, String recipeId, String outputId) {
        return "p|" + host.getX() + "," + host.getY() + "," + host.getZ() + "," + side.getSerializedName()
                + "|" + recipeId + "|" + outputId;
    }

    record ParsedHint(String wirelessItem, BlockPos host, Direction side, String recipeId, String outputId) {
        static Optional<ParsedHint> parse(String hint) {
            if (hint == null) return Optional.empty();
            String[] parts = hint.split("\\|");
            if (parts.length != 4) return Optional.empty();
            if ("w".equals(parts[0])) {
                return Optional.of(new ParsedHint(parts[1], null, null, parts[2], parts[3]));
            }
            if ("p".equals(parts[0])) {
                String[] loc = parts[1].split(",");
                if (loc.length != 4) return Optional.empty();
                Direction dir = Direction.byName(loc[3].toLowerCase(Locale.ROOT));
                if (dir == null) return Optional.empty();
                BlockPos pos = new BlockPos(Integer.parseInt(loc[0]), Integer.parseInt(loc[1]),
                        Integer.parseInt(loc[2]));
                return Optional.of(new ParsedHint(null, pos, dir, parts[2], parts[3]));
            }
            return Optional.empty();
        }
    }
}
