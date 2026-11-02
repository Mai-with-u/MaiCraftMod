// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.maiwithu.maicraft.behavior.interaction.InteractionAction;
import org.maiwithu.maicraft.behavior.inventory.hands.ClientMovesToMainhand;
import org.maiwithu.maicraft.game.interaction.Interaction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 近战出手一次：先把挑好的近战武器拿到主手，再原生攻击（充能够格才挥、换手当刻与目标无敌帧不挥）。
 *
 * <p>挑哪件按武器挑选的唯一判断（剑斧优先，没有剑斧抄加攻击力的工具，对手举盾时斧优先）。在快捷栏就按一下数字键；
 * 在主背包里时，对手还在四格外才打开背包把它换进快捷栏的空格再选中（不挤掉手里的镐），贴脸了不为换武器开背包，改拿快捷栏里最好的那件
 * （比手里的强才换）。连加攻击力的工具都挑不出时按空手打：先把主手的杂物腾掉（选中空的快捷栏格），不拿着木板挥；
 * 腾不掉（快捷栏与背包都满）才拿手里的东西出手。换不成也照样出手，不因此停下。
 * 原先挑好的武器只拿来算"打不打得过"，从没换到手上，结果里还写成用的是它。
 */
public final class MeleeStrike implements Action {

    private static final Logger LOG = LoggerFactory.getLogger(MeleeStrike.class);

    /** 对手离这么远以上，才为换武器打开背包。 */
    private static final double INVENTORY_SWAP_DISTANCE = 4.0;

    /** 每刻从世界里找回要打的那个：平常按实体编号；龙头这类碰撞部件按编号找不到，由调用方给找法。 */
    private final Function<ClientLevel, Entity> target;
    private PlayerContext current;
    private final ClientMovesToMainhand hands = new ClientMovesToMainhand(() -> this.current);
    private Action arming;
    private boolean armed;
    private Action attack;

    public MeleeStrike(int targetId) {
        this(level -> level.getEntity(targetId));
    }

    /** 打一个按实体编号找不到的目标（末影龙的龙头这类碰撞部件）：找法由调用方给，找不到就收场不打空拳。 */
    public MeleeStrike(Function<ClientLevel, Entity> target) {
        this.target = target;
    }

    @Override
    public ActionStatus tick(TickContext context) {
        PlayerContext player = context.player();
        current = player;
        Entity target = player == null || player.level() == null ? null : this.target.apply(player.level());
        if (target == null || player.localPlayer() == null || player.interactionSender() == null) {
            return ActionStatus.done();
        }
        if (!armed) {
            if (arming == null) arming = chooseArming(player.localPlayer(), target).orElse(null);
            if (arming != null) {
                ActionStatus status = arming.tick(context);
                if (status instanceof ActionStatus.Running) return status;
            }
            armed = true;
        }
        if (attack == null) {
            Interaction swing = Interaction.attackEntity(player, player.interactionSender(), player.menuActions(),
                    player.input(), target);
            attack = new InteractionAction(swing, "攻击 " + EntityType.getKey(target.getType()));
            LOG.info("[近战] 出手一次：主手 {}，眼睛到它碰撞箱 {} 格", player.localPlayer().getMainHandItem(),
                    String.format("%.2f", CombatGeometry.distanceToBox(player.localPlayer().getEyePosition(),
                            target.getBoundingBox())));
        }
        return attack.tick(context);
    }

    // 拿哪件、怎么拿：挑不出合格武器先腾空主手（按空手打）；已在主手不动；快捷栏的选中；主背包的看对手远近决定开不开背包。
    private Optional<Action> chooseArming(LocalPlayer self, Entity target) {
        boolean shielding = target instanceof LivingEntity living && living.isBlocking();
        List<WeaponChoice.Carried> carried = WeaponCarriedReader.read(self);
        WeaponChoice.Picked best = WeaponChoice.pickMelee(carried, shielding);
        if (best.weapon() == WeaponChoice.Weapon.BARE_HANDS) {
            // 身上连加攻击力的工具都没有：把主手的方块、杂物腾掉，空着手打——挥木板和空手一样狠，
            // 还让"拿什么打"的记录冒充成武器。腾不掉（快捷栏与背包都满）时给空，拿着手里的东西出手。
            return bareHands(self);
        }
        // 在快捷栏按数字键选中；在主背包就换进快捷栏的空格（或放零碎的格）再选中，手里的镐留在原格。
        Optional<String> take = weaponWorthTaking(self, shielding, self.distanceTo(target) < INVENTORY_SWAP_DISTANCE);
        if (take.isPresent()) return take.flatMap(hands::actionToMainhand);
        // 贴脸时不开背包、快捷栏里又没有合格武器，手里攥的还是杂物：同样腾空主手，不拿它挥。
        return heldIsWeapon(self, carried, shielding) ? Optional.empty() : bareHands(self);
    }

    // 空着手打：主手已经空着就不用动；不然选中一个空的快捷栏格。
    private Optional<Action> bareHands(LocalPlayer self) {
        return self.getMainHandItem().isEmpty() ? Optional.empty() : hands.emptyMainhand();
    }

    // 主手此刻拿的算不算合格武器（剑斧或加攻击力的工具）；空手为否。
    private static boolean heldIsWeapon(LocalPlayer self, List<WeaponChoice.Carried> carried, boolean shielding) {
        int selected = self.getInventory().selected;
        for (WeaponChoice.Carried stack : carried) {
            if (stack.slot() == selected) {
                return WeaponChoice.pickMelee(List.of(stack), shielding).weapon() != WeaponChoice.Weapon.BARE_HANDS;
            }
        }
        return false;
    }

    /**
     * 该换到手上的那把近战武器（物品编号）：剑斧里最好的那把；对手举盾时斧优先。手里已经是一样好的、身上没有剑斧时为空。
     * 出手前贴着对手（{@code hotbarOnly}）不为换武器开背包，只在快捷栏里挑；还没贴脸、要去追一只猎物之前不限，
     * 主背包里的也拿出来——等游到跟前再发现手里拿的是终端，就只能拿它一下一下敲。
     */
    public static Optional<String> weaponWorthTaking(LocalPlayer self, boolean opponentShielding, boolean hotbarOnly) {
        List<WeaponChoice.Carried> carried = WeaponCarriedReader.read(self);
        WeaponChoice.Picked best = WeaponChoice.pickMelee(carried, opponentShielding);
        if (best.weapon() == WeaponChoice.Weapon.BARE_HANDS) return Optional.empty();
        if (!WeaponChoice.inHotbar(best.slot()) && hotbarOnly) {
            List<WeaponChoice.Carried> hotbar = new ArrayList<>();
            for (WeaponChoice.Carried stack : carried) {
                if (WeaponChoice.inHotbar(stack.slot())) hotbar.add(stack);
            }
            best = WeaponChoice.pickMelee(hotbar, opponentShielding);
            if (best.weapon() == WeaponChoice.Weapon.BARE_HANDS) return Optional.empty();
        }
        if (best.slot() == self.getInventory().selected) return Optional.empty();
        if (!betterThanHeld(best, carried, self, opponentShielding)) return Optional.empty();
        return Optional.of(best.itemId());
    }

    // 挑中的比手里拿着的强才换：手里已经是一样好的剑斧，别为一样的东西来回切；手里不是剑斧就换。
    private static boolean betterThanHeld(WeaponChoice.Picked best, List<WeaponChoice.Carried> carried,
                                          LocalPlayer self, boolean shielding) {
        int selected = self.getInventory().selected;
        WeaponChoice.Carried held = null;
        WeaponChoice.Carried chosen = null;
        for (WeaponChoice.Carried stack : carried) {
            if (stack.slot() == selected) held = stack;
            if (stack.slot() == best.slot()) chosen = stack;
        }
        if (held == null || chosen == null) return true;
        if (WeaponChoice.pickMelee(List.of(held), shielding).weapon() == WeaponChoice.Weapon.BARE_HANDS) return true;
        // 手里的排在前面：挑中的要严格更好才会顶替它。
        return WeaponChoice.pickMelee(List.of(held, chosen), shielding).slot() == chosen.slot();
    }

    @Override
    public void pause() {
        if (arming != null) arming.pause();
        if (attack != null) attack.pause();
    }

    @Override
    public void close() {
        if (arming != null) arming.close();
        if (attack != null) attack.close();
    }

    @Override
    public Interruptibility interruptibility() {
        return attack != null ? attack.interruptibility()
                : arming != null ? arming.interruptibility() : Interruptibility.WORKING;
    }

    @Override
    public String describe() {
        return attack != null ? attack.describe() : "把武器拿到手上";
    }
}
