// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.HexFormat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 世界编号文件：放在世界目录的 data 下，跟着存档走，记着这个存档自己的世界编号和编号是在哪个位置生成的。
 *
 * <p>编号是第一次开服时随机生成的一串，之后同一个存档重进、重启游戏都不变；删了存档再用同名文件夹新建，
 * 新文件夹里没有这个文件，拿到的是新编号，不会和删掉的那个当成同一个世界。
 * 复制出来的存档会带着原存档的文件：记下的位置和现在不一样、原位置上还是同一个编号，就是复制出来的一份，换新编号；
 * 原位置上已经没有这个编号（整个存档搬了家、服务器换了机器），编号照旧，只改记下的位置。
 *
 * <p>先写临时文件再整体替换，写到一半崩溃也不会留下坏掉的半个文件；读不出来按没有文件处理，生成新编号。
 * 写不进去时这次开服没有世界编号，客户端退回按存档目录或服务器地址认世界。
 */
public final class WorldIdFile {
    private static final Logger LOG = LoggerFactory.getLogger(WorldIdFile.class);
    private static final String FILE_NAME = "maicraft_world_id.dat";
    private static final String ID = "id";
    private static final String CREATED_AT = "path";
    /** 世界编号：64 位小写十六进制，和按路径、地址算出的旧编号长得一样，存档文档库的键原样可用。 */
    private static final String ID_PATTERN = "[0-9a-f]{64}";

    private final Path worldRoot;

    /** @param worldRoot 世界目录（level.dat 所在的那一层） */
    public WorldIdFile(Path worldRoot) {
        this.worldRoot = worldRoot.toAbsolutePath().normalize();
    }

    /** 这台服务器（含单人游戏的内置服务器）当前存档的世界编号文件。 */
    public static WorldIdFile of(MinecraftServer server) {
        return new WorldIdFile(server.getWorldPath(LevelResource.ROOT));
    }

    /**
     * 读出这个存档的世界编号，没有就生成一个写下去。
     *
     * @return 世界编号；文件写不进去、拿不出一个下次开服还认得的编号时为 null
     */
    public String loadOrCreate() {
        Stored stored = read(file(worldRoot));
        if (stored != null) {
            if (sameDirectory(stored.createdAt(), worldRoot)) return stored.id();
            // 记下的位置和现在不一样：原位置上还是同一个编号，说明那边的原存档还在、这是复制出来的一份，换新编号；
            // 原位置上没有这个编号了，是整个存档搬了家，编号照旧，记下新位置（记不下也照用，下次开服再记）。
            Stored original = stored.createdAt() == null ? null : read(file(stored.createdAt()));
            if (original == null || !original.id().equals(stored.id())) {
                LOG.info("存档换了位置，世界编号照旧：{}", worldRoot);
                write(stored.id());
                return stored.id();
            }
            LOG.info("存档是从 {} 复制出来的，换一个新的世界编号：{}", stored.createdAt(), worldRoot);
        }
        // 没有编号（新存档、旧版本玩过的存档）或复制出来的一份：随机生成一个，写下去才算数。
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String fresh = HexFormat.of().formatHex(bytes);
        if (!write(fresh)) return null;
        LOG.info("给存档生成了世界编号：{}", worldRoot);
        return fresh;
    }

    private static Path file(Path worldRoot) {
        return worldRoot.resolve("data").resolve(FILE_NAME);
    }

    // 两个位置是不是同一个目录：都在时按文件系统认（Windows 不分大小写、链接指过来的也算同一个），不在时按写法认。
    private static boolean sameDirectory(Path recorded, Path current) {
        if (recorded == null) return false;
        try {
            return Files.isSameFile(recorded, current);
        } catch (IOException | SecurityException unreachable) {
            return false;
        }
    }

    /** 文件里记的世界编号与生成时的位置；文件不在、读不出来或编号不像样时为 null。 */
    private static Stored read(Path file) {
        if (Files.notExists(file)) return null;
        try {
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            String id = tag.getString(ID);
            if (!id.matches(ID_PATTERN)) {
                LOG.warn("世界编号文件里的编号不像样，按没有编号处理：{}", file);
                return null;
            }
            String createdAt = tag.getString(CREATED_AT);
            Path where;
            try {
                where = createdAt.isEmpty() ? null : Path.of(createdAt);
            } catch (InvalidPathException foreign) {
                // 别的系统上生成的路径（Linux 服务器的存档拿到 Windows 上开）：认不出原位置，按搬了家算。
                where = null;
            }
            return new Stored(id, where);
        } catch (IOException | RuntimeException failure) {
            LOG.warn("世界编号文件读不出来，按没有编号处理：{}", file, failure);
            return null;
        }
    }

    // 写下编号和此刻的位置：先写临时文件再整体替换；成功返回 true，失败记日志返回 false。
    private boolean write(String id) {
        Path file = file(worldRoot);
        try {
            Files.createDirectories(file.getParent());
            CompoundTag tag = new CompoundTag();
            tag.putString(ID, id);
            tag.putString(CREATED_AT, worldRoot.toString());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            NbtIo.writeCompressed(tag, temp);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException | RuntimeException failure) {
            LOG.warn("世界编号文件写不进去，这次开服不报世界编号：{}", file, failure);
            return false;
        }
    }

    private record Stored(String id, Path createdAt) {}
}
