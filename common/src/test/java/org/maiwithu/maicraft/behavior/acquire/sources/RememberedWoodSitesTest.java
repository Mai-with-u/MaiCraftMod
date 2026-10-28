// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.acquire.FakeTags;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.calc.NavGoal;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 记得的木头产地：附近看不见树、但路过记得哪里有树林时，按记得的位置报一条"走去那儿砍树"的路，
 * 数量按不知道有多少算；到了动手就是走过去，剩下的按现场重新问价。记忆里没有树林、要的不是木头，都如实说没有。
 */
class RememberedWoodSitesTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final WorldPosition HERE = WorldPosition.here(0, 100, 0);
    /** 记得的树林：水平离出发点 25 格，在默认搜索半径内；高度是当时看见它的人站的高度。 */
    private static final WorldPosition GROVE = WorldPosition.here(15, 120, 20);

    @TempDir Path temp;

    private final FakeTags tags = new FakeTags();
    private final AtomicReference<NavGoal> walkedTo = new AtomicReference<>();

    private WorldMemory memory() {
        return new WorldMemory(new DocumentStore(temp.resolve("state.sqlite")), "world-1");
    }

    private RememberedWoodSites source(WorldMemory memory) {
        return new RememberedWoodSites(memory, walks(), tags);
    }

    // 走到的替身：只记下要走到哪儿——走过去之后的事由引擎重新问价接手。
    private WalkTo walks() {
        return (target, permit) -> {
            walkedTo.set(target.goal());
            return new WalkRun() {
                @Override public ActionStatus tick(TickContext context) {
                    return ActionStatus.done();
                }
                @Override public String describe() {
                    return "走过去";
                }
                @Override public WalkReport report() {
                    return WalkReport.arrived(BlockPos.ZERO, false);
                }
                @Override public boolean resume() {
                    return false;
                }
                @Override public boolean stop() {
                    return true;
                }
            };
        };
    }

    private SourceContext context() {
        return SourceContext.unscoped(HERE, Permissions.DEFAULT);
    }

    @Test
    void 记得有树林时报一条走过去砍树的路() {
        WorldMemory memory = memory();
        memory.rememberSite(GROVE, List.of("木头"), NOW);
        SourceQuote quote = source(memory).quote(
                new ItemRequest(WantedItem.ofTag("minecraft:logs"), 4, "做工具"), context());
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, quote);
        // 只是路过看见过：数量按不知道有多少算，排在有确定货的来源后面。
        assertTrue(offer.countUnknown());
        assertEquals(AcquireVia.MINE, source(memory).via());
        assertEquals("记得的木头产地", offer.source());
        // 代价按到树林的水平距离算：线索的高度是当时看见的人站的高度，不算进远近。
        assertEquals(25, offer.cost().distanceBlocks(), 0.001);
        assertTrue(offer.risk().contains("看见过"), "说明里要写清那里只是看见过，树可能已经砍过");
        // 动手就是走进记得的那片树林：只走到那一柱列附近，高度不按线索里记的那个去凑。
        source(memory).begin(new ItemRequest(WantedItem.ofTag("minecraft:logs"), 4, "做工具"), offer, context());
        NavGoal goal = walkedTo.get();
        assertTrue(goal.isAt(new BlockPos(GROVE.x(), 64, GROVE.z())), "树长在山谷里也算到了");
        assertTrue(goal.isAt(new BlockPos(GROVE.x() + 5, 140, GROVE.z())), "树长在山脊上也算到了");
        assertFalse(goal.isAt(new BlockPos(HERE.x(), HERE.y(), HERE.z())), "原地不算走到了那片树林");
    }

    @Test
    void 点名具体原木也认记得的树林() {
        WorldMemory memory = memory();
        memory.rememberSite(GROVE, List.of("木头"), NOW);
        tags.put("minecraft:oak_log", "minecraft:logs");
        SourceQuote quote = source(memory).quote(
                new ItemRequest(WantedItem.ofItem("minecraft:oak_log"), 2, "做工具"), context());
        assertInstanceOf(SourceQuote.Offer.class, quote);
    }

    @Test
    void 记忆里没有树林时如实说没有() {
        WorldMemory memory = memory();
        memory.rememberSite(GROVE, List.of("煤矿"), NOW);
        SourceQuote quote = source(memory).quote(
                new ItemRequest(WantedItem.ofTag("minecraft:logs"), 4, "做工具"), context());
        SourceQuote.Unavailable unavailable = assertInstanceOf(SourceQuote.Unavailable.class, quote);
        assertTrue(unavailable.reason().contains("没有见过树林"));
    }

    @Test
    void 记得的树林在搜索半径之外时不报() {
        WorldMemory memory = memory();
        memory.rememberSite(WorldPosition.here(200, 100, 200), List.of("木头"), NOW);
        SourceContext scoped = SourceContext.scoped(HERE, Permissions.DEFAULT, 48, null);
        SourceQuote quote = source(memory).quote(
                new ItemRequest(WantedItem.ofTag("minecraft:logs"), 4, "做工具"), scoped);
        assertInstanceOf(SourceQuote.Unavailable.class, quote);
    }

    @Test
    void 要的不是木头时这条来源不掺和() {
        WorldMemory memory = memory();
        memory.rememberSite(GROVE, List.of("木头"), NOW);
        SourceQuote quote = source(memory).quote(
                new ItemRequest(WantedItem.ofItem("minecraft:cobblestone"), 4, "砌墙"), context());
        assertInstanceOf(SourceQuote.Unavailable.class, quote);
        // 木板这类做出来的不算：它们有合成那条路，不冒充砍树直接掉。
        SourceQuote planks = source(memory).quote(
                new ItemRequest(WantedItem.ofItem("minecraft:oak_planks"), 4, "做工具"), context());
        assertInstanceOf(SourceQuote.Unavailable.class, planks);
    }
}
