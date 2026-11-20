// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.tool;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.construction.ReadsBlocks;
import org.maiwithu.maicraft.behavior.construction.spi.PlacementRules;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * AE2 石英扳手的方块转正规则：放下朝向不对的 AE2 设备方块，或需要调整样板供应器推送面（PUSH_DIRECTION）时，
 * 使用石英扳手（ae2:certus_quartz_wrench 或 ae2:nether_quartz_wrench）普通右键按 IOrientationStrategy
 * 顺时针旋转方块；显式排除网络工具（ae2:network_tool），因为 AE2 的 InteractionUtil.canWrenchRotate 明确拒绝网络工具旋转。
 */
public final class Ae2WrenchPlacementRules implements PlacementRules {

    /** 24 种三维朝向与 7 种样板供应器推送面在 6 步顺时针旋转内全部可达。 */
    static final int MAX_TURNS = 6;

    private final Ae2Compat compat;
    private final Ae2ToolReads reads;
    private final Supplier<PlayerContext> context;
    private final Function<String, Optional<Item>> itemLookup;
    private final Optional<Item> customWrench;

    public Ae2WrenchPlacementRules(Ae2Compat compat, Ae2ToolReads reads) {
        this(compat, reads, () -> null);
    }

    /** 带角色上下文供应器的构造：优先返回角色实际携带的赛特斯或下界石英扳手（避免只带下界扳手时误判缺扳手跳过转正）。 */
    public Ae2WrenchPlacementRules(Ae2Compat compat, Ae2ToolReads reads, Supplier<PlayerContext> context) {
        this(compat, reads, context, Ae2WrenchPlacementRules::lookupItem, Optional.empty());
    }

    /** 离线单测用构造：允许在未注册 AE2 物品的测试环境注入替身扳手物品。 */
    Ae2WrenchPlacementRules(Ae2Compat compat, Ae2ToolReads reads, Optional<Item> customWrench) {
        this(compat, reads, () -> null, Ae2WrenchPlacementRules::lookupItem, customWrench);
    }

    Ae2WrenchPlacementRules(Ae2Compat compat, Ae2ToolReads reads, Supplier<PlayerContext> context,
            Function<String, Optional<Item>> itemLookup, Optional<Item> customWrench) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.context = Objects.requireNonNull(context, "context");
        this.itemLookup = Objects.requireNonNull(itemLookup, "itemLookup");
        this.customWrench = Objects.requireNonNull(customWrench, "customWrench");
    }

    @Override public Optional<Placement> placementFor(BlockState state, BlockPos at, ReadsBlocks world) {
        return Optional.empty();
    }

    @Override public List<BlockPos> placeAfter(BlockState state) {
        return List.of();
    }

    // 仅使用石英扳手转正，绝不返回网络工具；宽度优先找出从 placed 旋转到 wanted 的最少点击面序列。
    @Override public Optional<Turns> turnsTo(BlockState placed, Predicate<BlockState> wanted) {
        if (placed == null || wanted == null) return Optional.empty();
        Optional<Item> wrench = resolveQuartzWrench();
        if (wrench.isEmpty()) return Optional.empty();
        return findTurns(placed, wanted,
                (state, face) -> compat.call("计算 AE2 方块旋转结果", () -> reads.rotateBlock(state, face)))
                .map(faces -> new Turns(wrench.get(), faces));
    }

    /** 判断某件物品是否允许作为 AE2 旋转工具：石英扳手允许，网络工具显式禁止。 */
    public boolean canRotateWith(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        // 显式排除 ae2:network_tool：游戏内右键网络工具打开网络状态/工具箱界面而不旋转方块。
        if (Ae2Wrench.NETWORK_TOOL.equals(id)) return false;
        if (Ae2Wrench.CERTUS_WRENCH.equals(id) || Ae2Wrench.NETHER_WRENCH.equals(id)) return true;
        return compat.call("判断物品能否旋转 AE2 方块", () -> reads.canRotateTool(stack));
    }

    /** 宽度优先寻找最少旋转点击面序列的纯函数，便于离线测试各种朝向与样板供应器推送面切换。 */
    static Optional<List<Direction>> findTurns(BlockState placed, Predicate<BlockState> wanted,
            BiFunction<BlockState, Direction, Optional<BlockState>> rotator) {
        record Node(BlockState state, List<Direction> faces) {}
        Deque<Node> queue = new ArrayDeque<>(List.of(new Node(placed, List.of())));
        Set<BlockState> seen = new HashSet<>(Set.of(placed));
        while (!queue.isEmpty()) {
            Node current = queue.poll();
            if (current.faces().size() >= MAX_TURNS) continue;
            for (Direction face : Direction.values()) {
                Optional<BlockState> next = rotator.apply(current.state(), face);
                if (next.isEmpty()) continue;
                BlockState rotated = next.get();
                if (rotated.equals(current.state())) continue;
                List<Direction> nextFaces = new ArrayList<>(current.faces().size() + 1);
                nextFaces.addAll(current.faces());
                nextFaces.add(face);
                if (wanted.test(rotated)) {
                    return Optional.of(List.copyOf(nextFaces));
                }
                if (seen.add(rotated)) {
                    queue.add(new Node(rotated, nextFaces));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 按角色当前随身物品选出用于转正的石英扳手（纯函数）：
     * 只带下界石英扳手时返回下界石英扳手，带赛特斯或两者都带时优先赛特斯石英扳手；
     * 绝不返回 ae2:network_tool（网络工具在 AE2 中不能旋转方块）。
     */
    static Optional<Item> resolveQuartzWrench(Set<String> carriedItemIds,
            Function<String, Optional<Item>> itemLookup) {
        boolean hasCertus = carriedItemIds != null && carriedItemIds.contains(Ae2Wrench.CERTUS_WRENCH);
        boolean hasNether = carriedItemIds != null && carriedItemIds.contains(Ae2Wrench.NETHER_WRENCH);
        if (hasNether && !hasCertus) {
            Optional<Item> nether = itemLookup.apply(Ae2Wrench.NETHER_WRENCH);
            return nether.isPresent() ? nether : itemLookup.apply(Ae2Wrench.CERTUS_WRENCH);
        }
        Optional<Item> certus = itemLookup.apply(Ae2Wrench.CERTUS_WRENCH);
        return certus.isPresent() ? certus : itemLookup.apply(Ae2Wrench.NETHER_WRENCH);
    }

    private Optional<Item> resolveQuartzWrench() {
        if (customWrench.isPresent()) {
            String id = BuiltInRegistries.ITEM.getKey(customWrench.get()).toString();
            return Ae2Wrench.NETWORK_TOOL.equals(id) ? Optional.empty() : customWrench;
        }
        return resolveQuartzWrench(carriedItemIds(), itemLookup);
    }

    private Set<String> carriedItemIds() {
        PlayerContext current = context.get();
        LocalPlayer self = current == null ? null : current.localPlayer();
        if (self == null) return Set.of();
        Set<String> ids = new HashSet<>();
        Inventory inventory = self.getInventory();
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            ItemStack stack = inventory.items.get(slot);
            if (!stack.isEmpty()) ids.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        }
        ItemStack offhand = self.getOffhandItem();
        if (!offhand.isEmpty()) ids.add(BuiltInRegistries.ITEM.getKey(offhand.getItem()).toString());
        return ids;
    }

    private static Optional<Item> lookupItem(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        return key == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(key);
    }
}
