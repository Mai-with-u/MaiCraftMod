// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.param.ParseResult;

/**
 * 入参本地化名称反查测试：验证中文名唯一解析、多候选报错提示，以及 ParamSpecs 接入后的解析与回退。
 */
class LocalizedIdResolverTest {

    private static final ReadsLocalizedNames FAKE_NAMES = new ReadsLocalizedNames() {
        private final Map<IdKind, List<NamedId>> table = Map.of(
                IdKind.BLOCK, List.of(
                        new NamedId("touhou_little_maid:shrine", "神龛"),
                        new NamedId("touhou_little_maid:altar", "祭坛"),
                        new NamedId("create:mechanical_mixer", "动力搅拌器")),
                IdKind.ITEM, List.of(
                        new NamedId("touhou_little_maid:smart_slab_empty", "魂符"),
                        new NamedId("touhou_little_maid:smart_slab_has_maid", "魂符（已有女仆）"),
                        new NamedId("touhou_little_maid:smart_slab_init", "初始魂符"),
                        new NamedId("touhou_little_maid:hakurei_gohei", "博丽御币")),
                IdKind.ENTITY_TYPE, List.of(
                        new NamedId("touhou_little_maid:maid", "女仆"),
                        new NamedId("minecraft:sheep", "绵羊")));

        @Override
        public Optional<String> labelOf(IdKind kind, String id) {
            return table.getOrDefault(kind, List.of()).stream()
                    .filter(entry -> entry.id().equals(id))
                    .map(NamedId::label)
                    .findFirst();
        }

        @Override
        public List<NamedId> entries(IdKind kind) {
            return table.getOrDefault(kind, List.of());
        }
    };

    @Test
    void 中文精确匹配唯一时自动解析为标准英文编号() {
        LocalizedIdResolver.Resolution shrine = LocalizedIdResolver.resolve(FAKE_NAMES, IdKind.BLOCK, "神龛");
        LocalizedIdResolver.Resolution.Resolved matched = assertInstanceOf(
                LocalizedIdResolver.Resolution.Resolved.class, shrine);
        assertEquals("touhou_little_maid:shrine", matched.id());
        assertEquals("神龛", matched.label());

        // 精确命中唯一项时优先于包含它的其余近似词（例如"魂符"精确命中 smart_slab_empty）。
        LocalizedIdResolver.Resolution exactSlab = LocalizedIdResolver.resolve(FAKE_NAMES, IdKind.ITEM, "魂符");
        LocalizedIdResolver.Resolution.Resolved slabMatched = assertInstanceOf(
                LocalizedIdResolver.Resolution.Resolved.class, exactSlab);
        assertEquals("touhou_little_maid:smart_slab_empty", slabMatched.id());
    }

    @Test
    void 中文模糊匹配唯一时自动解析_多候选时返回候选清单() {
        // 模糊命中唯一项："御币" 只命中博丽御币。
        LocalizedIdResolver.Resolution gohei = LocalizedIdResolver.resolve(FAKE_NAMES, IdKind.ITEM, "御币");
        LocalizedIdResolver.Resolution.Resolved only = assertInstanceOf(
                LocalizedIdResolver.Resolution.Resolved.class, gohei);
        assertEquals("touhou_little_maid:hakurei_gohei", only.id());

        // 模糊命中多个候选："初始"之外的 "符" 命中全部三张魂符，返回清晰候选列表。
        LocalizedIdResolver.Resolution ambiguous = LocalizedIdResolver.resolve(FAKE_NAMES, IdKind.ITEM, "符");
        LocalizedIdResolver.Resolution.Ambiguous multiple = assertInstanceOf(
                LocalizedIdResolver.Resolution.Ambiguous.class, ambiguous);
        assertEquals(3, multiple.total());
        String message = LocalizedIdResolver.ambiguousMessage(IdKind.ITEM, "符", multiple);
        assertTrue(message.contains("touhou_little_maid:smart_slab_empty (魂符)"), message);
        assertTrue(message.contains("touhou_little_maid:smart_slab_has_maid (魂符（已有女仆）)"), message);
        assertTrue(message.contains("touhou_little_maid:smart_slab_init (初始魂符)"), message);
    }

    @Test
    void 参数规格按中文名解析方块物品与实体并拒绝多候选歧义() {
        ParamSpecs specs = ParamSpecs.of(
                ParamSpec.of("block", ParamType.BLOCK_OR_TAG).doc("方块").build(),
                ParamSpec.of("item", ParamType.ITEM_OR_TAG).doc("物品").build(),
                ParamSpec.of("entity", ParamType.ENTITY_TYPE).doc("实体").build());

        // 全部传中文唯一名时，自动换为标准英文 ID 并在 notes 里记录换算。
        ParseResult ok = specs.parse(JsonParser.parseString("""
                {"block": "神龛", "item": "御币", "entity": "女仆"}""").getAsJsonObject(), FAKE_NAMES);
        assertTrue(ok.ok(), ok.errors()::toString);
        assertEquals("touhou_little_maid:shrine", ok.params().text("block"));
        assertEquals("touhou_little_maid:hakurei_gohei", ok.params().text("item"));
        assertEquals("touhou_little_maid:maid", ok.params().text("entity"));
        assertEquals(3, ok.notes().size(), ok.notes()::toString);

        // 传多候选中文名时，返回清晰参数错误并附带 id (中文名) 候选。
        ParseResult ambiguous = specs.parse(JsonParser.parseString("""
                {"item": "符"}""").getAsJsonObject(), FAKE_NAMES);
        assertFalse(ambiguous.ok());
        assertEquals(1, ambiguous.errors().size());
        assertTrue(ambiguous.errors().getFirst().message().contains("touhou_little_maid:smart_slab_empty (魂符)"));

        // 标准英文 ID 与完全不匹配的字符串不受影响。
        ParseResult standard = specs.parse(JsonParser.parseString("""
                {"block": "minecraft:chest"}""").getAsJsonObject(), FAKE_NAMES);
        assertTrue(standard.ok());
        assertEquals("minecraft:chest", standard.params().text("block"));
        assertTrue(standard.notes().isEmpty());

        ParseResult bad = specs.parse(JsonParser.parseString("""
                {"block": "不存在的奇怪名字"}""").getAsJsonObject(), FAKE_NAMES);
        assertFalse(bad.ok());
        assertTrue(bad.errors().getFirst().message().contains("写法不对"));
    }
}
