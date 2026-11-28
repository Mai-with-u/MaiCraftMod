// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.inscriber;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.FailedOnlyAtSpots;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.acquire.spi.UnreachablePlaces;
import org.maiwithu.maicraft.behavior.menu.ClientMenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuTakeRound;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.permission.StorageUse;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.ObservationVisibility;
import org.maiwithu.maicraft.game.world.ScanTargets;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.ReportsUnconfirmed;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 压印器工作格单取来源：只在拿东西（{@code obtain}）提取侧开放从附近已见压印器的四个工作格（顶、底、中间、输出）
 * 取回占槽物品进角色背包，归入容器途径（{@code via=container}），而绝不把 {@code ae2:inscriber} 标成通用储物容器，
 * 从而彻底杜绝存东西或腾背包把不相干的杂物塞进压印器。
 */
public final class Ae2InscriberSource implements ItemSource {

    private static final String INSCRIBER_ID = "ae2:inscriber";
    private static final int SEARCH_RADIUS_BLOCKS = 32;
    private static final int MAX_CHUNK_RADIUS = 3;
    private static final int SCAN_BUDGET = 4096;
    private static final int WORK_SLOT_COUNT = 4;
    private static final long GIVE_UP_TICKS = 20L * 25;

    private final Ae2Compat compat;
    private final PlayerServices services;
    private final ScanTargets scanTargets;
    private final BiFunction<BlockPos, Permissions, MenuOpening> openings;

    public Ae2InscriberSource(Ae2Compat compat, PlayerServices services) {
        this(compat, services, (at, permissions) -> new ClientMenuOpening(at, permissions,
                services.menuLayouts(), services.approach(), services.interactions(), services.context()));
    }

    public Ae2InscriberSource(Ae2Compat compat, PlayerServices services,
            BiFunction<BlockPos, Permissions, MenuOpening> openings) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
        this.scanTargets = new ScanTargets(services.blockScans());
        this.openings = Objects.requireNonNull(openings, "openings");
    }

    @Override public String describe() {
        return "附近的压印器";
    }

    @Override public AcquireVia via() {
        return AcquireVia.CONTAINER;
    }

    @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
        int radius = context.radiusBlocks() == null ? SEARCH_RADIUS_BLOCKS : context.radiusBlocks();
        BlockPos center = new BlockPos(context.characterAt().x(), context.characterAt().y(), context.characterAt().z());
        List<FoundInscriber> candidates = findCandidates(center, radius, context);
        UnreachablePlaces unreachable = context.unreachable();
        boolean skippedUnreachable = false;
        for (FoundInscriber candidate : candidates) {
            if (unreachable != null && unreachable.skips(candidate.worldPos(), request.wanted().describe())) {
                skippedUnreachable = true;
                continue;
            }
            int count = countMatching(candidate.slots(), request);
            if (count > 0) {
                return new SourceQuote.Offer(describe(), Math.min(count, request.count()),
                        new AcquisitionCost(candidate.distance(), 3),
                        "从压印器工作格取出占着的物品", encodeHint(candidate.worldPos()));
            }
        }
        if (skippedUnreachable) {
            return new SourceQuote.Unavailable(describe(), UnreachablePlaces.SKIPPED);
        }
        return new SourceQuote.Unavailable(describe(),
                "附近已见的压印器槽位里没有" + request.wanted().describe());
    }

    @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        WorldPosition targetPos = decodeHint(offer.hint());
        BlockPos blockPos = new BlockPos(targetPos.x(), targetPos.y(), targetPos.z());
        StorageUse use = services.protection().storageUse(targetPos, INSCRIBER_ID,
                context.permissions().protectedLandmarks());
        if (!use.usable()) return Optional.empty();
        PlayerContext current = services.context().get();
        ClientLevel level = current == null ? null : current.level();
        Optional<Ae2Inscribers.Slots> slots = compat.inscriberSlots(level, blockPos);
        if (slots.isEmpty() || countMatching(slots.get(), request) <= 0) {
            return Optional.empty();
        }
        List<ItemRequest> wants = new ArrayList<>();
        wants.add(request);
        for (ItemRequest extra : context.alsoWanted()) {
            if (!extra.wanted().specifier().equals(request.wanted().specifier())
                    && countMatching(slots.get(), extra) > 0) {
                wants.add(extra);
            }
        }
        return Optional.of(new TakeFromInscriber(blockPos, targetPos, wants, context));
    }

    private List<FoundInscriber> findCandidates(BlockPos center, int radius, SourceContext context) {
        PlayerContext current = services.context().get();
        ClientLevel level = current == null ? null : current.level();
        LocalPlayer player = current == null ? null : current.localPlayer();
        Set<BlockPos> positions = new LinkedHashSet<>(compat.inscribersNear(level, center, radius));
        if (level != null) {
            Set<Block> blocks = Ae2Compat.blocksOf(List.of(INSCRIBER_ID));
            if (!blocks.isEmpty()) {
                scanTargets.ensure(level, blocks);
                var query = services.blockScans().query(level, center, blocks, Integer.MAX_VALUE,
                        Math.min(MAX_CHUNK_RADIUS, Math.max(1, radius / 16 + 1)), SCAN_BUDGET);
                for (BlockPos hit : query.hits()) {
                    // 现场扫描出的压印器须在搜索半径内且角色亲眼看得见（或就在身边跟前），不隔墙透视陌生地下室。
                    if (Math.sqrt(hit.distSqr(center)) <= radius
                            && (player == null || hit.distSqr(center) <= 16 || ObservationVisibility.block(player, hit))) {
                        positions.add(hit);
                    }
                }
            }
        }
        String dim = level != null ? level.dimension().location().toString() : context.characterAt().dimension();
        List<FoundInscriber> found = new ArrayList<>();
        for (BlockPos pos : positions) {
            double dist = Math.sqrt(pos.distSqr(center));
            if (dist > radius) continue;
            WorldPosition worldPos = new WorldPosition(pos.getX(), pos.getY(), pos.getZ(), dim);
            StorageUse use = services.protection().storageUse(worldPos, INSCRIBER_ID,
                    context.permissions().protectedLandmarks());
            if (!use.usable()) continue;
            compat.inscriberSlots(level, pos)
                    .ifPresent(slots -> found.add(new FoundInscriber(worldPos, slots, dist)));
        }
        found.sort(Comparator.comparingDouble(FoundInscriber::distance));
        return found;
    }

    private int countMatching(Ae2Inscribers.Slots slots, ItemRequest request) {
        int total = 0;
        for (MachineState.Shown shown : slots.allShown()) {
            if (matchesItem(shown.itemId(), request)) total += shown.count();
        }
        return total;
    }

    private boolean matchesItem(String itemId, ItemRequest request) {
        return request.wanted().matches(itemId, services.itemTags().tagsOf(itemId));
    }

    private static String encodeHint(WorldPosition at) {
        return at.x() + "," + at.y() + "," + at.z() + (at.dimension() == null ? "" : "@" + at.dimension());
    }

    private static WorldPosition decodeHint(String hint) {
        String[] parts = hint.split("@")[0].split(",");
        String dim = hint.contains("@") ? hint.split("@")[1] : null;
        return new WorldPosition(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]), dim);
    }

    private record FoundInscriber(WorldPosition worldPos, Ae2Inscribers.Slots slots, double distance) {}

    /** 打开目标压印器，只从 0..3 四个工作格按需搬回背包，跳过 4..7 升级卡槽，搬完关窗。 */
    private final class TakeFromInscriber implements Action, ReportsUnconfirmed, FailedOnlyAtSpots {
        private final BlockPos pos;
        private final WorldPosition worldPos;
        private final List<ItemRequest> requests;
        private final SourceContext sourceContext;
        private final MenuOpening opening;
        private OpenedMenu menu;
        private MenuTakeRound round;
        private int waited;

        TakeFromInscriber(BlockPos pos, WorldPosition worldPos, List<ItemRequest> requests, SourceContext context) {
            this.pos = pos;
            this.worldPos = worldPos;
            this.requests = List.copyOf(requests);
            this.sourceContext = context;
            this.opening = openings.apply(pos, context.permissions());
        }

        @Override public ActionStatus tick(TickContext tick) {
            if (menu != null && ++waited > GIVE_UP_TICKS) {
                round.abandonAndNote("总期限到了");
                menu.abandon();
                return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "从压印器取出物品超时", null));
            }
            if (menu == null) return open(tick);
            return round.tick(tick, menu.reading());
        }

        private ActionStatus open(TickContext tick) {
            ActionStatus status = opening.tick(tick);
            if (status instanceof ActionStatus.Failed failed) {
                if (failed.problem().kind() == Problem.Kind.UNREACHABLE && sourceContext.unreachable() != null) {
                    String label = describe() + " " + worldPos.x() + ", " + worldPos.y() + ", " + worldPos.z();
                    sourceContext.unreachable().note(worldPos, label,
                            requests.getFirst().wanted().describe(), failed.problem().message());
                }
                return status;
            }
            if (!(status instanceof ActionStatus.Done)) return status;
            menu = new WorkSlotsOnlyMenu(opening.opened().orElseThrow());
            List<InscriberWant> wants = requests.stream().map(InscriberWant::new).toList();
            round = new MenuTakeRound(menu, wants, new Endings());
            return ActionStatus.progressed();
        }

        @Override public boolean onlyThoseSpotsFailed() { return false; }
        @Override public List<String> unconfirmedFacts() {
            return round == null ? List.of() : round.unconfirmedFacts();
        }
        @Override public Interruptibility interruptibility() {
            if (menu == null) return opening.interruptibility();
            return round != null && round.unsafeToStop() ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
        }
        @Override public void pause() {
            opening.pause();
            if (menu != null) menu.abandon();
        }
        @Override public void close() {
            opening.close();
            if (round != null) round.close(true);
            else if (menu != null) menu.abandon();
        }
        @Override public String describe() {
            return menu == null ? opening.describe() : "从压印器槽位取出 " + requests.getFirst().wanted().describe();
        }

        private final class InscriberWant implements MenuTakeRound.Want {
            private final ItemRequest request;
            private int startTotal = -1;

            InscriberWant(ItemRequest request) {
                this.request = request;
            }

            @Override public String label() {
                return "从压印器（" + pos.toShortString() + "）拿" + request.wanted().describe();
            }

            @Override public Predicate<SlotSnapshot> matcher() {
                return snap -> !snap.isEmpty() && request.wanted().matches(snap.key(),
                        services.itemTags().tagsOf(itemIdOf(snap)));
            }

            @Override public void firstReading(MenuContent.Reading reading) {
                startTotal = totalMatched(reading);
            }

            @Override public int stillNeeded(MenuContent.Reading reading) {
                int taken = startTotal - totalMatched(reading);
                return request.count() - taken;
            }

            @Override public boolean settleAfterDiverge() { return false; }

            private int totalMatched(MenuContent.Reading reading) {
                int sum = 0;
                for (SlotSnapshot snap : reading.containerSnapshots()) {
                    if (matcher().test(snap)) sum += snap.count();
                }
                return sum;
            }
        }

        private final class Endings implements MenuTakeRound.Endings {
            @Override public void rememberBeforeClose(Optional<MenuContent.Reading> lastReading) {}
            @Override public ActionStatus finishAfterClosed(boolean tookAnything) { return ActionStatus.done(); }
            @Override public ActionStatus finishAfterMenuGone(boolean tookAnything) { return ActionStatus.done(); }
        }
    }

    /** 截断压印器菜单容器侧为前 4 个工作格（0 顶、1 底、2 中间、3 输出），屏蔽 4..7 升级卡槽以免被误拆走。 */
    private record WorkSlotsOnlyMenu(OpenedMenu delegate) implements OpenedMenu {
        @Override public Optional<MenuContent.Reading> reading() {
            return delegate.reading().map(raw -> {
                int limit = Math.min(WORK_SLOT_COUNT, raw.containerSlotIds().size());
                return new MenuContent.Reading(raw.channel(), raw.slots(),
                        raw.containerSlotIds().subList(0, limit), raw.playerSlotIds(), raw.playerInventoryIndices(),
                        raw.containerSnapshots().subList(0, limit), raw.playerSnapshots());
            });
        }
        @Override public boolean busy() { return delegate.busy(); }
        @Override public boolean cursorEmpty() { return delegate.cursorEmpty(); }
        @Override public SlotSnapshot cursor() { return delegate.cursor(); }
        @Override public boolean quickMove(int slotId) { return delegate.quickMove(slotId); }
        @Override public boolean click(int slotId, int button) { return delegate.click(slotId, button); }
        @Override public void noteCursorTakenFrom(int slotId) { delegate.noteCursorTakenFrom(slotId); }
        @Override public Action closing() { return delegate.closing(); }
        @Override public void abandon() { delegate.abandon(); }
    }

    private static String itemIdOf(SlotSnapshot snapshot) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(snapshot.stack().getItem());
        return key.toString();
    }
}
