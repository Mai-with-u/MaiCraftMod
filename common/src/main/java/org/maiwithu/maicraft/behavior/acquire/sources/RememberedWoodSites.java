// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.acquire.engine.ReadsItemTags;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.perception.FeatureSpotter;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryRecord;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 记得的木头产地：世界记忆里"这一片见过树林"的产地线索。附近看不见会掉原木的方块时
 * （树在山脊上、视线被地形挡住是常事），按记得的位置报一条"走去那儿砍树"的路，
 不让规划里只剩走得更远的记忆箱子。
 产地线索只是路过时看见过，树可能已经砍过：数量按"不知道有多少"报，排在有确定货的来源后面；
 到了那儿按眼前的现场重新问价——树上还在就由采掘接手，只剩空地就这一条如实收场换别的路，不把过时的记忆当成有货。
 */
public final class RememberedWoodSites implements ItemSource {

    /** 找记得的产地的半径：与采掘看现场的范围一致，再远就该走跨区出行，不在这里顺手砍。 */
    public static final int SEARCH_RADIUS_BLOCKS = MiningSource.SEARCH_RADIUS_BLOCKS;
    /** 原木的物品标签：问的是这类东西时，"记得哪里有树林"才算一条来路。 */
    private static final String LOGS_TAG = "minecraft:logs";

    private final WorldMemory memory;
    private final BringsPlayerClose bringsClose;
    private final ReadsItemTags tags;

    public RememberedWoodSites(WorldMemory memory, BringsPlayerClose bringsClose, ReadsItemTags tags) {
        this.memory = memory;
        this.bringsClose = bringsClose;
        this.tags = tags;
    }

    @Override public String describe() {
        return "记得的木头产地";
    }

    @Override public AcquireVia via() {
        return AcquireVia.MINE;
    }

    @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
        if (!wantsWood(request)) {
            return new SourceQuote.Unavailable(describe(),
                    "只记得哪里看见过成片的树，" + request.wanted().describe() + "没有记得的产地");
        }
        WorldPosition here = context.characterAt();
        // 记得的位置近的在前（recordsNear 已按远近排），报最近的一片：真过去时围着它走，看见别的树也一样砍。
        Optional<MemoryRecord> nearest = memory.recordsNear(here, searchRadius(context)).stream()
                .filter(record -> record.kind() == MemoryKind.SITE)
                .filter(record -> record.contents() != null
                        && record.contents().contains(FeatureSpotter.WOOD_CLUE))
                .findFirst();
        if (nearest.isEmpty()) {
            return new SourceQuote.Unavailable(describe(),
                    "半径 " + searchRadius(context) + " 格内记得的产地里没有见过树林的（只算路过时亲眼看见过的）");
        }
        MemoryRecord site = nearest.get();
        return new SourceQuote.Offer(describe(), SourceQuote.Offer.UNKNOWN_COUNT,
                new AcquisitionCost(RememberedStations.distance(site.position(), here), 1),
                "那里只是路过看见过有树，过去才知道还在不在（可能已经砍过）；到了按眼前的现场重新问价",
                positionHint(site.position()));
    }

    @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        // 动手就是走到报价认的那片产地：到了之后方块扫描看得见树时，下一次问价自然由采掘接手报价；
        // 树已经被砍过、到了一片空地，引擎清点后一件没拿到就把这条划掉换别的路，不围着空地反复走。
        WorldPosition site = parsePosition(offer.hint());
        return Optional.of(bringsClose.toward(ApproachTarget.ofBlockToDig(
                        new BlockPos(site.x(), site.y(), site.z())),
                context.permissions(), context.records()));
    }

    // 要的是不是木头：点名 #minecraft:logs 的算，点名具体物品、物品挂着原木标签的也算；
    // 木板、木棍这类做出来的不算——它们有合成那条路，不冒充"砍树直接掉"。
    private boolean wantsWood(ItemRequest request) {
        return request.wanted().isTag() ? LOGS_TAG.equals(request.wanted().tagId())
                : tags.tagsOf(request.wanted().itemId()).contains(LOGS_TAG);
    }

    /** 这次搜多大范围：任务给了半径就在这个范围里找（给了就不越界），没给用来源自己的默认。 */
    private static int searchRadius(SourceContext context) {
        return context.radiusBlocks() == null ? SEARCH_RADIUS_BLOCKS : context.radiusBlocks();
    }

    /** 报价认位置的写法：引擎不解读，动手时原样带回去查现场与记忆。 */
    private static String positionHint(WorldPosition at) {
        return at.x() + "," + at.y() + "," + at.z() + (at.dimension() == null ? "" : "@" + at.dimension());
    }

    private static WorldPosition parsePosition(String hint) {
        String[] parts = hint.split("@")[0].split(",");
        String dimension = hint.contains("@") ? hint.split("@")[1] : null;
        return new WorldPosition(
                Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), dimension);
    }
}
