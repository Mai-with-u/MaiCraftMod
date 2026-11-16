// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.inventory.hands.DigToolChoice;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 现做工具包装：清障不要掉落挖硬方块缺镐也会先合成镐再开挖，有对路工具或短活划不来时直接挖。 */
class ToolFirstDiggingTest {

    private static final BlockPos POS = new BlockPos(4, 64, 4);
    private static final DigToolChoice.Block STONE = new DigToolChoice.Block(1.5f, true, true);
    private static final DigToolChoice.Block DIRT = new DigToolChoice.Block(0.5f, false, false);
    private static final List<ToolMaking.Option> PICKAXES = List.of(
            new ToolMaking.Option("minecraft:stone_pickaxe", ToolMaking.Head.STONE, 4, true),
            new ToolMaking.Option("minecraft:wooden_pickaxe", ToolMaking.Head.PLANKS, 2, true));
    private static final List<ToolMaking.Option> SHOVELS = List.of(
            new ToolMaking.Option("minecraft:wooden_shovel", ToolMaking.Head.PLANKS, 2, true));

    private static TickContext tick(long gameTick) {
        return new TickContext() {
            @Override public long gameTick() {
                return gameTick;
            }

            @Override public PlayerContext player() {
                return null;
            }
        };
    }

    private static final class FakeLedger implements TaskRecords {
        final List<String> attempts = new ArrayList<>();

        @Override public void change(Change change) {}

        @Override public void unconfirmed(Change change) {}

        @Override public void attempt(String tried, String result) {
            attempts.add(tried + " -> " + result);
        }
    }

    private static DigsBlocks recordingDigs(List<String> events) {
        return target -> Optional.of(new Action() {
            @Override public ActionStatus tick(TickContext context) {
                events.add("dig");
                return ActionStatus.done();
            }

            @Override public String describe() {
                return "挖一格";
            }
        });
    }

    private static ItemNeeds recordingNeeds(List<String> events) {
        return (request, permissions) -> {
            events.add("make:" + request.wanted().specifier());
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    events.add("made");
                    return ActionStatus.done();
                }

                @Override public String describe() {
                    return "现做";
                }
            };
        };
    }

    private static ToolFirstDigging.Site fixedSite(String blockId, boolean requiresTool, DigToolChoice.Block block,
            OptionalInt handTicks, List<ToolMaking.Option> options, ToolMaking.Stock stock) {
        return new ToolFirstDigging.Site() {
            @Override public Optional<TargetCell> inspect(TickContext context, BlockPos target, boolean wantsDrop) {
                return Optional.of(new TargetCell(blockId, requiresTool, block, handTicks, options));
            }

            @Override public ToolMaking.Stock stock(TickContext context) {
                return stock;
            }

            @Override public String reservedMention(TickContext context, BlockPos target, String itemId) {
                return null;
            }
        };
    }

    @Test
    void 清障不要掉落挖石头且身上没镐时先做一把镐再开挖() {
        List<String> events = new ArrayList<>();
        FakeLedger ledger = new FakeLedger();
        ToolFirstDigging digging = new ToolFirstDigging(recordingDigs(events), recordingNeeds(events),
                fixedSite("minecraft:stone", true, STONE, OptionalInt.of(150), PICKAXES,
                        new ToolMaking.Stock(9, 0, 0, 0, false, 0, 0)));

        Action action = digging.dig(POS, new DigsBlocks.ToolPrep(1, false, Permissions.DEFAULT, ledger)).orElseThrow();
        assertInstanceOf(ActionStatus.Running.class, action.tick(tick(1)));
        assertInstanceOf(ActionStatus.Done.class, action.tick(tick(2)));
        assertEquals(List.of("make:minecraft:wooden_pickaxe", "made", "dig"), events);
    }

    @Test
    void 清障缺料做不出镐时记一笔直接徒手挖_要掉落挖石头缺料则报NEED_ITEM() {
        List<String> clearEvents = new ArrayList<>();
        FakeLedger clearLedger = new FakeLedger();
        ToolFirstDigging.Site noPlanks = fixedSite("minecraft:stone", true, STONE, OptionalInt.of(150), PICKAXES,
                new ToolMaking.Stock(0, 0, 0, 0, false, 0, 0));
        ToolFirstDigging clearing = new ToolFirstDigging(recordingDigs(clearEvents), recordingNeeds(clearEvents), noPlanks);

        Action clearAction = clearing.dig(POS, new DigsBlocks.ToolPrep(2, false, Permissions.DEFAULT, clearLedger)).orElseThrow();
        assertInstanceOf(ActionStatus.Done.class, clearAction.tick(tick(1)));
        assertEquals(List.of("dig"), clearEvents);
        assertEquals(1, clearLedger.attempts.size());

        List<String> mineEvents = new ArrayList<>();
        ToolFirstDigging mining = new ToolFirstDigging(recordingDigs(mineEvents), recordingNeeds(mineEvents), noPlanks);
        Action mineAction = mining.dig(POS, new DigsBlocks.ToolPrep(1, true, Permissions.DEFAULT, new FakeLedger())).orElseThrow();
        var failed = assertInstanceOf(ActionStatus.Failed.class, mineAction.tick(tick(1)));
        assertEquals(Problem.Kind.NEED_ITEM, failed.problem().kind());
        assertTrue(mineEvents.isEmpty());
    }

    @Test
    void 已有对路工具或短活泥土划不来时不重复合成直接挖() {
        List<String> events = new ArrayList<>();
        ToolFirstDigging hasPick = new ToolFirstDigging(recordingDigs(events), recordingNeeds(events),
                fixedSite("minecraft:stone", true, STONE, OptionalInt.empty(), PICKAXES,
                        new ToolMaking.Stock(9, 0, 0, 3, true, 0, 0)));
        assertInstanceOf(ActionStatus.Done.class,
                hasPick.dig(POS, new DigsBlocks.ToolPrep(5, false, Permissions.DEFAULT, new FakeLedger())).orElseThrow().tick(tick(1)));

        ToolFirstDigging shortDirt = new ToolFirstDigging(recordingDigs(events), recordingNeeds(events),
                fixedSite("minecraft:dirt", false, DIRT, OptionalInt.of(15), SHOVELS,
                        new ToolMaking.Stock(9, 0, 0, 0, false, 0, 0)));
        assertInstanceOf(ActionStatus.Done.class,
                shortDirt.dig(POS, new DigsBlocks.ToolPrep(2, false, Permissions.DEFAULT, new FakeLedger())).orElseThrow().tick(tick(2)));
        assertEquals(List.of("dig", "dig"), events);
    }
}
