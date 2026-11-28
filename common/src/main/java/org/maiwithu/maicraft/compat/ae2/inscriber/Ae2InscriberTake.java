// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.inscriber;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuTakeRound;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.OutputLook;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.ItemMatch;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 压印器收产物并顺手收回角色本趟放进去的压印模板：点开界面先把输出格产物搬进背包，
 * 再把顶、底两个模板格里由角色自己放进去的不消耗模板一并收回背包，机内原本就有的模板不动。
 *
 * <p>既防止借来的唯一模板压完忘在机器里，也避免单台压印器紧接着压第二种模板或压处理器时被上一轮模板堵死。
 * 产物的看见数与拿回数只按目标产物结算，不把顺手收回的模板混进产出计数。
 */
public final class Ae2InscriberTake implements OutputLook {

    /** 点开以后取回产物与模板、关上界面的总期限（刻）。 */
    private static final int GIVE_UP_TICKS = 20 * 20;
    /** 压印器容器侧前两格为顶、底模板格（0 顶、1 底）。 */
    private static final int PLATE_SLOT_COUNT = 2;

    private final String itemId;
    private final Set<String> ownPlates;
    private final MenuOpening opening;
    private final Supplier<PlayerContext> contexts;
    private final List<String> notes = new ArrayList<>();
    private OpenedMenu menu;
    private MenuTakeRound round;
    private Action closing;
    private int seen;
    private int carriedBefore = -1;
    private int ownSamePlateBefore;
    private int ownSamePlateLeft;
    private int taken;
    private int waited;

    public Ae2InscriberTake(String itemId, Set<String> ownPlates, MenuOpening opening, Supplier<PlayerContext> contexts) {
        this.itemId = Objects.requireNonNull(itemId, "itemId");
        this.ownPlates = Objects.requireNonNull(ownPlates, "ownPlates");
        this.opening = Objects.requireNonNull(opening, "opening");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
    }

    @Override public ActionStatus tick(TickContext tick) {
        if (menu == null) return open(tick);
        if (++waited > GIVE_UP_TICKS) {
            if (round != null) round.abandonAndNote("总期限到了");
            menu.abandon();
            settleTaken();
            return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "点开压印器界面收产物超时", null));
        }
        if (round != null) {
            ActionStatus status = round.tick(tick, menu.reading());
            if (!(status instanceof ActionStatus.Running)) {
                notes.addAll(round.unconfirmedFacts());
                settleTaken();
            }
            return status;
        }
        if (closing == null) closing = menu.closing();
        ActionStatus status = closing.tick(tick);
        if (status instanceof ActionStatus.Failed failed) {
            notes.add("收完关压印器界面没能确认关上：" + failed.problem().message());
            return ActionStatus.done();
        }
        return status;
    }

    private ActionStatus open(TickContext tick) {
        ActionStatus status = opening.tick(tick);
        if (!(status instanceof ActionStatus.Done)) return status;
        menu = opening.opened().orElseThrow();
        Optional<MenuContent.Reading> reading = menu.reading();
        carriedBefore = carried();
        if (reading.isPresent()) {
            MenuContent.Reading current = reading.get();
            ownSamePlateBefore = countInPlateSlots(current, this::matchesOutputAndOwnPlate);
            ownSamePlateLeft = ownSamePlateBefore;
            int totalMatching = countInAllSlots(current, this::matchesOutput);
            seen = Math.max(0, totalMatching - ownSamePlateBefore);
            List<MenuTakeRound.Want> wants = buildWants(current, totalMatching);
            if (!wants.isEmpty()) {
                round = new MenuTakeRound(menu, wants, new Endings());
            }
        }
        return ActionStatus.progressed();
    }

    private List<MenuTakeRound.Want> buildWants(MenuContent.Reading reading, int totalMatchingOutput) {
        List<MenuTakeRound.Want> wants = new ArrayList<>();
        if (totalMatchingOutput > 0) {
            wants.add(new TakeOutput());
        }
        for (String plateSpec : List.copyOf(ownPlates)) {
            Predicate<SlotSnapshot> matcher = snapshot -> matchesSpec(snapshot, plateSpec);
            // 与目标产物同种的母版已由 TakeOutput 一并搬回，不重复建第二条取货项。
            if (countInPlateSlots(reading, matcher) > 0
                    && !plateSpec.equals(itemId)) {
                wants.add(new TakePlate(plateSpec, matcher));
            }
        }
        return wants;
    }

    private boolean matchesOutput(SlotSnapshot snapshot) {
        return !snapshot.isEmpty() && idOf(snapshot.stack()).equals(itemId);
    }

    private boolean matchesOutputAndOwnPlate(SlotSnapshot snapshot) {
        return matchesOutput(snapshot) && ownPlates.stream().anyMatch(spec -> matchesSpec(snapshot, spec));
    }

    private static boolean matchesSpec(SlotSnapshot snapshot, String spec) {
        return !snapshot.isEmpty() && ItemMatch.matches(snapshot.stack(), spec);
    }

    private static int countInAllSlots(MenuContent.Reading reading, Predicate<SlotSnapshot> matcher) {
        int total = 0;
        for (SlotSnapshot snapshot : reading.containerSnapshots()) {
            if (matcher.test(snapshot)) total += snapshot.count();
        }
        return total;
    }

    private static int countInPlateSlots(MenuContent.Reading reading, Predicate<SlotSnapshot> matcher) {
        List<SlotSnapshot> slots = reading.containerSnapshots();
        int limit = Math.min(PLATE_SLOT_COUNT, slots.size());
        int total = 0;
        for (int i = 0; i < limit; i++) {
            if (matcher.test(slots.get(i))) total += slots.get(i).count();
        }
        return total;
    }

    private void settleTaken() {
        if (carriedBefore < 0) return;
        int reclaimedSame = Math.max(0, ownSamePlateBefore - ownSamePlateLeft);
        taken = Math.max(0, carried() - carriedBefore - reclaimedSame);
    }

    private int carried() {
        PlayerContext current = contexts.get();
        if (current == null || current.localPlayer() == null) return 0;
        var inventory = current.localPlayer().getInventory();
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && idOf(stack).equals(itemId)) total += stack.getCount();
        }
        return total;
    }

    private static String idOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    @Override public int seen() { return seen; }

    @Override public int taken() { return taken; }

    @Override public List<String> notes() { return List.copyOf(notes); }

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
        if (round != null) {
            round.close(true);
        } else if (menu != null && closing == null) {
            menu.abandon();
        }
        if (closing != null) closing.close();
        settleTaken();
    }

    @Override public String describe() {
        return menu == null ? opening.describe() : "从压印器界面拿出 " + itemId + " 并收回这次放进的模板";
    }

    private final class TakeOutput implements MenuTakeRound.Want {
        @Override public String label() { return "从压印器界面拿 " + itemId; }
        @Override public Predicate<SlotSnapshot> matcher() { return Ae2InscriberTake.this::matchesOutput; }
        @Override public int stillNeeded(MenuContent.Reading reading) {
            return countInAllSlots(reading, Ae2InscriberTake.this::matchesOutput);
        }
        @Override public boolean settleAfterDiverge() { return true; }
    }

    private static final class TakePlate implements MenuTakeRound.Want {
        private final String spec;
        private final Predicate<SlotSnapshot> matcher;

        TakePlate(String spec, Predicate<SlotSnapshot> matcher) {
            this.spec = spec;
            this.matcher = matcher;
        }

        @Override public String label() { return "从压印器收回模板 " + spec; }
        @Override public Predicate<SlotSnapshot> matcher() { return matcher; }
        @Override public int stillNeeded(MenuContent.Reading reading) {
            return countInPlateSlots(reading, matcher);
        }
        @Override public boolean settleAfterDiverge() { return true; }
    }

    private final class Endings implements MenuTakeRound.Endings {
        @Override public void rememberBeforeClose(Optional<MenuContent.Reading> lastReading) {
            if (lastReading.isEmpty()) return;
            MenuContent.Reading reading = lastReading.get();
            ownSamePlateLeft = countInPlateSlots(reading, Ae2InscriberTake.this::matchesOutputAndOwnPlate);
            List<String> reclaimed = new ArrayList<>();
            for (String spec : List.copyOf(ownPlates)) {
                if (countInPlateSlots(reading, snapshot -> matchesSpec(snapshot, spec)) == 0) {
                    ownPlates.remove(spec);
                    reclaimed.add(spec);
                }
            }
            if (!reclaimed.isEmpty()) {
                notes.add("顺手收回了这次放进压印器的模板：" + String.join("、", reclaimed));
            }
        }

        @Override public ActionStatus finishAfterClosed(boolean tookAnything) {
            return ActionStatus.done();
        }

        @Override public ActionStatus finishAfterMenuGone(boolean tookAnything) {
            return ActionStatus.done();
        }
    }
}
