// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;

import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 预留存在这个世界的文档库里：重启客户端、重进世界都还在，换一个世界是另一份。
 * 存不进、读不出时如实记日志，手上这一份照常生效，不因为存盘出错把调用方写下的预留弄丢。
 */
public final class StoredReservations implements ReservedItems.Storage {

    private static final Logger LOG = LoggerFactory.getLogger(StoredReservations.class);
    static final String SCOPE = "reserved-items";
    private static final String KEY = "reservations";
    private static final int LIMIT = 64 * 1024;

    private final DocumentStore documents;
    private final String worldKey;

    public StoredReservations(DocumentStore documents, String worldKey) {
        this.documents = Objects.requireNonNull(documents, "documents");
        this.worldKey = Objects.requireNonNull(worldKey, "worldKey");
    }

    @Override
    public List<ReservedItems.Reservation> load() {
        try {
            String json = documents.read(SCOPE, worldKey, KEY, LIMIT);
            return json == null ? List.of() : parse(json);
        } catch (IOException | JsonParseException | IllegalStateException | IllegalArgumentException broken) {
            LOG.warn("[预留] 这个世界存着的预留读不出来，按没有预留开始：{}", broken.toString());
            return List.of();
        }
    }

    @Override
    public void save(List<ReservedItems.Reservation> reservations) {
        try {
            if (reservations.isEmpty()) {
                documents.delete(SCOPE, worldKey, KEY);
            } else {
                documents.write(SCOPE, worldKey, KEY, write(reservations), false);
            }
        } catch (IOException failed) {
            LOG.warn("[预留] 预留没存进这个世界的存档，这次进世界照样生效：{}", failed.toString());
        }
    }

    // 每条一个对象：item 必有，at_least 没有就是全都留着；写坏的那一条跳过，别的照读。
    static List<ReservedItems.Reservation> parse(String json) {
        List<ReservedItems.Reservation> read = new ArrayList<>();
        for (JsonElement element : JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("reservations")) {
            try {
                JsonObject entry = element.getAsJsonObject();
                JsonElement atLeast = entry.get("at_least");
                read.add(new ReservedItems.Reservation(entry.get("item").getAsString(),
                        atLeast == null || atLeast.isJsonNull() ? null : atLeast.getAsInt()));
            } catch (RuntimeException skipped) {
                LOG.warn("[预留] 跳过一条写坏的预留 {}：{}", element, skipped.toString());
            }
        }
        return read;
    }

    static String write(List<ReservedItems.Reservation> reservations) {
        JsonArray list = new JsonArray();
        for (ReservedItems.Reservation reservation : reservations) {
            JsonObject entry = new JsonObject();
            entry.addProperty("item", reservation.item());
            if (reservation.atLeast() != null) entry.addProperty("at_least", reservation.atLeast());
            list.add(entry);
        }
        JsonObject json = new JsonObject();
        json.add("reservations", list);
        return json.toString();
    }
}
