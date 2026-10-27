// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.engine;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.FakeBackpack;
import org.maiwithu.maicraft.behavior.acquire.FakeOffhand;
import org.maiwithu.maicraft.behavior.acquire.FakeTags;
import org.maiwithu.maicraft.behavior.acquire.FixedSpot;
import org.maiwithu.maicraft.behavior.acquire.ScriptedSource;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.behavior.acquire.StubTick;

/**
 * 重新问价、换来源只是换了想法再试，不算真实进展：首选来源一直接不上时，身体站桩也要能被
 * 停滞守卫判成卡住；真动手的那几刻（走动、东西进包）照旧由动作本身报告进展。
 */
class ItemAcquisitionProgressTest {

    private static final String LOG = "minecraft:spruce_log";

    /** 按引擎真实处境记账的进度跟踪：动作报告进展才记，不然只推进刻数——与 PhasedTask 的记法一致。 */
    private static final class TrackerFeed {
        final ProgressTracker tracker = new ProgressTracker(20, Long.MAX_VALUE);

        ActionStatus feed(ActionStatus status) {
            tracker.tick();
            if (status instanceof ActionStatus.Running running && running.progressed()) {
                tracker.recordProgress("动作报告了进展");
            }
            return status;
        }
    }

    /** 十几个都接不上的来源：引擎每轮重新问价、换下一个，始终拿不到东西。 */
    private List<ItemSource> unavailableSources(int count) {
        FakeBackpack backpack = new FakeBackpack(36);
        List<ItemSource> sources = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            sources.add(new ScriptedSource("来源" + i, backpack)
                    .answer(new SourceQuote.Offer("来源" + i, 4, new AcquisitionCost(3, 2), null))
                    .onBegin(ScriptedSource.Ending.UNAVAILABLE));
        }
        return sources;
    }

    @Test
    void 反复换来源的每刻都不带进展_站桩能判成卡住() {
        FakeBackpack backpack = new FakeBackpack(36);
        ItemAcquisition engine = new ItemAcquisition(unavailableSources(15), backpack, new FakeOffhand(),
                new FakeTags(), new FixedSpot(0, 64, 0), Optional.empty(), ItemAcquisition.DEFAULT_MAX_DEPTH);
        Action action = engine.need(new ItemRequest(WantedItem.ofItem(LOG), 4, "木头"), Permissions.DEFAULT);
        TrackerFeed feed = new TrackerFeed();
        for (int tick = 1; tick <= 100; tick++) {
            ActionStatus status = feed.feed(action.tick(new StubTick(tick)));
            if (status instanceof ActionStatus.Running
                    && feed.tracker.status() instanceof ProgressTracker.Status.Stuck) {
                return;
            }
        }
        throw new AssertionError("换了十几轮来源，站桩二十刻还没被停滞守卫判成卡住");
    }

    @Test
    void 都接不上时如实失败_不说还在做() {
        FakeBackpack backpack = new FakeBackpack(36);
        ItemAcquisition engine = new ItemAcquisition(unavailableSources(3), backpack, new FakeOffhand(),
                new FakeTags(), new FixedSpot(0, 64, 0), Optional.empty(), ItemAcquisition.DEFAULT_MAX_DEPTH);
        Action action = engine.need(new ItemRequest(WantedItem.ofItem(LOG), 4, "木头"), Permissions.DEFAULT);
        ActionStatus status = ActionStatus.running();
        for (int tick = 1; tick <= 50 && status instanceof ActionStatus.Running; tick++) {
            status = action.tick(new StubTick(tick));
        }
        assertInstanceOf(ActionStatus.Failed.class, status);
    }

    @Test
    void 真拿到东西的那几刻照旧带进展() {
        FakeBackpack backpack = new FakeBackpack(36);
        ScriptedSource forest = new ScriptedSource("林子", backpack)
                .answer(new SourceQuote.Offer("林子", 4, new AcquisitionCost(5, 2), null))
                .onBegin(ScriptedSource.Ending.DELIVER);
        ItemAcquisition engine = new ItemAcquisition(List.of(forest), backpack, new FakeOffhand(),
                new FakeTags(), new FixedSpot(0, 64, 0), Optional.empty(), ItemAcquisition.DEFAULT_MAX_DEPTH);
        Action action = engine.need(new ItemRequest(WantedItem.ofItem(LOG), 1, "木头"), Permissions.DEFAULT);
        TrackerFeed feed = new TrackerFeed();
        boolean sawProgress = false;
        for (int tick = 1; tick <= 20; tick++) {
            ActionStatus status = feed.feed(action.tick(new StubTick(tick)));
            if (status instanceof ActionStatus.Running running && running.progressed()) sawProgress = true;
            if (status instanceof ActionStatus.Done) break;
        }
        assertEquals(4, backpack.stacks().stream()
                .filter(stack -> stack.itemId().equals(LOG))
                .mapToInt(stack -> stack.count()).sum());
        assertEquals(true, sawProgress);
        assertEquals(ProgressTracker.Status.PROGRESSING, feed.tracker.status());
    }
}
