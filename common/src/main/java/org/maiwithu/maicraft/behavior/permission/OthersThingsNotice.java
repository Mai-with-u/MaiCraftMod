// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 动了别人放的东西的通报器：归属不拦动作，当角色确认从别人放的容器取放了物品、拆掉或改动了别人放的方块时，
 * 只在归属记录明确写着是其他玩家放的（whose=player）那一格上发一条 others_things_touched 事件。
 *
 * <p>自然战利品容器、自己放的、无放置记录与归属未问到的格子都不发。
 * 一次开箱只发一条；同一个目标里连着拆同一个人的方块先攒在一起，换人、停手满两秒或目标收段时合成一条发出，不逐格刷屏。
 */
public final class OthersThingsNotice {

    /** 没接保护判断或事件出口时的空通报器：离线单测不用专门配事件流。 */
    public static final OthersThingsNotice NONE = new OthersThingsNotice(null, id -> false, TaskEventSink.NONE);

    /** 连着拆同一个人的方块时，停手多久（刻）算这一段拆完并合发一条事件。 */
    public static final long BREAK_BATCH_WINDOW_TICKS = 40L;

    /** 容器两侧确认搬动的一样物品：带物品注册 ID、可读名字（没有时给空）与件数。 */
    public record MovedItem(String itemId, String displayName, int count) {
        public MovedItem {
            Objects.requireNonNull(itemId, "itemId");
        }
    }

    /** 动手或开挖前方块还在世界里时抄下的归属快照：挖破或改掉后服务端可能清掉这一格的归属记录，靠快照结算。 */
    public record BlockOwner(String ownerUuid, String ownerName, String blockId, WorldPosition at) {
        public BlockOwner {
            Objects.requireNonNull(ownerUuid, "ownerUuid");
            Objects.requireNonNull(ownerName, "ownerName");
            Objects.requireNonNull(blockId, "blockId");
            Objects.requireNonNull(at, "at");
        }
    }

    /** 正在攒着的一段连拆记录：记住第一格、去重后的坐标集、动手那一刻冻结的目标归属出口与最近破块刻号。 */
    private static final class PendingBreak {
        final BlockOwner first;
        final TaskEventSink capturedSink;
        final Set<WorldPosition> cells = new LinkedHashSet<>();
        long lastTick;

        PendingBreak(BlockOwner first, TaskEventSink capturedSink, long tick) {
            this.first = first;
            this.capturedSink = capturedSink;
            this.cells.add(first.at());
            this.lastTick = tick;
        }
    }

    private final Protection protection;
    private final Predicate<String> naturalLoot;
    private final TaskEventSink events;
    private PendingBreak pendingBreak;

    public OthersThingsNotice(Protection protection, Predicate<String> naturalLoot, TaskEventSink events) {
        this.protection = protection;
        this.naturalLoot = Objects.requireNonNull(naturalLoot, "naturalLoot");
        this.events = Objects.requireNonNull(events, "events");
    }

    /** 趁方块还在世界里时查看它是不是其他玩家放的；是就抄下放置者 UUID、名字、方块类型与坐标，不是就给空。 */
    public Optional<BlockOwner> snapshotBlock(WorldPosition at, String blockId) {
        if (protection == null || at == null || blockId == null || naturalLoot.test(blockId)) {
            return Optional.empty();
        }
        return protection.placedByOther(at)
                .map(uuid -> new BlockOwner(uuid, protection.nameOf(uuid), blockId, at));
    }

    /** 一次开箱取货收尾：只对确认是其他玩家放的容器，把确认少掉的各样物品合在一条事件里发出。 */
    public void containerTook(WorldPosition at, String blockId, List<MovedItem> taken) {
        List<MovedItem> actual = nonZero(taken);
        if (actual.isEmpty()) return;
        snapshotBlock(at, blockId).ifPresent(owner -> {
            List<OthersThingsTouched.Item> items = toItems(actual);
            String message = "从 " + owner.ownerName() + " 放的" + containerLabel(blockId)
                    + coords(at) + "拿了 " + describeItems(actual);
            events.publishInAction(TaskEvent.Kind.OTHERS_THINGS_TOUCHED, message,
                    OthersThingsTouched.took(owner.ownerName(), owner.ownerUuid(), blockId, at, items));
        });
    }

    /** 一次开箱存货收尾：只对确认是其他玩家放的容器，把确认存进去的各样物品合在一条事件里发出。 */
    public void containerStored(WorldPosition at, String blockId, List<MovedItem> stored) {
        List<MovedItem> actual = nonZero(stored);
        if (actual.isEmpty()) return;
        snapshotBlock(at, blockId).ifPresent(owner -> {
            List<OthersThingsTouched.Item> items = toItems(actual);
            String message = "往 " + owner.ownerName() + " 放的" + containerLabel(blockId)
                    + coords(at) + "存进了 " + describeItems(actual);
            events.publishInAction(TaskEvent.Kind.OTHERS_THINGS_TOUCHED, message,
                    OthersThingsTouched.stored(owner.ownerName(), owner.ownerUuid(), blockId, at, items));
        });
    }

    /** 对别人放的方块用了会改动它的东西并确认生效后发一条事件。 */
    public void blockChanged(BlockOwner snapshot) {
        if (snapshot == null) return;
        String message = "改动了 " + snapshot.ownerName() + " 放的 " + shortId(snapshot.blockId())
                + coords(snapshot.at());
        events.publishInAction(TaskEvent.Kind.OTHERS_THINGS_TOUCHED, message,
                OthersThingsTouched.changed(snapshot.ownerName(), snapshot.ownerUuid(),
                        snapshot.blockId(), snapshot.at()));
    }

    /** 确认拆掉了一格别人放的方块：同一个人的连拆先合并记账，换人时先把上一人的那段发出去。 */
    public void blockBroken(BlockOwner snapshot, long gameTick) {
        if (snapshot == null) return;
        if (pendingBreak != null && !pendingBreak.first.ownerUuid().equals(snapshot.ownerUuid())) {
            flushNow();
        }
        if (pendingBreak == null) {
            // 第一格破块时就把当刻推进的目标与步骤能力冻结下来，稍后收段冲刷时仍挂在原目标名下。
            pendingBreak = new PendingBreak(snapshot, events.captureAction(), gameTick);
            return;
        }
        if (pendingBreak.cells.add(snapshot.at())) {
            pendingBreak.lastTick = gameTick;
        }
    }

    /** 每刻看一眼：连拆停手满窗口期就把攒着的那一段合发出去。 */
    public void flushDue(long gameTick) {
        if (pendingBreak != null && gameTick - pendingBreak.lastTick >= BREAK_BATCH_WINDOW_TICKS) {
            flushNow();
        }
    }

    /** 目标收段、暂停或离开世界时立即把手里攒着的连拆发出去，不跨目标留账。 */
    public void flushNow() {
        if (pendingBreak == null) return;
        PendingBreak done = pendingBreak;
        pendingBreak = null;
        BlockOwner first = done.first;
        int count = done.cells.size();
        String message = count == 1
                ? "拆掉了 " + first.ownerName() + " 放的 " + shortId(first.blockId()) + coords(first.at())
                : "拆掉了 " + first.ownerName() + " 放的 " + count + " 格方块（从 "
                        + first.at().x() + ", " + first.at().y() + ", " + first.at().z()
                        + " 的 " + shortId(first.blockId()) + " 起）";
        done.capturedSink.publishInAction(TaskEvent.Kind.OTHERS_THINGS_TOUCHED, message,
                OthersThingsTouched.broke(first.ownerName(), first.ownerUuid(), first.blockId(), first.at(), count));
    }

    private static List<MovedItem> nonZero(List<MovedItem> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<MovedItem> filtered = new ArrayList<>();
        for (MovedItem item : raw) {
            if (item != null && item.count() > 0) filtered.add(item);
        }
        return filtered;
    }

    private static List<OthersThingsTouched.Item> toItems(List<MovedItem> items) {
        List<OthersThingsTouched.Item> out = new ArrayList<>(items.size());
        for (MovedItem item : items) {
            out.add(new OthersThingsTouched.Item(item.itemId(), item.count()));
        }
        return out;
    }

    private static String describeItems(List<MovedItem> items) {
        List<String> parts = new ArrayList<>(items.size());
        for (MovedItem item : items) {
            String label = item.displayName() != null && !item.displayName().isBlank()
                    ? item.displayName() : shortId(item.itemId());
            parts.add(item.count() + " 个" + label);
        }
        return String.join("、", parts);
    }

    private static String containerLabel(String blockId) {
        if ("minecraft:chest".equals(blockId) || "minecraft:trapped_chest".equals(blockId)) return "箱子";
        if ("minecraft:barrel".equals(blockId)) return "木桶";
        return shortId(blockId);
    }

    private static String shortId(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    private static String coords(WorldPosition at) {
        return "（" + at.x() + ", " + at.y() + ", " + at.z() + "）";
    }
}
