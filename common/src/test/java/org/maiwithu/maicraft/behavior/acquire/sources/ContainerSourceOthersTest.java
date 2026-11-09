// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.FakeTags;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.acquire.spi.StarvingFoodReport;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.inventory.containers.ContainerChooser;
import org.maiwithu.maicraft.behavior.inventory.containers.SpotsContainers;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsBlockOwnership;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryRecord;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 谁放的箱子都能翻：别人放的照常报价与开箱，被额外保护的地标、记住的区域挡住的跳过并在说明里
 * 按原因合并计数。请求带着饿急通报（饿到危险线弄口粮）时，从不是自己放的箱子里拿就绑上通报——
 * 开箱只拿通报点名的那一样、不顺路拿别样；从自己放的箱子里拿不绑。
 */
class ContainerSourceOthersTest {

    private static final String BOB = "00000000-0000-0000-0000-00000000000b";
    private static final String CAROL = "00000000-0000-0000-0000-00000000000c";
    private static final ItemRequest STEW = new ItemRequest(WantedItem.ofItem("minecraft:mushroom_stew"), 1, "吃");
    private static final SourceContext HERE = SourceContext.unscoped(WorldPosition.here(0, 64, 0), Permissions.DEFAULT);

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

    private static String reason(Protection protection, int chests, SourceContext context) {
        ContainerSource source = new ContainerSource(spots(chests(chests)), new FakeTags(), protection,
                (container, request, permissions) -> Optional.empty());
        return assertInstanceOf(SourceQuote.Unavailable.class, source.quote(STEW, context)).reason();
    }

    private static int occurrences(String text, String part) {
        return text.split(Pattern.quote(part), -1).length - 1;
    }

    @Test
    void 别人放的箱子照常报价() {
        // x=1..3 是 bob 放的，x=4 是 carol 放的：都能翻，报最近那只有货的。
        Protection recorded = new Protection((dimension, x, y, z) -> Optional.of(
                new ReadsBlockOwnership.PlacedBy(x <= 3 ? BOB : CAROL)), List::of, name -> Optional.empty(),
                "self");
        ContainerSource source = new ContainerSource(spots(chests(4)), new FakeTags(), recorded,
                (container, request, permissions) -> Optional.empty());
        assertInstanceOf(SourceQuote.Offer.class, source.quote(STEW, HERE));
    }

    @Test
    void 被额外保护的地标挡住的说是哪个地标_几只合并计数() {
        Protection landmark = new Protection((dimension, x, y, z) -> Optional.empty(), List::of,
                name -> "家".equals(name) ? Optional.of(new WorldPosition(2, 64, 0, null)) : Optional.empty(),
                "self");
        SourceContext guarded = SourceContext.unscoped(WorldPosition.here(0, 64, 0),
                Permissions.DEFAULT.mergedWith(null, null, null, null, null, Set.of("家")));
        assertEquals("记得的容器里没有装着minecraft:mushroom_stew的，能用的都给挡住了："
                        + "2 只在这次额外保护的地标「家」旁",
                reason(landmark, 2, guarded));
        assertEquals(1, occurrences(reason(landmark, 2, guarded), "「家」"), "同一个原因只说一次");
        // 同一份保护判断，不带地标的任务照常报价。
        ContainerSource source = new ContainerSource(spots(chests(1)), new FakeTags(), landmark,
                (container, request, permissions) -> Optional.empty());
        assertInstanceOf(SourceQuote.Offer.class, source.quote(STEW, HERE));
    }

    /** 替身通报：开着就认蘑菇煲 1 件；绑到哪只箱子、绑时给的放它的人记下来。 */
    private static final class Starving implements StarvingFoodReport {
        boolean open = true;
        WorldPosition boundAt;
        String boundOwner;

        @Override public boolean open() {
            return open;
        }

        @Override public int atMost(String itemId) {
            return open ? 1 : 0;
        }

        @Override public StarvingFoodReport at(WorldPosition position, String ownerName) {
            boundAt = position;
            boundOwner = ownerName;
            return this;
        }

        @Override public boolean bound() {
            return boundAt != null;
        }

        @Override public void taken(List<Taken> items) {
        }
    }

    // x=1 是 bob 放的，x=2 是角色自己放的（只在两只时用到），都记得里面有蘑菇煲。
    private static final Protection BOB_THEN_SELF = new Protection((dimension, x, y, z) -> Optional.of(
            new ReadsBlockOwnership.PlacedBy(x == 1 ? BOB : "self")), List::of, name -> Optional.empty(),
            "self");

    private static ContainerSource source(Protection protection, int chests, List<List<ItemRequest>> opened) {
        return new ContainerSource(spots(chests(chests)), new FakeTags(), protection, (container, wants, permissions) -> {
            opened.add(wants);
            return Optional.empty();
        });
    }

    @Test
    void 饿急了从别人放的箱子里拿_绑上通报_不顺路拿别样() {
        Starving starving = new Starving();
        ItemRequest hungry = STEW.withFoodReport(starving);
        List<List<ItemRequest>> opened = new ArrayList<>();
        ContainerSource source = source(BOB_THEN_SELF, 1, opened);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source.quote(hungry, HERE));

        SourceContext alsoBread = SourceContext.alsoWanting(WorldPosition.here(0, 64, 0), Permissions.DEFAULT,
                List.of(new ItemRequest(WantedItem.ofItem("minecraft:bread"), 3, "吃")));
        source.begin(hungry, offer, alsoBread);
        assertEquals(1, opened.size());
        assertEquals(1, opened.getFirst().size(), "顺路的面包不在这一趟拿");
        assertTrue(opened.getFirst().getFirst().foodReport().bound(), "通报绑到了这只箱子上");
        assertEquals(1, starving.boundAt.x());
        assertEquals(BOB, starving.boundOwner, "放它的人照归属记录带上，事件里照它说");
    }

    @Test
    void 从自己放的箱子里拿_不绑通报_照常顺路拿() {
        // 两只都是自己放的：就近去 x=1，通报不绑，照常顺手拿顺路的几样。
        Starving starving = new Starving();
        List<List<ItemRequest>> opened = new ArrayList<>();
        Protection own = new Protection((dimension, x, y, z) -> Optional.of(
                new ReadsBlockOwnership.PlacedBy("self")), List::of, name -> Optional.empty(), "self");
        ContainerSource source = new ContainerSource(spots(chests(2)), new FakeTags(), own,
                (container, wants, permissions) -> {
                    opened.add(wants);
                    return Optional.empty();
                });
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class,
                source.quote(STEW.withFoodReport(starving), HERE));
        assertEquals("1,64,0", offer.hint());

        SourceContext alsoBread = SourceContext.alsoWanting(WorldPosition.here(0, 64, 0), Permissions.DEFAULT,
                List.of(new ItemRequest(WantedItem.ofItem("minecraft:bread"), 3, "吃")));
        source.begin(STEW.withFoodReport(starving), offer, alsoBread);
        assertFalse(starving.bound(), "自己放的箱子不绑通报");
        assertEquals(2, opened.getFirst().size(), "照常顺手拿顺路的几样");
    }

    @Test
    void 归属没问到的也绑通报_写不知道是谁的() {
        Protection unasked = new Protection(new ReadsBlockOwnership() {
            @Override public Optional<PlacedBy> whoPlaced(String dimension, int x, int y, int z) {
                return Optional.empty();
            }

            @Override public boolean known(String dimension, int x, int y, int z) {
                return false;
            }
        }, List::of, name -> Optional.empty(), "self");
        Starving starving = new Starving();
        List<List<ItemRequest>> opened = new ArrayList<>();
        ContainerSource source = source(unasked, 1, opened);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class,
                source.quote(STEW.withFoodReport(starving), HERE));
        source.begin(STEW.withFoodReport(starving), offer, HERE);
        assertTrue(starving.bound());
        assertNull(starving.boundOwner, "说不清是谁的，事件里写不知道是谁的箱子");
    }

    @Test
    void 额外保护的地标旁拿不了() {
        Protection landmark = new Protection((dimension, x, y, z) -> Optional.empty(), List::of,
                name -> "家".equals(name) ? Optional.of(new WorldPosition(1, 64, 0, null)) : Optional.empty(),
                "self");
        SourceContext guarded = SourceContext.unscoped(WorldPosition.here(0, 64, 0),
                Permissions.DEFAULT.mergedWith(null, null, null, null, null, Set.of("家")));
        assertInstanceOf(SourceQuote.Unavailable.class,
                source(landmark, 1, new ArrayList<>()).quote(STEW.withFoodReport(new Starving()), guarded));
    }
}
