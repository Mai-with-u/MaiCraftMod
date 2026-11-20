// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.cook;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.maiwithu.maicraft.behavior.acquire.spi.CookingLeftBehind;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 进程内会话级箱式加工站在跑账本：记录角色投料后先去干别的事的加工站批次，
 * 复用与熔炉相同的自动挂/摘计时器口子（{@link FurnacesLeftCooking.SetsTimers}）。
 *
 * <p>同一座加工站同一产物新留下一批时自动摘掉旧计时；回来取够、确认堵料或清掉记录时摘掉还挂着的计时，
 * 避免人回来处理完了还被旧计时重复叫醒。每个世界一份，换世界不带过去，只在客户端线程使用。
 */
public final class StationsLeftCooking implements CookingLeftBehind {

    /** 计时比预计可取时刻多留的宽限（秒），宁可稍晚一点回来也别过早空跑。 */
    static final int TIMER_MARGIN_SECONDS = 1;

    private record Kept(LeftStationBatch batch, int number) {}

    private final FurnacesLeftCooking.SetsTimers timers;
    private final List<Kept> kept = new ArrayList<>();
    private int timesLeft;
    private LeftStationBatch last;

    public StationsLeftCooking(FurnacesLeftCooking.SetsTimers timers) {
        this.timers = Objects.requireNonNull(timers, "timers");
    }

    /** 投完料登记在跑并自动挂一个计时；同站同产物原先挂着的旧计时会被先摘掉。 */
    public LeftStationBatch leave(LeftStationBatch batch) {
        clear(batch.stationName(), batch.resultItemId());
        int afterSeconds = (int) Math.min(Integer.MAX_VALUE, batch.readyInSeconds() + TIMER_MARGIN_SECONDS);
        Integer timerId = timers.set(afterSeconds, batch.timerNote(), batch.leftAtGameTick()).orElse(null);
        LeftStationBatch stored = batch.withTimer(timerId);
        kept.add(new Kept(stored, ++timesLeft));
        last = stored;
        return stored;
    }

    /** 查找当前维度里正在加工指定产物的在跑批次（先登记的在前）；dimension 为 null 时不分维度。 */
    public List<LeftStationBatch> runningFor(String resultItemId, String dimension) {
        return kept.stream().map(Kept::batch)
                .filter(batch -> batch.resultItemId().equals(resultItemId))
                .filter(batch -> sameDimension(batch.output().dimension(), dimension))
                .toList();
    }

    /** 查找指定加工站上指定产物的在跑记录。 */
    public Optional<LeftStationBatch> atStation(String stationName, String resultItemId) {
        return kept.stream().map(Kept::batch)
                .filter(batch -> batch.stationName().equals(stationName) && batch.resultItemId().equals(resultItemId))
                .findFirst();
    }

    /** 划掉指定加工站上指定产物的在跑记录，并摘掉还挂着的计时。 */
    public void clear(String stationName, String resultItemId) {
        kept.removeIf(entry -> {
            if (!entry.batch().stationName().equals(stationName)
                    || !entry.batch().resultItemId().equals(resultItemId)) {
                return false;
            }
            if (entry.batch().timerId() != null) timers.cancel(entry.batch().timerId());
            return true;
        });
    }

    /** 第 mark 回以后留下、此刻仍在账本里的批次，给 obtain 收场结算使用。 */
    public List<LeftStationBatch> since(int mark) {
        return kept.stream().filter(entry -> entry.number() > mark).map(Kept::batch).toList();
    }

    @Override public int timesLeft() {
        return timesLeft;
    }

    @Override public Problem lastLeft() {
        if (last == null) throw new IllegalStateException("没有留下过箱式加工站批次，不该问怎么说");
        return Problem.of(Problem.Kind.WRONG_TIME, "已投料进加工站先去干别的：" + last.sentence(), last.suggestion());
    }

    private static boolean sameDimension(String first, String second) {
        return first == null || second == null || first.equals(second);
    }
}
