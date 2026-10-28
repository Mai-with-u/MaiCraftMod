// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.maiwithu.maicraft.kernel.knowledge.KnowledgeDocument;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeSource;

/**
 * 载具部件的资料，随包一篇：飞艇和车各要哪些部件、怎么摆、常见的坑。只有 Simulated 一套接上时才登记，
 * 没装的实例资料目录里不出现它；Simulated、Create Aeronautics、Offroad 三个模组的物品资料页经"相关资料"挂上它，
 * LLM 查一个螺旋桨轴承也能顺着找到整艘船该怎么搭。从搭到开的先后写在技能 create-vehicles 里，这里只放部件事实。
 */
final class VehiclePartsKnowledge implements KnowledgeSource {

    static final String URI = "maicraft://knowledge/vehicle/parts";
    static final String RESOURCE = "/assets/maicraft/knowledge/vehicle/parts.md";
    /** 这一套三个模组的命名空间：它们的物品资料页都挂这一篇。 */
    private static final Set<String> NAMESPACES = Set.of("simulated", "aeronautics", "offroad");
    private static final String TITLE = "载具部件：飞艇、固定翼和车怎么搭";
    private static final String DESCRIPTION = "升力、机翼、推进、转向、车轮、无线红石打字机与收端、胶与物理组装器各怎么摆，以及组装和起飞常见的坑";
    private static final KnowledgeDocument.Entry ENTRY = new KnowledgeDocument.Entry(URI, "vehicle.parts", TITLE, DESCRIPTION,
            "载具 飞艇 飞船 固定翼 飞机 机翼 跑道 起落架 小车 汽车 车 气球 蒙皮 燃烧室 燃烧器 浮空水晶 升力 螺旋桨 桨 风帆 推进 舵 转向 车轮 悬架 轮胎 刹车 "
                    + "物理组装器 组装 强力胶 胶 无线红石打字机 收端 频率 便携式引擎 定向齿轮换向器 扭力弹簧 配重 重心 "
                    + "airship plane airplane wing runway car vehicle balloon envelope burner levitite propeller sail wheel tire assembler glue "
                    + "simulated aeronautics offroad");

    @Override public List<KnowledgeDocument.Entry> entries() {
        return List.of(ENTRY);
    }

    // 只认这一篇的地址；别的地址不归这里管，交回资料库统一说找不到。
    @Override public KnowledgeDocument read(String uri) {
        if (!URI.equals(uri)) return null;
        return new KnowledgeDocument(URI, ENTRY.name(), TITLE, DESCRIPTION, text());
    }

    // 查这一套三个模组里任何一样东西的资料页时，都把这一篇列进相关资料：单看一个部件不知道整艘怎么搭。
    @Override public List<KnowledgeDocument.Entry> entriesAbout(String registryId) {
        int colon = registryId.indexOf(':');
        return colon > 0 && NAMESPACES.contains(registryId.substring(0, colon)) ? List.of(ENTRY) : List.of();
    }

    @Override public String status() {
        return "载具部件资料：可用，1 篇";
    }

    // 正文随包，读的时候才取：篇幅小、很少有人读，不为它在登记时多一道会失败的步骤。
    private static String text() {
        try (InputStream input = VehiclePartsKnowledge.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("缺少随包资料：" + RESOURCE);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("读随包资料失败：" + RESOURCE, failure);
        }
    }
}
