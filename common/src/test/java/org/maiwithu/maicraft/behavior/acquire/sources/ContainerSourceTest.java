// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Optional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.inventory.containers.ContainerChooser;
import org.maiwithu.maicraft.behavior.inventory.containers.KnownContainer;
import org.maiwithu.maicraft.behavior.inventory.containers.SpotsContainers;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryRecord;
import org.maiwithu.maicraft.behavior.permission.GuessesPlayerMade;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsBlockOwnership;
import org.maiwithu.maicraft.behavior.permission.TrustedPlayers;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.StubTick;
import org.maiwithu.maicraft.behavior.acquire.FakeTags;

/**
 * 容器的来源：开过、记了内容的箱子报明确的数；只看见过、现场扫到的报"不知道有多少"；
 * 动手时按报价认的那只箱子交开箱取物的接缝，箱子没了就交回空。
 */
class ContainerSourceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final WorldPosition CHEST_AT = new WorldPosition(10, 64, 5, null);
    private static final SourceContext CONTEXT = SourceContext.unscoped(
            WorldPosition.here(0, 64, 0), Permissions.DEFAULT);

    /** 谁的箱子都不是：归属问过了、没人放过、也不在谁的地盘里。 */
    private static final Protection NOBODY_OWNS = new Protection((dimension, x, y, z) -> Optional.empty(),
            List::of, name -> Optional.empty(), GuessesPlayerMade.NOTHING, "self");

    @TempDir Path temp;

    private final FakeTags tags = new FakeTags();

    private WorldMemory memory() {
        return new WorldMemory(new DocumentStore(temp.resolve("state.sqlite")), "world-1");
    }

    /**
     * 找容器读端的替身：只做世界记忆那一半的合并（现场扫到的候选由用例直接摆），
     * 开过没开过、离多远、归属都按接缝的能力说明读好，替 ContainerSource 挡住真扫描。
     */
    private SpotsContainers memorySpots(WorldMemory memory) {
        return new SpotsContainers() {
            @Override public List<ContainerChooser.Candidate> around(BlockPos center, int radius) {
                List<ContainerChooser.Candidate> found = new ArrayList<>();
                for (MemoryRecord record : memory.recordsNear(
                        WorldPosition.here(center.getX(), center.getY(), center.getZ()), radius)) {
                    if (record.kind() != MemoryKind.CONTAINER) continue;
                    BlockPos at = new BlockPos(record.position().x(), record.position().y(), record.position().z());
                    found.add(new ContainerChooser.Candidate("chest（" + at.getX() + ", "
                            + at.getY() + ", " + at.getZ() + "）", record.blockType(), record.position(),
                            record.openedBefore() ? record.contents() : null, true,
                            Math.sqrt(at.distSqr(center)), false, true, ContainerChooser.Lid.CLEAR, false));
                }
                found.sort(Comparator.comparingDouble(ContainerChooser.Candidate::walkCost));
                return found;
            }

            @Override public boolean scanComplete() {
                return true;
            }

            @Override public Optional<ContainerChooser.Candidate> at(WorldPosition position) {
                return memory.recordAt(MemoryKind.CONTAINER, position)
                        .map(record -> new ContainerChooser.Candidate("chest（" + position.x() + ", "
                                + position.y() + ", " + position.z() + "）", record.blockType(), record.position(),
                                record.openedBefore() ? record.contents() : null, true, 0,
                                false, true, ContainerChooser.Lid.CLEAR, false));
            }
        };
    }

    /** 现场扫描摆出的一只箱子：记忆里没有，开没开过、装没装东西由用例说了算。 */
    private static ContainerChooser.Candidate liveChest(WorldPosition at, List<String> contents) {
        return new ContainerChooser.Candidate("chest（" + at.x() + ", " + at.y() + ", " + at.z() + "）",
                "minecraft:chest", at, contents, true,
                Math.sqrt((long) (at.x() - 0) * (at.x() - 0) + (long) (at.y() - 64) * (at.y() - 64)
                        + (long) (at.z() - 0) * (at.z() - 0)),
                false, true, ContainerChooser.Lid.CLEAR, false);
    }

    /** 只有一只现场候选的读端替身；scanIncomplete 控制扫描完没完。 */
    private SpotsContainers liveSpots(List<ContainerChooser.Candidate> candidates, boolean scanIncomplete) {
        return new SpotsContainers() {
            @Override public List<ContainerChooser.Candidate> around(BlockPos center, int radius) {
                return candidates;
            }

            @Override public boolean scanComplete() {
                return !scanIncomplete;
            }

            @Override public Optional<ContainerChooser.Candidate> at(WorldPosition position) {
                return candidates.stream().filter(c -> MemoryRecord.sameSpot(c.at(), position)).findFirst();
            }
        };
    }

    private ContainerSource source(WorldMemory memory) {
        return new ContainerSource(memorySpots(memory), tags, NOBODY_OWNS,
                (container, request, permissions) -> Optional.empty());
    }

    private ItemRequest request(int count) {
        return new ItemRequest(WantedItem.ofItem("minecraft:iron_ingot"), count, "工具准备");
    }

    @Test
    void 开过的箱子按记下的内容报价() {
        WorldMemory memory = memory();
        memory.rememberContainerOpened(CHEST_AT, "minecraft:chest",
                List.of("minecraft:iron_ingot", "minecraft:iron_ingot", "minecraft:bread"), NOW);
        ContainerSource source = source(memory);

        SourceQuote quote = source.quote(request(2), CONTEXT);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, quote);
        assertEquals(2, offer.obtainableCount());
        assertEquals(11.180, offer.cost().distanceBlocks(), 0.01);
        assertTrue(offer.risk().contains("现场为准"));
    }

    @Test
    void 深处的记忆箱子_代价把落差折进去_说明里写清() {
        // 实机：地表要木镐，报价先挑了记忆里在地底深处的箱子（走路代价按直线算，把它算得很近），
        // 走到跟前才发现下不去。落差大的箱子把落差再折一遍进代价，只降排序不划掉，说明里交代原因。
        WorldMemory memory = memory();
        WorldPosition deep = new WorldPosition(-47, -34, 63, null);
        memory.rememberContainerOpened(deep, "minecraft:chest", List.of("minecraft:iron_ingot"), NOW);
        ContainerSource source = source(memory);

        // 那只箱子在一百多格外：任务给的搜索半径盖住它，才轮得到它报价。
        SourceContext wide = SourceContext.scoped(CONTEXT.characterAt(), CONTEXT.permissions(), 200, null);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source.quote(request(1), wide));
        double straight = Math.sqrt(47d * 47 + 98d * 98 + 63d * 63);
        assertEquals(straight + 98, offer.cost().distanceBlocks(), 0.01, "98 格落差再折一遍进代价");
        assertTrue(offer.risk().contains("深处"), offer.risk());
    }

    @Test
    void 脚边几格落差的箱子_代价照旧() {
        WorldMemory memory = memory();
        memory.rememberContainerOpened(new WorldPosition(6, 70, -3, null), "minecraft:chest",
                List.of("minecraft:iron_ingot"), NOW);
        ContainerSource source = source(memory);

        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source.quote(request(1), CONTEXT));
        assertEquals(Math.sqrt(36d + 36d + 9d), offer.cost().distanceBlocks(), 0.01, "落差没超过门槛，不折算");
    }

    @Test
    void 只看见过的箱子报不知道有多少_排在后面由选择器决定() {
        WorldMemory memory = memory();
        memory.rememberContainerSeen(CHEST_AT, "minecraft:chest", NOW);
        ContainerSource source = source(memory);

        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class,
                source.quote(request(1), CONTEXT));
        assertEquals(SourceQuote.Offer.UNKNOWN_COUNT, offer.obtainableCount());
        assertTrue(offer.risk().contains("没开过"));
    }

    @Test
    void 记忆里没有装着它的箱子_如实回答给不了() {
        WorldMemory memory = memory();
        memory.rememberContainerOpened(CHEST_AT, "minecraft:chest", List.of("minecraft:bread"), NOW);
        ContainerSource source = source(memory);

        SourceQuote quote = source.quote(request(1), CONTEXT);
        SourceQuote.Unavailable unavailable = assertInstanceOf(SourceQuote.Unavailable.class, quote);
        assertTrue(unavailable.reason().contains("没有装着"));
    }

    @Test
    void 记得没有但就在跟前的箱子_再开一眼() {
        // 实机（全量测试 10-12）：主播刚往旁边箱子里放了原木，记忆里那只箱子没有，obtain 直接说"记得的容器里没有"。
        WorldMemory memory = memory();
        memory.rememberContainerOpened(new WorldPosition(3, 64, 0, null), "minecraft:chest",
                List.of("minecraft:bread"), NOW);
        ContainerSource source = source(memory);

        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source.quote(request(1), CONTEXT));
        assertEquals(SourceQuote.Offer.UNKNOWN_COUNT, offer.obtainableCount());
        assertTrue(offer.risk().contains("再开一眼"), offer.risk());
    }

    @Test
    void 玩家的箱子记得有货也不翻() {
        // 默认绝不碰玩家的箱子：服务端记着这只箱子是 bob 放的，里面有铁也跳过，说明里交代一句。
        WorldMemory memory = memory();
        memory.rememberContainerOpened(CHEST_AT, "minecraft:chest", List.of("minecraft:iron_ingot"), NOW);
        Protection bobsChest = new Protection((dimension, x, y, z) -> x == 10 && y == 64 && z == 5
                ? Optional.of(new ReadsBlockOwnership.PlacedBy("bob")) : Optional.empty(),
                List::of, name -> Optional.empty(), GuessesPlayerMade.NOTHING, "self");
        ContainerSource source = new ContainerSource(memorySpots(memory), tags, bobsChest,
                (container, request, permissions) -> Optional.empty());

        SourceQuote.Unavailable unavailable = assertInstanceOf(SourceQuote.Unavailable.class,
                source.quote(request(1), CONTEXT));
        assertTrue(unavailable.reason().contains("别人的箱子不翻"), unavailable.reason());
    }

    @Test
    void 自家人的箱子照常翻() {
        // bob 是所有者在实例配置里列的自家人：他放的箱子和角色自己的一样，记得有铁就去拿。
        WorldMemory memory = memory();
        memory.rememberContainerOpened(CHEST_AT, "minecraft:chest", List.of("minecraft:iron_ingot"), NOW);
        String bob = new UUID(0, 42).toString();
        Protection family = new Protection((dimension, x, y, z) -> x == 10 && y == 64 && z == 5
                ? Optional.of(new ReadsBlockOwnership.PlacedBy(bob)) : Optional.empty(),
                List::of, name -> Optional.empty(), GuessesPlayerMade.NOTHING, "self",
                new TrustedPlayers(List.of(bob), name -> Optional.empty()));
        ContainerSource source = new ContainerSource(memorySpots(memory), tags, family,
                (container, request, permissions) -> Optional.empty());

        assertInstanceOf(SourceQuote.Offer.class, source.quote(request(1), CONTEXT));
    }

    @Test
    void 动手时按报价认的箱子交开箱接缝() {
        WorldMemory memory = memory();
        memory.rememberContainerOpened(CHEST_AT, "minecraft:chest", List.of("minecraft:iron_ingot"), NOW);
        AtomicReference<KnownContainer> taken = new AtomicReference<>();
        ContainerSource source = new ContainerSource(memorySpots(memory), tags, NOBODY_OWNS, (container, request, permissions) -> {
            taken.set(container);
            return Optional.of(new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    return ActionStatus.done();
                }
                @Override public String describe() {
                    return "开箱取物";
                }
            });
        });
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source.quote(request(1), CONTEXT));
        Action action = source.begin(request(1), offer, CONTEXT).orElseThrow();
        assertEquals(ActionStatus.done(), action.tick(new StubTick(1)));
        assertEquals(10, taken.get().at().x());
        assertEquals(64, taken.get().at().y());
        assertEquals(5, taken.get().at().z());
    }

    @Test
    void 动手时把这一趟还要的另外几样一起交给开箱接缝() {
        // 开都开了：这一样与旁边还缺的那样一起交给取货动作，箱里有的就顺手拿了。
        WorldMemory memory = memory();
        memory.rememberContainerOpened(CHEST_AT, "minecraft:chest", List.of("minecraft:iron_ingot"), NOW);
        AtomicReference<List<ItemRequest>> wants = new AtomicReference<>();
        ContainerSource source = new ContainerSource(memorySpots(memory), tags, NOBODY_OWNS, (container, requests, permissions) -> {
            wants.set(requests);
            return Optional.of(new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    return ActionStatus.done();
                }

                @Override public String describe() {
                    return "开箱取物";
                }
            });
        });
        ItemRequest iron = request(1);
        ItemRequest coal = new ItemRequest(WantedItem.ofItem("minecraft:coal"), 3, "工具准备");
        SourceContext withAlso = SourceContext.alsoWanting(WorldPosition.here(0, 64, 0), Permissions.DEFAULT, List.of(coal));
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source.quote(iron, CONTEXT));
        source.begin(iron, offer, withAlso).orElseThrow();

        assertEquals(2, wants.get().size());
        assertEquals("minecraft:iron_ingot", wants.get().get(0).wanted().specifier());
        assertEquals("minecraft:coal", wants.get().get(1).wanted().specifier());
        assertEquals(3, wants.get().get(1).count());
    }

    @Test
    void 报价认的箱子从记忆里没了_交回空由引擎换路() {
        WorldMemory memory = memory();
        ContainerSource source = source(memory);
        SourceQuote.Offer stale = new SourceQuote.Offer("记得的箱子", 3,
                new AcquisitionCost(5, 4), null, "99,64,99");
        assertTrue(source.begin(request(1), stale, CONTEXT).isEmpty());
    }

    @Test
    void 快查没对上时重读现场_对上了照样交给开箱接缝() {
        // 实机（verify-13）：同箱再取被"动手时现场对不上"挡住整路放弃。快查（记忆/索引那一半）没并齐时
        // 应按报价认的那格重读一遍现场候选，对上了照常去开——箱里实际有什么，开了才算数。
        WorldMemory memory = memory();
        memory.rememberContainerOpened(CHEST_AT, "minecraft:chest", List.of("minecraft:iron_ingot"), NOW);
        ContainerChooser.Candidate live = new ContainerChooser.Candidate("chest（10, 64, 5）",
                "minecraft:chest", CHEST_AT, List.of("minecraft:iron_ingot"), true, 11.18,
                false, true, ContainerChooser.Lid.CLEAR, false);
        AtomicReference<KnownContainer> taken = new AtomicReference<>();
        ContainerSource source = new ContainerSource(new SpotsContainers() {
            @Override public List<ContainerChooser.Candidate> around(BlockPos center, int radius) {
                return List.of(live);
            }

            @Override public boolean scanComplete() {
                return true;
            }

            // 快查那一半没并齐：指定位置答不上来。
            @Override public Optional<ContainerChooser.Candidate> at(WorldPosition position) {
                return Optional.empty();
            }
        }, tags, NOBODY_OWNS, (container, requests, permissions) -> {
            taken.set(container);
            return Optional.of(new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    return ActionStatus.done();
                }

                @Override public String describe() {
                    return "开箱取物";
                }
            });
        });

        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source.quote(request(1), CONTEXT));
        Action action = source.begin(request(1), offer, CONTEXT).orElseThrow();
        assertEquals(ActionStatus.done(), action.tick(new StubTick(1)));
        assertEquals(10, taken.get().at().x());
        assertEquals(5, taken.get().at().z());
    }

    @Test
    void 重读现场也没有报价认的那格_交回空如实划掉() {
        // 快查与重读都没有：那一格确实什么都没有了，不硬开一格空地，交回空让引擎换路。
        WorldMemory memory = memory();
        ContainerSource source = source(memory);
        SourceQuote.Offer stale = new SourceQuote.Offer("记得的箱子", 3,
                new AcquisitionCost(5, 4), null, "99,64,99");
        assertTrue(source.begin(request(1), stale, CONTEXT).isEmpty());
    }

    @Test
    void 现场扫到记忆里没有的箱子_照样成为候选上门去开() {
        // 实机（verify-12）：命令刚在角色旁摆的箱子装了货，记忆里从没有过，obtain 秒答"一件都没拿到"不上门。
        // 候选来自现场扫描，没开过的报"不知道有多少"，动手时按它认的那格交开箱接缝。
        WorldMemory memory = memory();
        ContainerChooser.Candidate fresh = liveChest(new WorldPosition(6, 65, -3, null), null);
        AtomicReference<KnownContainer> taken = new AtomicReference<>();
        ContainerSource source = new ContainerSource(liveSpots(List.of(fresh), false), tags, NOBODY_OWNS,
                (container, request, permissions) -> {
                    taken.set(container);
                    return Optional.empty();
                });

        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class,
                source.quote(request(1), CONTEXT));
        assertEquals(SourceQuote.Offer.UNKNOWN_COUNT, offer.obtainableCount());
        source.begin(request(1), offer, CONTEXT);
        assertEquals(6, taken.get().at().x());
        assertEquals(-3, taken.get().at().z());
        assertEquals("minecraft:chest", taken.get().blockTypeId());
    }

    @Test
    void 附近的容器还没扫完_交回再问不冒充没有() {
        // 扫描没扫完时不把"还没看完"当成"没有"：交回 NotYet 让引擎等一会儿重新问价。
        ContainerSource source = new ContainerSource(liveSpots(List.of(), true), tags, NOBODY_OWNS,
                (container, request, permissions) -> Optional.empty());

        SourceQuote.NotYet notYet = assertInstanceOf(SourceQuote.NotYet.class,
                source.quote(request(1), CONTEXT));
        assertTrue(notYet.reason().contains("还没扫完"));
    }
}
