// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.ae2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import appeng.api.components.ExportedUpgrades;
import appeng.api.ids.AEComponents;
import appeng.api.implementations.items.IAEItemPowerStorage;
import appeng.api.implementations.items.MemoryCardColors;
import appeng.api.orientation.BlockOrientation;
import appeng.api.orientation.IOrientationStrategy;
import appeng.api.orientation.RelativeSide;
import appeng.api.parts.IFacadePart;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.StorageCell;
import appeng.api.util.AEColor;
import appeng.block.AEBaseEntityBlock;
import appeng.block.crafting.PatternProviderBlock;
import appeng.block.crafting.PushDirection;
import appeng.block.networking.CableBusBlock;
import appeng.block.storage.SkyChestBlock;
import appeng.core.definitions.AEItems;
import appeng.core.network.serverbound.ColorApplicatorSelectColorPacket;
import appeng.datagen.providers.tags.ConventionTags;
import appeng.hooks.CompassManager;
import appeng.items.contents.PortableCellMenuHost;
import appeng.items.misc.PaintBallItem;
import appeng.items.tools.MemoryCardItem;
import appeng.items.tools.NetworkToolItem;
import appeng.items.tools.powered.ColorApplicatorItem;
import appeng.items.tools.powered.MatterCannonItem;
import appeng.items.tools.powered.PortableCellItem;
import appeng.me.cells.BasicCellInventory;
import appeng.menu.implementations.QuartzKnifeMenu;
import appeng.menu.me.common.MEStorageMenu;
import appeng.menu.me.networktool.NetworkToolMenu;
import appeng.menu.slot.RestrictedInputSlot;
import appeng.parts.BusCollisionHelper;
import appeng.recipes.AERecipeTypes;
import appeng.recipes.mattercannon.MatterCannonAmmo;
import appeng.util.InteractionUtil;
import appeng.util.Platform;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SnowballItem;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.PacketDistributor;

import org.maiwithu.maicraft.compat.ae2.tool.Ae2ToolReads;

/**
 * AE2 手持工具、便携存储元件、石英扳手、陨石罗盘与指南资源的 NeoForge 读写端：
 * 全部直接调用 AE2 19.2.17 公开接口与网络包，只翻译不判断，严禁使用反射。
 */
public final class AppliedEnergisticsTools implements Ae2ToolReads {

    private final AppliedEnergisticsToolsGuide guide = new AppliedEnergisticsToolsGuide();

    // 显式排除陨石箱子（其 disassembleWithWrench 恒返回 FAIL），其余继承 AEBaseEntityBlock 的方块可用扳手拆解。
    @Override public boolean canDisassembleBlock(BlockState state) {
        if (state == null || state.getBlock() instanceof SkyChestBlock) return false;
        return state.getBlock() instanceof AEBaseEntityBlock<?>;
    }

    @Override public boolean canDisassembleTool(ItemStack stack) {
        return stack != null && !stack.isEmpty() && InteractionUtil.canWrenchDisassemble(stack);
    }

    @Override public boolean canRotateTool(ItemStack stack) {
        return stack != null && !stack.isEmpty() && InteractionUtil.canWrenchRotate(stack);
    }

    // 样板供应器按 PatternProviderBlock.setSide 规则切推送面，其余方块按 IOrientationStrategy 绕点击面顺时针旋转。
    @Override public Optional<BlockState> rotateBlock(BlockState state, Direction clickedFace) {
        if (state == null || clickedFace == null) return Optional.empty();
        if (state.getBlock() instanceof PatternProviderBlock) {
            Direction pushSide = state.getValue(PatternProviderBlock.PUSH_DIRECTION).getDirection();
            PushDirection next;
            if (pushSide == clickedFace.getOpposite()) {
                next = PushDirection.fromDirection(clickedFace);
            } else if (pushSide == clickedFace) {
                next = PushDirection.ALL;
            } else if (pushSide == null) {
                next = PushDirection.fromDirection(clickedFace.getOpposite());
            } else {
                next = PushDirection.fromDirection(Platform.rotateAround(pushSide, clickedFace));
            }
            return Optional.of(state.setValue(PatternProviderBlock.PUSH_DIRECTION, next));
        }
        IOrientationStrategy strategy = IOrientationStrategy.get(state);
        if (!strategy.allowsPlayerRotation()) return Optional.empty();
        BlockOrientation orientation = BlockOrientation.get(strategy, state).rotateClockwiseAround(clickedFace);
        BlockState next = strategy.setOrientation(state, orientation.getSide(RelativeSide.FRONT),
                orientation.getSpin());
        return next == state ? Optional.empty() : Optional.of(next);
    }

    @Override public boolean isCableBus(BlockState state) {
        return state != null && state.getBlock() instanceof CableBusBlock;
    }

    // 枚举线缆格上的 6 面部件、中心线缆与 6 面伪装板，按 BusCollisionHelper 计算各自世界坐标选中盒。
    @Override public List<CableTarget> cableDisassembleTargets(ClientLevel level, BlockPos cell) {
        if (level == null || cell == null || !level.isLoaded(cell)) return List.of();
        if (!(level.getBlockEntity(cell) instanceof IPartHost host)) return List.of();
        List<CableTarget> targets = new ArrayList<>();
        for (Direction side : Direction.values()) {
            IPart part = host.getPart(side);
            if (part != null) {
                collectPartBox(part, side, cell).ifPresent(box ->
                        targets.add(new CableTarget(TargetKind.PART, side, box)));
            }
            IFacadePart facade = host.getFacadeContainer().getFacade(side);
            if (facade != null) {
                collectFacadeBox(facade, side, cell).ifPresent(box ->
                        targets.add(new CableTarget(TargetKind.FACADE, side, box)));
            }
        }
        IPart center = host.getPart(null);
        if (center != null) {
            collectPartBox(center, null, cell).ifPresent(box ->
                    targets.add(new CableTarget(TargetKind.CABLE, null, box)));
        }
        return List.copyOf(targets);
    }

    @Override public boolean cableTargetPresent(ClientLevel level, BlockPos cell, TargetKind kind, Direction side) {
        if (level == null || cell == null || !level.isLoaded(cell)) return false;
        if (!(level.getBlockEntity(cell) instanceof IPartHost host)) return false;
        return switch (kind) {
            case PART -> host.getPart(side) != null;
            case CABLE -> host.getPart(null) != null;
            case FACADE -> side != null && host.getFacadeContainer().getFacade(side) != null;
        };
    }

    // 免开界面读取便携存储元件的电量、字节/种类容量与客户端同步的 STORAGE_CELL_INV 库存。
    @Override public Optional<PortableCellSnapshot> readPortableCell(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof PortableCellItem cell)) {
            return Optional.empty();
        }
        boolean fluid = cell.getKeyType() == AEKeyType.fluids();
        double currentEnergy = cell.getAECurrentPower(stack);
        double maxEnergy = cell.getAEMaxPower(stack);
        long totalBytes = cell.getBytes(stack);
        int bytesPerType = cell.getBytesPerType(stack);
        int totalTypes = cell.getTotalTypes(stack);
        long usedBytes = 0L;
        int storedTypes = 0;
        List<CellStockEntry> entries = new ArrayList<>();
        StorageCell inv = StorageCells.getCellInventory(stack, null);
        if (inv instanceof BasicCellInventory basic) {
            totalBytes = basic.getTotalBytes();
            usedBytes = basic.getUsedBytes();
            storedTypes = (int) basic.getStoredItemTypes();
            totalTypes = (int) basic.getTotalItemTypes();
        }
        if (inv != null) {
            for (var entry : inv.getAvailableStacks()) {
                long amount = entry.getLongValue();
                if (amount <= 0L) continue;
                AEKey key = entry.getKey();
                if (key instanceof AEItemKey itemKey) {
                    String itemId = BuiltInRegistries.ITEM.getKey(itemKey.getItem()).toString();
                    entries.add(CellStockEntry.item(itemId, amount));
                } else if (key instanceof AEFluidKey fluidKey) {
                    String fluidId = BuiltInRegistries.FLUID.getKey(fluidKey.getFluid()).toString();
                    Item bucket = fluidKey.getFluid().getBucket();
                    String bucketId = bucket == Items.AIR ? fluidId : BuiltInRegistries.ITEM.getKey(bucket).toString();
                    entries.add(CellStockEntry.fluid(bucketId, fluidId, amount));
                }
            }
        }
        String id = BuiltInRegistries.ITEM.getKey(cell).toString();
        return Optional.of(new PortableCellSnapshot(id, fluid, currentEnergy, maxEnergy,
                totalBytes, usedBytes, bytesPerType, storedTypes, totalTypes, entries));
    }

    @Override public boolean isPortableCellMenu(AbstractContainerMenu menu, boolean fluid) {
        if (!(menu instanceof MEStorageMenu storage)) return false;
        if (!(storage.getHost() instanceof PortableCellMenuHost<?>)) return false;
        return menu.getType() == (fluid ? MEStorageMenu.PORTABLE_FLUID_CELL_TYPE : MEStorageMenu.PORTABLE_ITEM_CELL_TYPE);
    }

    @Override public boolean isMetalIngot(ItemStack stack) {
        return stack != null && !stack.isEmpty() && RestrictedInputSlot.isMetalIngot(stack);
    }

    @Override public boolean isQuartzKnifeMenu(AbstractContainerMenu menu) {
        return menu instanceof QuartzKnifeMenu;
    }

    // 直接调公开 QuartzKnifeMenu.setName：同时更新客户端 currentName 并向服务端发 GuiActionPacket("setName")，
    // 保证客户端输出槽 QuartzKniveSlot 立即可见待取的命名压印模板。
    @Override public boolean setQuartzKnifeName(AbstractContainerMenu menu, String name) {
        if (!(menu instanceof QuartzKnifeMenu knife) || name == null || name.isBlank()) return false;
        knife.setName(name);
        return true;
    }

    @Override public Optional<String> namePressName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Optional.empty();
        Component name = stack.get(AEComponents.NAME_PRESS_NAME);
        return name == null ? Optional.empty() : Optional.of(name.getString());
    }

    // 仅由已核实玩家真实携带陨石罗盘的调用方触发，按 AE2 原生方式带预取查询最近陨石。
    @Override public Optional<BlockPos> closestMeteorite(BlockPos playerPos) {
        if (playerPos == null) return Optional.empty();
        return Optional.ofNullable(CompassManager.INSTANCE.getClosestMeteorite(new ChunkPos(playerPos), true));
    }

    // 客户端直接读取全部 12 项网络同步的 AEComponents，不依赖开窗或服务端查询。
    @Override public Optional<MemoryCardSnapshot> readMemoryCard(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof MemoryCardItem)) {
            return Optional.empty();
        }
        Component sourceComp = stack.get(AEComponents.EXPORTED_SETTINGS_SOURCE);
        Component customComp = stack.get(AEComponents.EXPORTED_CUSTOM_NAME);
        ExportedUpgrades upgrades = stack.get(AEComponents.EXPORTED_UPGRADES);
        Map<String, String> settings = stack.get(AEComponents.EXPORTED_SETTINGS);
        Integer priority = stack.get(AEComponents.EXPORTED_PRIORITY);
        Item p2pItem = stack.get(AEComponents.EXPORTED_P2P_TYPE);
        Short p2pFreq = stack.get(AEComponents.EXPORTED_P2P_FREQUENCY);
        MemoryCardColors colors = stack.get(AEComponents.MEMORY_CARD_COLORS);
        List<GenericStack> configInv = stack.get(AEComponents.EXPORTED_CONFIG_INV);
        Long emitterVal = stack.get(AEComponents.EXPORTED_LEVEL_EMITTER_VALUE);
        ItemContainerContents patterns = stack.get(AEComponents.EXPORTED_PATTERNS);
        PushDirection pushDir = stack.get(AEComponents.EXPORTED_PUSH_DIRECTION);

        int upgradesCount = countUpgrades(upgrades);
        int filterCount = countNonNullStacks(configInv);
        int patternCount = countContainerItems(patterns);
        String p2pType = p2pItem == null ? "" : BuiltInRegistries.ITEM.getKey(p2pItem).toString();
        String freqHex = p2pFreq == null ? "" : String.format(Locale.ROOT, "%04X", p2pFreq & 0xFFFF);
        String colorSummary = formatCardColors(colors);
        String pushName = pushDir == null ? "" : pushDir.getSerializedName();
        boolean hasAny = sourceComp != null || customComp != null || upgrades != null
                || (settings != null && !settings.isEmpty()) || priority != null || p2pItem != null
                || p2pFreq != null || colors != null || filterCount > 0 || emitterVal != null
                || patternCount > 0 || pushDir != null;

        return Optional.of(new MemoryCardSnapshot(
                sourceComp == null ? "" : sourceComp.getString(),
                customComp == null ? "" : customComp.getString(),
                priority,
                p2pType,
                freqHex,
                colorSummary,
                upgradesCount,
                settings == null ? Map.of() : settings,
                filterCount,
                emitterVal,
                patternCount,
                pushName,
                hasAny));
    }

    // 直接只读 DataComponents.CONTAINER，避免调用 NetworkToolItem.getInventory 往原始堆叠回写空容器组件。
    @Override public List<ItemStack> readNetworkToolbox(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof NetworkToolItem)) {
            return List.of();
        }
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents == null) return List.of();
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack item : contents.nonEmptyItems()) {
            out.add(item.copy());
        }
        return List.copyOf(out);
    }

    @Override public boolean isNetworkToolMenu(AbstractContainerMenu menu) {
        return menu instanceof NetworkToolMenu;
    }

    @Override public Optional<EnergyReading> readEnergy(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof IAEItemPowerStorage storage)) {
            return Optional.empty();
        }
        return Optional.of(new EnergyReading(storage.getAECurrentPower(stack), storage.getAEMaxPower(stack)));
    }

    // 物质炮弹药权重经客户端 RecipeManager.byType(AERecipeTypes.MATTER_CANNON_AMMO) 查询，绝不触碰服务端单例。
    @Override public Optional<MatterCannonSnapshot> readMatterCannon(ItemStack stack, Level level) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof MatterCannonItem cannon)) {
            return Optional.empty();
        }
        double current = cannon.getAECurrentPower(stack);
        double max = cannon.getAEMaxPower(stack);
        int speedCards = cannon.getUpgrades(stack).getInstalledUpgrades(AEItems.SPEED_CARD);
        List<CannonAmmoEntry> entries = new ArrayList<>();
        StorageCell inv = StorageCells.getCellInventory(stack, null);
        if (inv != null) {
            for (var entry : inv.getAvailableStacks()) {
                if (entry.getLongValue() <= 0L || !(entry.getKey() instanceof AEItemKey itemKey)) continue;
                boolean paintBall = itemKey.getItem() instanceof PaintBallItem;
                float weight = paintBall ? 0.0f : lookupAmmoWeight(level, itemKey);
                String id = BuiltInRegistries.ITEM.getKey(itemKey.getItem()).toString();
                entries.add(new CannonAmmoEntry(id, entry.getLongValue(), weight, paintBall));
            }
        }
        return Optional.of(new MatterCannonSnapshot(current, max, speedCards, entries));
    }

    @Override public long matterCannonTotalAmmo(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof MatterCannonItem)) {
            return 0L;
        }
        StorageCell inv = StorageCells.getCellInventory(stack, null);
        if (inv == null) return 0L;
        long total = 0L;
        for (var entry : inv.getAvailableStacks()) {
            if (entry.getLongValue() > 0L) total += entry.getLongValue();
        }
        return total;
    }

    @Override public Optional<ColorApplicatorSnapshot> readColorApplicator(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof ColorApplicatorItem applicator)) {
            return Optional.empty();
        }
        double current = applicator.getAECurrentPower(stack);
        double max = applicator.getAEMaxPower(stack);
        // 对副本调 getActiveColor，防止未设 SELECTED_COLOR 时 AE2 findNextColor 顺手写改客户端原始物品组件。
        AEColor active = applicator.getActiveColor(stack.copy());
        String activeName = active == null ? "" : normalizeAeColor(active);
        Map<String, Long> pigments = new LinkedHashMap<>();
        StorageCell inv = StorageCells.getCellInventory(stack, null);
        if (inv != null) {
            for (var entry : inv.getAvailableStacks()) {
                if (entry.getLongValue() <= 0L) continue;
                AEColor color = colorOfKey(entry.getKey());
                if (color != null) {
                    pigments.merge(normalizeAeColor(color), entry.getLongValue(), Long::sum);
                }
            }
        }
        return Optional.of(new ColorApplicatorSnapshot(current, max, activeName, pigments));
    }

    @Override public boolean selectApplicatorColor(String colorName) {
        AEColor color = parseAeColor(colorName);
        if (color == null) return false;
        PacketDistributor.sendToServer(new ColorApplicatorSelectColorPacket(color));
        return true;
    }

    @Override public List<String> listGuidePages() {
        return guide.listPages();
    }

    @Override public Optional<String> readGuidePage(String relativePath) {
        return guide.readPage(relativePath);
    }

    private static float lookupAmmoWeight(Level level, AEItemKey itemKey) {
        if (level == null) return 0.0f;
        for (RecipeHolder<MatterCannonAmmo> holder
                : level.getRecipeManager().getAllRecipesFor(AERecipeTypes.MATTER_CANNON_AMMO)) {
            MatterCannonAmmo recipe = holder.value();
            if (itemKey.matches(recipe.getAmmo())) {
                return recipe.getWeight();
            }
        }
        return 0.0f;
    }

    private static Optional<AABB> collectPartBox(IPart part, Direction side, BlockPos cell) {
        List<AABB> boxes = new ArrayList<>();
        part.getBoxes(new BusCollisionHelper(boxes, side, true));
        return mergeBoxes(boxes, cell);
    }

    private static Optional<AABB> collectFacadeBox(IFacadePart facade, Direction side, BlockPos cell) {
        List<AABB> boxes = new ArrayList<>();
        facade.getBoxes(new BusCollisionHelper(boxes, side, true), false);
        return mergeBoxes(boxes, cell);
    }

    private static Optional<AABB> mergeBoxes(List<AABB> boxes, BlockPos cell) {
        if (boxes.isEmpty()) return Optional.empty();
        AABB merged = boxes.getFirst();
        for (AABB box : boxes) merged = merged.minmax(box);
        return Optional.of(merged.move(cell));
    }

    private static int countUpgrades(ExportedUpgrades upgrades) {
        if (upgrades == null) return 0;
        int sum = 0;
        for (ItemStack stack : upgrades.upgrades()) sum += stack.getCount();
        return sum;
    }

    private static int countNonNullStacks(List<GenericStack> list) {
        if (list == null) return 0;
        int count = 0;
        for (GenericStack stack : list) {
            if (stack != null && stack.amount() > 0L) count++;
        }
        return count;
    }

    private static int countContainerItems(ItemContainerContents contents) {
        if (contents == null) return 0;
        int count = 0;
        for (ItemStack stack : contents.nonEmptyItems()) count += stack.getCount();
        return count;
    }

    private static String formatCardColors(MemoryCardColors colors) {
        if (colors == null || colors.equals(MemoryCardColors.DEFAULT)) return "";
        return normalizeAeColor(colors.top1()) + "," + normalizeAeColor(colors.top2()) + ","
                + normalizeAeColor(colors.top3()) + "," + normalizeAeColor(colors.top4()) + "/"
                + normalizeAeColor(colors.bottom1()) + "," + normalizeAeColor(colors.bottom2()) + ","
                + normalizeAeColor(colors.bottom3()) + "," + normalizeAeColor(colors.bottom4());
    }

    private static AEColor colorOfKey(AEKey key) {
        if (key instanceof AEItemKey itemKey) {
            Item item = itemKey.getItem();
            if (item instanceof SnowballItem) return AEColor.TRANSPARENT;
            if (item instanceof PaintBallItem paintBall) return paintBall.getColor();
            if (item instanceof DyeItem dyeItem) return AEColor.fromDye(dyeItem.getDyeColor());
            for (AEColor color : AEColor.VALID_COLORS) {
                if (item.builtInRegistryHolder().is(ConventionTags.dye(color.dye))) return color;
            }
        } else if (key instanceof AEFluidKey fluidKey && fluidKey.isTagged(FluidTags.WATER)) {
            return AEColor.TRANSPARENT;
        }
        return null;
    }

    private static String normalizeAeColor(AEColor color) {
        return color == AEColor.TRANSPARENT ? "transparent" : color.name().toLowerCase(Locale.ROOT);
    }

    private static AEColor parseAeColor(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        if ("transparent".equals(lower) || "fluix".equals(lower)) return AEColor.TRANSPARENT;
        for (AEColor color : AEColor.VALID_COLORS) {
            if (color.name().toLowerCase(Locale.ROOT).equals(lower)) return color;
        }
        return null;
    }
}
