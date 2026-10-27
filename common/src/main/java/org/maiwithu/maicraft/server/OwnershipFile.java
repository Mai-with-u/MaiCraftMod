// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 方块归属记录的存档文件：放在世界目录的 data 下，跟着存档走。
 *
 * <p>先写临时文件再整体替换，写到一半崩溃也不会留下坏掉的半个文件；读不出来时记日志、从空记录开始，
 * 不让一个坏文件挡住开服。
 * 平时存盘在服务器主线程上只拍一份快照，压缩和写盘放到后台：记录上百万格时压缩要几百毫秒，放在主线程上每五分钟就卡一下服。
 */
public final class OwnershipFile {
    private static final Logger LOG = LoggerFactory.getLogger(OwnershipFile.class);
    private static final String FILE_NAME = "maicraft_block_owners.dat";

    private final Path file;

    public OwnershipFile(Path file) {
        this.file = file;
    }

    /** 这台服务器（含单人游戏的内置服务器）当前存档里的归属记录文件。 */
    public static OwnershipFile of(MinecraftServer server) {
        return new OwnershipFile(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(FILE_NAME));
    }

    /** 读回存档里的记录；还没有文件就是新存档，从空记录开始。 */
    public BlockOwnershipRecord load() {
        if (Files.notExists(file)) {
            return new BlockOwnershipRecord();
        }
        try {
            return BlockOwnershipRecord.fromTag(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()));
        } catch (IOException | RuntimeException failure) {
            LOG.warn("方块归属记录读不出来，从空记录开始：{}", file, failure);
            return new BlockOwnershipRecord();
        }
    }

    /** 有改动就在调用的线程上存完盘；失败只记日志，改动留着下次再存。 */
    public void saveIfDirty(BlockOwnershipRecord record) {
        if (!record.dirty()) {
            return;
        }
        CompoundTag snapshot = record.toTag();
        record.saved();
        if (!write(snapshot)) record.markDirty();
    }

    /**
     * 有改动就在调用的线程（服务器主线程）上拍一份快照，压缩和写盘交给 writer 在后台做；写失败把记录标回有改动，下次再存。
     * writer 一次只做一件事：先交的快照先写，旧快照不会在新快照之后写进去、把新的盖掉。
     */
    public CompletableFuture<Void> saveIfDirtyLater(BlockOwnershipRecord record, Executor writer) {
        if (!record.dirty()) {
            return CompletableFuture.completedFuture(null);
        }
        CompoundTag snapshot = record.toTag();
        record.saved();
        return CompletableFuture.runAsync(() -> {
            if (!write(snapshot)) record.markDirty();
        }, writer);
    }

    // 把一份快照写进文件：先写临时文件再整体替换；成功返回 true，失败记日志返回 false。
    private boolean write(CompoundTag snapshot) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            NbtIo.writeCompressed(snapshot, temp);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException | RuntimeException failure) {
            LOG.warn("方块归属记录存盘失败，下次再试：{}", file, failure);
            return false;
        }
    }
}
