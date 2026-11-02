// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore.cave;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.behavior.navigation.util.BlockPassability;
import org.maiwithu.maicraft.behavior.perception.ClientCaveOpeningSight;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.game.world.ObservationVisibility;
import org.maiwithu.maicraft.game.world.ScanTargets;
import org.maiwithu.maicraft.game.world.TargetIndex;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 钻洞现场的生产读端：量路读客户端世界的方块与天空光，看矿走方块扫描服务按 #c:ores 标签找露在外面的矿，
 * 再一格一格过视线闸——索引里有、但被墙挡着的不算看见。只读，不动身体、不改方块。
 */
public final class ClientCaveWorld implements CaveWorld {

    /** 矿的通用标签：原版矿和模组矿都挂在下面，两个加载器都有。 */
    private static final String ORES_TAG = "c:ores";
    /** 一次最多拿几处矿与留意的方块：一眼看过去的洞壁上露出来的不会更多。 */
    private static final int WANT = 256;
    /** 一次最多拿几格岩浆：一片岩浆湖几百格，和矿分开查，不让它把矿挤出名额。 */
    private static final int LAVA_WANT = 32;
    /** 索引一刻最多补建多少：与采掘扫描同一档。 */
    private static final int BUILD_BUDGET = 4096;

    private final Supplier<PlayerContext> context;
    private final BlockScanService scans;
    /** 要查的方块按维度补登记进扫描索引：没登记的方块索引里查不到。 */
    private final ScanTargets registered;

    public ClientCaveWorld(Supplier<PlayerContext> context, BlockScanService scans) {
        this.context = Objects.requireNonNull(context, "context");
        this.scans = Objects.requireNonNull(scans, "scans");
        this.registered = new ScanTargets(scans);
    }

    @Override
    public WorldPosition currentSpot() {
        LocalPlayer player = player();
        if (player == null) {
            return null;
        }
        return new WorldPosition((int) Math.floor(player.getX()), (int) Math.floor(player.getY()),
                (int) Math.floor(player.getZ()), player.level().dimension().location().toString());
    }

    @Override
    public Float facingYaw() {
        LocalPlayer player = player();
        return player == null ? null : player.getYRot();
    }

    @Override
    public boolean loaded(BlockPos cell) {
        ClientLevel level = level();
        return level != null && level.isLoaded(cell);
    }

    @Override
    public boolean standable(BlockPos feet) {
        ClientLevel level = level();
        return level != null && level.isLoaded(feet) && BlockPassability.isDryStandable(level, feet);
    }

    @Override
    public boolean roomy(BlockPos cell) {
        ClientLevel level = level();
        return level != null && level.isLoaded(cell) && ClientCaveOpeningSight.roomy(level, cell);
    }

    @Override
    public Liquid liquid(BlockPos cell) {
        ClientLevel level = level();
        if (level == null || !level.isLoaded(cell)) {
            return Liquid.NONE;
        }
        FluidState fluid = level.getFluidState(cell);
        if (fluid.is(FluidTags.LAVA)) {
            return Liquid.LAVA;
        }
        return fluid.is(FluidTags.WATER) ? Liquid.WATER : Liquid.NONE;
    }

    @Override
    public int skyLight(BlockPos cell) {
        ClientLevel level = level();
        return level == null ? 0 : level.getBrightness(LightLayer.SKY, cell);
    }

    @Override
    public boolean visible(BlockPos cell) {
        return ObservationVisibility.point(player(), new Vec3(cell.getX() + 0.5, cell.getY() + 0.5, cell.getZ() + 0.5));
    }

    @Override
    public boolean prepareSight(int radius, List<String> interestBlocks) {
        // 看一眼的两秒里每刻问一次：索引一刻补一点，这一片都补完了才算扫稳。
        ClientLevel level = level();
        LocalPlayer player = player();
        if (level == null || player == null) {
            return true;
        }
        boolean oresDone = query(level, player.blockPosition(), radius, targets(interestBlocks), WANT).complete();
        boolean lavaDone = query(level, player.blockPosition(), radius, Set.of(Blocks.LAVA), LAVA_WANT).complete();
        return oresDone && lavaDone;
    }

    @Override
    public List<Sighting> blocksInSight(int radius, List<String> interestBlocks) {
        ClientLevel level = level();
        LocalPlayer player = player();
        if (level == null || player == null) {
            return List.of();
        }
        BlockPos center = player.blockPosition();
        Set<Block> interests = interestTargets(interestBlocks);
        Set<Block> ores = new LinkedHashSet<>(oreBlocks());
        Set<Block> wanted = targets(interestBlocks);
        List<BlockPos> hits = new ArrayList<>(query(level, center, radius, wanted, WANT).hits());
        hits.addAll(query(level, center, radius, Set.of(Blocks.LAVA), LAVA_WANT).hits());
        hits.removeIf(hit -> Math.sqrt(hit.distSqr(center)) > radius);
        hits.sort(Comparator.comparingDouble(hit -> hit.distSqr(center)));
        // 索引只说"露在外面"，看没看见还要一格一格过视线闸：洞壁上露着、但隔着一道墙的不算。
        List<Sighting> seen = new ArrayList<>();
        Map<BlockPos, Boolean> checked = new HashMap<>();
        for (BlockPos hit : hits) {
            if (checked.putIfAbsent(hit, Boolean.TRUE) != null || !ObservationVisibility.block(player, hit)) {
                continue;
            }
            Block block = level.getBlockState(hit).getBlock();
            seen.add(new Sighting(BuiltInRegistries.BLOCK.getKey(block).toString(), hit,
                    ores.contains(block), block == Blocks.LAVA, interests.contains(block)));
        }
        return seen;
    }

    // 查索引：先登记，只要露在外面的，半径换成区块圈数。
    private TargetIndex.Result query(ClientLevel level, BlockPos center, int radius, Set<Block> targets,
            int want) {
        registered.ensure(level, targets);
        return scans.query(level, center, targets, want, Math.max(1, radius / 16 + 1), BUILD_BUDGET,
                Set.of(), false, true);
    }

    // 要看的方块：挂在 #c:ores 下的矿，加上留意清单里的。
    private Set<Block> targets(List<String> interestBlocks) {
        Set<Block> targets = new LinkedHashSet<>(oreBlocks());
        targets.addAll(interestTargets(interestBlocks));
        return targets;
    }

    private static Set<Block> oreBlocks() {
        Set<Block> ores = new LinkedHashSet<>();
        BuiltInRegistries.BLOCK.getTag(TagKey.create(Registries.BLOCK, ResourceLocation.parse(ORES_TAG)))
                .ifPresent(tag -> tag.forEach(holder -> ores.add(holder.value())));
        return ores;
    }

    // 留意清单展开成方块：# 开头按方块标签展开，其余按注册 ID；认不出的能力做决定时已经报过，这里跳过。
    private static Set<Block> interestTargets(List<String> selectors) {
        Set<Block> blocks = new LinkedHashSet<>();
        for (String selector : selectors) {
            String spec = selector.toLowerCase(Locale.ROOT);
            if (spec.startsWith("#")) {
                ResourceLocation tagId = ResourceLocation.tryParse(spec.substring(1));
                if (tagId != null) {
                    BuiltInRegistries.BLOCK.getTag(TagKey.create(Registries.BLOCK, tagId))
                            .ifPresent(tag -> tag.forEach(holder -> blocks.add(holder.value())));
                }
                continue;
            }
            ResourceLocation id = ResourceLocation.tryParse(spec);
            if (id != null) {
                BuiltInRegistries.BLOCK.getOptional(id).ifPresent(blocks::add);
            }
        }
        return blocks;
    }

    private LocalPlayer player() {
        PlayerContext current = context.get();
        return current == null ? null : current.localPlayer();
    }

    private ClientLevel level() {
        PlayerContext current = context.get();
        return current == null ? null : current.level();
    }
}
