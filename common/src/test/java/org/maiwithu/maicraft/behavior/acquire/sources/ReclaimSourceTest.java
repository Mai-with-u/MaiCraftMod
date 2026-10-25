// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsBlockOwnership;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.behavior.permission.TrustedPlayers;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.behavior.acquire.mining.CollectsBlocks;
import org.maiwithu.maicraft.behavior.acquire.engine.WantedItem;
import org.maiwithu.maicraft.behavior.acquire.engine.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.FakeTags;

/**
 * 收回自己放下的床、工作台、熔炉：按路程报价，走过去挖下来；别人放的、家附近的、归属没问到的、
 * 已经不在原处的都不收；许可不让挖就说要哪项许可。
 */
class ReclaimSourceTest {

    @TempDir Path temp;

    /** 角色在家附近站着；野外那张床在一百多格外。 */
    private static final WorldPosition HERE = WorldPosition.here(20, 63, 26);
    private static final WorldPosition FIELD_BED = WorldPosition.here(-86, 66, 50);
    private static final WantedItem ANY_BED = WantedItem.ofTag("minecraft:beds");

    private final Map<WorldPosition, String> world = new HashMap<>();
    private final Map<WorldPosition, String> owners = new HashMap<>();
    private final Set<WorldPosition> unknownOwnership = new HashSet<>();
    private final Map<String, WorldPosition> places = new HashMap<>();
    /** 周围看得见村民的格子（村里的床）。 */
    private final Set<WorldPosition> nearVillagers = new HashSet<>();
    /** 玩家放置推断猜成玩家放的格子。 */
    private final Set<WorldPosition> guessedPlayerMade = new HashSet<>();
    private final List<BlockPos> collected = new ArrayList<>();
    private final FakeTags tags = new FakeTags();
    private WorldMemory memory;
    /** 收的那一步怎么收场：默认做完；测「挖不成」的收账时改成失败。 */
    private ActionStatus collectEnds = ActionStatus.done();

    /** 这个世界的记忆：第一次用时建在临时目录里。 */
    private WorldMemory memory() {
        if (memory == null) memory = new WorldMemory(new DocumentStore(temp.resolve("state.sqlite")), "world-1");
        return memory;
    }

    private ReclaimSource source() {
        tags.put("minecraft:white_bed", "minecraft:beds");
        ReadsBlockOwnership ownership = new ReadsBlockOwnership() {
            @Override public Optional<PlacedBy> whoPlaced(String dimension, int x, int y, int z) {
                return Optional.ofNullable(owners.get(WorldPosition.here(x, y, z))).map(PlacedBy::new);
            }
            @Override public boolean known(String dimension, int x, int y, int z) {
                return !unknownOwnership.contains(WorldPosition.here(x, y, z));
            }
        };
        Protection protection = new Protection(ownership, () -> List.of(),
                name -> Optional.ofNullable(places.get(name)),
                (position, blockType) -> guessedPlayerMade.contains(position), "self", TrustedPlayers.NOBODY,
                (position, radius) -> nearVillagers.contains(position));
        PermissionCheck check = new PermissionCheck(protection, new ReadsCreatureSituation() {
            @Override public Optional<CreatureSituation> situationOf(UUID entityId) {
                return Optional.empty();
            }
        });
        CollectsBlocks collects = new CollectsBlocks() {
            @Override public Optional<Action> collect(BlockPos cell, Permissions permissions) {
                collected.add(cell);
                return Optional.of(new Action() {
                    @Override public ActionStatus tick(TickContext context) {
                        return collectEnds;
                    }
                    @Override public String describe() {
                        return "收 " + cell.toShortString();
                    }
                });
            }
        };
        return new ReclaimSource(memory(), at -> Optional.ofNullable(world.get(at)), protection, check, collects, tags);
    }

    /** 自己放下、睡过一晚的那张床：记在世界记忆里，现场还在，归属记录是自己。 */
    private void placedBed(WorldPosition at, String owner) {
        memory().rememberWorkstationUsed(at, "minecraft:white_bed", Instant.now());
        world.put(at, "minecraft:white_bed");
        owners.put(at, owner);
    }

    private SourceQuote quote(WantedItem wanted, Permissions permissions) {
        return source().quote(new ItemRequest(wanted, 1, "睡觉"), new SourceContext(HERE, permissions));
    }

    @Test
    void 自己放下的床_按路程报价_动手走过去挖下来() {
        placedBed(FIELD_BED, "self");
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, quote(ANY_BED, Permissions.DEFAULT));
        assertEquals(1, offer.obtainableCount());
        assertTrue(offer.cost().distanceBlocks() > 100 && offer.cost().distanceBlocks() < 112, offer.toString());

        ItemRequest request = new ItemRequest(ANY_BED, 1, "睡觉");
        source().begin(request, offer, new SourceContext(HERE, Permissions.DEFAULT)).orElseThrow();
        assertEquals(List.of(new BlockPos(-86, 66, 50)), collected);
    }

    @Test
    void 点名要白床也收得回() {
        placedBed(FIELD_BED, "self");
        assertInstanceOf(SourceQuote.Offer.class, quote(WantedItem.ofItem("minecraft:white_bed"), Permissions.DEFAULT));
        assertInstanceOf(SourceQuote.Unavailable.class, quote(WantedItem.ofItem("minecraft:red_bed"), Permissions.DEFAULT));
    }

    @Test
    void 别人放的床_不收() {
        placedBed(FIELD_BED, "someone");
        assertInstanceOf(SourceQuote.Unavailable.class, quote(ANY_BED, Permissions.DEFAULT));
    }

    @Test
    void 只睡过没有放下记录的床_也收() {
        // 野外睡过一晚的床：记忆里只有"亲手睡过"一条，放下动作发生在更早的会话、归属记录不在了。
        // 按能力说明它是收得回的招牌场景——不再等归属问到，也不因"不是自己放的记录"就划掉。
        placedBed(FIELD_BED, "self");
        owners.remove(FIELD_BED);
        unknownOwnership.add(FIELD_BED);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, quote(ANY_BED, Permissions.DEFAULT));
        source().begin(new ItemRequest(ANY_BED, 1, "睡觉"), offer, new SourceContext(HERE, Permissions.DEFAULT))
                .orElseThrow();
        assertEquals(List.of(new BlockPos(-86, 66, 50)), collected);
    }

    @Test
    void 睡过的床_归属记录写着是别人放的_不收() {
        // 睡过一张村民的床不等于床是自己的：记录写明是别人放的就让开。
        placedBed(FIELD_BED, "someone");
        assertInstanceOf(SourceQuote.Unavailable.class, quote(ANY_BED, Permissions.DEFAULT));
    }

    @Test
    void 睡过的村里的床_周围看得见村民_不收也不要点名() {
        // 借住过村庄的床（没有归属记录）：周围看得见村民就当是村民的，收回来源让开；不走"要点名许可"那条路。
        placedBed(FIELD_BED, "self");
        owners.remove(FIELD_BED);
        nearVillagers.add(FIELD_BED);
        assertInstanceOf(SourceQuote.Unavailable.class, quote(ANY_BED, Permissions.DEFAULT));
        assertTrue(collected.isEmpty());
    }

    @Test
    void 睡过的床_推断像玩家放的_受保护要点名许可() {
        // 推断这一路照别的方块一样问：没有归属记录、推断说像玩家放的，就按别人的东西挡下，要点名许可。
        placedBed(FIELD_BED, "self");
        owners.remove(FIELD_BED);
        guessedPlayerMade.add(FIELD_BED);
        SourceQuote.NeedsApproval approval = assertInstanceOf(SourceQuote.NeedsApproval.class,
                quote(ANY_BED, Permissions.DEFAULT));
        assertEquals(Problem.Kind.NEED_APPROVAL, approval.problem().kind());
    }

    @Test
    void 睡过的床只给了临时的档位_也收得回() {
        // 收回自己放的东西 temporary 档就放行；睡过的床按"自己用的"算，不吃"记录不是自己放的"那一档。
        placedBed(FIELD_BED, "self");
        owners.remove(FIELD_BED);
        unknownOwnership.add(FIELD_BED);
        Permissions temporary = new Permissions(Permissions.BlockChanges.TEMPORARY, Permissions.Fight.HOSTILE_MOBS,
                false, Permissions.AnimalKilling.WILD, Permissions.SurvivalNeeds.ON, Set.of());
        assertInstanceOf(SourceQuote.Offer.class, quote(ANY_BED, temporary));
    }

    @Test
    void 家附近的床_不收() {
        placedBed(FIELD_BED, "self");
        places.put("家", WorldPosition.here(-84, 66, 52));
        assertInstanceOf(SourceQuote.Unavailable.class, quote(ANY_BED, Permissions.DEFAULT));
    }

    @Test
    void 归属还没问到_先等一等再问() {
        // 台子、炉子仍要归属记录说话：没问到就等一两刻，不把"还不知道是谁放的"当成"不是自己的"。
        // 床放宽了这一档——自己睡过的床不用等归属问到（放下记录可能本就不在记忆里）。
        WorldPosition table = WorldPosition.here(24, 63, 30);
        memory().rememberWorkstationUsed(table, "minecraft:crafting_table", Instant.now());
        world.put(table, "minecraft:crafting_table");
        unknownOwnership.add(table);
        assertInstanceOf(SourceQuote.NotYet.class,
                quote(WantedItem.ofItem("minecraft:crafting_table"), Permissions.DEFAULT));
    }

    @Test
    void 记得的床已经不在原处_不收() {
        placedBed(FIELD_BED, "self");
        world.remove(FIELD_BED);
        assertInstanceOf(SourceQuote.Unavailable.class, quote(ANY_BED, Permissions.DEFAULT));
    }

    @Test
    void 一格都不许动_说要哪项许可() {
        placedBed(FIELD_BED, "self");
        Permissions none = new Permissions(Permissions.BlockChanges.NONE, Permissions.Fight.HOSTILE_MOBS, false,
                Permissions.AnimalKilling.WILD, Permissions.SurvivalNeeds.ON, Set.of());
        assertInstanceOf(SourceQuote.NeedsApproval.class, quote(ANY_BED, none));
    }

    @Test
    void 工作台与熔炉也收_箱子不收() {
        WorldPosition table = WorldPosition.here(25, 63, 30);
        WorldPosition chest = WorldPosition.here(26, 63, 30);
        memory().rememberWorkstationUsed(table, "minecraft:crafting_table", Instant.now());
        memory().rememberWorkstationUsed(chest, "minecraft:chest", Instant.now());
        world.put(table, "minecraft:crafting_table");
        world.put(chest, "minecraft:chest");
        owners.put(table, "self");
        owners.put(chest, "self");
        assertInstanceOf(SourceQuote.Offer.class,
                quote(WantedItem.ofItem("minecraft:crafting_table"), Permissions.DEFAULT));
        assertInstanceOf(SourceQuote.Unavailable.class, quote(WantedItem.ofItem("minecraft:chest"), Permissions.DEFAULT));
    }

    @Test
    void 收回挖掉了_这条记忆从世界记忆划掉() {
        // 挖完收尾照现场核对：那一格不再是记着的方块，记忆划掉——不留给下次照着空格子再跑一趟，
        // 「收回→记忆消失」的闭环也收在这一笔上。
        placedBed(FIELD_BED, "self");
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, quote(ANY_BED, Permissions.DEFAULT));
        Action collect = source().begin(new ItemRequest(ANY_BED, 1, "睡觉"),
                offer, new SourceContext(HERE, Permissions.DEFAULT)).orElseThrow();
        world.remove(FIELD_BED);
        assertInstanceOf(ActionStatus.Done.class, collect.tick(null));
        assertTrue(memory().recordsNear(HERE, 128).isEmpty(), "挖掉了，这条记忆划掉");
    }

    @Test
    void 收完了现场还在_记忆留着() {
        // 动作说做完但现场还是那张床（比如挖完掉了又同格放回）：不冒目划掉，下次还收得回。
        placedBed(FIELD_BED, "self");
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, quote(ANY_BED, Permissions.DEFAULT));
        Action collect = source().begin(new ItemRequest(ANY_BED, 1, "睡觉"),
                offer, new SourceContext(HERE, Permissions.DEFAULT)).orElseThrow();
        assertInstanceOf(ActionStatus.Done.class, collect.tick(null));
        assertEquals(1, memory().recordsNear(HERE, 128).size(), "现场还在，记忆留着");
    }

    @Test
    void 挖不成_记忆留着_下次还收得回() {
        // 挖不成、掉出的没捡到：床多半还在原处，记忆不划，不把没收成的当成已收回。
        placedBed(FIELD_BED, "self");
        collectEnds = ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "有 1 件掉出的东西没捡到", null));
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, quote(ANY_BED, Permissions.DEFAULT));
        Action collect = source().begin(new ItemRequest(ANY_BED, 1, "睡觉"),
                offer, new SourceContext(HERE, Permissions.DEFAULT)).orElseThrow();
        world.remove(FIELD_BED);
        assertInstanceOf(ActionStatus.Failed.class, collect.tick(null));
        assertEquals(1, memory().recordsNear(HERE, 128).size(), "没收成，床多半还在原处，记忆留着");
    }
}
