// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.machine;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * AE2 工作方块与自动合成界面的客户端读写接缝：由 NeoForge 读写端实现并直接调用 AE2 公开接口，
 * common 层只做判断、编排与状态核对，不触碰任何模组类，也绝不使用反射。
 */
public interface Ae2MachineReads {

    /** 未接入读写端时的空实现：所有读数给空，发包全部返回 false。 */
    Ae2MachineReads NONE = new Ae2MachineReads() {};

    /** 充能器客户端同步视图：正面朝向、是否工作中、槽 0 物品与带电工具电量。 */
    record ChargerView(Direction front, boolean working, String slotItemId, int slotCount,
            boolean chargeable, double itemEnergy, double itemEnergyMax) {
        public boolean slotEmpty() {
            return slotItemId == null || slotItemId.isEmpty() || slotCount <= 0;
        }
        public boolean fullyCharged() {
            return chargeable && itemEnergyMax > 0 && itemEnergy + 0.01 >= itemEnergyMax;
        }
    }

    /** 木质曲柄客户端同步视图：朝向、附着主机格、可视旋转角与当前是否可继续摇动。 */
    record CrankView(Direction facing, BlockPos attachedTo, float visibleRotation, boolean canTurn) {}

    /** 充能器转化配方视图：配方原料示例物品与产物 ID。 */
    record ChargerRecipeView(String inputItemId, String outputItemId) {}

    /** 随身带电物品的电量视图。 */
    record ItemPowerView(double current, double max) {
        public boolean full() {
            return max > 0 && current + 0.01 >= max;
        }
    }

    /** 振动室客户端同步视图：是否处于燃烧发电状态。 */
    record VibrationView(boolean active) {}

    /** ME 箱子客户端同步视图：通电在线态、槽内元件 ID、元件状态、颜色与相对顶面。 */
    record MeChestView(boolean online, String cellItemId, String cellStatus, String color, Direction topFace) {}

    /** 陨石储罐客户端同步视图：流体 ID、当前毫桶数与容量（16,000 mB）。 */
    record TankView(String fluidId, int amount, int capacity) {}

    /** 水晶催生器客户端同步视图：供电状态与前后两面连网方向。 */
    record GrowthView(boolean powered, Direction front, Direction back) {}

    /** 分子组装室客户端同步视图：供电状态。 */
    record AssemblerView(boolean powered) {}

    /** 振动室打开界面时的同步视图。 */
    record VibrationMenuView(int remainingBurnPercent, double powerPerTick, double fuelEfficiency,
            String fuelItemId, int fuelCount, List<String> upgrades) {}

    /** 物质聚合器打开界面时的同步视图。 */
    record CondenserMenuView(String mode, long storedPower, long requiredEnergy,
            String storageComponentId, String outputItemId, int outputCount) {}

    /** 样板供应器打开界面时的同步视图。 */
    record PatternProviderMenuView(String blockingMode, String lockCraftingMode, String showInAccessTerminal,
            String craftingLockedReason, String unlockStack) {}

    /** 分子组装室打开界面时的同步视图。 */
    record AssemblerMenuView(int craftProgress, boolean hasPattern, String patternOutput, List<String> upgrades) {}

    /** 压印器打开界面时的同步视图。 */
    record InscriberMenuView(String separateSides, String autoExport, String inputCapacity,
            int processingTime, int maxProcessingTime, List<String> upgrades) {}

    /** 合成状态列表中的单台 CPU 条目。 */
    record CpuEntryView(int serial, long storage, int coProcessors, String name, String mode,
            String currentJob, float progress, long elapsedTimeNanos) {}

    /** 合成 CPU 或合成状态界面打开时的同步视图。 */
    record CraftingCpuMenuView(String schedulingMode, boolean cantStoreItems, boolean statusMenu,
            int selectedCpuSerial, List<CpuEntryView> cpus) {}

    /** 样板编码终端打开界面时的同步视图。 */
    record PatternEncodingMenuView(String mode, boolean substitute, boolean substituteFluids,
            String stonecuttingRecipeId, List<String> stonecuttingRecipes,
            String craftingResultId, int blankPatternCount, String encodedPatternOutputId,
            boolean hasEncodedPattern, boolean processingInputNonEmpty, boolean processingPrimaryOutputNonEmpty,
            boolean smithingValid) {}

    /** 样板管理终端中当前可见的一台样板容器记录（经公开 PatternSlot 读取，零反射）。 */
    record VisiblePatternContainer(long serverId, String groupName, int totalSlots, int occupiedSlots) {}

    /** 样板管理终端打开界面时的同步视图。 */
    record PatternAccessMenuView(int containerId, String showProviders, boolean linked,
            List<VisiblePatternContainer> visibleContainers) {}

    /** 合成终端或无线合成终端打开界面时的 3×3 合成格视图。 */
    record CraftingTermMenuView(boolean linked, String resultItemId, int resultCount, boolean gridOccupied) {}

    /** 附近已加载的合成终端部件定位。 */
    record CraftingTerminalSpot(BlockPos host, Direction side, AABB panelBox, String partId) {}

    default Optional<ChargerView> chargerAt(ClientLevel level, BlockPos at) { return Optional.empty(); }
    default Optional<CrankView> crankAt(ClientLevel level, BlockPos at) { return Optional.empty(); }
    default Optional<ChargerRecipeView> chargerRecipeForOutput(ClientLevel level, String outputItemId) { return Optional.empty(); }
    default Optional<ItemPowerView> itemPower(ItemStack stack) { return Optional.empty(); }
    default Optional<VibrationView> vibrationAt(ClientLevel level, BlockPos at) { return Optional.empty(); }
    default Optional<MeChestView> meChestAt(ClientLevel level, BlockPos at) { return Optional.empty(); }
    default Optional<TankView> skyStoneTankAt(ClientLevel level, BlockPos at) { return Optional.empty(); }
    default Optional<GrowthView> growthAcceleratorAt(ClientLevel level, BlockPos at) { return Optional.empty(); }
    default Optional<AssemblerView> molecularAssemblerAt(ClientLevel level, BlockPos at) { return Optional.empty(); }
    default Optional<String> patternProviderPushDirection(ClientLevel level, BlockPos at) { return Optional.empty(); }
    default Optional<AABB> partBox(ClientLevel level, BlockPos host, Direction side) { return Optional.empty(); }
    default Set<Direction> crankAttachableFaces(BlockState hostState, ClientLevel level, BlockPos hostPos) { return Set.of(); }
    default Optional<Direction> crankFacing(BlockState crankState) { return Optional.empty(); }
    default boolean isWrenchRotateTool(ItemStack stack) { return false; }

    default Optional<VibrationMenuView> openVibrationMenu() { return Optional.empty(); }
    default Optional<CondenserMenuView> openCondenserMenu() { return Optional.empty(); }
    default Optional<PatternProviderMenuView> openPatternProviderMenu() { return Optional.empty(); }
    default Optional<Integer> openPriorityMenu() { return Optional.empty(); }
    default boolean sendSetPriority(int priority) { return false; }
    default Optional<AssemblerMenuView> openMolecularAssemblerMenu() { return Optional.empty(); }
    default Optional<InscriberMenuView> openInscriberMenu() { return Optional.empty(); }
    default Optional<CraftingCpuMenuView> openCraftingCpuMenu() { return Optional.empty(); }
    default boolean sendCpuCancel() { return false; }
    default boolean sendCpuTogglePause() { return false; }
    default boolean sendCpuSelect(int serial) { return false; }

    default Optional<PatternEncodingMenuView> openPatternEncodingMenu() { return Optional.empty(); }
    default boolean sendEncodeMode(String mode) { return false; }
    default boolean sendEncodeSubstitute(boolean substitute) { return false; }
    default boolean sendEncodeSubstituteFluids(boolean substituteFluids) { return false; }
    default boolean sendEncodeStonecuttingRecipe(String recipeId) { return false; }
    default boolean sendEncodeClear() { return false; }
    default boolean sendEncodeCommit() { return false; }
    default ItemStack wrapGenericStack(String kind, String id, long amount) { return ItemStack.EMPTY; }

    default Optional<PatternAccessMenuView> openPatternAccessMenu() { return Optional.empty(); }
    default boolean sendQuickMovePattern(int containerId, int playerSlotIndex, List<Long> allowedServerIds) { return false; }

    default Optional<CraftingTermMenuView> openCraftingTermMenu() { return Optional.empty(); }
    default boolean sendFillCraftingGrid(String recipeId, List<ItemStack> templates9) { return false; }
    default boolean sendCraftResult(int outputSlotIndex, boolean shift) { return false; }
    default boolean sendClearCraftingGrid(boolean toPlayerInventory) { return false; }
    default List<CraftingTerminalSpot> craftingTerminalsNear(ClientLevel level, BlockPos center, int radius) {
        return List.of();
    }
    default Optional<String> carriedLinkedWirelessCraftingTerminal(Player player) { return Optional.empty(); }
}
