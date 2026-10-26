// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.vehicle.api.VehicleControls;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleAssembler;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleReader;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;

/**
 * 载具档案跟着结构走：拆回成方块时，把这辆的操纵表连同拆前的控制回路、方块清单改记到世界里组装器落回的那一格
 * （车头换成世界朝向）；再从这台组装器组装出新结构时认是不是同一艘，是就把操纵表搬到新结构身份上，
 * 方块与回路的变化照现场列出来、回路变了的试过的角色不带；认不出照新载具处理并说明。
 * 拆回记录按"维度|x,y,z"存在这个世界的文档库里，操纵表本身由 {@link VehicleControls} 按结构身份存。
 */
public final class VehicleArchives {

    /** 文档库里的范围名：拆回记录都存这个范围下。 */
    static final String SCOPE = "vehicle-disassembled";
    /** 一份拆回记录的存盘预算。 */
    private static final int DOCUMENT_LIMIT = 128 * 1024;

    /** 档案这一次怎么走的：改记在世界里、跟到新结构、当新载具、留在原结构身份下。 */
    static final String MOVED_TO_WORLD = "moved_to_world";
    static final String FOLLOWED = "followed";
    static final String NEW_VEHICLE = "new_vehicle";
    static final String KEPT = "kept";

    private final VehicleControls controls;
    private final DocumentStore documents;
    private final String worldKey;
    private final List<VehicleReader> readers;
    private final Supplier<String> dimension;
    private final Function<String, Optional<String>> seenIdOf;

    /**
     * @param readers   读载具的办法：拆回前读回路与朝向、组装后读新回路
     * @param dimension 角色此刻所在的维度：拆回记录按维度与组装器的格找
     * @param seenIdOf  结构身份换成观察编号 s#（新结构还没领到时为空）
     */
    public VehicleArchives(VehicleControls controls, DocumentStore documents, String worldKey,
            List<VehicleReader> readers, Supplier<String> dimension, Function<String, Optional<String>> seenIdOf) {
        this.controls = Objects.requireNonNull(controls, "controls");
        this.documents = Objects.requireNonNull(documents, "documents");
        this.worldKey = Objects.requireNonNull(worldKey, "worldKey");
        this.readers = List.copyOf(readers);
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.seenIdOf = Objects.requireNonNull(seenIdOf, "seenIdOf");
    }

    /**
     * 拉杆拆回前读下来的：结构身份、此刻的样子（回路与朝向；读不到为空）、操纵表存下来的部分。
     */
    record Before(String structureId, Optional<VehicleReader.View> view, VehicleControls.Stored stored) {}

    /**
     * 档案这一次怎么走的（组装结果的 archive）。
     *
     * @param state     moved_to_world 改记在世界里 / followed 跟到新结构 / new_vehicle 当新载具 / kept 留在原结构身份下
     * @param structure 档案现在记在哪：新结构的 s#（还没领到编号写结构身份）、或"世界里 x,y,z"
     * @param changes   重新组装时方块清单与控制回路的变化，一处一句
     * @param dropped   没带过去的操纵表行，一行一句为什么
     * @param note      一句话
     */
    record Handover(String state, String structure, List<String> changes, List<String> dropped, String note) {
        Handover {
            changes = List.copyOf(changes);
            dropped = List.copyOf(dropped);
        }
    }

    /** 拉杆前读下这辆：拆完结构就没了，回路、朝向、操纵表都要先读。 */
    Before before(String structureId) {
        return new Before(structureId, read(structureId), controls.stored(structureId));
    }

    /** 新结构这会儿的观察编号；还没领到为空。 */
    Optional<String> seenId(String structureId) {
        return seenIdOf.apply(structureId);
    }

    /**
     * 拆回转换了：把档案改记到组装器落回的那一格，车头换成世界朝向，按旧身份存的那份删掉。
     * 不知道组装器落在哪、或存不进去就留在原结构身份下照实说。不是物理组装器拆的（没有组装器也没有方块清单）不谈档案。
     */
    Handover disassembled(Before before, VehicleAssembler.Conversion conversion) {
        if (conversion.assembler() == null && conversion.makeup().isEmpty()) return null;
        if (conversion.assembler() == null) {
            return new Handover(KEPT, before.structureId(), List.of(), List.of(),
                    "拆回后没找到组装器落在哪，档案留在原结构身份下");
        }
        VehicleControls.Stored stored = before.stored();
        String note = "";
        String forward = "";
        if (!stored.forward().isEmpty()) {
            // 车头是结构自己的轴：按拆前的朝向换成世界轴（拆回会摆正到最近的整 90°），新结构的轴就是世界轴。
            if (before.view().isPresent()) {
                forward = Reassembly.worldAxis(stored.forward(), before.view().get().state().heading());
            } else {
                note = "；拆前没读到朝向，车头没法换算，再组装后要重新定车头";
            }
        }
        List<Reassembly.Circuit> circuits = before.view().map(VehicleArchives::circuits).orElse(List.of());
        JsonObject record = new JsonObject();
        record.addProperty("structure", before.structureId());
        JsonObject makeup = new JsonObject();
        conversion.makeup().forEach(makeup::addProperty);
        record.add("makeup", makeup);
        JsonArray wires = new JsonArray();
        for (Reassembly.Circuit circuit : circuits) {
            JsonObject wire = new JsonObject();
            wire.addProperty("control", circuit.control());
            wire.addProperty("frequency", circuit.frequency());
            JsonArray drives = new JsonArray();
            circuit.drives().forEach(drives::add);
            wire.add("drives", drives);
            wires.add(wire);
        }
        record.add("controls", wires);
        record.add("control_map", VehicleControls.toJson(new VehicleControls.Stored(stored.entries(), forward,
                forward.isEmpty() ? "" : stored.forwardSource())));
        String at = place(conversion.assembler());
        if (!save(key(conversion.assembler()), record)) {
            return new Handover(KEPT, before.structureId(), List.of(), List.of(), "拆回记录没存进去，档案留在原结构身份下");
        }
        controls.forget(before.structureId());
        return new Handover(MOVED_TO_WORLD, "世界里 " + at, List.of(), List.of(),
                "档案改记在组装器落回的那一格（" + at + "），从这台组装器再组装会跟到新结构" + note);
    }

    /**
     * 组装转换了：这台组装器有拆回记录、方块清单对得上，就把操纵表搬到新结构上并列出变化；
     * 没有记录、对不上都当新载具，照实说。不是物理组装器组的（没有组装器的格）不谈档案。
     */
    Handover assembled(VehicleAssembler.Conversion conversion) {
        if (conversion.assembler() == null) return null;
        String structureId = conversion.structureId();
        String named = seenIdOf.apply(structureId).orElse(structureId);
        String key = key(conversion.assembler());
        Optional<JsonObject> record = load(key);
        if (record.isEmpty()) {
            return new Handover(NEW_VEHICLE, named, List.of(), List.of(), "这台组装器没有拆回过的档案，按新载具");
        }
        Reassembly.Verdict verdict = Reassembly.match(makeup(record.get()), conversion.makeup());
        if (!verdict.same()) {
            delete(key);
            return new Handover(NEW_VEHICLE, named, List.of(), List.of(), verdict.note() + "，旧档案没搬");
        }
        Optional<VehicleReader.View> now = read(structureId);
        Reassembly.CircuitChange change = now.isPresent()
                ? Reassembly.circuits(wires(record.get()), circuits(now.get()))
                : new Reassembly.CircuitChange(List.of(), Set.of(), Set.of());
        Reassembly.Carried carried = Reassembly.carry(VehicleControls.fromJson(record.get().getAsJsonObject("control_map")),
                change);
        List<String> changes = new ArrayList<>(verdict.changes());
        changes.addAll(change.lines());
        if (!controls.adopt(structureId, carried.stored())) {
            return new Handover(NEW_VEHICLE, named, changes, List.of(), "认出是拆回的那一艘，但操纵表没存到新结构上，旧档案还记在世界里");
        }
        delete(key);
        return new Handover(FOLLOWED, named, changes, carried.dropped(), "档案跟到新结构 " + named + "（" + verdict.note() + "）"
                + (now.isEmpty() ? "；新结构的回路没读到，回路没比" : ""));
    }

    private Optional<VehicleReader.View> read(String structureId) {
        return readers.stream().map(reader -> reader.read(structureId)).flatMap(Optional::stream).findFirst();
    }

    private static List<Reassembly.Circuit> circuits(VehicleReader.View view) {
        return view.controls().stream()
                .map(input -> new Reassembly.Circuit(input.control(), input.frequency(), input.drives())).toList();
    }

    private static Map<String, Integer> makeup(JsonObject record) {
        Map<String, Integer> found = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : record.getAsJsonObject("makeup").entrySet()) {
            found.put(entry.getKey(), entry.getValue().getAsInt());
        }
        return found;
    }

    private static List<Reassembly.Circuit> wires(JsonObject record) {
        List<Reassembly.Circuit> found = new ArrayList<>();
        for (JsonElement element : record.getAsJsonArray("controls")) {
            JsonObject wire = element.getAsJsonObject();
            List<String> drives = new ArrayList<>();
            for (JsonElement drive : wire.getAsJsonArray("drives")) drives.add(drive.getAsString());
            found.add(new Reassembly.Circuit(wire.get("control").getAsString(), wire.get("frequency").getAsString(), drives));
        }
        return found;
    }

    private String key(BlockPos assembler) {
        return dimension.get() + "|" + assembler.getX() + "," + assembler.getY() + "," + assembler.getZ();
    }

    private static String place(BlockPos at) {
        return at.getX() + "," + at.getY() + "," + at.getZ();
    }

    private boolean save(String key, JsonObject record) {
        try {
            String json = record.toString();
            if (json.getBytes(StandardCharsets.UTF_8).length > DOCUMENT_LIMIT) return false;
            return documents.write(SCOPE, worldKey, key, json, false);
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    // 读一份拆回记录；没有、读不出来（写法不对）都当没有。
    private Optional<JsonObject> load(String key) {
        try {
            String json = documents.read(SCOPE, worldKey, key, DOCUMENT_LIMIT);
            if (json == null) return Optional.empty();
            JsonObject record = JsonParser.parseString(json).getAsJsonObject();
            makeup(record);
            wires(record);
            VehicleControls.fromJson(record.getAsJsonObject("control_map"));
            return Optional.of(record);
        } catch (IOException | RuntimeException broken) {
            return Optional.empty();
        }
    }

    private void delete(String key) {
        try {
            documents.delete(SCOPE, worldKey, key);
        } catch (IOException | RuntimeException ignored) {
            // 删不掉的旧记录下次在同一格拆回时会被盖掉，不影响这次。
        }
    }
}
