// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.acquire.mining.DropOdds;
import org.maiwithu.maicraft.behavior.acquire.mining.FailedSpots;
import org.maiwithu.maicraft.behavior.acquire.mining.MinableSpot;
import org.maiwithu.maicraft.behavior.acquire.mining.PlacesBlockBeside;
import org.maiwithu.maicraft.behavior.acquire.mining.ScansMinables;
import org.maiwithu.maicraft.behavior.acquire.spi.FailedOnlyAtSpots;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 碰运气挖：挖概率掉落的方块，直到要的东西进包够数——砾石出燧石、草出种子、树叶出树苗。
 * 挖一块不出是常态，不因为一块没掉就收场：按"还差几件 ÷ 每块平均出几件"一批一批挖，每批挖完捡起来、
 * 按背包重新清点进了几件。挖的块数有上限（期望的三倍）；到了上限还不够、附近能挖的挖完了，都如实写
 * 挖了几块、出了几件、还差几件，交给 LLM 换做法。
 *
 * <p>砾石没出燧石掉的是砾石自己：许可准放方块、身上有砾石时，近处没有天然的就把身上的放在脚边再挖，
 * 用它代替走去找下一块远处的砾石；放不下就照常去找别的。放下的在结果里记一笔放了几次、挖回几次。
 */
final class ChanceDigging implements Action, FailedOnlyAtSpots {

    /**
     * 挖的块数上限是期望的几倍：每块 10% 的砾石，要 1 个燧石按期望挖 10 块，最多挖 30 块，要几个按比例放大。
     * 30 块一个都不出的机会约 4%（0.9 的 30 次方），挖到这里还没有多半是运气太差，如实交给 LLM 定，不闷头一直挖。
     */
    static final int DIG_LIMIT_TIMES_EXPECTED = 3;
    /** 下一块天然的在这么近以内就直接去挖；再远、身上又有能放的，就放在脚边挖，省一趟路。 */
    static final double NEAR_BLOCKS = 4;

    /** 碰运气挖要读的现场与要用的动作：由采掘来源接上（扫描、许可、刚试过做不成的格都在那边筛），测试用替身。 */
    interface Scene {
        /** 这一刻看得见、准挖、这一阵没试过做不成的概率方块，按离 from 由近及远排好。 */
        List<MinableSpot> spots(BlockPos from);

        /** 把这几格挖掉捡起来；guessed 是线索点名、掉没掉说不准的方块。跳过的格交给 givenUp。接不上为空。 */
        Optional<Action> collect(List<BlockPos> cells, boolean guessed, Consumer<BlockPos> givenUp);

        /** 身上要的东西此刻有几件。 */
        int carriedWanted();

        /** 身上有几件这种东西（放下再挖用的砾石）。 */
        int carried(String itemId);
    }

    /**
     * 放下再挖：放哪种方块、谁来放。许可不准放、用不上时整个为 null，原因另外交给 placeBackNote。
     *
     * @param what   放下再挖的方块与机会
     * @param placer 把身上的一格放在身边
     */
    record PlaceBack(ScansMinables.PlaceAndDig what, PlacesBlockBeside placer) {}

    /** 碰运气挖走到哪一步：挑下一步、挖一批天然的、把身上的放一块、挖放下的那块。 */
    private enum Stage { CHOOSE, DIG, PLACE, DIG_PLACED }

    private final String wanted;
    private final int count;
    private final Scene scene;
    private final PlaceBack placeBack;
    /** 不能放下再挖时的原因（这次不许放、身上没这种方块之外的）；能放或用不上为空串。 */
    private final String placeBackNote;
    private final TaskRecords records;
    private final DropOdds odds;
    private final int limit;
    private int carriedAtStart = -1;
    private Stage stage = Stage.CHOOSE;
    private Action step;
    /** 从哪一格量远近：刚挖的那一格（角色挖完捡完就在它旁边），开头是角色所在。 */
    private BlockPos from;
    /** 一共挖下来几块，按方块种类记。 */
    private final Map<String, Integer> dugByType = new LinkedHashMap<>();
    private int dug;
    /** 这一批：哪种方块、几格、跳过了几格、最后一格在哪。 */
    private String roundType;
    private int roundCells;
    private int roundGivenUp;
    private BlockPos roundLast;
    /** 一共跳过了几格（走不到、挖不下来）。 */
    private int noted;
    /** 放下再挖：放了几次、挖回几次、刚放下的那一格、没挖回来的格。 */
    private int placed;
    private int placedDugBack;
    private BlockPos placedCell;
    private final List<BlockPos> leftBehind = new ArrayList<>();
    /** 放下再挖做不成了（放不下、放下的挖不回来）：这一趟不再放，写明原因。 */
    private String placeStopped;
    private ActionStatus ended;
    private boolean recorded;

    /**
     * @param wanted        要的东西，写进说明
     * @param count         这次要多出几件
     * @param odds          挖一块出它的机会（按挖的时候会拿的工具算过时运）；块数上限按它算
     * @param placeBack     放下再挖；不许放、用不上时为 null
     * @param placeBackNote 放不了时说给 LLM 的原因，没有为空串
     * @param start         角色动手时站在哪一格
     * @param records       发起任务的记账口：放下再挖的方块记进结果；为 null 时不记
     */
    ChanceDigging(String wanted, int count, DropOdds odds, Scene scene, PlaceBack placeBack, String placeBackNote,
            BlockPos start, TaskRecords records) {
        this.wanted = wanted;
        this.count = Math.max(1, count);
        this.odds = odds;
        this.scene = scene;
        this.placeBack = placeBack;
        this.placeBackNote = placeBackNote == null ? "" : placeBackNote;
        this.from = start;
        this.records = records;
        this.limit = digLimit(this.count, odds);
    }

    /** 按期望要挖几块：要几件除以每块平均出几件，向上取整。 */
    static int expectedDigs(int count, DropOdds odds) {
        return (int) Math.ceil(count / odds.perBlock() - 1e-9);
    }

    /** 这一趟最多挖几块：期望的三倍，要的件数越多按比例越多。 */
    static int digLimit(int count, DropOdds odds) {
        return (int) Math.ceil(DIG_LIMIT_TIMES_EXPECTED * count / odds.perBlock() - 1e-9);
    }

    @Override public ActionStatus tick(TickContext context) {
        if (ended != null) return ended;
        if (carriedAtStart < 0) carriedAtStart = scene.carriedWanted();
        if (stage == Stage.CHOOSE) return choose();
        ActionStatus status = step.tick(context);
        if (status instanceof ActionStatus.Running) return status;
        boolean failed = status instanceof ActionStatus.Failed;
        step.close();
        step = null;
        return switch (stage) {
            case DIG -> dugRound();
            case PLACE -> placedOne(failed ? ((ActionStatus.Failed) status).problem().message() : null);
            case DIG_PLACED -> dugPlaced();
            case CHOOSE -> choose();
        };
    }

    // 挑下一步：够数就收工；到了上限、能挖的挖完了就如实收场；近处有天然的先挖近的，远了而身上有砾石就放在脚边挖。
    private ActionStatus choose() {
        int got = got();
        if (got >= count) return finish(ActionStatus.done());
        if (dug >= limit) {
            return fail("到了这次挖的上限（按期望的 " + DIG_LIMIT_TIMES_EXPECTED + " 倍挖），再挖多半也是运气太差",
                    "再要一次会接着碰运气；也可以换个地方，或换别的来路");
        }
        List<MinableSpot> spots = scene.spots(from);
        MinableSpot nearest = spots.isEmpty() ? null : spots.getFirst();
        boolean canPlace = placeBack != null && placeStopped == null
                && scene.carried(placeBack.what().blockType()) > 0;
        if (nearest != null && (!canPlace || Math.sqrt(nearest.pos().distSqr(from)) <= NEAR_BLOCKS)) {
            return digRound(spots, nearest, got);
        }
        if (canPlace) return placeOne();
        if (dug == 0 && placed == 0 && noted > 0) {
            return fail("看得见的都走不到跟前或挖不下来，这几处 " + FailedSpots.REMEMBER_MILLIS / 60_000 + " 分钟内先不挑",
                    "换个站位、垫脚或挖开路再试");
        }
        return fail("附近看得见、准挖的都挖完了" + placeBackHint(), "换个地方再要，或换别的来路");
    }

    // 一批天然的：从最近那格起连一片同种方块，按还差几件和每块的机会定挖几格（不超过剩下的上限）。
    private ActionStatus digRound(List<MinableSpot> spots, MinableSpot nearest, int got) {
        int size = Math.max(1, Math.min(limit - dug, expectedDigs(count - got, nearest.odds())));
        List<MinableSpot> batch = MiningSource.batchOf(spots, size);
        Optional<Action> collect = scene.collect(batch.stream().map(MinableSpot::pos).toList(),
                nearest.odds().guessed(), cell -> {
                    roundGivenUp++;
                    noted++;
                });
        if (collect.isEmpty()) {
            return fail("挖方块、捡掉落的现场动作没接上", null);
        }
        roundType = nearest.blockType();
        roundCells = batch.size();
        roundGivenUp = 0;
        roundLast = batch.getLast().pos();
        step = collect.get();
        stage = Stage.DIG;
        return ActionStatus.progressed();
    }

    // 这一批挖完捡完：记下真挖下来几块，从最后那格起量下一批的远近。
    private ActionStatus dugRound() {
        int done = Math.max(0, roundCells - roundGivenUp);
        dug += done;
        if (done > 0) dugByType.merge(roundType, done, Integer::sum);
        from = roundLast;
        stage = Stage.CHOOSE;
        return ActionStatus.progressed();
    }

    // 把身上的一块放在脚边：放下了接着挖它，放不了这一趟就不再放。
    private ActionStatus placeOne() {
        placedCell = null;
        Optional<Action> placing = placeBack.placer().placeBeside(placeBack.what().blockType(), cell -> placedCell = cell);
        if (placing.isEmpty()) {
            placeStopped = "放方块的现场动作没接上";
            return ActionStatus.progressed();
        }
        step = placing.get();
        stage = Stage.PLACE;
        return ActionStatus.progressed();
    }

    // 放完：放下了就走过去挖它、捡起来；放不下记下原因，回去挑别的。
    private ActionStatus placedOne(String refusal) {
        if (refusal != null || placedCell == null) {
            placeStopped = refusal != null ? refusal : "放下去没看到落在哪一格";
            stage = Stage.CHOOSE;
            return ActionStatus.progressed();
        }
        placed++;
        Optional<Action> collect = scene.collect(List.of(placedCell), false, cell -> roundGivenUp++);
        if (collect.isEmpty()) {
            leftBehind.add(placedCell);
            placeStopped = "挖方块、捡掉落的现场动作没接上";
            stage = Stage.CHOOSE;
            return ActionStatus.progressed();
        }
        roundGivenUp = 0;
        step = collect.get();
        stage = Stage.DIG_PLACED;
        return ActionStatus.progressed();
    }

    // 放下的那块挖回来了：算挖了一块；挖不回来就留在那里如实记，这一趟不再放。
    private ActionStatus dugPlaced() {
        String blockType = placeBack.what().blockType();
        if (roundGivenUp > 0) {
            leftBehind.add(placedCell);
            placeStopped = "放在 " + placedCell.toShortString() + " 的那块没挖回来";
        } else {
            dug++;
            placedDugBack++;
            dugByType.merge(blockType, 1, Integer::sum);
            from = placedCell;
        }
        stage = Stage.CHOOSE;
        return ActionStatus.progressed();
    }

    private int got() {
        return Math.max(0, scene.carriedWanted() - carriedAtStart);
    }

    // 没挖够收场：挖了几块、出了几件、还差几件、为什么停，一句说完。
    private ActionStatus fail(String why, String suggestion) {
        int got = got();
        String outcome = got == 0 ? "一件" + wanted + "都没出" : "出了 " + got + " 件" + wanted + "，还差 " + (count - got) + " 件";
        return finish(ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM,
                "挖了 " + dugText() + "，" + outcome + "（" + odds.describe() + "）：" + why, suggestion)));
    }

    // 挖了几块：一种方块写"30 块 minecraft:gravel"，几种分开写。
    private String dugText() {
        if (dugByType.size() == 1) {
            var only = dugByType.entrySet().iterator().next();
            return only.getValue() + " 块 " + only.getKey();
        }
        if (dugByType.isEmpty()) return "0 块";
        List<String> parts = new ArrayList<>();
        dugByType.forEach((type, n) -> parts.add(type + " " + n + " 块"));
        return dug + " 块（" + String.join("、", parts) + "）";
    }

    // 挖完了还不够时，放下再挖为什么没接着做：没许可、身上没有、放不下。
    private String placeBackHint() {
        if (placeBack == null) return placeBackNote;
        if (placeStopped != null) return "；放在脚边再挖没做成：" + placeStopped;
        return "；身上也没有" + placeBack.what().blockType() + "可以放在脚边再挖";
    }

    private ActionStatus finish(ActionStatus end) {
        ended = end;
        record();
        return end;
    }

    // 放下再挖的方块记一笔：放了几次、挖回几次；没挖回来的写清在哪一格。只记一次。
    private void record() {
        if (recorded || records == null || placed == 0) return;
        recorded = true;
        String blockType = placeBack.what().blockType();
        String note = "身上的" + blockType + "放在脚边再挖：放了 " + placed + " 次，挖回 " + placedDugBack + " 次"
                + (leftBehind.isEmpty() ? "" : "；没挖回来的在 "
                        + String.join("、", leftBehind.stream().map(BlockPos::toShortString).toList()));
        records.change(new Change(Change.Kind.BLOCK_PLACED, blockType, placed, note));
    }

    @Override public boolean onlyThoseSpotsFailed() {
        return dug == 0 && placed == 0 && noted > 0;
    }

    @Override public void pause() {
        if (step != null) step.pause();
    }

    @Override public void close() {
        if (step != null) {
            step.close();
            step = null;
        }
        record();
    }

    @Override public Interruptibility interruptibility() {
        return step == null ? Interruptibility.BETWEEN_ACTIONS : step.interruptibility();
    }

    @Override public String describe() {
        String progress = "碰运气挖" + wanted + "：挖了 " + dug + " 块，出了 " + (carriedAtStart < 0 ? 0 : got())
                + " 件，最多挖 " + limit + " 块";
        return step == null ? progress : progress + "，正在" + step.describe();
    }
}
