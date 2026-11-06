// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.bodylanguage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.interaction.FirstPersonScene;
import org.maiwithu.maicraft.behavior.inventory.drops.ClientDropPickup;
import org.maiwithu.maicraft.behavior.inventory.drops.ThrowsItems;
import org.maiwithu.maicraft.behavior.inventory.hands.ClientMovesToMainhand;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.behavior.perception.SeenRegistry;
import org.maiwithu.maicraft.behavior.perception.bodylanguage.NonverbalExchange;
import org.maiwithu.maicraft.behavior.perception.looking.Aim;
import org.maiwithu.maicraft.behavior.perception.looking.TurnsHead;
import org.maiwithu.maicraft.game.interaction.AttackKeyPress;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 做肢体动作的生产现场：观察编号与玩家名认人、最近冲角色做过肢体动作的人、背包里找东西、换手、走过去、
 * 和丢东西同一下"抛"、捡掉落物、转头、潜行与跳的输入、按攻击键，都接在真实客户端上。只在客户端线程用。
 */
public final class LiveNonverbalMoves implements NonverbalMoves {

    /** 不给 target 时对着最近多久（刻）里冲角色做过肢体动作的人：30 秒。 */
    static final int RECENT_TOWARD_TICKS = 600;
    /** 不点名捡东西时捡最近多久（刻）里扔到身边的：60 秒。 */
    static final int RECENT_TOSS_TICKS = 1200;
    /** 捡东西：地上那件要在几格内。 */
    static final double PICK_UP_BLOCKS = 6;
    /** 走到他跟前：重算几次路还没到就算走不过去。 */
    static final int WALK_RETRIES = 3;

    private final SeenRegistry seen;
    private final NonverbalExchange exchange;
    private final ClientMovesToMainhand hands;
    private final WalkTo walks;
    private final ClientDropPickup drops;
    private final AttackKeyPress keys;

    public LiveNonverbalMoves(SeenRegistry seen, NonverbalExchange exchange, ClientMovesToMainhand hands, WalkTo walks,
                              ClientDropPickup drops, AttackKeyPress keys) {
        this.seen = Objects.requireNonNull(seen, "seen");
        this.exchange = Objects.requireNonNull(exchange, "exchange");
        this.hands = Objects.requireNonNull(hands, "hands");
        this.walks = Objects.requireNonNull(walks, "walks");
        this.drops = Objects.requireNonNull(drops, "drops");
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    // 点名的玩家按名字在这个世界里找，观察编号按登记找；不点名找最近 30 秒里最后一个冲角色做过肢体动作的玩家。
    @Override public Partner partner(TickContext context, Target target) {
        LocalPlayer me = me(context);
        if (me == null) return null;
        ClientLevel level = me.clientLevel;
        Entity found = null;
        if (target instanceof Target.Player named) {
            found = level.players().stream().filter(other -> other != me
                    && other.getGameProfile().getName().equalsIgnoreCase(named.name())).findFirst().orElse(null);
        } else if (target instanceof Target.Seen id) {
            found = seen.gameEntityOf(id.id()).map(level::getEntity).orElse(null);
        } else if (target == null) {
            found = exchange.lastTowardMe(context.gameTick(), RECENT_TOWARD_TICKS).map(level::getPlayerByUUID)
                    .orElse(null);
        }
        if (found == null || found == me || found.isRemoved()) return null;
        String name = found instanceof Player player ? player.getGameProfile().getName() : found.getName().getString();
        return new Partner(found.getId(), found.getUUID(), name);
    }

    @Override public double distanceTo(TickContext context, int entityId) {
        LocalPlayer me = me(context);
        Entity him = me == null ? null : me.clientLevel.getEntity(entityId);
        return him == null || him.isRemoved() ? Double.POSITIVE_INFINITY : me.distanceTo(him);
    }

    @Override public boolean riding(TickContext context) {
        LocalPlayer me = me(context);
        return me != null && me.isPassenger();
    }

    // 身上找这件东西（主背包、快捷栏、副手，不算穿着的）：选中的那格对得上就用它，标签按物品挂的标签认。
    @Override public Optional<String> carried(TickContext context, String itemOrTag) {
        LocalPlayer me = me(context);
        if (me == null) return Optional.empty();
        Inventory inventory = me.getInventory();
        if (matches(inventory.getSelected(), itemOrTag)) return Optional.of(idOf(inventory.getSelected()));
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            if (matches(inventory.items.get(slot), itemOrTag)) return Optional.of(idOf(inventory.items.get(slot)));
        }
        ItemStack offhand = inventory.offhand.get(0);
        return matches(offhand, itemOrTag) ? Optional.of(idOf(offhand)) : Optional.empty();
    }

    private static boolean matches(ItemStack stack, String itemOrTag) {
        if (stack.isEmpty()) return false;
        if (itemOrTag.startsWith("#")) {
            ResourceLocation tag = ResourceLocation.tryParse(itemOrTag.substring(1));
            return tag != null && stack.is(TagKey.create(Registries.ITEM, tag));
        }
        return idOf(stack).equals(itemOrTag);
    }

    private static String idOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    @Override public int selectedSlot(TickContext context) {
        LocalPlayer me = me(context);
        return me == null ? -1 : me.getInventory().selected;
    }

    @Override public boolean selectSlot(TickContext context, int slot) {
        PlayerContext player = context.player();
        if (player == null || player.interactionSender() == null) return false;
        return player.interactionSender().selectHotbar(player, slot, 10) != null;
    }

    // 已经拿在手上就不用换：给一个立刻做完的动作。
    @Override public Optional<Action> takeOut(TickContext context, String itemId) {
        LocalPlayer me = me(context);
        if (me != null && !me.getMainHandItem().isEmpty() && idOf(me.getMainHandItem()).equals(itemId)) {
            return Optional.of(new Ready());
        }
        return hands.moveToMainhand(itemId);
    }

    @Override public Optional<Action> emptyHand() {
        return hands.emptyMainhand();
    }

    @Override public Action walkNear(int entityId, double within) {
        return new WalkNear(entityId, within);
    }

    @Override public ThrowsItems toss(String itemId, int count) {
        return new ThrowsItems(itemId, count, FirstPersonScene::of);
    }

    // 点名的那件（观察编号）要是地上的掉落物；不点名就是最近 60 秒里别的玩家扔到身边的。都只算 6 格内还在的。
    @Override public Set<Integer> pickable(TickContext context, Target target) {
        LocalPlayer me = me(context);
        Set<Integer> items = new HashSet<>();
        if (me == null) return items;
        if (target instanceof Target.Seen id) {
            seen.gameEntityOf(id.id()).ifPresent(items::add);
        } else {
            items.addAll(exchange.tossedToMe(context.gameTick(), RECENT_TOSS_TICKS));
        }
        items.removeIf(entityId -> !(me.clientLevel.getEntity(entityId) instanceof ItemEntity item)
                || item.isRemoved() || me.distanceTo(item) > PICK_UP_BLOCKS);
        return items;
    }

    // 扔过来的东西落在脚边，原版过两秒就自己捡进背包：按服务器发来的拾取包认是哪几件、几个什么。
    @Override public Optional<String> alreadyPickedUp(TickContext context, Target target) {
        Set<Integer> wanted = new HashSet<>();
        if (target instanceof Target.Seen id) {
            seen.gameEntityOf(id.id()).ifPresent(wanted::add);
        } else {
            wanted.addAll(exchange.tossedToMe(context.gameTick(), RECENT_TOSS_TICKS));
        }
        List<String> parts = new ArrayList<>();
        drops.alreadyPickedUp(wanted).forEach((item, count) -> parts.add(count + " 个 " + item));
        return parts.isEmpty() ? Optional.empty() : Optional.of(String.join("、", parts));
    }

    // 只捡这几件：一件就点名捡它；几件就把身边别的掉落物都算成"早就在地上的"，不去碰。
    @Override public Action pickUp(Set<Integer> items) {
        if (items.size() == 1) return drops.pickUpOne(items.iterator().next());
        Set<Integer> others = drops.nearby();
        others.removeAll(items);
        return drops.pickUpNewSince(others);
    }

    @Override public void answered(UUID who) {
        exchange.answered(who);
    }

    @Override public TurnsHead head(TickContext context) {
        return TurnsHead.of(context);
    }

    @Override public Aim aimAt(TickContext context, int entityId, AimPoint point) {
        LocalPlayer me = me(context);
        Entity him = me == null ? null : me.clientLevel.getEntity(entityId);
        if (him == null || him.isRemoved()) return null;
        Vec3 eye = me.getEyePosition();
        Vec3 at = switch (point) {
            case EYES -> him.getEyePosition();
            case CENTER -> him.getBoundingBox().getCenter();
            case FEET -> him.position();
        };
        return Aim.between(eye.x, eye.y, eye.z, at.x, at.y, at.z);
    }

    // 只按潜行、跳，不走路；人按 F8 拿着角色时不碰按键。
    @Override public void press(TickContext context, boolean sneak, boolean jump) {
        PlayerContext player = context.player();
        if (player == null || !player.isCurrent() || player.input() == null
                || !player.input().automationOwnsControls()) return;
        player.input().applyMovement(new PlayerInput.Movement(0, 0, jump, sneak, false), player.clientTick());
    }

    @Override public boolean crouched(TickContext context) {
        LocalPlayer me = me(context);
        return me != null && me.isShiftKeyDown();
    }

    @Override public boolean onGround(TickContext context) {
        LocalPlayer me = me(context);
        return me != null && me.onGround();
    }

    @Override public boolean aimsAtNothing() {
        return keys.aimsAtNothing();
    }

    @Override public int aimedEntity() {
        return keys.aimedEntity();
    }

    @Override public boolean pressAttack(TickContext context) {
        return keys.worldInFront() && keys.press(context.player());
    }

    private static LocalPlayer me(TickContext context) {
        PlayerContext player = context == null ? null : context.player();
        return player == null ? null : player.localPlayer();
    }

    /** 已经拿在手上：立刻做完。 */
    private static final class Ready implements Action {
        @Override public ActionStatus tick(TickContext context) {
            return ActionStatus.done();
        }

        @Override public String describe() {
            return "已经拿在手上";
        }
    }

    /** 走到离他几格以内：他走动了就按他新的位置重算路，重算几次还没到就如实说走不过去；只走路不动方块。 */
    private final class WalkNear implements Action {
        private final int entityId;
        private final double within;
        private WalkRun run;
        private int starts;

        WalkNear(int entityId, double within) {
            this.entityId = entityId;
            this.within = within;
        }

        @Override public ActionStatus tick(TickContext context) {
            LocalPlayer me = me(context);
            Entity him = me == null ? null : me.clientLevel.getEntity(entityId);
            if (him == null || him.isRemoved()) {
                return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "他不见了", null));
            }
            if (me.distanceTo(him) <= within) {
                stop();
                return ActionStatus.done();
            }
            WalkReport report = run == null ? null : run.report();
            if (report != null && report.state() == WalkReport.State.FAILED) {
                return ActionStatus.failed(report.problem());
            }
            if (run == null || report.state() == WalkReport.State.ARRIVED || report.state() == WalkReport.State.STOPPED) {
                if (++starts > WALK_RETRIES) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "走不到他跟前", "他停下来以后再试"));
                }
                run = walks.start(GoalCompiler.near(him.blockPosition(), Math.max(1, within - 1)),
                        TerrainPermit.WALK_ONLY);
            }
            return ActionStatus.progressed();
        }

        private void stop() {
            if (run != null) run.stop();
            run = null;
        }

        @Override public void pause() {
            stop();
        }

        @Override public void close() {
            stop();
        }

        @Override public String describe() {
            return "走到他跟前";
        }
    }
}
