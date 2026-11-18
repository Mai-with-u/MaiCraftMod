// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import org.maiwithu.maicraft.behavior.permission.ReadsBlockOwnership;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryRecord;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.game.world.ClientLightLevels;
import org.maiwithu.maicraft.game.world.FieldOfView;
import org.maiwithu.maicraft.game.world.ScanTargets;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 看见罕见目标的客户端观察读端：每刻从周围渲染实体里认 Boss，每隔半秒从方块扫描索引里找眼前看得清的天然稀有矿脉，
 * 交给 {@link RareSightings} 去重并发事件。
 */
public final class ClientRareSightingWatch {

    /** 通用 Boss 实体标签（NeoForge 与 Fabric 共用）。 */
    private static final TagKey<EntityType<?>> BOSSES_TAG =
            TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath("c", "bosses"));
    /** 通用矿石方块标签。 */
    private static final TagKey<Block> ORES_TAG =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("c", "ores"));

    /** 稀有矿脉每隔这么多刻查一轮（半秒）：玩家转头路过足以当场捕捉，无需每刻重复查方块。C 玩家常识，来源：视线采样节奏。 */
    private static final int ORE_CHECK_INTERVAL_TICKS = 10;
    /** 一次最多取几格露在外面的稀有矿候选。C 玩家常识，来源：单眼视野矿脉上限。 */
    private static final int ORE_WANT = 48;
    /** 查稀有矿的区块半径（16 格视距落在周围 2 圈区块内）。F 游戏事实，来源：区块尺寸换算。 */
    private static final int ORE_CHUNK_RADIUS = 2;
    /** 扫描索引每刻补建预算，与设施扫描一致。F 游戏事实，来源：客户端单刻预算。 */
    private static final int BUILD_BUDGET = 4096;

    private final BlockScanService scans;
    private final ScanTargets registered;
    private final SeenRegistry seenIds;
    private final ReadsBlockOwnership ownership;
    private final RareSightings sightings;
    private Set<Block> rareOreBlocks;
    private long lastOreCheckTick = Long.MIN_VALUE;

    public ClientRareSightingWatch(BlockScanService scans, SeenRegistry seenIds, ReadsBlockOwnership ownership,
                                   WorldMemory memory, TaskEventSink events) {
        this.scans = Objects.requireNonNull(scans, "scans");
        this.registered = new ScanTargets(scans);
        this.seenIds = Objects.requireNonNull(seenIds, "seenIds");
        this.ownership = Objects.requireNonNull(ownership, "ownership");
        Objects.requireNonNull(memory, "memory");
        this.sightings = new RareSightings(events, memory, (spot, blockId) -> alreadyInWorldMemory(memory, spot, blockId));
    }

    /** 每刻随世界扫描推进：先看周围有没有 Boss 进入视野，到半秒间隔再看眼前有没有露出来的稀有矿脉。 */
    public void tick(PlayerContext current, long gameTick, Instant when) {
        if (current == null || current.localPlayer() == null || current.level() == null) {
            return;
        }
        LocalPlayer player = current.localPlayer();
        ClientLevel level = current.level();
        String dimension = level.dimension().location().toString();
        RareSightings.Viewer viewer = new RareSightings.Viewer(
                player.getX(), player.getY(), player.getZ(), current.facingYaw(), dimension);

        sightings.observeBosses(viewer, gameTick, collectBossCandidates(player, level, dimension));
        if (lastOreCheckTick == Long.MIN_VALUE || Math.abs(gameTick - lastOreCheckTick) >= ORE_CHECK_INTERVAL_TICKS) {
            lastOreCheckTick = gameTick;
            sightings.observeOres(viewer, when, collectVisibleOres(player, level, dimension));
        }
    }

    private List<RareSightings.BossCandidate> collectBossCandidates(LocalPlayer player, ClientLevel level, String dimension) {
        List<RareSightings.BossCandidate> found = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || entity == player || living.isRemoved() || living.isDeadOrDying()) {
                continue;
            }
            String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(living.getType()).toString();
            if (!RareSightings.isBossType(typeId, living.getType().is(BOSSES_TAG))) {
                continue;
            }
            boolean visibleInFront = FieldOfView.canSeeEntity(player, living, RareSightings.BOSS_MAX_RANGE);
            String name = living.hasCustomName()
                    ? living.getCustomName().getString()
                    : living.getType().getDescription().getString();
            String seenId = seenIds.idOfEntity(living.getId()).orElse(null);
            WorldPosition at = new WorldPosition(
                    (int) Math.floor(living.getX()),
                    (int) Math.floor(living.getY()),
                    (int) Math.floor(living.getZ()),
                    dimension);
            found.add(new RareSightings.BossCandidate(living.getStringUUID(), typeId, name, seenId, at, visibleInFront));
        }
        return found;
    }

    private List<RareSightings.VisibleOre> collectVisibleOres(LocalPlayer player, ClientLevel level, String dimension) {
        Set<Block> targets = rareOreBlocks();
        if (targets.isEmpty()) {
            return List.of();
        }
        registered.ensure(level, targets);
        var query = scans.query(level, player.blockPosition(), targets, ORE_WANT, ORE_CHUNK_RADIUS, BUILD_BUDGET,
                Set.of(), false, true);
        BlockPos head = BlockPos.containing(player.getEyePosition());
        List<RareSightings.VisibleOre> visible = new ArrayList<>();
        for (BlockPos hit : query.hits()) {
            if (!FieldOfView.canSeeBlock(player, hit, RareSightings.ORE_MAX_RANGE)) {
                continue;
            }
            int light = ClientLightLevels.exposedFaceLight(level, hit);
            if (!SeeingInTheDark.canMakeOut(light, head, hit)) {
                continue;
            }
            // 等所在区块归属查回并确认没有玩家放置记录再报，避免把基地里摆出来敲的矿石当成野外新发现。
            if (!ownership.known(dimension, hit.getX(), hit.getY(), hit.getZ())
                    || ownership.whoPlaced(dimension, hit.getX(), hit.getY(), hit.getZ()).isPresent()) {
                continue;
            }
            Block block = level.getBlockState(hit).getBlock();
            String blockId = BuiltInRegistries.BLOCK.getKey(block).toString();
            String name = block.getName().getString();
            visible.add(new RareSightings.VisibleOre(hit.immutable(), blockId, name));
        }
        return visible;
    }

    private Set<Block> rareOreBlocks() {
        if (this.rareOreBlocks != null) {
            return this.rareOreBlocks;
        }
        Set<Block> blocks = new LinkedHashSet<>();
        addTagBlocks(blocks, BlockTags.DIAMOND_ORES);
        addTagBlocks(blocks, BlockTags.EMERALD_ORES);
        blocks.add(Blocks.ANCIENT_DEBRIS);
        BuiltInRegistries.BLOCK.getTag(ORES_TAG).ifPresent(tag -> tag.forEach(holder -> {
            Block block = holder.value();
            ItemStack item = block.asItem().getDefaultInstance();
            if (!item.isEmpty() && item.getRarity() != Rarity.COMMON) {
                blocks.add(block);
            }
        }));
        this.rareOreBlocks = Set.copyOf(blocks);
        return this.rareOreBlocks;
    }

    private static void addTagBlocks(Set<Block> out, TagKey<Block> tagKey) {
        BuiltInRegistries.BLOCK.getTag(tagKey).ifPresent(tag -> tag.forEach(holder -> out.add(holder.value())));
    }

    private static boolean alreadyInWorldMemory(WorldMemory memory, WorldPosition spot, String blockId) {
        for (MemoryRecord record : memory.recordsNear(spot, RareSightings.ORE_CLUSTER_RADIUS)) {
            if (record.kind() == MemoryKind.SITE && record.contents() != null && record.contents().contains(blockId)) {
                return true;
            }
        }
        return false;
    }
}
