// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.FakeTags;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.OthersStorageAllowance;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.inventory.containers.ContainerChooser;
import org.maiwithu.maicraft.behavior.inventory.containers.SpotsContainers;
import org.maiwithu.maicraft.behavior.permission.GuessesPlayerMade;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsBlockOwnership;
import org.maiwithu.maicraft.behavior.permission.StorageUse;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryRecord;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 跳过别人的箱子时说清是谁的：记在谁名下、不知道是谁的、被地标挡住，几只同一个原因合并计数；
 * 有记在别人名下的才教一次怎么把自家人写进实例配置。带着饿急了的放行时，能用的箱子都没有才报别人的箱子，
 * 开箱时只拿放行的那一样、不顺路拿别样，额外保护的地标旁照旧不碰。
 */
class ContainerSourceOthersTest {

    private static final String BOB = "00000000-0000-0000-0000-00000000000b";
    private static final String CAROL = "00000000-0000-0000-0000-00000000000c";
    private static final ItemRequest STEW = new ItemRequest(WantedItem.ofItem("minecraft:mushroom_stew"), 1, "吃");
    private static final SourceContext HERE = SourceContext.unscoped(WorldPosition.here(0, 64, 0), Permissions.DEFAULT);
    private static final String HOW_TO_ALLOW =
            "放的人是自家人就写进实例配置 config/maicraft.json 的 trustedPlayers，写了就能拿";

    /** 一排记得开过、里面有蘑菇煲的箱子，摆在 x=1..n。 */
    private static List<ContainerChooser.Candidate> chests(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(x -> new ContainerChooser.Candidate(
                "chest（" + x + ", 64, 0）", "minecraft:chest", new WorldPosition(x, 64, 0, null),
                List.of("minecraft:mushroom_stew"), true, x, false, true, ContainerChooser.Lid.CLEAR, false)).toList();
    }

    private static SpotsContainers spots(List<ContainerChooser.Candidate> candidates) {
        return new SpotsContainers() {
            @Override public List<ContainerChooser.Candidate> around(BlockPos center, int radius) {
                return candidates;
            }

            @Override public boolean scanComplete() {
                return true;
            }

            @Override public Optional<ContainerChooser.Candidate> at(WorldPosition position) {
                return candidates.stream().filter(c -> MemoryRecord.sameSpot(c.at(), position)).findFirst();
            }
        };
    }

    private static String reason(Protection protection, int chests, Function<String, String> names,
            SourceContext context) {
        ContainerSource source = new ContainerSource(spots(chests(chests)), new FakeTags(), protection,
                (container, request, permissions) -> Optional.empty(), names);
        return assertInstanceOf(SourceQuote.Unavailable.class, source.quote(STEW, context)).reason();
    }

    private static int occurrences(String text, String part) {
        return text.split(Pattern.quote(part), -1).length - 1;
    }

    @Test
    void 记在别人名下的说清是谁的_几只合并计数_自家人那句只说一次() {
        // x=1..3 是 bob 放的，x=4 是 carol 放的；bob 在线写名字，carol 不在线写编号。
        Protection recorded = new Protection((dimension, x, y, z) -> Optional.of(
                new ReadsBlockOwnership.PlacedBy(x <= 3 ? BOB : CAROL)), List::of, name -> Optional.empty(),
                GuessesPlayerMade.NOTHING, "self");
        String reason = reason(recorded, 4, id -> id.equals(BOB) ? "bob" : id, HERE);
        assertEquals("记得的容器里没有装着minecraft:mushroom_stew的，别人的箱子不翻：3 只记在 bob 名下、1 只记在 "
                + CAROL + " 名下；" + HOW_TO_ALLOW, reason);
        assertEquals(1, occurrences(reason, "trustedPlayers"));
    }

    @Test
    void 归属还没问到的说不知道是谁的_不教写自家人() {
        Protection unasked = new Protection(new ReadsBlockOwnership() {
            @Override public Optional<PlacedBy> whoPlaced(String dimension, int x, int y, int z) {
                return Optional.empty();
            }

            @Override public boolean known(String dimension, int x, int y, int z) {
                return false;
            }
        }, List::of, name -> Optional.empty(), GuessesPlayerMade.NOTHING, "self");
        String reason = reason(unasked, 2, Function.identity(), HERE);
        assertEquals("记得的容器里没有装着minecraft:mushroom_stew的，别人的箱子不翻：2 只不知道是谁的（按别人的算）", reason);
        assertFalse(reason.contains("trustedPlayers"), "写进自家人名单也拿不了的，不教");
    }

    @Test
    void 被额外保护的地标挡住的说是哪个地标() {
        Protection landmark = new Protection((dimension, x, y, z) -> Optional.empty(), List::of,
                name -> "家".equals(name) ? Optional.of(new WorldPosition(2, 64, 0, null)) : Optional.empty(),
                GuessesPlayerMade.NOTHING, "self");
        SourceContext guarded = SourceContext.unscoped(WorldPosition.here(0, 64, 0),
                Permissions.DEFAULT.mergedWith(null, null, null, null, null, Set.of("家")));
        assertEquals("记得的容器里没有装着minecraft:mushroom_stew的，别人的箱子不翻：1 只在这次额外保护的地标「家」旁",
                reason(landmark, 1, Function.identity(), guarded));
        // 同一份保护判断，不带地标的任务照常报价。
        ContainerSource source = new ContainerSource(spots(chests(1)), new FakeTags(), landmark,
                (container, request, permissions) -> Optional.empty(), Function.identity());
        assertInstanceOf(SourceQuote.Offer.class, source.quote(STEW, HERE));
    }

    /** 替身放行：开着就放行蘑菇煲 1 件；绑到哪只箱子记下来。 */
    private static final class Starving implements OthersStorageAllowance {
        boolean open = true;
        StorageUse boundUse;
        WorldPosition boundAt;

        @Override public boolean open() {
            return open;
        }

        @Override public int atMost(String itemId) {
            return open ? 1 : 0;
        }

        @Override public OthersStorageAllowance at(StorageUse use, WorldPosition position) {
            boundUse = use;
            boundAt = position;
            return this;
        }

        @Override public boolean bound() {
            return boundUse != null;
        }

        @Override public void taken(List<Taken> items) {
        }
    }

    // x=1 是 bob 放的，x=2 是角色自己放的（只在两只时用到），都记得里面有蘑菇煲。
    private static final Protection BOB_THEN_SELF = new Protection((dimension, x, y, z) -> Optional.of(
            new ReadsBlockOwnership.PlacedBy(x == 1 ? BOB : "self")), List::of, name -> Optional.empty(),
            GuessesPlayerMade.NOTHING, "self");

    private static ContainerSource source(Protection protection, int chests, List<List<ItemRequest>> opened) {
        return new ContainerSource(spots(chests(chests)), new FakeTags(), protection, (container, wants, permissions) -> {
            opened.add(wants);
            return Optional.empty();
        }, id -> id.equals(BOB) ? "bob" : id);
    }

    @Test
    void 饿急了能用的箱子都没有_报别人的箱子_开箱只拿放行的那一样() {
        Starving starving = new Starving();
        ItemRequest hungry = STEW.withOthersStorage(starving);
        List<List<ItemRequest>> opened = new ArrayList<>();
        ContainerSource source = source(BOB_THEN_SELF, 1, opened);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source.quote(hungry, HERE));
        assertTrue(offer.risk().contains("别人的箱子（记在 bob 名下）"), offer.risk());

        SourceContext alsoBread = SourceContext.alsoWanting(WorldPosition.here(0, 64, 0), Permissions.DEFAULT,
                List.of(new ItemRequest(WantedItem.ofItem("minecraft:bread"), 3, "吃")));
        source.begin(hungry, offer, alsoBread);
        assertEquals(1, opened.size());
        assertEquals(1, opened.getFirst().size(), "顺路的面包不在别人的箱子里拿");
        assertTrue(opened.getFirst().getFirst().othersStorage().bound(), "放行绑到了这只箱子上");
        assertEquals(StorageUse.Kind.PLACED_BY_OTHER, starving.boundUse.kind());
        assertEquals(1, starving.boundAt.x());
    }

    @Test
    void 自己的箱子里有就不碰别人的() {
        // bob 的箱子更近（x=1），自己的在 x=2：照样去自己的。
        ContainerSource source = source(BOB_THEN_SELF, 2, new ArrayList<>());
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class,
                source.quote(STEW.withOthersStorage(new Starving()), HERE));
        assertEquals("2,64,0", offer.hint());
    }

    @Test
    void 不放行或放行用掉了_别人的箱子照旧不翻() {
        ContainerSource source = source(BOB_THEN_SELF, 1, new ArrayList<>());
        assertInstanceOf(SourceQuote.Unavailable.class, source.quote(STEW, HERE), "平时不放行");
        Starving spent = new Starving();
        spent.open = false;
        assertInstanceOf(SourceQuote.Unavailable.class, source.quote(STEW.withOthersStorage(spent), HERE),
                "这一次已经从一只别人的箱子拿过");
    }

    @Test
    void 额外保护的地标旁饿了也不拿() {
        Protection landmark = new Protection((dimension, x, y, z) -> Optional.empty(), List::of,
                name -> "家".equals(name) ? Optional.of(new WorldPosition(1, 64, 0, null)) : Optional.empty(),
                GuessesPlayerMade.NOTHING, "self");
        SourceContext guarded = SourceContext.unscoped(WorldPosition.here(0, 64, 0),
                Permissions.DEFAULT.mergedWith(null, null, null, null, null, Set.of("家")));
        assertInstanceOf(SourceQuote.Unavailable.class,
                source(landmark, 1, new ArrayList<>()).quote(STEW.withOthersStorage(new Starving()), guarded));
    }
}
