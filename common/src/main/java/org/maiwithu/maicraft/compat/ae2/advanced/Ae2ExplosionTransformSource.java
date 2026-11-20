// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.advanced;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.acquire.spi.StepwiseActions;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * AE2 奇点爆炸转化的独立物品来源（途径 {@code ae2_explosion_transform}）：
 * 仅针对 {@code ae2:quantum_entangled_singularity} 报价，单次转化恒产 2 枚同频缠绕奇点。
 * 优先选用不破坏地形的 {@code ae2:tiny_tnt}；无迷你 TNT 且许可放开 {@code change_blocks=any}
 * 时才允许使用原版 {@code minecraft:tnt}；执行前强制完成保护区、地标、建筑、机器与权限选点校验。
 */
public final class Ae2ExplosionTransformSource implements ItemSource {

    /** 搜索安全爆炸空地的最大水平半径（格）。C 玩家常识。 */
    private static final int SITE_SEARCH_RADIUS = 16;
    /** 缺一项原料折算的额外代价格数。 */
    private static final double SHORTFALL_BLOCKS_PER_ITEM = 32.0;

    private final Ae2AdvancedReads reads;
    private final PlayerServices services;

    public Ae2ExplosionTransformSource(Ae2AdvancedReads reads, PlayerServices services) {
        this.reads = Objects.requireNonNull(reads, "reads");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public String describe() {
        return "在安全空地引爆转化奇点";
    }

    @Override public AcquireVia via() {
        return ExplosionTransformPlan.VIA;
    }

    @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
        if (!matchesTarget(request)) {
            return new SourceQuote.Unavailable(describe(), "奇点爆炸转化只产出 " + ExplosionTransformPlan.RESULT_ITEM);
        }
        Map<String, Integer> carried = carriedCounts();
        Function<String, Set<String>> tags = item -> services.itemTags().tagsOf(item);
        ExplosionTransformPlan.LoadoutChoice loadoutChoice = ExplosionTransformPlan.chooseLoadout(
                context.permissions(), carried, tags);
        if (loadoutChoice instanceof ExplosionTransformPlan.LoadoutChoice.Rejected rejected) {
            return new SourceQuote.NeedsApproval(describe(), rejected.problem());
        }
        ExplosionTransformPlan.Loadout loadout = ((ExplosionTransformPlan.LoadoutChoice.Chosen) loadoutChoice).loadout();
        Optional<ExplosionTransformPlan.SiteChoice> siteChoice = selectSite(context, loadout);
        if (siteChoice.isEmpty()) {
            return new SourceQuote.Unavailable(describe(), "角色现在不在世界里，无法挑选安全引爆空地");
        }
        if (siteChoice.get() instanceof ExplosionTransformPlan.SiteChoice.Blocked blocked) {
            return blocked.problem().kind() == Problem.Kind.NEED_APPROVAL
                    ? new SourceQuote.NeedsApproval(describe(), blocked.problem())
                    : new SourceQuote.Unavailable(describe(), blocked.problem().message());
        }
        ExplosionTransformPlan.SiteChoice.Found found = (ExplosionTransformPlan.SiteChoice.Found) siteChoice.get();
        BlockPos origin = blockPosOf(context.characterAt());
        double distance = Math.sqrt(found.explosiveAt().distSqr(origin));
        double shortfall = loadout.missingCount() * SHORTFALL_BLOCKS_PER_ITEM;
        int offerCount = Math.min(request.count(), ExplosionTransformPlan.PAIR_YIELD);
        String risk = "在 " + found.explosiveAt().toShortString() + " 用 " + loadout.explosiveItem()
                + " 引爆 1 枚奇点与 1 枚 " + loadout.enderItem() + "，单次产出 2 枚同频缠绕奇点";
        return new SourceQuote.Offer(describe(), offerCount, new AcquisitionCost(distance, 8, shortfall),
                risk, loadout.explosiveItem());
    }

    @Override public List<ItemRequest> missingIngredients(ItemRequest request, SourceQuote.Offer offer,
                                                          SourceContext context) {
        if (!matchesTarget(request)) return List.of();
        Map<String, Integer> carried = carriedCounts();
        Function<String, Set<String>> tags = item -> services.itemTags().tagsOf(item);
        ExplosionTransformPlan.LoadoutChoice choice = ExplosionTransformPlan.chooseLoadout(
                context.permissions(), carried, tags);
        if (!(choice instanceof ExplosionTransformPlan.LoadoutChoice.Chosen chosen)) return List.of();
        ExplosionTransformPlan.Loadout loadout = chosen.loadout();
        List<ItemRequest> missing = new ArrayList<>();
        addIfMissing(missing, carried, ExplosionTransformPlan.SINGULARITY_ITEM, "奇点爆炸转化原料");
        addIfMissing(missing, carried, loadout.enderItem(), "奇点爆炸转化末影原料");
        addIfMissing(missing, carried, loadout.explosiveItem(), "奇点爆炸转化引爆块");
        addIfMissing(missing, carried, loadout.igniterItem(), "奇点爆炸转化点火件");
        return List.copyOf(missing);
    }

    @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        if (!matchesTarget(request)) return Optional.empty();
        Map<String, Integer> carried = carriedCounts();
        Function<String, Set<String>> tags = item -> services.itemTags().tagsOf(item);
        ExplosionTransformPlan.LoadoutChoice loadoutChoice = ExplosionTransformPlan.chooseLoadout(
                context.permissions(), carried, tags);
        if (!(loadoutChoice instanceof ExplosionTransformPlan.LoadoutChoice.Chosen chosen)) {
            return Optional.empty();
        }
        ExplosionTransformPlan.Loadout loadout = chosen.loadout();
        Optional<ExplosionTransformPlan.SiteChoice> siteChoice = selectSite(context, loadout);
        if (siteChoice.isEmpty() || !(siteChoice.get() instanceof ExplosionTransformPlan.SiteChoice.Found found)) {
            return Optional.empty();
        }
        // 先逐项备齐奇点、末影珍珠/粉、引爆块与点火件，再走安全放爆 → 丢料 → 点燃撤离 → 核对同频拾取。
        List<Action> steps = new ArrayList<>();
        addNeedStep(steps, context, ExplosionTransformPlan.SINGULARITY_ITEM, "奇点爆炸转化");
        addNeedStep(steps, context, loadout.enderItem(), "奇点爆炸转化");
        addNeedStep(steps, context, loadout.explosiveItem(), "奇点爆炸转化引爆");
        addNeedStep(steps, context, loadout.igniterItem(), "奇点爆炸转化点火");
        steps.add(new ExplosionTransformRun(loadout, found, context.permissions(), reads, services, context.records()));
        return Optional.of(new StepwiseActions("备料并在安全空地引爆转化成对量子缠绕奇点", steps.toArray(Action[]::new)));
    }

    private boolean matchesTarget(ItemRequest request) {
        return request.wanted().matches(ExplosionTransformPlan.RESULT_ITEM,
                services.itemTags().tagsOf(ExplosionTransformPlan.RESULT_ITEM));
    }

    private Optional<ExplosionTransformPlan.SiteChoice> selectSite(SourceContext context,
                                                                   ExplosionTransformPlan.Loadout loadout) {
        PlayerContext current = services.context().get();
        if (current == null || current.level() == null) return Optional.empty();
        ClientLevel level = current.level();
        int maxRadius = context.radiusBlocks() == null
                ? SITE_SEARCH_RADIUS : Math.min(SITE_SEARCH_RADIUS, Math.max(4, context.radiusBlocks()));
        BlockPos origin = blockPosOf(context.characterAt());
        List<BlockPos> candidates = ExplosionTransformPlan.spiralCandidates(origin, 2, maxRadius);
        ExplosionTransformPlan.SiteProbe probe = new ClientSiteProbe(level);
        return Optional.of(ExplosionTransformPlan.chooseSafeSite(
                candidates, loadout, context.permissions(), services.protection(), probe));
    }

    private Map<String, Integer> carriedCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (BackpackStack stack : services.backpack().stacks()) {
            counts.merge(stack.itemId(), stack.count(), Integer::sum);
        }
        return counts;
    }

    private void addIfMissing(List<ItemRequest> list, Map<String, Integer> carried, String itemId, String why) {
        if (carried.getOrDefault(itemId, 0) < 1) {
            list.add(new ItemRequest(WantedItem.ofItem(itemId), 1, why));
        }
    }

    private void addNeedStep(List<Action> steps, SourceContext context, String itemId, String why) {
        steps.add(services.needs().actionFor(new ItemRequest(WantedItem.ofItem(itemId), 1, why), context.permissions()));
    }

    private static BlockPos blockPosOf(WorldPosition pos) {
        return new BlockPos(pos.x(), pos.y(), pos.z());
    }

    /** 在客户端世界检查地面是否开阔平坦、周边是否有方块实体机器。 */
    private static final class ClientSiteProbe implements ExplosionTransformPlan.SiteProbe {

        private final ClientLevel level;

        private ClientSiteProbe(ClientLevel level) {
            this.level = level;
        }

        @Override public boolean openGround(BlockPos cell) {
            if (!level.isLoaded(cell) || !level.isLoaded(cell.below()) || !level.isLoaded(cell.above())) {
                return false;
            }
            BlockState below = level.getBlockState(cell.below());
            BlockState at = level.getBlockState(cell);
            BlockState above = level.getBlockState(cell.above());
            return below.isSolidRender(level, cell.below())
                    && at.canBeReplaced() && at.getFluidState().isEmpty()
                    && above.canBeReplaced() && above.getFluidState().isEmpty();
        }

        @Override public boolean hasMachineNear(BlockPos center, int radius) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -2; dy <= 3; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        BlockPos pos = center.offset(dx, dy, dz);
                        if (level.isLoaded(pos) && level.getBlockEntity(pos) != null) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        @Override public String dimension() {
            return level.dimension().location().toString();
        }
    }
}
