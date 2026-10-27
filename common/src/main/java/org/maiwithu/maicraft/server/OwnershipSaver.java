// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.minecraft.server.MinecraftServer;

/**
 * 方块归属记录的存盘：服务器主线程上拍一份快照，压缩和写盘交给唯一一条后台写盘线程。
 * 记录上百万格时压缩要几百毫秒，放在主线程上每次存盘都会卡一下服；写盘线程只有一条，先交的快照先写，旧的不会盖掉新的。
 * 停服时交最后一份并等排着的都写完：写盘线程是守护线程，进程退出时会被直接掐掉。
 */
public final class OwnershipSaver {

    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "MaiCraft 方块归属存盘");
        thread.setDaemon(true);
        return thread;
    });

    /** 有改动就拍快照交去写；还没登记（服务器第一刻之前）就什么都不做。 */
    public void saveLater(MinecraftServer server) {
        BlockOwnershipRecord record = ServerLinkServices.ownership(server);
        if (record != null) OwnershipFile.of(server).saveIfDirtyLater(record, writer);
    }

    /** 停服：交最后一份快照，再等写盘线程把排着的都写完才返回。 */
    public void saveAndWait(MinecraftServer server) {
        saveLater(server);
        CompletableFuture.runAsync(() -> { }, writer).join();
    }
}
