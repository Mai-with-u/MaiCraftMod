// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.FakeTags;
import org.maiwithu.maicraft.behavior.acquire.StubTick;
import org.maiwithu.maicraft.behavior.acquire.spi.FailedOnlyAtSpots;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.acquire.spi.UnreachablePlaces;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.inventory.containers.ContainerChooser;
import org.maiwithu.maicraft.behavior.inventory.containers.SpotsContainers;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 走不到跟前的箱子：开箱取货按靠近收场为到不了时，这只箱子记进这一次的"走不到的地方"，
 * 后面别的东西不再去问它、只算又问到一次；别的原因没拿到（超时、点不开）不记，下一样照常问。
 */
class ContainerSourceUnreachableTest {

    private static final WorldPosition CHEST_AT = new WorldPosition(10, 64, 5, null);
    private static final Protection NOBODY_OWNS = new Protection((dimension, x, y, z) -> Optional.empty(),
            List::of, name -> Optional.empty(), "self");

    private final UnreachablePlaces places = new UnreachablePlaces();
    private final SourceContext context = SourceContext.unscoped(WorldPosition.here(0, 64, 0), Permissions.DEFAULT)
            .withUnreachable(places);

    // 只有一只没开过的箱子；开箱取货按给的问题失败。
    private ContainerSource source(Problem.Kind failsWith) {
        ContainerChooser.Candidate chest = new ContainerChooser.Candidate("chest（10, 64, 5）", "minecraft:chest",
                CHEST_AT, null, true, 11, false, true, ContainerChooser.Lid.CLEAR, false);
        SpotsContainers spots = new SpotsContainers() {
            @Override public List<ContainerChooser.Candidate> around(BlockPos center, int radius) {
                return List.of(chest);
            }

            @Override public boolean scanComplete() {
                return true;
            }

            @Override public Optional<ContainerChooser.Candidate> at(WorldPosition position) {
                return Optional.of(chest);
            }
        };
        return new ContainerSource(spots, new FakeTags(), NOBODY_OWNS, (container, wants, permissions) ->
                Optional.of(new Action() {
                    @Override public ActionStatus tick(TickContext tick) {
                        return ActionStatus.failed(Problem.of(failsWith, "走不到 chest（10, 64, 5）跟前：没有路", null));
                    }

                    @Override public String describe() {
                        return "开箱取货";
                    }
                }));
    }

    private static ItemRequest wanted(String itemId) {
        return new ItemRequest(WantedItem.ofItem(itemId), 1, "备料");
    }

    // 按报价动手推进一刻，返回那一刻的结论。
    private ActionStatus tryOnce(ContainerSource source, ItemRequest request) {
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source.quote(request, context));
        Action take = source.begin(request, offer, context).orElseThrow();
        return take.tick(new StubTick(1));
    }

    @Test
    void unreachableChestIsNotAskedAgainForTheNextItem() {
        ContainerSource source = source(Problem.Kind.UNREACHABLE);
        assertInstanceOf(ActionStatus.Failed.class, tryOnce(source, wanted("minecraft:coal")));

        // 下一样原料：不再报价，交回"走不到、不再去问"的那一句，由引擎在最外层合成说。
        SourceQuote.Unavailable skipped = assertInstanceOf(SourceQuote.Unavailable.class,
                source.quote(wanted("minecraft:stick"), context));
        assertEquals(UnreachablePlaces.SKIPPED, skipped.reason());
        assertEquals(List.of("记得的箱子 10, 64, 5 走不到（问了 2 种东西）：走不到 chest（10, 64, 5）跟前：没有路"),
                places.lines());
    }

    @Test
    void otherFailuresDoNotMarkTheChestUnreachable() {
        // 开箱超时、点不开这类：箱子走得到，下一样照常问它。
        ContainerSource source = source(Problem.Kind.STUCK);
        assertInstanceOf(ActionStatus.Failed.class, tryOnce(source, wanted("minecraft:coal")));
        assertInstanceOf(SourceQuote.Offer.class, source.quote(wanted("minecraft:stick"), context));
        assertTrue(places.lines().isEmpty());
    }

    @Test
    void withoutUnreachablePlacesTheChestIsAskedAsBefore() {
        // 问价时没带这一次的记录（单独问价、估算）：照旧报价。
        ContainerSource source = source(Problem.Kind.UNREACHABLE);
        SourceContext plain = SourceContext.unscoped(WorldPosition.here(0, 64, 0), Permissions.DEFAULT);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source.quote(wanted("minecraft:coal"), plain));
        assertInstanceOf(ActionStatus.Failed.class,
                source.begin(wanted("minecraft:coal"), offer, plain).orElseThrow().tick(new StubTick(1)));
        assertInstanceOf(SourceQuote.Offer.class, source.quote(wanted("minecraft:stick"), plain));
    }

    @Test
    void unsupportedContainerIsCachedAcrossRunsAndSkippedInRecursiveAcquire() {
        int[] clicks = {0};
        String[] blockType = {"somemod:mystery_chest"};
        SpotsContainers spots = new SpotsContainers() {
            private ContainerChooser.Candidate candidate() {
                return new ContainerChooser.Candidate("mystery（10, 64, 5）", blockType[0],
                        CHEST_AT, null, true, 5, false, true, ContainerChooser.Lid.CLEAR, false);
            }

            @Override public List<ContainerChooser.Candidate> around(BlockPos center, int radius) {
                return List.of(candidate());
            }

            @Override public boolean scanComplete() {
                return true;
            }

            @Override public Optional<ContainerChooser.Candidate> at(WorldPosition position) {
                return Optional.of(candidate());
            }
        };
        ContainerSource source = new ContainerSource(spots, new FakeTags(), NOBODY_OWNS, (container, wants, permissions) ->
                Optional.of(new Action() {
                    @Override public ActionStatus tick(TickContext tick) {
                        clicks[0]++;
                        return ActionStatus.failed(Problem.of(Problem.Kind.UNSUPPORTED,
                                "这种界面认不出两侧怎么分，一格都不点", null));
                    }

                    @Override public String describe() {
                        return "开箱取货";
                    }
                }));

        // 第一趟取货遇到不认识的模组箱子：点一次返回 UNSUPPORTED，且标记为仅该点失败不连坐其余箱子。
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source.quote(wanted("minecraft:coal"), context));
        Action take = source.begin(wanted("minecraft:coal"), offer, context).orElseThrow();
        assertInstanceOf(ActionStatus.Failed.class, take.tick(new StubTick(1)));
        assertTrue(take instanceof FailedOnlyAtSpots spotOnly && spotOnly.onlyThoseSpotsFailed());
        assertEquals(1, clicks[0]);

        // 同场递归备料再问下一样原料：直接跳过，记录为界面不支持而非走不到。
        SourceQuote.Unavailable skippedInSameRun = assertInstanceOf(SourceQuote.Unavailable.class,
                source.quote(wanted("minecraft:stick"), context));
        assertEquals(UnreachablePlaces.SKIPPED, skippedInSameRun.reason());
        assertTrue(places.lines().getFirst().contains("界面不支持"), places.lines().toString());
        assertTrue(places.lines().getFirst().contains("问了 2 种东西"), places.lines().toString());

        // 换一份全新的 UnreachablePlaces（模拟下一次 Run）：会话级负缓存仍生效，绝不再报价点击同一只模组箱子。
        UnreachablePlaces nextRunPlaces = new UnreachablePlaces();
        SourceContext nextRun = SourceContext.unscoped(WorldPosition.here(0, 64, 0), Permissions.DEFAULT)
                .withUnreachable(nextRunPlaces);
        SourceQuote.Unavailable skippedAcrossRun = assertInstanceOf(SourceQuote.Unavailable.class,
                source.quote(wanted("minecraft:iron_ingot"), nextRun));
        assertEquals(UnreachablePlaces.SKIPPED, skippedAcrossRun.reason());
        assertEquals(1, clicks[0], "跨 Run 会话级负缓存命中时绝不重复点击");
        assertTrue(nextRunPlaces.lines().getFirst().contains("界面不支持"), nextRunPlaces.lines().toString());

        // 若该位置原地被拆换为普通箱子（方块类型变了），新一趟取货中会话级负缓存自动解除，恢复正常报价。
        blockType[0] = "minecraft:chest";
        SourceContext replacedRun = SourceContext.unscoped(WorldPosition.here(0, 64, 0), Permissions.DEFAULT)
                .withUnreachable(new UnreachablePlaces());
        assertInstanceOf(SourceQuote.Offer.class, source.quote(wanted("minecraft:iron_ingot"), replacedRun));
    }
}
