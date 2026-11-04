// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 世界编号文件：同一个存档重进不变，删档同名重建、复制出来的存档换新编号，整个搬家编号照旧。 */
class WorldIdFileTest {
    @TempDir
    Path saves;

    @Test
    void 新存档生成编号_重进同一个存档编号不变() {
        Path world = saves.resolve("新的世界");
        String id = new WorldIdFile(world).loadOrCreate();

        assertNotNull(id);
        assertTrue(id.matches("[0-9a-f]{64}"), id);
        assertTrue(Files.exists(world.resolve("data").resolve("maicraft_world_id.dat")));
        // 退出再进、重启游戏：读回的还是这一个。
        assertEquals(id, new WorldIdFile(world).loadOrCreate());
    }

    @Test
    void 删了存档用同名文件夹重建拿到新编号() throws IOException {
        Path world = saves.resolve("新的世界");
        String deleted = new WorldIdFile(world).loadOrCreate();
        deleteTree(world);

        // 默认名又建了一个"新的世界"：文件夹同名，里面没有编号文件，是另一个世界。
        Files.createDirectories(world);
        String rebuilt = new WorldIdFile(world).loadOrCreate();

        assertNotNull(rebuilt);
        assertNotEquals(deleted, rebuilt);
    }

    @Test
    void 复制出来的存档换新编号_原存档编号不变() throws IOException {
        Path original = saves.resolve("生存");
        String id = new WorldIdFile(original).loadOrCreate();
        Path copy = saves.resolve("生存 - 副本");
        copyTree(original, copy);

        // 副本带着原存档的编号文件，但原位置上的原存档还在：副本是另一个世界。
        String copied = new WorldIdFile(copy).loadOrCreate();
        assertNotEquals(id, copied);
        // 副本记下了自己的位置，之后重进副本编号不再变；原存档照旧。
        assertEquals(copied, new WorldIdFile(copy).loadOrCreate());
        assertEquals(id, new WorldIdFile(original).loadOrCreate());
    }

    @Test
    void 整个存档搬了家编号照旧() throws IOException {
        Path before = saves.resolve("生存");
        String id = new WorldIdFile(before).loadOrCreate();
        Path after = saves.resolve("改了名的生存");
        Files.move(before, after);

        // 原位置上已经没有这个存档：是搬家不是复制，还是同一个世界；新位置记下来，之后不再当搬家。
        assertEquals(id, new WorldIdFile(after).loadOrCreate());
        assertEquals(id, new WorldIdFile(after).loadOrCreate());
    }

    @Test
    void 编号文件坏了按没有编号处理_生成新编号() throws IOException {
        Path world = saves.resolve("新的世界");
        Path file = world.resolve("data").resolve("maicraft_world_id.dat");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "不是 NBT");

        String id = new WorldIdFile(world).loadOrCreate();
        assertNotNull(id);
        assertEquals(id, new WorldIdFile(world).loadOrCreate());
    }

    @Test
    void 编号文件写不进去时不报编号() throws IOException {
        // data 位置被一个同名文件占着，目录建不起来：这次开服没有编号，客户端退回按目录认。
        Path world = saves.resolve("只读的世界");
        Files.createDirectories(world);
        Files.writeString(world.resolve("data"), "占位");

        assertNull(new WorldIdFile(world).loadOrCreate());
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path path : paths.toList()) {
                Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else Files.copy(path, target);
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.delete(path);
            }
        }
    }
}
