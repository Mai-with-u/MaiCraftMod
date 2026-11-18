// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.engine.AcquisitionSelector;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.acquire.spi.UnreachablePlaces;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.inventory.drops.PicksUpDrops;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.ObservationVisibility;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 附近可见地面掉落物来源与贴地掉落物视线悬浮采样单元测试：
 * 核对地上掉落物排在同距离容器之前、按实体编号逐件追捡凑够即停、够不着记入走不到的位置，
 * 以及 {@code 0.25} 高贴地掉落物在敞开台阶与有封盖时的悬浮采样边界。
 */
class GroundDropSourceTest {

    private static final WorldPosition HERE = WorldPosition.here(0, 64, 0);
    private static final TickContext TICK = new TickContext() {
        @Override public long gameTick() { return 1L; }
        @Override public PlayerContext player() { return null; }
    };

    private static final class MutableBackpack implements BackpackView {
        final List<BackpackStack> stacks = new ArrayList<>();

        @Override public List<BackpackStack> stacks() { return List.copyOf(stacks); }
        @Override public int usedSlots() { return stacks.size(); }
        @Override public int totalSlots() { return 36; }

        void add(String itemId, int count) {
            stacks.add(new BackpackStack(itemId, count, 64, false, false, false, false));
        }
    }

    private static GroundDropSource.VisibleDrop drop(int entityId, String itemId, int count, int x, double dist) {
        return new GroundDropSource.VisibleDrop(entityId, itemId, itemId, count,
                WorldPosition.here(x, 63, 0), dist);
    }

    @Test
    void 附近可见地面掉落物按地上拾取报价且优先于同距离容器() {
        MutableBackpack backpack = new MutableBackpack();
        GroundDropSource source = new GroundDropSource(
                radius -> List.of(drop(11, "minecraft:iron_ingot", 3, 4, 4.0)),
                itemId -> Set.of(),
                new PicksUpDrops() {
                    @Override public Set<Integer> nearby() { return Set.of(); }
                    @Override public Action pickUpNewSince(Set<Integer> before) { throw new AssertionError(); }
                },
                backpack);

        assertEquals(AcquireVia.PICKUP, source.via());
        ItemRequest request = new ItemRequest(WantedItem.ofItem("minecraft:iron_ingot"), 2, "测试拾取");
        SourceQuote.Offer dropOffer = assertInstanceOf(SourceQuote.Offer.class,
                source.quote(request, SourceContext.unscoped(HERE, Permissions.DEFAULT)));
        assertEquals(2, dropOffer.obtainableCount());

        // 同在 4 格外的已知箱子需要开关界面（4 个动作），地面掉落物直接走过去吸进包（1 个动作），排在箱子前面。
        SourceQuote.Offer chestOffer = new SourceQuote.Offer("记得的箱子", 2, new AcquisitionCost(4.0, 4), "", "4,64,0");
        List<SourceQuote.Offer> chosen = AcquisitionSelector.choose(List.of(chestOffer, dropOffer), 2);
        assertEquals(List.of(dropOffer), chosen);
    }

    @Test
    void 多件散落掉落物按由近到远追捡_凑够件数即停() {
        MutableBackpack backpack = new MutableBackpack();
        List<Integer> pickedIds = new ArrayList<>();
        List<GroundDropSource.VisibleDrop> worldDrops = List.of(
                drop(21, "minecraft:cobblestone", 2, 2, 2.0),
                drop(22, "minecraft:cobblestone", 2, 5, 5.0),
                drop(23, "minecraft:cobblestone", 4, 9, 9.0));
        PicksUpDrops pickup = new PicksUpDrops() {
            @Override public Set<Integer> nearby() { return Set.of(); }
            @Override public Action pickUpNewSince(Set<Integer> before) { throw new AssertionError(); }
            @Override public Action pickUpOne(int entityId) {
                return new Action() {
                    @Override public ActionStatus tick(TickContext context) {
                        pickedIds.add(entityId);
                        backpack.add("minecraft:cobblestone", 2);
                        return ActionStatus.done();
                    }
                    @Override public String describe() { return "追捡 " + entityId; }
                };
            }
        };
        GroundDropSource source = new GroundDropSource(radius -> worldDrops, itemId -> Set.of(), pickup, backpack);
        ItemRequest request = new ItemRequest(WantedItem.ofItem("minecraft:cobblestone"), 3, "拿圆石");
        SourceContext context = SourceContext.unscoped(HERE, Permissions.DEFAULT);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source.quote(request, context));

        Action action = source.begin(request, offer, context).orElseThrow();
        ActionStatus first = action.tick(TICK);
        assertInstanceOf(ActionStatus.Running.class, first);
        ActionStatus second = action.tick(TICK);
        assertInstanceOf(ActionStatus.Done.class, second);
        assertEquals(List.of(21, 22), pickedIds, "前两堆已凑够 4 个，不再跑去捡第 3 堆");
    }

    @Test
    void 走不到的地面掉落物记入不可达位置_下轮问价自动跳过() {
        MutableBackpack backpack = new MutableBackpack();
        UnreachablePlaces unreachable = new UnreachablePlaces();
        List<GroundDropSource.VisibleDrop> worldDrops = List.of(drop(31, "minecraft:diamond", 1, 6, 6.0));
        PicksUpDrops blockedPickup = new PicksUpDrops() {
            @Override public Set<Integer> nearby() { return Set.of(); }
            @Override public Action pickUpNewSince(Set<Integer> before) { throw new AssertionError(); }
            @Override public Action pickUpOne(int entityId) {
                return new Action() {
                    @Override public ActionStatus tick(TickContext context) {
                        return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE, "悬崖底下走不过去"));
                    }
                    @Override public String describe() { return "追捡失败"; }
                };
            }
        };
        GroundDropSource source = new GroundDropSource(radius -> worldDrops, itemId -> Set.of(),
                blockedPickup, backpack);
        ItemRequest request = new ItemRequest(WantedItem.ofItem("minecraft:diamond"), 1, "捡钻石");
        SourceContext context = SourceContext.unscoped(HERE, Permissions.DEFAULT).withUnreachable(unreachable);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source.quote(request, context));
        Action action = source.begin(request, offer, context).orElseThrow();

        ActionStatus status = action.tick(TICK);
        assertInstanceOf(ActionStatus.Failed.class, status);
        SourceQuote nextQuote = source.quote(request, context);
        SourceQuote.Unavailable unavailable = assertInstanceOf(SourceQuote.Unavailable.class, nextQuote);
        assertEquals(UnreachablePlaces.SKIPPED, unavailable.reason());
    }

    @Test
    void 贴地掉落物悬浮采样在敞开格向上延伸_被半砖压顶时不越过封盖() {
        AABB groundItemBox = new AABB(11.375, 63.0, 10.375, 11.625, 63.25, 10.625);
        // 敞开台阶净空无顶盖时，采样点向上延伸到 63.85，越过 y=64 台阶唇沿可见。
        List<Vec3> openSamples = ObservationVisibility.lowItemHoverSamples(groundItemBox, 63.85);
        assertTrue(!openSamples.isEmpty());
        assertTrue(openSamples.stream().anyMatch(point -> point.y > 63.8 && point.y <= 63.85));

        // 头顶紧贴 63.25 被封盖挡住时，不产生穿出封盖上方的采样点。
        List<Vec3> cappedSamples = ObservationVisibility.lowItemHoverSamples(groundItemBox, 63.26);
        assertTrue(cappedSamples.isEmpty());
    }
}
