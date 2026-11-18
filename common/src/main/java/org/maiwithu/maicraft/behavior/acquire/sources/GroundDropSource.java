// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.behavior.acquire.engine.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.FailedOnlyAtSpots;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.acquire.spi.UnreachablePlaces;
import org.maiwithu.maicraft.behavior.inventory.drops.PicksUpDrops;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.ClientBackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.ObservationVisibility;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 附近可见地面掉落物的来源：身上不够时先看周围地面有没有亲眼看得见的目标掉落物（{@code ItemEntity}），
 * 有就直接走过去让原版拾取吸进背包，排在身上之后、翻箱子与现做之前。
 *
 * <p>只认角色视线看得见且在搜索半径内的掉落物，隔墙或埋在地下的不透视；走过去够不着或被收走的单件记入
 * 本轮走不到的位置，引擎跳过它继续捡下一件或换去开箱、合成、采掘。
 */
public final class GroundDropSource implements ItemSource {

    /** 不指定半径时默认在角色周围多大范围（格）里找看得见的地上掉落物。 */
    public static final int SEARCH_RADIUS_BLOCKS = 32;

    /** 地面可见掉落物的读端接缝：生产扫客户端世界并做视线检查，单元测试直接给候选表。 */
    public interface ScansDrops {

        /** 半径内此刻看得见的地面掉落物，按离角色由近到远排列。 */
        List<VisibleDrop> visibleAround(int radiusBlocks);
    }

    /**
     * 一件看得见的地面掉落物快照。
     *
     * @param entityId       游戏实体编号，交给精确拾取动作追捡这一件
     * @param key            带药水等区分信息的物品写法
     * @param itemId         物品注册 ID
     * @param count          这一堆有几件
     * @param at             此刻所在的方块位置
     * @param distanceBlocks 离角色当前位置的直线距离（格）
     */
    public record VisibleDrop(int entityId, String key, String itemId, int count,
            WorldPosition at, double distanceBlocks) {
        public VisibleDrop {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(itemId, "itemId");
            Objects.requireNonNull(at, "at");
        }
    }

    private final ScansDrops scans;
    private final ReadsItemTags tags;
    private final PicksUpDrops drops;
    private final BackpackView backpack;

    public GroundDropSource(ScansDrops scans, ReadsItemTags tags, PicksUpDrops drops, BackpackView backpack) {
        this.scans = Objects.requireNonNull(scans, "scans");
        this.tags = Objects.requireNonNull(tags, "tags");
        this.drops = Objects.requireNonNull(drops, "drops");
        this.backpack = Objects.requireNonNull(backpack, "backpack");
    }

    /** 生产读端：扫描渲染范围内未移除的非空掉落物实体，只保留半径内且通过角色视线遮挡检查的条目。 */
    public static ScansDrops clientScans(Supplier<PlayerContext> contexts) {
        Objects.requireNonNull(contexts, "contexts");
        return radiusBlocks -> {
            PlayerContext current = contexts.get();
            if (current == null || current.localPlayer() == null || current.level() == null) {
                return List.of();
            }
            LocalPlayer player = current.localPlayer();
            ClientLevel level = current.level();
            double maxDistSqr = (double) radiusBlocks * radiusBlocks;
            String dimension = level.dimension().location().toString();
            List<VisibleDrop> found = new ArrayList<>();
            for (Entity entity : level.entitiesForRendering()) {
                if (!(entity instanceof ItemEntity itemEntity) || !itemEntity.isAlive()) continue;
                ItemStack stack = itemEntity.getItem();
                if (stack.isEmpty()) continue;
                double distSqr = itemEntity.distanceToSqr(player);
                if (distSqr > maxDistSqr) continue;
                // 隔墙或封死在地下的掉落物不算眼前可见来源，低一格台阶唇沿由视线悬浮采样放行。
                if (!ObservationVisibility.entity(player, itemEntity)) continue;
                String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                WorldPosition at = new WorldPosition(
                        (int) Math.floor(itemEntity.getX()),
                        (int) Math.floor(itemEntity.getY()),
                        (int) Math.floor(itemEntity.getZ()),
                        dimension);
                found.add(new VisibleDrop(itemEntity.getId(), ClientBackpackView.keyOf(stack), itemId,
                        stack.getCount(), at, Math.sqrt(distSqr)));
            }
            found.sort(Comparator.comparingDouble(VisibleDrop::distanceBlocks));
            return List.copyOf(found);
        };
    }

    @Override public String describe() {
        return "地上的掉落物";
    }

    @Override public AcquireVia via() {
        return AcquireVia.PICKUP;
    }

    @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
        int radius = searchRadius(context);
        UnreachablePlaces unreachable = context.unreachable();
        List<VisibleDrop> matched = new ArrayList<>();
        boolean skippedUnreachable = false;
        for (VisibleDrop drop : scans.visibleAround(radius)) {
            if (!matches(drop, request)) continue;
            if (unreachable != null && unreachable.skips(drop.at(), request.wanted().describe())) {
                skippedUnreachable = true;
                continue;
            }
            matched.add(drop);
        }
        if (matched.isEmpty()) {
            if (skippedUnreachable) {
                return new SourceQuote.Unavailable(describe(), UnreachablePlaces.SKIPPED);
            }
            return new SourceQuote.Unavailable(describe(),
                    "附近地上没有看得见的" + request.wanted().describe() + "掉落物");
        }
        int totalCount = 0;
        for (VisibleDrop drop : matched) {
            totalCount += drop.count();
        }
        VisibleDrop nearest = matched.getFirst();
        // 走过去原版自动吸进包，不用开关容器界面（1 个走近动作，代价低于同距离翻箱子）。
        return new SourceQuote.Offer(describe(), Math.min(totalCount, request.count()),
                new AcquisitionCost(nearest.distanceBlocks(), 1),
                "地上的掉落物可能滚动、消失或被别人先捡走，到跟前以实际进包为准",
                String.valueOf(nearest.entityId()));
    }

    @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        List<VisibleDrop> targets = matchingNow(request, context);
        if (targets.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PickSequence(request, context, targets));
    }

    private List<VisibleDrop> matchingNow(ItemRequest request, SourceContext context) {
        int radius = searchRadius(context);
        UnreachablePlaces unreachable = context.unreachable();
        List<VisibleDrop> matched = new ArrayList<>();
        for (VisibleDrop drop : scans.visibleAround(radius)) {
            if (!matches(drop, request)) continue;
            if (unreachable != null && unreachable.skips(drop.at(), request.wanted().describe())) continue;
            matched.add(drop);
        }
        return matched;
    }

    private boolean matches(VisibleDrop drop, ItemRequest request) {
        return request.wanted().matches(drop.key(), tags.tagsOf(drop.itemId()));
    }

    private int carriedWanted(ItemRequest request) {
        int total = 0;
        for (BackpackStack stack : backpack.stacks()) {
            if (request.wanted().matches(stack, tags.tagsOf(stack.itemId()))) {
                total += stack.count();
            }
        }
        return total;
    }

    private static int searchRadius(SourceContext context) {
        return context.radiusBlocks() == null ? SEARCH_RADIUS_BLOCKS : context.radiusBlocks();
    }

    /** 按由近到远逐件追捡匹配掉落物，凑够件数即停；单处够不着记入走不到的位置，交回引擎继续换路。 */
    private final class PickSequence implements Action, FailedOnlyAtSpots {

        private final ItemRequest request;
        private final SourceContext sourceContext;
        private final List<VisibleDrop> queue;
        private int index;
        private int baseline = -1;
        private Action active;
        private boolean spotFailed;

        PickSequence(ItemRequest request, SourceContext sourceContext, List<VisibleDrop> queue) {
            this.request = request;
            this.sourceContext = sourceContext;
            this.queue = List.copyOf(queue);
        }

        @Override public ActionStatus tick(TickContext tick) {
            if (baseline < 0) baseline = carriedWanted(request);
            if (carriedWanted(request) - baseline >= request.count()) {
                closeActive();
                return ActionStatus.done();
            }
            if (active == null) {
                if (index >= queue.size()) {
                    return carriedWanted(request) > baseline ? ActionStatus.done() : spotFailure();
                }
                active = drops.pickUpOne(queue.get(index).entityId());
            }
            ActionStatus status = active.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            VisibleDrop tried = queue.get(index);
            active.close();
            active = null;
            index++;
            if (status instanceof ActionStatus.Failed failed) {
                noteUnreachable(tried, failed.problem());
                if (index >= queue.size() && carriedWanted(request) <= baseline) {
                    spotFailed = true;
                    return failed;
                }
            }
            return carriedWanted(request) - baseline >= request.count()
                    ? ActionStatus.done() : ActionStatus.progressed();
        }

        private ActionStatus spotFailure() {
            spotFailed = true;
            return ActionStatus.failed(Problem.of(Problem.Kind.NOT_FOUND,
                    "地上的" + request.wanted().describe() + "没捡进背包（可能已被收走或够不着）"));
        }

        private void noteUnreachable(VisibleDrop tried, Problem problem) {
            UnreachablePlaces unreachable = sourceContext.unreachable();
            if (unreachable == null) return;
            String label = describe() + " " + tried.at().x() + ", " + tried.at().y() + ", " + tried.at().z();
            unreachable.note(tried.at(), label, request.wanted().describe(), problem.message());
        }

        private void closeActive() {
            if (active != null) {
                active.close();
                active = null;
            }
        }

        @Override public boolean onlyThoseSpotsFailed() { return spotFailed; }
        @Override public void pause() { if (active != null) active.pause(); }
        @Override public void close() { closeActive(); }
        @Override public boolean stopSafely() { return active != null && active.stopSafely(); }
        @Override public boolean stoppedSafely() { return active == null || active.stoppedSafely(); }
        @Override public Interruptibility interruptibility() {
            return active == null ? Interruptibility.WORKING : active.interruptibility();
        }
        @Override public String describe() {
            return "走过去捡地上的" + request.wanted().describe();
        }
    }
}
