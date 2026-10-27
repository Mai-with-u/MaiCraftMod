// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;

import org.maiwithu.maicraft.ability.machine.api.ArchivedMachines;
import org.maiwithu.maicraft.behavior.construction.CellKind;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.construction.PlannedCellCodec;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;

/**
 * 机器档案库：按世界把每台机器的档案存成一篇文档，键是档案名。
 * 存不进去（库坏了、超预算）时保存返回假，施工照常结算，只是档案没留住；读不到按没有这台机器对待。
 * 组装成物理结构的机器档案跟着结构走：组装时平移进结构的地块，拆回时按组装器落点和转的整 90° 跟回世界。
 */
final class MachineArchives implements ArchivedMachines {

    /** 文档库里的范围名：机器档案都存这个范围下，与别的用途互不覆盖。 */
    static final String SCOPE = "machine-archive";

    /** 一篇档案的存盘预算：一台机器几十到几百格的蓝图加机器条目，远用不到这么大；超出时不写。 */
    private static final int DOCUMENT_LIMIT = 2 * 1024 * 1024;

    private final DocumentStore documents;
    private final String worldKey;

    MachineArchives(DocumentStore documents, String worldKey) {
        this.documents = documents;
        this.worldKey = worldKey;
    }

    /** 按名字找档案；没有这个名、或存的那篇读不出来时给空。 */
    Optional<MachineArchive> find(String name) {
        try {
            String json = documents.read(SCOPE, worldKey, key(name), DOCUMENT_LIMIT);
            return json == null ? Optional.empty() : Optional.of(decode(name, json));
        } catch (IOException | RuntimeException broken) {
            return Optional.empty();
        }
    }

    // 按一格找档案：库里没有位置索引，逐篇读这个世界的机器档案，挑维度对得上、没拆、声明了这一格要放方块的。
    // 只在组装载具这类一次性的事里用，档案不多，逐篇读省得另维护一份会和档案对不上的索引；读不出的那篇跳过。
    @Override public Optional<Machine> covering(String dimension, BlockPos cell) {
        List<String> keys;
        try {
            keys = documents.keys(SCOPE, worldKey);
        } catch (IOException broken) {
            return Optional.empty();
        }
        for (String key : keys) {
            Optional<MachineArchive> archive = readKey(key);
            // 记在结构上的档案锚点是地块里的格，和世界里的格对不上，不算在世界里盖住这一格。
            if (archive.isEmpty() || archive.get().removed() || archive.get().onStructure()
                    || !archive.get().dimension().equals(dimension)) continue;
            Set<BlockPos> blocks = blocksOf(archive.get());
            if (blocks.contains(cell)) return Optional.of(new Machine(archive.get().name(), blocks));
        }
        return Optional.empty();
    }

    // 组装后：组装器所在的那份档案跟到结构上。组装是纯平移，锚点挪的和组装器挪的一样多，偏移不变。
    @Override public Optional<Moved> moveOntoStructure(String dimension, BlockPos worldAssembler, String structureId,
            BlockPos plotAssembler) {
        Optional<Machine> covering = covering(dimension, worldAssembler);
        if (covering.isEmpty()) return Optional.empty();
        Optional<MachineArchive> archive = find(covering.get().name());
        if (archive.isEmpty()) return Optional.empty();
        BlockPos shift = plotAssembler.subtract(worldAssembler);
        MachineArchive moved = archive.get().movedOnto(structureId, archive.get().anchor().offset(shift));
        return Optional.of(new Moved(moved.name(), save(moved)));
    }

    // 拆回后：记在这个结构上的档案跟回世界。拆回以组装器为轴转整 90° 落到组装器当时的世界格：
    // 世界锚点 = 组装器落点 + 转过的（地块锚点 − 地块里的组装器），蓝图跟着转。
    @Override public Optional<Moved> moveToWorld(String structureId, BlockPos plotAssembler, BlockPos worldAssembler,
            Rotation turn) {
        Optional<MachineArchive> archive = on(structureId);
        if (archive.isEmpty()) return Optional.empty();
        BlockPos fromAssembler = archive.get().anchor().subtract(plotAssembler).rotate(turn);
        MachineArchive moved = archive.get().movedToWorld(worldAssembler.offset(fromAssembler),
                archive.get().blueprint().turned(turn));
        return Optional.of(new Moved(moved.name(), save(moved)));
    }

    @Override public Optional<String> onStructure(String structureId) {
        return on(structureId).map(MachineArchive::name);
    }

    /** 记在这个结构上、还没拆除的那份档案；几份都记在上面时取档案名排在前面的那份，没有为空。 */
    Optional<MachineArchive> on(String structureId) {
        List<String> keys;
        try {
            keys = documents.keys(SCOPE, worldKey);
        } catch (IOException broken) {
            return Optional.empty();
        }
        for (String key : keys) {
            Optional<MachineArchive> archive = readKey(key);
            if (archive.isPresent() && !archive.get().removed() && structureId.equals(archive.get().structure())) {
                return archive;
            }
        }
        return Optional.empty();
    }

    // 档案落地后声明要放方块的格：锚点加偏移；清空与倒桶的格不是这台机器的方块。
    static Set<BlockPos> blocksOf(MachineArchive archive) {
        Set<BlockPos> blocks = new HashSet<>();
        for (PlannedCell cell : archive.blueprint().cells()) {
            if (cell.kind() == CellKind.BLOCK) blocks.add(archive.anchor().offset(cell.pos()));
        }
        return blocks;
    }

    // 按库里的键读一篇：名字取正文里存的那个（键是小写过的）。
    private Optional<MachineArchive> readKey(String key) {
        try {
            String json = documents.read(SCOPE, worldKey, key, DOCUMENT_LIMIT);
            if (json == null) return Optional.empty();
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            return Optional.of(decode(root.has("name") ? root.get("name").getAsString() : key, json));
        } catch (IOException | RuntimeException broken) {
            return Optional.empty();
        }
    }

    /** 存一份档案；存不进去返回假，调用方把"档案没留住"记进结果，不因此改判施工。 */
    boolean save(MachineArchive archive) {
        try {
            String json = encode(archive);
            if (json.getBytes(StandardCharsets.UTF_8).length > DOCUMENT_LIMIT) {
                return false;
            }
            return documents.write(SCOPE, worldKey, key(archive.name()), json, false);
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    // 档案的正文：蓝图逐格照计划格的存盘写法存（建筑档案也是这一份），读回后原样重建。
    private static String encode(MachineArchive archive) {
        JsonObject root = new JsonObject();
        root.addProperty("name", archive.name());
        root.addProperty("dimension", archive.dimension());
        root.addProperty("anchor", archive.anchor().asLong());
        root.add("blueprint", encodeBlueprint(archive.blueprint()));
        if (archive.designId() != null) {
            root.addProperty("design_id", archive.designId());
        }
        root.addProperty("removed", archive.removed());
        JsonObject networks = new JsonObject();
        archive.networks().forEach(networks::addProperty);
        root.add("networks", networks);
        if (archive.lastCheckAt() != null) {
            root.addProperty("last_check_at", archive.lastCheckAt().toString());
            root.addProperty("last_check_note", archive.lastCheckNote());
        }
        if (archive.structure() != null) {
            root.addProperty("structure", archive.structure());
        }
        return root.toString();
    }

    private static MachineArchive decode(String name, String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        BlockPos anchor = BlockPos.of(root.get("anchor").getAsLong());
        MachineBlueprint blueprint = decodeBlueprint(root.getAsJsonObject("blueprint"));
        String designId = root.has("design_id") ? root.get("design_id").getAsString() : null;
        Map<String, String> networks = new LinkedHashMap<>();
        if (root.has("networks")) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("networks").entrySet()) {
                networks.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return new MachineArchive(name, root.get("dimension").getAsString(), anchor, blueprint, designId,
                root.get("removed").getAsBoolean(), networks,
                root.has("last_check_at") ? Instant.parse(root.get("last_check_at").getAsString()) : null,
                root.has("last_check_note") ? root.get("last_check_note").getAsString() : null,
                root.has("structure") ? root.get("structure").getAsString() : null);
    }

    private static JsonObject encodeBlueprint(MachineBlueprint blueprint) {
        JsonObject body = new JsonObject();
        JsonArray cells = new JsonArray();
        for (PlannedCell cell : blueprint.cells()) {
            cells.add(PlannedCellCodec.encode(cell));
        }
        body.add("cells", cells);
        body.add("parts", MachineBlueprintCodec.parts(blueprint));
        body.add("installations", MachineBlueprintCodec.installations(blueprint));
        body.add("settings", MachineBlueprintCodec.settings(blueprint));
        body.add("processes", MachineBlueprintCodec.processes(blueprint));
        return body;
    }

    private static MachineBlueprint decodeBlueprint(JsonObject body) {
        List<PlannedCell> cells = new ArrayList<>();
        for (JsonElement element : body.getAsJsonArray("cells")) {
            cells.add(PlannedCellCodec.decode(element.getAsJsonObject()));
        }
        return new MachineBlueprint(cells,
                MachineBlueprintCodec.readParts(body.getAsJsonArray("parts")),
                MachineBlueprintCodec.readInstallations(body.getAsJsonArray("installations")),
                MachineBlueprintCodec.readSettings(body.getAsJsonArray("settings")),
                MachineBlueprintCodec.readProcesses(body.getAsJsonArray("processes")));
    }
}
