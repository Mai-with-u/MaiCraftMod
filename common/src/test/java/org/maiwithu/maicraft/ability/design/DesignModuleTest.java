// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.design.api.DesignStore;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.construction.PlannedCellCodec;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.ability.design.api.BuildingRecords;
import org.maiwithu.maicraft.ability.design.api.BuildingArchives;
import org.maiwithu.maicraft.ability.design.api.BuildingArchive;
import org.maiwithu.maicraft.behavior.construction.AnchorResolver;
import org.maiwithu.maicraft.behavior.construction.Blueprint;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.param.ParseResult;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 画图能力：画、改、看、投影、导出都当场做完，统计随结果给；图纸写错按参数问题带定位失败。 */
class DesignModuleTest {

    private static final String DIM = "minecraft:overworld";

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private final List<Blueprint> shown = new ArrayList<>();
    private final Path directory;
    private final DesignModule module;
    private final FakeSite site = FakeSite.cottage();
    private final BuildingArchives archives;
    private final List<BuildingArchive> remembered = new ArrayList<>();

    DesignModuleTest() throws Exception {
        directory = Files.createTempDirectory("design-module-");
        AnchorResolver anchors = new AnchorResolver(id -> Optional.empty(), name -> Optional.empty(),
                () -> new WorldPosition(5, 64, 5, DIM), (x, z) -> Optional.of(70));
        archives = new BuildingArchives(new DocumentStore(directory.resolve("world.sqlite")), "w".repeat(64));
        BuildingRecords records = new BuildingRecords(archives, name -> Optional.empty(), remembered::add, id -> id.equals("minecraft:chest"));
        module = new DesignModule(new DesignStore(directory.resolve("designs.sqlite")), anchors, shown::add, directory.resolve("schematics"),
                records, site);
    }

    private TaskResult run(Target target, JsonObject raw) {
        ParseResult parsed = module.spec().paramSpecs().parse(raw);
        assertTrue(parsed.ok(), () -> parsed.errors().toString());
        ParamValues params = parsed.params();
        Goal goal = Goal.of("maicraft:design", target, params);
        StepContext step = new StepContext() {
            @Override public Goal goal() { return goal; }
            @Override public int stepIndex() { return 0; }
            @Override public TickContext tick() { return null; }
        };
        return assertInstanceOf(StepDecision.Finish.class, module.decide(step)).result();
    }

    private static JsonObject operation(String name) {
        JsonObject raw = new JsonObject();
        raw.addProperty("operation", name);
        return raw;
    }

    private DesignDetails create(JsonObject drawing) {
        JsonObject raw = operation("create");
        raw.add("drawing", drawing);
        TaskResult result = run(null, raw);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        return assertInstanceOf(DesignDetails.class, result.details());
    }

    @Test
    void 读现场默认纯只读不建永久档案_即使传了name也不落档不记记忆() {
        // 床是自己放的：屋里看不见也读得到。
        site.selfPlaced.add(new BlockPos(3, 64, 1));
        site.selfPlaced.add(new BlockPos(3, 64, 2));
        JsonObject raw = operation("read_site");
        raw.addProperty("name", "庇护所");
        TaskResult result = run(new Target.Position(2, 64, 0, null), raw);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        DesignDetails details = assertInstanceOf(DesignDetails.class, result.details());
        assertEquals(new WorldPosition(0, 62, 0, DIM), details.anchor(), "设计原点是范围最小角");
        assertEquals("LAYERS", details.site().get("type").getAsString());
        assertNotNull(details.designId(), "只读勘查仍生成临时图纸");
        assertNull(details.archive(), "默认不传 save_archive 时不建永久建筑档案");
        assertEquals(1, details.layout().doors().size(), details.layout().toString());
        assertEquals(1, details.layout().beds().size());
        assertTrue(!details.layout().freeFloor().isEmpty(), "屋里有能放东西的空地");
        assertTrue(archives.find("庇护所").isEmpty(), "不落永久建筑档案");
        assertTrue(remembered.isEmpty(), "不往世界记忆写入 BUILDING 记录");
    }

    @Test
    void 读现场显式建档需传非空name_建档后指档案再读照常比对() {
        // 床是自己放的：屋里看不见也读得到。
        site.selfPlaced.add(new BlockPos(3, 64, 1));
        site.selfPlaced.add(new BlockPos(3, 64, 2));
        // 显式 save_archive=true 但没给 name：必须直接报参数错误。
        JsonObject missingName = operation("read_site");
        missingName.addProperty("save_archive", true);
        TaskResult rejected = run(new Target.Position(2, 64, 0, null), missingName);
        assertEquals(TaskResult.Status.FAILED, rejected.status());
        assertEquals(Problem.Kind.INVALID_PARAMETER, rejected.problem().kind());
        assertTrue(remembered.isEmpty());

        // 显式 save_archive=true 并传非空 name：建永久建筑档案（标记为读现场建的）并写入世界记忆。
        JsonObject raw = operation("read_site");
        raw.addProperty("name", "庇护所");
        raw.addProperty("save_archive", true);
        TaskResult result = run(new Target.Position(2, 64, 0, null), raw);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        DesignDetails details = assertInstanceOf(DesignDetails.class, result.details());
        assertEquals("庇护所", details.archive());
        BuildingArchive created = archives.find("庇护所").orElseThrow();
        assertEquals(details.designId(), created.designId(), "档案照读出来的这一版");
        // 读现场建的档存下又读回来仍记着是读来的：那一栋可能是别人的房子，圈她自己的家时不算进去。
        assertTrue(created.readFromSite(), "读现场建的档记成读来的");
        assertEquals(List.of(created), remembered);

        // 指着已有建筑档案再读（默认不传 save_archive）：读档案的范围，另给整栋比对。
        JsonObject again = operation("read_site");
        TaskResult second = run(new Target.Landmark("庇护所"), again);
        DesignDetails compared = assertInstanceOf(DesignDetails.class, second.details());
        assertTrue(compared.check().allMatch(), compared.check().toString());
        assertEquals("庇护所", compared.archive());
    }

    @Test
    void 非read_site操作传入save_archive报参数错误() {
        JsonObject createRaw = operation("create");
        createRaw.add("drawing", DesignSamples.drawing(DesignSamples.mesh("Box", "cube", new double[] {1, 1, 1}, new int[] {2, 2, 2}, "Body")));
        createRaw.addProperty("save_archive", false);
        assertEquals(Problem.Kind.INVALID_PARAMETER, run(null, createRaw).problem().kind());

        JsonObject inspectRaw = operation("inspect");
        inspectRaw.addProperty("design_id", "00000000-0000-0000-0000-000000000000");
        inspectRaw.addProperty("save_archive", true);
        assertEquals(Problem.Kind.INVALID_PARAMETER, run(null, inspectRaw).problem().kind());
    }

    @Test
    void 看图带布局() {
        JsonObject drawing = DesignSamples.drawing(DesignSamples.mesh("Box", "cube", new double[] {1, 1, 1}, new int[] {2, 2, 2}, "Body"));
        DesignDetails created = create(drawing);
        JsonObject raw = operation("inspect");
        raw.addProperty("design_id", created.designId());
        DesignDetails inspected = assertInstanceOf(DesignDetails.class, run(null, raw).details());
        assertNotNull(inspected.layout(), "看图也给这张图纸的布局");
    }

    @Test
    void 画完再改再看再导出() {
        JsonObject drawing = DesignSamples.drawing(DesignSamples.mesh("Box", "cube", new double[] {1, 1, 1}, new int[] {2, 2, 2}, "Body"));
        drawing.addProperty("name", "小盒");
        DesignDetails created = create(drawing);
        assertNotNull(created.designId());
        assertNull(created.parentDesignId());
        assertEquals(8, created.cellCount());
        assertEquals(8, created.materials().get("minecraft:stone"));
        assertEquals(List.of(0, 0, 0), created.bounds().min());

        JsonObject raw = operation("update");
        raw.addProperty("design_id", created.designId());
        raw.add("edits", DesignSamples.json("{\"objects\":[{\"name\":\"Box\",\"dimensions\":[4,2,2]}]}"));
        TaskResult updated = run(null, raw);
        DesignDetails revised = assertInstanceOf(DesignDetails.class, updated.details());
        assertEquals(created.designId(), revised.parentDesignId(), "改出新的一版，父版本留着");
        assertEquals(16, revised.cellCount(), "只改尺寸，材质还是原来的");

        raw = operation("inspect");
        raw.addProperty("design_id", revised.designId());
        DesignDetails looked = assertInstanceOf(DesignDetails.class, run(null, raw).details());
        assertNotNull(looked.inspection());
        assertNull(looked.cellCount(), "看图不重复报统计");

        raw = operation("export");
        raw.addProperty("design_id", revised.designId());
        DesignDetails exported = assertInstanceOf(DesignDetails.class, run(null, raw).details());
        assertTrue(Files.exists(Path.of(exported.file())), exported.file());
        assertTrue(exported.file().endsWith(".json"));
    }

    @Test
    void 投影落到锚点交给叠加() {
        DesignDetails created = create(DesignSamples.drawing(DesignSamples.mesh("Box", "cube", new double[] {0.5, 0.5, 0.5}, new int[] {1, 1, 1}, "Body")));
        JsonObject raw = operation("preview");
        raw.addProperty("design_id", created.designId());
        raw.addProperty("rotation", 90);
        TaskResult result = run(new Target.Position(20, null, 3, null), raw);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(1, shown.size());
        assertEquals(new BlockPos(20, 70, 3), shown.getFirst().anchor(), "省略 y 取地表");
        assertEquals(DIM, shown.getFirst().dimension());

        raw.addProperty("rotation", 45);
        assertEquals(Problem.Kind.INVALID_PARAMETER, run(new Target.Here(), raw).problem().kind());
        raw.addProperty("rotation", 0);
        assertEquals(Problem.Kind.NOT_FOUND, run(new Target.Landmark("家"), raw).problem().kind());
    }

    @Test
    void 写错按参数问题带定位() {
        JsonObject raw = operation("create");
        raw.add("drawing", DesignSamples.drawing(DesignSamples.mesh("Box", "cube", new double[] {1, 1, 1}, new int[] {2, 2, 2}, "没有的材质")));
        TaskResult result = run(null, raw);
        assertEquals(Problem.Kind.INVALID_PARAMETER, result.problem().kind());
        assertTrue(result.summary().contains("Box"), "定位到对象：" + result.summary());

        assertEquals(Problem.Kind.INVALID_PARAMETER, run(null, operation("create")).problem().kind(), "create 没给 drawing");
        raw = operation("inspect");
        raw.addProperty("design_id", "00000000-0000-0000-0000-000000000000");
        assertEquals(Problem.Kind.INVALID_PARAMETER, run(null, raw).problem().kind(), "编号不存在");
    }

    @Test
    void 退役建筑档案_显式标记removed解除保护_重复退役与不存在各报准确问题() throws Exception {
        // 先经 read_site + save_archive 建一份永久建筑档案。
        JsonObject save = operation("read_site");
        save.addProperty("name", "旧木屋");
        save.addProperty("save_archive", true);
        assertEquals(TaskResult.Status.DONE, run(new Target.Position(2, 64, 0, null), save).status());
        String revBefore = archives.revision();
        assertTrue(!archives.find("旧木屋").orElseThrow().removed());

        // 显式 remove_archive 退役：落盘 removed=true、蓝图保留、修订号推进。
        JsonObject remove = operation("remove_archive");
        remove.addProperty("name", "旧木屋");
        TaskResult retired = run(null, remove);
        assertEquals(TaskResult.Status.DONE, retired.status(), retired.summary());
        assertEquals("旧木屋", assertInstanceOf(DesignDetails.class, retired.details()).archive());
        assertTrue(archives.find("旧木屋").orElseThrow().removed(), "退役标记已落盘");
        assertTrue(!archives.revision().equals(revBefore), "退役后档案修订号变化以刷新结构保护快照");
        assertTrue(archives.containing(DIM, new BlockPos(2, 64, 0)).isEmpty(), "已退役档案不再命中 containing");

        // 重复退役：报 NOT_POSSIBLE_HERE。
        TaskResult again = run(new Target.Landmark("旧木屋"), operation("remove_archive"));
        assertEquals(TaskResult.Status.FAILED, again.status());
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, again.problem().kind());

        // 不存在的档案名：报 NOT_FOUND；缺少名字：报 INVALID_PARAMETER。
        JsonObject missing = operation("remove_archive");
        missing.addProperty("name", "不存在的房子");
        assertEquals(Problem.Kind.NOT_FOUND, run(null, missing).problem().kind());
        assertEquals(Problem.Kind.INVALID_PARAMETER, run(null, operation("remove_archive")).problem().kind());
    }

    @Test
    void 建筑档案兼容旧JSON_缺省removed字段反序列化为false() throws Exception {
        DocumentStore store = new DocumentStore(directory.resolve("legacy-building.sqlite"));
        String worldKey = "l".repeat(64);
        JsonObject root = new JsonObject();
        root.addProperty("name", "老木屋");
        root.addProperty("dimension", DIM);
        root.addProperty("anchor", new BlockPos(10, 64, 10).asLong());
        root.addProperty("rotation", 0);
        JsonArray cells = new JsonArray();
        cells.add(PlannedCellCodec.encode(PlannedCell.block(new BlockPos(0, 0, 0), Blocks.OAK_PLANKS.defaultBlockState(), Set.of())));
        cells.add(PlannedCellCodec.encode(PlannedCell.block(new BlockPos(1, 0, 0), Blocks.OAK_PLANKS.defaultBlockState(), Set.of())));
        root.add("cells", cells);
        // 旧版档案 JSON 不包含 removed 字段。
        assertTrue(store.write("building-archive", worldKey, "老木屋", root.toString(), false));
        BuildingArchives loaded = new BuildingArchives(store, worldKey);
        BuildingArchive archive = loaded.find("老木屋").orElseThrow();
        assertTrue(!archive.removed(), "旧档缺少 removed 字段应默认视为在役（false）");
        assertEquals(2L, archive.nonAirCellCount());
    }
}
