// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Optional;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

import net.minecraft.world.entity.Entity;

import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.ClientEffectReads;
import org.maiwithu.maicraft.game.player.ClientEquipmentView;
import org.maiwithu.maicraft.game.player.ClientFoodValues;
import org.maiwithu.maicraft.game.player.ClientGearFit;
import org.maiwithu.maicraft.game.player.ClientHungerView;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.ReadsEffects;
import org.maiwithu.maicraft.game.player.ReadsEquipment;
import org.maiwithu.maicraft.game.player.ReadsFoodValues;
import org.maiwithu.maicraft.game.player.ReadsGearFit;
import org.maiwithu.maicraft.game.player.ReadsHunger;
import org.maiwithu.maicraft.game.world.FieldOfView;

/**
 * 当刻角色视图的接法：能力与玩家行为模型构造一次、用到整局，读的却是每一刻的角色——
 * 各视图接到角色上下文的供给者上，每刻读当刻的玩家对象。
 *
 * <p>上下文不在手（不在世界、本刻没有角色）时如实给空：背包是空的、没有效果、
 * 什么都装不进，让调用方按"读不到"处理，不把上一刻的角色当成这一刻的。
 */
public final class PlayerViews {

    private PlayerViews() {}

    /** 背包视图：每刻从角色上下文里的那份背包读，不自己再包一遍玩家对象。 */
    public static BackpackView backpack(Supplier<PlayerContext> context) {
        Objects.requireNonNull(context, "context");
        return new BackpackView() {
            @Override public List<BackpackStack> stacks() {
                BackpackView current = currentBackpack(context);
                return current == null ? List.of() : current.stacks();
            }

            @Override public int usedSlots() {
                BackpackView current = currentBackpack(context);
                return current == null ? 0 : current.usedSlots();
            }

            @Override public int totalSlots() {
                BackpackView current = currentBackpack(context);
                return current == null ? 0 : current.totalSlots();
            }

            @Override public OptionalInt hotbarSlotOf(String itemId) {
                BackpackView current = currentBackpack(context);
                return current == null ? OptionalInt.empty() : current.hotbarSlotOf(itemId);
            }

            @Override public int selectedHotbarSlot() {
                BackpackView current = currentBackpack(context);
                return current == null ? -1 : current.selectedHotbarSlot();
            }
        };
    }

    /** 饥饿情况：饱食度与有没有饥饿机制按当刻的角色答。 */
    public static ReadsHunger hunger(Supplier<PlayerContext> context) {
        Objects.requireNonNull(context, "context");
        return new ReadsHunger() {
            @Override public int foodLevel() {
                PlayerContext current = context.get();
                return current == null || current.localPlayer() == null
                        ? 0 : new ClientHungerView(current.localPlayer()).foodLevel();
            }

            @Override public boolean hungerMechanicsOn() {
                PlayerContext current = context.get();
                return current == null || current.localPlayer() == null
                        || new ClientHungerView(current.localPlayer()).hungerMechanicsOn();
            }
        };
    }

    /** 食物数值：按当刻的角色读物品的食物组件，并按配方表缓存生食材识别结果避免每刻重复扫表。 */
    public static ReadsFoodValues foods(Supplier<PlayerContext> context) {
        Objects.requireNonNull(context, "context");
        return new ReadsFoodValues() {
            private ClientFoodValues cached;
            private Object cachedPlayer;
            private Object cachedRecipes;

            @Override
            public Optional<FoodValue> of(String itemId) {
                PlayerContext current = context.get();
                if (current == null || current.localPlayer() == null) {
                    cached = null;
                    cachedPlayer = null;
                    cachedRecipes = null;
                    return Optional.empty();
                }
                Object recipes = current.level() == null ? null : current.level().getRecipeManager().getRecipes();
                if (cached == null || cachedPlayer != current.localPlayer() || cachedRecipes != recipes) {
                    cached = new ClientFoodValues(current.localPlayer());
                    cachedPlayer = current.localPlayer();
                    cachedRecipes = recipes;
                }
                return cached.of(itemId);
            }
        };
    }

    /** 装备栏视图：按当刻的角色读主手、副手与护甲。 */
    public static ReadsEquipment equipment(Supplier<PlayerContext> context) {
        Objects.requireNonNull(context, "context");
        return slot -> {
            PlayerContext current = context.get();
            return current == null || current.localPlayer() == null
                    ? Optional.empty() : new ClientEquipmentView(current.localPlayer()).slot(slot);
        };
    }

    /** 状态效果：按当刻的角色读身上挂着的效果。 */
    public static ReadsEffects effects(Supplier<PlayerContext> context) {
        Objects.requireNonNull(context, "context");
        return () -> {
            PlayerContext current = context.get();
            return current == null || current.localPlayer() == null
                    ? List.of() : new ClientEffectReads(current.localPlayer()).active();
        };
    }

    /** 物品与栏位的匹配：头盔进不了胸甲栏，按当刻的角色读游戏规则。 */
    public static ReadsGearFit gearFit(Supplier<PlayerContext> context) {
        Objects.requireNonNull(context, "context");
        // 优先核对原版 Equipable，模组扩展穿戴物（如精妙背包）再经当前角色的背包护甲槽问加载器。
        return new ClientGearFit(context);
    }

    /** 眼前视野看不看得见指定实体：挨打后转头追认凶手与连打入账时，按当刻角色的前方视锥和无遮挡视线判断。 */
    public static IntPredicate canSeeEntity(Supplier<PlayerContext> context) {
        Objects.requireNonNull(context, "context");
        return entityId -> {
            PlayerContext current = context.get();
            if (current == null || current.localPlayer() == null || current.level() == null) {
                return false;
            }
            Entity target = current.level().getEntity(entityId);
            return FieldOfView.canSeeEntity(current.localPlayer(), target, 64.0);
        };
    }

    private static BackpackView currentBackpack(Supplier<PlayerContext> context) {
        PlayerContext current = context.get();
        return current == null ? null : current.backpack();
    }
}
