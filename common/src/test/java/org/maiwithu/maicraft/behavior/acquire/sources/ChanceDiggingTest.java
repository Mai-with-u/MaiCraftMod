// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.maiwithu.maicraft.behavior.acquire.sources.ChanceField.FLINT;
import static org.maiwithu.maicraft.behavior.acquire.sources.ChanceField.GRAVEL;
import static org.maiwithu.maicraft.behavior.acquire.sources.ChanceField.PLAIN;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.FakeBackpack;
import org.maiwithu.maicraft.behavior.acquire.StubTick;
import org.maiwithu.maicraft.behavior.acquire.mining.DropOdds;
import org.maiwithu.maicraft.behavior.acquire.mining.MinableSpot;
import org.maiwithu.maicraft.behavior.acquire.mining.ScansMinables;
import org.maiwithu.maicraft.behavior.acquire.mining.VanillaBlockDrops;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 碰运气挖：挖一块没出不收场，按期望一批批挖到出了为止；块数上限是期望的三倍，到上限、附近挖完了如实写
 * 挖了几块出了几件；近处没有砾石就把身上的放在脚边再挖，记一笔放了几次；线索点名、说不准的方块照常挖。
 */
class ChanceDiggingTest {

    private final FakeBackpack backpack = new FakeBackpack(36);
    private final List<Change> recorded = new ArrayList<>();
    private final TaskRecords records = new TaskRecords() {
        @Override public void change(Change change) {
            recorded.add(change);
        }

        @Override public void unconfirmed(Change change) {}

        @Override public void attempt(String tried, String whatHappened) {}
    };

    // 现场接到替身上：候选按离刚挖那格远近排；概率表里的走"碰运气收"，说不准的走普通收。
    private static ChanceDigging.Scene scene(ChanceField field, String wanted) {
        return new ChanceDigging.Scene() {
            @Override public List<MinableSpot> spots(BlockPos from) {
                return field.spots.stream().sorted(Comparator.comparingDouble(spot -> spot.pos().distSqr(from))).toList();
            }

            @Override public Optional<Action> collect(List<BlockPos> cells, boolean guessed, Consumer<BlockPos> givenUp) {
                return guessed ? field.collectBatch(cells, Permissions.DEFAULT)
                        : field.collectForChanceDrops(cells, Permissions.DEFAULT, givenUp);
            }

            @Override public int carriedWanted() {
                return field.carried(wanted);
            }

            @Override public int carried(String itemId) {
                return field.carried(itemId);
            }
        };
    }

    // 要 count 个燧石；placeBack 为真时身上的砾石能放在脚边再挖。
    private ChanceDigging flint(ChanceField field, int count, boolean placeBack) {
        ChanceDigging.PlaceBack back = placeBack
                ? new ChanceDigging.PlaceBack(new ScansMinables.PlaceAndDig(GRAVEL, FLINT, PLAIN), field) : null;
        return new ChanceDigging(FLINT, count, PLAIN, scene(field, FLINT), back, "", new BlockPos(0, 64, 0), records);
    }

    private static ActionStatus runAll(Action action) {
        ActionStatus status = ActionStatus.running();
        for (int tick = 0; tick < 2000 && status instanceof ActionStatus.Running; tick++) {
            status = action.tick(new StubTick(tick));
        }
        action.close();
        return status;
    }

    @Test
    void 上限是期望的三倍_按件数放大() {
        assertEquals(10, ChanceDigging.expectedDigs(1, PLAIN));
        assertEquals(30, ChanceDigging.digLimit(1, PLAIN));
        assertEquals(60, ChanceDigging.digLimit(2, PLAIN));
        // 草出种子 12.5%：要 1 颗期望挖 8 块、最多 24 块。
        DropOdds seeds = VanillaBlockDrops.chanceSources("minecraft:wheat_seeds").getFirst().odds(0);
        assertEquals(8, ChanceDigging.expectedDigs(1, seeds));
        assertEquals(24, ChanceDigging.digLimit(1, seeds));
    }

    @Test
    void 一块没出不收场_挖到出了为止() {
        // 前 12 块都只掉砾石，第 13 块才出燧石：第一批按期望挖 10 块一个没出，接着挖下一批，出了就收工。
        ChanceField field = new ChanceField(backpack, 40, Set.of(13));
        assertInstanceOf(ActionStatus.Done.class, runAll(flint(field, 1, false)));
        assertEquals(1, field.carried(FLINT));
        assertEquals(List.of("碰运气收 10 格", "碰运气收 10 格"), field.log, "挖的时候走概率掉落的挑法");
    }

    @Test
    void 到上限还不够_如实写挖了几块出了几件() {
        ChanceField field = new ChanceField(backpack, 40, Set.of());
        ChanceDigging digging = flint(field, 1, false);
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, runAll(digging));
        assertEquals(30, field.digs, "要 1 个燧石最多挖 30 块");
        assertTrue(failed.problem().message().contains("挖了 30 块 minecraft:gravel，一件minecraft:flint都没出（每块 10%）"),
                failed.problem().message());
        assertTrue(failed.problem().message().contains("上限"), failed.problem().message());
        assertFalse(digging.onlyThoseSpotsFailed(), "到上限不是只坏在几处：引擎按走不通划掉，不再重来三遍");
    }

    @Test
    void 附近的挖完了还差_写明出了几件还差几件() {
        // 只有 5 块砾石、也不放方块：第 3 块出了一个，要 2 个，挖完 5 块还差 1 个。
        ChanceField field = new ChanceField(backpack, 5, Set.of(3));
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, runAll(flint(field, 2, false)));
        assertTrue(failed.problem().message().contains("挖了 5 块 minecraft:gravel，出了 1 件minecraft:flint，还差 1 件"),
                failed.problem().message());
        assertTrue(failed.problem().message().contains("都挖完了"), failed.problem().message());
    }

    @Test
    void 看得见的都走不到_一块都没挖下来_按只坏在几处交给引擎换别处() {
        ChanceField field = new ChanceField(backpack, 3, Set.of()) {
            @Override public Optional<Action> collectForChanceDrops(List<BlockPos> cells, Permissions permissions,
                    Consumer<BlockPos> givenUp) {
                // 每格都走不到：跳过、报给来源；来源这一阵不再挑它们。
                return Optional.of(new Action() {
                    @Override public ActionStatus tick(TickContext context) {
                        cells.forEach(givenUp);
                        spots.removeIf(spot -> cells.contains(spot.pos()));
                        return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE, "走不到"));
                    }

                    @Override public String describe() {
                        return "走不到";
                    }
                });
            }
        };
        ChanceDigging digging = flint(field, 1, false);
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, runAll(digging));
        assertTrue(failed.problem().message().contains("都走不到跟前或挖不下来"), failed.problem().message());
        assertTrue(digging.onlyThoseSpotsFailed());
    }

    @Test
    void 近处没有砾石就把身上的放在脚边再挖_记一笔放了几次() {
        // 附近一块天然的都没有，身上带着 1 块砾石：放下、挖起（掉回砾石），再放、再挖，第 3 块出了燧石。
        ChanceField field = new ChanceField(backpack, 0, Set.of(3));
        backpack.add(GRAVEL, 1);
        assertInstanceOf(ActionStatus.Done.class, runAll(flint(field, 1, true)));
        assertEquals(1, field.carried(FLINT));
        assertEquals(3, field.placed);
        assertEquals(1, recorded.size());
        Change change = recorded.getFirst();
        assertEquals(Change.Kind.BLOCK_PLACED, change.kind());
        assertEquals(GRAVEL, change.what());
        assertEquals(3, change.count());
        assertTrue(change.note().contains("放了 3 次，挖回 3 次"), change.note());
    }

    @Test
    void 远处的砾石不去找_先放身上的() {
        // 天然的砾石在 10 格外，身上有砾石：放在脚边挖，省一趟路；第一块就出了。
        ChanceField field = new ChanceField(backpack, 0, Set.of(1));
        field.spots.add(new MinableSpot(new BlockPos(10, 64, 0), GRAVEL, FLINT, PLAIN));
        backpack.add(GRAVEL, 1);
        assertInstanceOf(ActionStatus.Done.class, runAll(flint(field, 1, true)));
        assertEquals(List.of("放下一块", "碰运气收 1 格"), field.log);
        assertEquals(1, field.spots.size(), "远处那块没去挖");
    }

    @Test
    void 近处有天然的先挖近的_挖出来的砾石再放下接着挖() {
        // 脚边一块天然砾石：先挖它（掉砾石），挖完附近没有了，就把挖出来的放下接着挖，第 2 块出了。
        ChanceField field = new ChanceField(backpack, 1, Set.of(2));
        assertInstanceOf(ActionStatus.Done.class, runAll(flint(field, 1, true)));
        assertEquals(List.of("碰运气收 1 格", "放下一块", "碰运气收 1 格"), field.log);
    }

    @Test
    void 线索点名_说不准掉不掉的方块_一块没出也不收场() {
        // LLM 说锌矿掉锌锭（其实掉粗锌）：说不准的按每块一件估，挖一块没出接着挖，挖到三倍（3 块）还没有才如实说。
        ChanceField field = new ChanceField(backpack, 0, Set.of());
        for (int x = 1; x <= 5; x++) {
            field.spots.add(new MinableSpot(new BlockPos(x, 64, 0), "create:zinc_ore", "create:zinc_ingot",
                    DropOdds.GUESSED));
        }
        ChanceDigging digging = new ChanceDigging("create:zinc_ingot", 1, DropOdds.GUESSED,
                scene(field, "create:zinc_ingot"), null, "", new BlockPos(0, 64, 0), records);
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, runAll(digging));
        assertEquals(3, field.digs);
        assertTrue(failed.problem().message().contains("挖了 3 块 create:zinc_ore，一件create:zinc_ingot都没出（掉没掉说不准）"),
                failed.problem().message());
        assertEquals(List.of("普通收 1 格", "普通收 1 格", "普通收 1 格"), field.log, "说不准的照常挖，可以连锁");
    }
}
