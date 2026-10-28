// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;

/** 预留存进世界的文档库：重开还在、换一个世界是另一份、撤光了就删掉，写坏的一条跳过。 */
class StoredReservationsTest {

    @TempDir
    Path dir;

    @Test
    void 存进去再读出来还是那一份_换个世界是另一份() {
        DocumentStore documents = new DocumentStore(dir.resolve(DocumentStore.FILE_NAME));
        List<ReservedItems.Reservation> written = List.of(
                new ReservedItems.Reservation("minecraft:oak_planks", 40),
                new ReservedItems.Reservation("#minecraft:logs", null));
        new StoredReservations(documents, "world-a").save(written);

        assertEquals(written, new StoredReservations(new DocumentStore(dir.resolve(DocumentStore.FILE_NAME)), "world-a").load());
        assertTrue(new StoredReservations(documents, "world-b").load().isEmpty());
    }

    @Test
    void 撤光了就删掉() {
        DocumentStore documents = new DocumentStore(dir.resolve(DocumentStore.FILE_NAME));
        StoredReservations stored = new StoredReservations(documents, "world-a");
        stored.save(List.of(new ReservedItems.Reservation("minecraft:coal", 8)));
        stored.save(List.of());
        assertTrue(stored.load().isEmpty());
    }

    @Test
    void 写坏的一条跳过_别的照读() {
        List<ReservedItems.Reservation> read = StoredReservations.parse(
                "{\"reservations\":[{\"item\":\"minecraft:coal\",\"at_least\":0},{\"item\":\"minecraft:diamond\"}]}");
        assertEquals(List.of(new ReservedItems.Reservation("minecraft:diamond", null)), read);
    }
}
