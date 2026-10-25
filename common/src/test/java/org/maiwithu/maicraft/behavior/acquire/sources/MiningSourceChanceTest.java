// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.maiwithu.maicraft.behavior.acquire.sources.ChanceField.FLINT;
import static org.maiwithu.maicraft.behavior.acquire.sources.ChanceField.GRAVEL;
import static org.maiwithu.maicraft.behavior.acquire.sources.ChanceField.WANT_FLINT;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.FakeBackpack;
import org.maiwithu.maicraft.behavior.acquire.FakeOffhand;
import org.maiwithu.maicraft.behavior.acquire.FakeTags;
import org.maiwithu.maicraft.behavior.acquire.FixedSpot;
import org.maiwithu.maicraft.behavior.acquire.StubTick;
import org.maiwithu.maicraft.behavior.acquire.cook.ReadsToolRequirements;
import org.maiwithu.maicraft.behavior.acquire.engine.ItemAcquisition;
import org.maiwithu.maicraft.behavior.acquire.mining.DigsStairsDown;
import org.maiwithu.maicraft.behavior.acquire.mining.DropOdds;
import org.maiwithu.maicraft.behavior.acquire.mining.VanillaBlockDrops;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.permission.GuessesPlayerMade;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;

/**
 * 采掘接上碰运气挖：只看得见概率掉落的方块时按期望放大报价、有上限；引擎不因一块没掉划掉采掘；
 * 身上的砾石放下再挖受放方块许可管；时运按工具算进报价。
 */
class MiningSourceChanceTest {

    private static final ItemRequest ONE_FLINT = new ItemRequest(WANT_FLINT, 1, "打火石");

    private final FakeBackpack backpack = new FakeBackpack(36);
    private final FakeOffhand offhand = new FakeOffhand();

    private static Permissions changeBlocks(Permissions.BlockChanges changes) {
        return new Permissions(changes, Permissions.Fight.HOSTILE_MOBS, false, Permissions.AnimalKilling.WILD,
                Permissions.SurvivalNeeds.ON, Set.of());
    }

    private static SourceContext context(Permissions permissions) {
        return new SourceContext(WorldPosition.here(0, 64, 0), permissions);
    }

    private MiningSource source(ChanceField field) {
        // 真许可检查点加全空的替身保护：本场景里没有任何受保护的东西，挡路的只有许可档位。
        PermissionCheck permission = new PermissionCheck(
                new Protection((dimension, x, y, z) -> Optional.empty(), List::of,
                        name -> Optional.empty(), GuessesPlayerMade.NOTHING, "self"),
                entityId -> Optional.empty());
        ReadsToolRequirements noTools = new ReadsToolRequirements() {
            @Override public Optional<String> toolRequired(String blockType) {
                return Optional.empty();
            }

            @Override public boolean sufficient(String toolItemId, String blockType) {
                return true;
            }
        };
        DigsStairsDown noStairs = new DigsStairsDown() {
            @Override public Action digDown(Predicate<String> yields, int wantedCells, Permissions permissions) {
                throw new AssertionError("砾石不往下挖楼梯找");
            }
        };
        return new MiningSource(field, field, noStairs, noTools, permission, backpack, offhand, new FakeTags(),
                (request, permissions) -> null, field);
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
    void 报价按期望放大挖的块数_写明每块的机会与上限() {
        MiningSource mining = source(new ChanceField(backpack, 40, Set.of()));
        SourceQuote.Offer one = assertInstanceOf(SourceQuote.Offer.class,
                mining.quote(ONE_FLINT, context(Permissions.DEFAULT)));
        assertEquals(10, one.cost().actionCount(), "要 1 个燧石按期望挖 10 块");
        assertTrue(one.risk().contains("每块 10%"), one.risk());
        assertTrue(one.risk().contains("最多挖 30 块"), one.risk());
        SourceQuote.Offer three = assertInstanceOf(SourceQuote.Offer.class,
                mining.quote(new ItemRequest(WANT_FLINT, 3, "打火石"), context(Permissions.DEFAULT)));
        assertEquals(30, three.cost().actionCount(), "要 3 个按比例放大到 30 块");
        assertTrue(three.risk().contains("最多挖 90 块"), three.risk());
    }

    @Test
    void 引擎不因一块没掉就把采掘划掉() {
        // 挖一块砾石没出燧石不算"一件都没拿到"：第 15 块才出也照样拿到，引擎不把采掘划掉。
        ChanceField field = new ChanceField(backpack, 40, Set.of(15));
        ItemAcquisition engine = new ItemAcquisition(List.of(source(field)), backpack, offhand, new FakeTags(),
                new FixedSpot(0, 64, 0), Optional.empty(), ItemAcquisition.DEFAULT_MAX_DEPTH);
        assertInstanceOf(ActionStatus.Done.class, runAll(engine.need(ONE_FLINT, Permissions.DEFAULT)));
        assertEquals(1, field.carried(FLINT));
    }

    @Test
    void 附近看不见砾石_身上带着就报价放在脚边再挖() {
        ChanceField field = new ChanceField(backpack, 0, Set.of(2));
        backpack.add(GRAVEL, 1);
        MiningSource mining = source(field);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class,
                mining.quote(ONE_FLINT, context(Permissions.DEFAULT)));
        assertTrue(offer.risk().contains("放在脚边"), offer.risk());
        assertInstanceOf(ActionStatus.Done.class,
                runAll(mining.begin(ONE_FLINT, offer, context(Permissions.DEFAULT)).orElseThrow()));
        assertEquals(2, field.placed);
    }

    @Test
    void 只许搭临时方块时_天然砾石不挖_身上的照样放下再挖() {
        ChanceField field = new ChanceField(backpack, 3, Set.of(2));
        backpack.add(GRAVEL, 1);
        MiningSource mining = source(field);
        Permissions temporary = changeBlocks(Permissions.BlockChanges.TEMPORARY);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, mining.quote(ONE_FLINT, context(temporary)));
        assertInstanceOf(ActionStatus.Done.class, runAll(mining.begin(ONE_FLINT, offer, context(temporary)).orElseThrow()));
        assertEquals(3, field.spots.size(), "天然的砾石一块没动");
        assertEquals(2, field.placed);
    }

    @Test
    void 不许放方块时_身上的砾石也不放() {
        ChanceField field = new ChanceField(backpack, 0, Set.of());
        backpack.add(GRAVEL, 5);
        SourceQuote quote = source(field).quote(ONE_FLINT, context(changeBlocks(Permissions.BlockChanges.NONE)));
        assertFalse(quote instanceof SourceQuote.Offer, "change_blocks=none：身上的砾石也不能放下再挖");
    }

    @Test
    void 时运按工具算进机会_报价跟着少挖() {
        // 时运 I 的铲子挖砾石：14.3%，要 1 个按期望挖 7 块、最多 21 块；时运 III 必出，按一挖就掉算。
        DropOdds fortuneOne = VanillaBlockDrops.chanceSources(FLINT).getFirst().odds(1);
        ChanceField field = new ChanceField(backpack, 40, Set.of()).withOdds(fortuneOne);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class,
                source(field).quote(ONE_FLINT, context(Permissions.DEFAULT)));
        assertEquals(7, offer.cost().actionCount());
        assertTrue(offer.risk().contains("每块 14.3%"), offer.risk());
        assertTrue(offer.risk().contains("最多挖 21 块"), offer.risk());
        DropOdds fortuneThree = VanillaBlockDrops.chanceSources(FLINT).getFirst().odds(3);
        SourceQuote.Offer certain = assertInstanceOf(SourceQuote.Offer.class,
                source(new ChanceField(backpack, 40, Set.of()).withOdds(fortuneThree))
                        .quote(ONE_FLINT, context(Permissions.DEFAULT)));
        assertTrue(certain.risk().contains("一格不一定掉一件"), "必出时照一挖就掉的报价走：" + certain.risk());
    }
}
