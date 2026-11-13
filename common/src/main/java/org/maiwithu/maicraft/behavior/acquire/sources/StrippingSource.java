// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import net.minecraft.tags.ItemTags;

import org.maiwithu.maicraft.behavior.acquire.engine.CarriedItems;
import org.maiwithu.maicraft.behavior.acquire.engine.OffhandContents;
import org.maiwithu.maicraft.behavior.acquire.engine.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.acquire.spi.StepwiseActions;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 斧头去皮来源：要拿去皮原木、去皮木头、去皮菌柄菌核或去皮竹块（以及 #c:stripped_logs 等去皮木料标签）时，
 * 备好一把斧头与对应的未去皮木料，在身边安全空位一块块放下、拿斧头削皮（副手持盾时自动潜行右键）、当场挖回进包。
 *
 * <p>归入合成途径（{@link AcquireVia#CRAFT}）：不给 via 或给 via=craft 都会参与盘算；机械动力手工涂抹做安山机壳、
 * 铜机壳、黄铜机壳缺去皮木料时，也会经由本来源一条龙把原木削好再涂。
 */
public final class StrippingSource implements ItemSource {

    /** 斧头标签：任意材质的斧头都能削皮。 */
    public static final String AXES_TAG = "minecraft:axes";
    /** 一把木斧的耐久是 59 点，每削一块耗 1 点耐久。 */
    static final int WOODEN_AXE_USES = 59;
    /** 一趟最多连削几块，做完回引擎清点，还缺再来下一趟，不把背包卡死。 */
    static final int MAX_BATCH_PER_RUN = 16;

    private final Map<String, String> resultToRaw;
    private final BackpackView backpack;
    private final OffhandContents offhand;
    private final ReadsItemTags tags;
    private final ItemNeeds needs;
    private final PermissionCheck permission;
    private final InWorldBlockTransform.Seams seams;
    private final IntSupplier axeUsesCarried;

    private Supplier<List<ItemRequest>> outerWanted = List::of;
    private boolean pricingIngredients;

    public StrippingSource(BackpackView backpack, OffhandContents offhand, ReadsItemTags tags, ItemNeeds needs,
            PermissionCheck permission, InWorldBlockTransform.Seams seams, IntSupplier axeUsesCarried) {
        this(VanillaStrippables.resultToRaw(), backpack, offhand, tags, needs, permission, seams, axeUsesCarried);
    }

    public StrippingSource(Map<String, String> resultToRaw, BackpackView backpack, OffhandContents offhand,
            ReadsItemTags tags, ItemNeeds needs, PermissionCheck permission, InWorldBlockTransform.Seams seams,
            IntSupplier axeUsesCarried) {
        this.resultToRaw = Map.copyOf(Objects.requireNonNull(resultToRaw, "resultToRaw"));
        this.backpack = Objects.requireNonNull(backpack, "backpack");
        this.offhand = offhand;
        this.tags = Objects.requireNonNull(tags, "tags");
        this.needs = Objects.requireNonNull(needs, "needs");
        this.permission = Objects.requireNonNull(permission, "permission");
        this.seams = seams;
        this.axeUsesCarried = axeUsesCarried != null ? axeUsesCarried : this::defaultAxeUses;
    }

    /** 从当刻角色的真实背包统计所有斧头剩余可削的总次数；不在世界里时返回 0。 */
    public static IntSupplier liveAxeUses(Supplier<PlayerContext> contexts) {
        Objects.requireNonNull(contexts, "contexts");
        return () -> {
            PlayerContext current = contexts.get();
            if (current == null || current.localPlayer() == null) return 0;
            var inventory = current.localPlayer().getInventory();
            int total = 0;
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                var stack = inventory.getItem(slot);
                if (stack.isEmpty() || !stack.is(ItemTags.AXES)) continue;
                int uses = stack.getMaxDamage() > 0
                        ? Math.max(1, stack.getMaxDamage() - stack.getDamageValue())
                        : WOODEN_AXE_USES;
                total += uses * stack.getCount();
            }
            return total;
        };
    }

    /** 接上引擎当前推进的请求链，防止把外层正要留着的未去皮原木拿去削掉。 */
    public void watchChain(Supplier<List<ItemRequest>> inProgress, Supplier<List<ItemRequest>> outer) {
        Objects.requireNonNull(inProgress, "inProgress");
        this.outerWanted = Objects.requireNonNull(outer, "outer");
    }

    @Override
    public String describe() {
        return "斧头去皮";
    }

    @Override
    public AcquireVia via() {
        return AcquireVia.CRAFT;
    }

    @Override
    public SourceQuote quote(ItemRequest request, SourceContext context) {
        if (request.wanted().potion() != null) {
            return new SourceQuote.Unavailable(describe(), "斧头去皮做不出药水");
        }
        List<Candidate> candidates = candidatesFor(request, context);
        if (candidates.isEmpty()) {
            return new SourceQuote.Unavailable(describe(), "斧头削不出 " + request.wanted().specifier());
        }
        Candidate chosen = candidates.getFirst();
        Optional<Problem> blocked = permission.blockAllowed(context.permissions(),
                PermissionCheck.WorldAction.PLACE_BLOCK, context.characterAt(), chosen.rawId());
        if (blocked.isPresent()) {
            return new SourceQuote.NeedsApproval(describe(), blocked.get());
        }
        int axesShort = axesShortFor(request.count());
        int totalMissing = chosen.rawShort() + axesShort;
        double shortfall = totalMissing * RecipeSource.SHORTFALL_BLOCKS_PER_ITEM;
        int actions = request.count() * 3 + (axesShort > 0 ? 2 : 0);
        String risk = "在身边放下 " + request.count() + " 块 " + chosen.rawId() + " 用斧头削皮再挖回"
                + (totalMissing > 0 ? "（缺的原木或斧头先去备）" : "");
        return new SourceQuote.Offer(describe(), request.count(),
                new AcquisitionCost(0, actions, shortfall), risk, chosen.strippedId());
    }

    @Override
    public List<ItemRequest> missingIngredients(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        String strippedId = offer.hint();
        String rawId = resultToRaw.get(strippedId);
        if (rawId == null) return List.of();
        List<ItemRequest> missing = new ArrayList<>();
        int axesShort = axesShortFor(request.count());
        if (axesShort > 0) {
            missing.add(new ItemRequest(WantedItem.ofTag(AXES_TAG), axesShort, "给 " + rawId + " 去皮"));
        }
        int rawShort = Math.max(0, request.count() - carried(rawId));
        if (rawShort > 0) {
            missing.add(new ItemRequest(WantedItem.ofItem(rawId), rawShort, "放下削皮做 " + strippedId));
        }
        return List.copyOf(missing);
    }

    @Override
    public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        if (seams == null) return Optional.empty();
        String strippedId = offer.hint();
        String rawId = resultToRaw.get(strippedId);
        if (rawId == null || blockedByOuter(rawId)) return Optional.empty();
        if (permission.blockAllowed(context.permissions(), PermissionCheck.WorldAction.PLACE_BLOCK,
                context.characterAt(), rawId).isPresent()) {
            return Optional.empty();
        }
        int rounds = Math.min(request.count(), MAX_BATCH_PER_RUN);
        Permissions permissions = context.permissions();
        // 先备斧头，再按做完斧头那一刻背包实际剩余的原木补足差额（避免做木斧用掉身上的原木后不够削），最后逐块放下削皮挖回。
        Action prepareAxe = new DeferredStep("准备去皮用的斧头", () -> {
            int shortAxes = axesShortFor(rounds);
            return shortAxes <= 0 ? Optional.empty()
                    : Optional.of(needs.actionForTool(
                            new ItemRequest(WantedItem.ofTag(AXES_TAG), shortAxes, "给 " + rawId + " 去皮"),
                            permissions, context.records()));
        });
        Action prepareRaw = new DeferredStep("准备要削皮的 " + rawId, () -> {
            int shortRaw = rounds - carried(rawId);
            return shortRaw <= 0 ? Optional.empty()
                    : Optional.of(needs.actionFor(
                            new ItemRequest(WantedItem.ofItem(rawId), shortRaw, "放下削皮做 " + strippedId,
                                    request.prefer()),
                            permissions, context.records()));
        });
        Action strip = new DeferredStep("放下 " + rawId + " 削成 " + strippedId, () -> {
            int doable = Math.min(rounds, Math.min(carried(rawId), axeUsesCarried.getAsInt()));
            if (doable <= 0) doable = rounds;
            return Optional.of(new InWorldBlockTransform(rawId, WantedItem.ofTag(AXES_TAG), strippedId, false,
                    doable, permissions, context.records(), seams));
        });
        return Optional.of(new StepwiseActions("用斧头把 " + rawId + " 削成 " + strippedId,
                prepareAxe, prepareRaw, strip));
    }

    // 筛出所有能削出目标且不跟外层抢料的木种，按缺料数、材料倾向、拿取总代价与身上余量排好先后。
    private List<Candidate> candidatesFor(ItemRequest request, SourceContext context) {
        List<Candidate> found = new ArrayList<>();
        Set<String> prefer = Set.copyOf(request.prefer());
        boolean wasPricing = pricingIngredients;
        pricingIngredients = true;
        try {
            for (Map.Entry<String, String> entry : resultToRaw.entrySet()) {
                String strippedId = entry.getKey();
                String rawId = entry.getValue();
                if (!request.wanted().matches(strippedId, tags.tagsOf(strippedId))) continue;
                // 未去皮原木本身已经满足请求（例如要 #minecraft:logs）时不白削一刀。
                if (request.wanted().matches(rawId, tags.tagsOf(rawId))) continue;
                if (blockedByOuter(rawId)) continue;
                int rawCarried = carried(rawId);
                int rawShort = Math.max(0, request.count() - rawCarried);
                boolean preferred = prefers(rawId, strippedId, prefer);
                double fetchCost = rawShort == 0 || wasPricing ? 0
                        : needs.cheapestCost(new ItemRequest(WantedItem.ofItem(rawId), rawShort, "挑去皮用的木料"),
                                context.permissions(), true).orElse(Double.MAX_VALUE);
                found.add(new Candidate(strippedId, rawId, rawCarried, rawShort, preferred, fetchCost));
            }
        } finally {
            pricingIngredients = wasPricing;
        }
        found.sort(Comparator.comparingInt((Candidate c) -> c.rawShort == 0 ? 0 : 1)
                .thenComparing(c -> !c.preferred)
                .thenComparingDouble(Candidate::fetchCost)
                .thenComparingInt(Candidate::rawShort)
                .thenComparing(Comparator.comparingInt(Candidate::rawCarried).reversed()));
        return found;
    }

    private boolean prefers(String rawId, String strippedId, Set<String> prefer) {
        if (prefer.isEmpty()) return false;
        for (String p : prefer) {
            WantedItem target = new WantedItem(p);
            if (target.matches(rawId, tags.tagsOf(rawId)) || target.matches(strippedId, tags.tagsOf(strippedId))) {
                return true;
            }
        }
        return false;
    }

    private boolean blockedByOuter(String itemId) {
        Set<String> itemTags = tags.tagsOf(itemId);
        for (ItemRequest outer : outerWanted.get()) {
            if (outer.wanted().matches(itemId, itemTags)) return true;
        }
        return false;
    }

    private int carried(String itemId) {
        return CarriedItems.matching(backpack, offhand, WantedItem.ofItem(itemId), tags);
    }

    private int axesShortFor(int neededUses) {
        int haveUses = axeUsesCarried.getAsInt();
        if (haveUses >= neededUses) return 0;
        return (neededUses - haveUses + WOODEN_AXE_USES - 1) / WOODEN_AXE_USES;
    }

    private int defaultAxeUses() {
        return CarriedItems.matching(backpack, offhand, WantedItem.ofTag(AXES_TAG), tags) * WOODEN_AXE_USES;
    }

    private record Candidate(String strippedId, String rawId, int rawCarried, int rawShort,
                             boolean preferred, double fetchCost) {}
}
