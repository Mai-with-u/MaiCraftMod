// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BiPredicate;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;

/**
 * 方块归属记录：服务端记下每个方块是谁放的，供客户端查询（许可与保护的判断据此不再靠猜）。
 *
 * <p>记录跟着存档走：存进世界目录、重启后读回，玩家盖的房子不会因为服务器重启就变成"不知道是谁的"。
 * 方块被拆掉时这一格的记录一并去掉。按维度分开保存；每个维度有上限，超出后最早的记录先忘，
 * 忘掉的按"查不到"处理，不猜。
 */
public final class BlockOwnershipRecord {
    /** 每个维度保留的记录上限：一百多万格，盖一座大城也够；超出后最早的先忘。 */
    static final int MAX_ENTRIES_PER_DIMENSION = 1 << 20;
    private static final int FORMAT = 1;

    /** 一条归属：放它的人与放下的服务端刻号。 */
    public record Owner(UUID playerId, long tick) {}

    private final Map<String, LinkedHashMap<BlockPos, Owner>> byDimension = new LinkedHashMap<>();
    /**
     * 按区块分的索引：客户端一次问一整个区块里哪些格有主，不必逐格问，也不必翻遍整个维度。
     * 区块里的格按位置编号从小到大排好：一个区块有主的格太多、要分几页回答时，下一页接着上一页最后那一格往下读。
     */
    private final Map<String, Map<Long, NavigableSet<Long>>> byChunk = new HashMap<>();
    /** 有没有还没存盘的改动：后台写盘线程写失败时会把它标回来，所以两个线程都看得见。 */
    private volatile boolean dirty;

    /** 记录一次放置；同一格被替换时以后来的为准。 */
    public void recordPlacement(String dimension, BlockPos position, UUID playerId, long tick) {
        var placements = byDimension.computeIfAbsent(dimension, ignored -> new LinkedHashMap<>());
        // 重新放下的格子挪到最新的位置，淘汰时按真正的先后。
        placements.remove(position);
        placements.put(position.immutable(), new Owner(playerId, tick));
        index(dimension, position.immutable());
        while (placements.size() > MAX_ENTRIES_PER_DIMENSION) {
            BlockPos eldest = placements.keySet().iterator().next();
            placements.remove(eldest);
            unindex(dimension, eldest);
        }
        dirty = true;
    }

    /** 这一格的方块被拆掉了：原来的归属不再对应任何方块，去掉这条。 */
    public void forget(String dimension, BlockPos position) {
        var placements = byDimension.get(dimension);
        if (placements != null && placements.remove(position) != null) {
            unindex(dimension, position);
            dirty = true;
        }
    }

    /**
     * 放下这个方块时原版顺带放下的另一半：门、高花的上半在正上方，床头在床脚朝向的那一格；单格方块给空。
     * 这一半不经放置钩子，要由放的人一并记上，不然整扇门只有下半有主，上半被当成"不知道是谁的"。
     */
    public static Optional<BlockPos> otherHalf(BlockPos position, BlockState placed) {
        if (placed.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && placed.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
            return Optional.of(position.above());
        }
        if (placed.getBlock() instanceof BedBlock && placed.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT) {
            return Optional.of(position.relative(BedBlock.getConnectedDirection(placed)));
        }
        return Optional.empty();
    }

    /** 查一格是谁放的；没记到（太久远或本模组装上之前放的）返回空。 */
    public Optional<Owner> ownerOf(String dimension, BlockPos position) {
        var placements = byDimension.get(dimension);
        return placements == null ? Optional.empty() : Optional.ofNullable(placements.get(position.immutable()));
    }

    /** 一个区块里每一格有主的方块是谁放的，按位置编号从小到大；这个区块一格都没记到时给空表。 */
    public Map<BlockPos, Owner> ownedInChunk(String dimension, int chunkX, int chunkZ) {
        Map<BlockPos, Owner> owned = new LinkedHashMap<>();
        readChunk(dimension, chunkX, chunkZ, OptionalLong.empty(), (position, owner) -> {
            owned.put(position, owner);
            return true;
        });
        return owned;
    }

    /**
     * 按位置编号从小到大读一个区块里有主的格，一格一格交给 reader，reader 返回 false 就停。
     * after 给了只从编号比它大的格读起：一个区块有主的格可能上万，回答要分几页，每页接着上一页最后那一格往下读，
     * 两页之间有人放下、拆掉方块也不会重复给同一格。
     */
    public void readChunk(String dimension, int chunkX, int chunkZ, OptionalLong after,
                          BiPredicate<BlockPos, Owner> reader) {
        NavigableSet<Long> positions = byChunk.getOrDefault(dimension, Map.of()).get(ChunkPos.asLong(chunkX, chunkZ));
        var placements = byDimension.get(dimension);
        if (positions == null || placements == null) return;
        NavigableSet<Long> rest = after.isPresent() ? positions.tailSet(after.getAsLong(), false) : positions;
        for (long key : rest) {
            BlockPos position = BlockPos.of(key);
            Owner owner = placements.get(position);
            if (owner != null && !reader.test(position, owner)) return;
        }
    }

    private void index(String dimension, BlockPos position) {
        byChunk.computeIfAbsent(dimension, ignored -> new HashMap<>())
                .computeIfAbsent(ChunkPos.asLong(position), ignored -> new TreeSet<>())
                .add(position.asLong());
    }

    private void unindex(String dimension, BlockPos position) {
        var chunks = byChunk.get(dimension);
        if (chunks == null) return;
        long key = ChunkPos.asLong(position);
        var positions = chunks.get(key);
        if (positions == null) return;
        positions.remove(position.asLong());
        if (positions.isEmpty()) chunks.remove(key);
    }

    /** 有没有还没存盘的改动。 */
    public boolean dirty() {
        return dirty;
    }

    /** 拍好存盘快照后调用：之后再有放置、拆除就又算有改动。 */
    void saved() {
        dirty = false;
    }

    /** 快照没写进盘：标回有改动，下次存盘重新拍一份再写。 */
    void markDirty() {
        dirty = true;
    }

    /**
     * 写成存盘用的标签。每个维度存成三列：位置、放置人在人名表里的序号、放置刻号，
     * 按放置先后排列；人名表单独一份，同一个人放的几万格不重复写他的编号。
     */
    public CompoundTag toTag() {
        CompoundTag root = new CompoundTag();
        root.putInt("format", FORMAT);
        List<UUID> people = new ArrayList<>();
        Map<UUID, Integer> index = new HashMap<>();
        CompoundTag dimensions = new CompoundTag();
        for (var entry : byDimension.entrySet()) {
            var placements = entry.getValue();
            long[] positions = new long[placements.size()];
            int[] owners = new int[placements.size()];
            long[] ticks = new long[placements.size()];
            int i = 0;
            for (var placement : placements.entrySet()) {
                positions[i] = placement.getKey().asLong();
                owners[i] = index.computeIfAbsent(placement.getValue().playerId(), id -> {
                    people.add(id);
                    return people.size() - 1;
                });
                ticks[i] = placement.getValue().tick();
                i++;
            }
            CompoundTag dimension = new CompoundTag();
            dimension.put("positions", new LongArrayTag(positions));
            dimension.put("owners", new IntArrayTag(owners));
            dimension.put("ticks", new LongArrayTag(ticks));
            dimensions.put(entry.getKey(), dimension);
        }
        ListTag peopleTag = new ListTag();
        for (UUID person : people) {
            peopleTag.add(NbtUtils.createUUID(person));
        }
        root.put("people", peopleTag);
        root.put("dimensions", dimensions);
        return root;
    }

    /** 从存盘的标签读回；格式对不上的存档不猜，当作没有记录。 */
    public static BlockOwnershipRecord fromTag(CompoundTag root) {
        BlockOwnershipRecord record = new BlockOwnershipRecord();
        if (root.getInt("format") != FORMAT) {
            return record;
        }
        ListTag peopleTag = root.getList("people", Tag.TAG_INT_ARRAY);
        List<UUID> people = new ArrayList<>();
        for (Tag person : peopleTag) {
            people.add(NbtUtils.loadUUID(person));
        }
        CompoundTag dimensions = root.getCompound("dimensions");
        for (String name : dimensions.getAllKeys()) {
            CompoundTag dimension = dimensions.getCompound(name);
            long[] positions = dimension.getLongArray("positions");
            int[] owners = dimension.getIntArray("owners");
            long[] ticks = dimension.getLongArray("ticks");
            var placements = record.byDimension.computeIfAbsent(name, ignored -> new LinkedHashMap<>());
            for (int i = 0; i < positions.length && i < owners.length && i < ticks.length; i++) {
                if (owners[i] >= 0 && owners[i] < people.size()) {
                    BlockPos position = BlockPos.of(positions[i]);
                    placements.put(position, new Owner(people.get(owners[i]), ticks[i]));
                    record.index(name, position);
                }
            }
        }
        return record;
    }
}
