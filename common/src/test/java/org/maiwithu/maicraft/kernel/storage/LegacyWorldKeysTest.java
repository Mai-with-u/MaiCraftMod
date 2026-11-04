// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 旧编号名下的世界记忆：玩过的世界第一次用世界编号进时搬过去，刚建好的世界不搬，每个世界只决定一次。 */
class LegacyWorldKeysTest {
    private static final int LIMIT = 1024;
    /** 旧版本按存档目录"新的世界"算出的那串编号。 */
    private static final String LEGACY = "1a".repeat(32);
    private static final String WORLD = "ab".repeat(32);
    private static final String REBUILT = "cd".repeat(32);

    @TempDir
    Path temp;

    @Test
    void 旧版本玩过的存档第一次进_记忆搬到世界编号名下() throws IOException {
        var store = new DocumentStore(temp.resolve("state.sqlite"));
        store.write("world_memory", LEGACY, "book", "{\"places\":[\"家\"]}", false);
        store.write("goal_run", LEGACY, "7", "{}", false);

        LegacyWorldKeys.Decision decision = LegacyWorldKeys.adopt(store, LEGACY, WORLD, false);
        assertEquals(2, decision.moved());
        // 宿主按旧编号存过的数据也在旧编号名下：告诉它旧编号，它照同一个判断搬自己的。
        assertEquals(LEGACY, decision.worldKey());

        // 地点与没做完的目标都跟过来了，旧编号名下不再留一份。
        assertEquals("{\"places\":[\"家\"]}", store.read("world_memory", WORLD, "book", LIMIT));
        assertEquals(List.of("7"), store.keys("goal_run", WORLD));
        assertEquals(List.of(), store.keys("world_memory", LEGACY));
        // 再进这个世界：已经决定过，不再搬；旧数据在哪个编号名下照记下的决定答。
        LegacyWorldKeys.Decision again = LegacyWorldKeys.adopt(store, LEGACY, WORLD, false);
        assertEquals(0, again.moved());
        assertEquals(LEGACY, again.worldKey());
    }

    @Test
    void 删档后同名重建的新世界不继承旧存档的记忆() throws IOException {
        var store = new DocumentStore(temp.resolve("state.sqlite"));
        // 删掉的旧存档只在旧版本里玩过：它的记忆还挂在按目录算的旧编号名下。
        store.write("world_memory", LEGACY, "book", "{\"places\":[\"漏风屋子\"]}", false);

        LegacyWorldKeys.Decision decision = LegacyWorldKeys.adopt(store, LEGACY, REBUILT, true);
        assertEquals(0, decision.moved());
        assertNull(store.read("world_memory", REBUILT, "book", LIMIT));
        // 刚建好的世界没有旧数据可接：宿主拿到的就是世界编号，不会去搬删掉的旧存档的数据。
        assertEquals(REBUILT, decision.worldKey());
        // 在新世界里玩了一阵再进来：已经决定过不搬，游戏时间走了也不改主意，答的还是世界编号。
        LegacyWorldKeys.Decision later = LegacyWorldKeys.adopt(store, LEGACY, REBUILT, false);
        assertEquals(0, later.moved());
        assertEquals(REBUILT, later.worldKey());
        assertNull(store.read("world_memory", REBUILT, "book", LIMIT));
    }

    @Test
    void 新版本玩过的存档删了重建_旧编号名下已经空了() throws IOException {
        var store = new DocumentStore(temp.resolve("state.sqlite"));
        store.write("world_memory", LEGACY, "book", "{\"places\":[\"家\"]}", false);
        LegacyWorldKeys.adopt(store, LEGACY, WORLD, false);

        // 同一个文件夹删了重建：旧编号一样，但数据早已搬到上一个世界编号名下，这里搬不到任何东西。
        assertEquals(0, LegacyWorldKeys.adopt(store, LEGACY, REBUILT, false).moved());
        assertNull(store.read("world_memory", REBUILT, "book", LIMIT));
        assertEquals("{\"places\":[\"家\"]}", store.read("world_memory", WORLD, "book", LIMIT));
    }

    @Test
    void 编号没变或认不出旧编号时什么都不做() throws IOException {
        var store = new DocumentStore(temp.resolve("state.sqlite"));
        store.write("world_memory", LEGACY, "book", "{}", false);

        // 服务器没报世界编号、仍按地址认：世界编号就是旧编号，不用搬，也不记决定。
        assertEquals(new LegacyWorldKeys.Decision(0, LEGACY), LegacyWorldKeys.adopt(store, LEGACY, LEGACY, false));
        assertEquals(List.of(), store.keys(LegacyWorldKeys.SCOPE, LEGACY));
        assertEquals(new LegacyWorldKeys.Decision(0, WORLD), LegacyWorldKeys.adopt(store, null, WORLD, false));
        assertEquals(List.of(), store.keys(LegacyWorldKeys.SCOPE, WORLD));
    }

    @Test
    void 世界编号名下已经有数据时不和旧数据混在一起() throws IOException {
        var store = new DocumentStore(temp.resolve("state.sqlite"));
        store.write("world_memory", LEGACY, "book", "{\"old\":true}", false);
        store.write("world_memory", WORLD, "book", "{\"new\":true}", false);

        assertEquals(0, store.moveIdentity(LEGACY, WORLD));
        assertEquals("{\"new\":true}", store.read("world_memory", WORLD, "book", LIMIT));
        assertEquals("{\"old\":true}", store.read("world_memory", LEGACY, "book", LIMIT));
    }
}
