// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.inventory.containers.ContainerChooser;
import org.maiwithu.maicraft.behavior.inventory.containers.SpotsContainers;
import org.maiwithu.maicraft.behavior.permission.GuessesPlayerMade;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsBlockOwnership;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryRecord;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 跳过别人的箱子时说清是谁的：记在谁名下、不知道是谁的、被地标挡住，几只同一个原因合并计数；
 * 有记在别人名下的才教一次怎么把自家人写进实例配置。
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
}
