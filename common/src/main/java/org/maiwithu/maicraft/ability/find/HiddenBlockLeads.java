// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.find;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.behavior.acquire.mining.NaturalBlocks;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryRecord;
import org.maiwithu.maicraft.behavior.worldmemory.RememberedRegion;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.world.GeneratedTerrain;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 找方块时的近处被遮挡线索账本：仅在严格不透视未知地下自然矿脉的前提下，
 * 对玩家近距（32 格以内）或已探访、已记住建筑与区域内被墙体或屋檐挡住的功能块与结构块
 * （例如神龛、祭坛、机器、容器）跨轮次收集遮挡位置，并在无可见命中或数量不足时给出线索。
 *
 * <p>自然矿石与天然石土地形方块一律拒绝纳入线索，防止把埋在墙后或地下洞穴里的矿脉透视泄露给调用方。
 */
final class HiddenBlockLeads {

    /** 近距遮挡线索的半径上限（格）：超过此距离且不在已记住建筑或区域内的被挡方块不给线索。 */
    static final int NEAR_HIDDEN_RADIUS = 32;
    /** 已记住地点、建筑、机器或设施周边算作"已探访建筑区"的半径（格）。 */
    static final int REMEMBERED_PLACE_RADIUS = 24;

    private static final TagKey<Block> C_ORES = TagKey.create(
            Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("c", "ores"));
    private static final List<TagKey<Block>> VANILLA_ORE_TAGS = List.of(
            BlockTags.COAL_ORES,
            BlockTags.IRON_ORES,
            BlockTags.GOLD_ORES,
            BlockTags.DIAMOND_ORES,
            BlockTags.EMERALD_ORES,
            BlockTags.REDSTONE_ORES,
            BlockTags.LAPIS_ORES,
            BlockTags.COPPER_ORES);
    // 数据包标签尚未绑定时（例如离线单测或进服首刻）对原版天然基础地形方块做实例兜底，严守不透视自然地形的红线。
    private static final Set<Block> VANILLA_TERRAIN_BLOCKS = Set.of(
            Blocks.STONE, Blocks.DEEPSLATE, Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.COARSE_DIRT,
            Blocks.PODZOL, Blocks.MYCELIUM, Blocks.ROOTED_DIRT, Blocks.MUD,
            Blocks.SAND, Blocks.RED_SAND, Blocks.GRAVEL, Blocks.SANDSTONE, Blocks.RED_SANDSTONE,
            Blocks.NETHERRACK, Blocks.END_STONE, Blocks.BEDROCK, Blocks.CLAY, Blocks.TUFF,
            Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE, Blocks.CALCITE, Blocks.DRIPSTONE_BLOCK,
            Blocks.SOUL_SAND, Blocks.SOUL_SOIL, Blocks.BASALT, Blocks.BLACKSTONE,
            Blocks.SNOW_BLOCK, Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE);

    private final Set<BlockPos> hidden = new HashSet<>();
    private BlockPos center;
    private List<String> targetIds = List.of();
    private int want = -1;
    private int radius = -1;

    /** 查询参数变化时清空旧账本：位置、目标、数量或半径任一变化都重新统计遮挡线索。 */
    void followQuery(BlockPos queryCenter, List<String> selectors, int count, int searchRadius) {
        boolean same = queryCenter != null && queryCenter.equals(center)
                && selectors.equals(targetIds) && count == want && searchRadius == radius;
        if (same) {
            return;
        }
        center = queryCenter;
        targetIds = List.copyOf(selectors);
        want = count;
        radius = searchRadius;
        hidden.clear();
    }

    /** 这一格若从遮挡变为可见并命中，从被遮挡集合移除，避免同一格既算找到又算被挡住。 */
    void markVisible(BlockPos pos) {
        hidden.remove(pos);
    }

    /** 生产入口：判断被视线挡住的方块状态是否允许记入遮挡线索，允许则收进当前查询账本。 */
    void recordIfEligible(BlockPos pos, BlockState state, BlockPos queryCenter, String dimension, WorldMemory memory) {
        recordIfEligible(pos, isNaturalOreOrTerrain(state), queryCenter, dimension, memory);
    }

    /** 离线可测入口：满足非自然矿石/地形且处于近距或已记住建筑/区域内时，记入当前查询的遮挡线索。 */
    void recordIfEligible(BlockPos pos, boolean naturalOreOrTerrain, BlockPos queryCenter,
            String dimension, WorldMemory memory) {
        // 严守防透视红线：自然矿石与天然地形方块哪怕就在墙后也不泄露坐标线索。
        if (naturalOreOrTerrain || pos == null || queryCenter == null) {
            return;
        }
        if (withinAllowedArea(pos, queryCenter, dimension, memory)) {
            hidden.add(pos.immutable());
        }
    }

    /** 生成与实体搜索一致风格的遮挡线索文案；没有符合条件的被遮挡方块时返回 null。 */
    String lead(BlockPos queryCenter) {
        if (hidden.isEmpty() || queryCenter == null) {
            return null;
        }
        BlockPos nearest = null;
        double nearestDistance = 0;
        for (BlockPos pos : hidden) {
            double distance = Math.sqrt(pos.distSqr(queryCenter));
            if (nearest == null || distance < nearestDistance) {
                nearest = pos;
                nearestDistance = distance;
            }
        }
        return "近处另有 " + hidden.size() + " 处被墙体挡住、看不见，最近一处在 ("
                + nearest.getX() + ", " + nearest.getY() + ", " + nearest.getZ()
                + ")，约 " + Math.round(nearestDistance) + " 格，走过去看得见了再找";
    }

    /**
     * 判断方块状态是不是自然矿石或天然地形：原版矿石标签、通用矿石标签、远古残骸、下界矿石、
     * 天然石土地形以及命名符合矿石规律的方块都算禁止透视的对象。
     */
    static boolean isNaturalOreOrTerrain(BlockState state) {
        if (state == null) {
            return true;
        }
        if (state.is(C_ORES) || state.is(Blocks.ANCIENT_DEBRIS)
                || state.is(Blocks.NETHER_QUARTZ_ORE) || state.is(Blocks.NETHER_GOLD_ORE)) {
            return true;
        }
        for (TagKey<Block> oreTag : VANILLA_ORE_TAGS) {
            if (state.is(oreTag)) {
                return true;
            }
        }
        if (VANILLA_TERRAIN_BLOCKS.contains(state.getBlock())
                || NaturalBlocks.terrain(state, GeneratedTerrain.NONE)) {
            return true;
        }
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return isOreBlockId(blockId);
    }

    /** 按方块注册 ID 兜底识别未挂矿石标签的矿石方块，避免漏拦模组矿脉。 */
    static boolean isOreBlockId(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return false;
        }
        String lower = blockId.toLowerCase(Locale.ROOT);
        String path = lower.contains(":") ? lower.substring(lower.indexOf(':') + 1) : lower;
        return path.endsWith("_ore") || path.startsWith("ore_") || path.contains("_ore_")
                || path.equals("ancient_debris") || path.equals("gilded_blackstone");
    }

    /** 判断被遮挡的方块是否位于近距 32 格以内，或落在已探访、已记住的建筑与区域范围内。 */
    static boolean withinAllowedArea(BlockPos pos, BlockPos queryCenter, String dimension, WorldMemory memory) {
        if (pos.distSqr(queryCenter) <= (double) NEAR_HIDDEN_RADIUS * NEAR_HIDDEN_RADIUS) {
            return true;
        }
        if (memory == null) {
            return false;
        }
        WorldPosition spot = new WorldPosition(pos.getX(), pos.getY(), pos.getZ(), dimension);
        for (RememberedRegion region : memory.regions()) {
            if (region.contains(spot)) {
                return true;
            }
        }
        for (WorldPosition place : memory.places().values()) {
            if (sameDimension(place, dimension) && withinDistance(pos, place, REMEMBERED_PLACE_RADIUS)) {
                return true;
            }
        }
        for (MemoryRecord record : memory.recordsNear(spot, REMEMBERED_PLACE_RADIUS)) {
            if (isBuiltMemory(record.kind())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBuiltMemory(MemoryKind kind) {
        return kind == MemoryKind.BUILDING || kind == MemoryKind.MACHINE
                || kind == MemoryKind.WORKSTATION || kind == MemoryKind.CONTAINER;
    }

    private static boolean sameDimension(WorldPosition position, String dimension) {
        return position.dimension() == null || dimension == null || position.dimension().equals(dimension);
    }

    private static boolean withinDistance(BlockPos pos, WorldPosition other, int maxBlocks) {
        double dx = pos.getX() - other.x();
        double dy = pos.getY() - other.y();
        double dz = pos.getZ() - other.z();
        return dx * dx + dy * dy + dz * dz <= (double) maxBlocks * maxBlocks;
    }
}
