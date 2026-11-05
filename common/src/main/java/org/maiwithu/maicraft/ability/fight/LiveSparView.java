// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fight;

import java.util.Objects;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;

import org.maiwithu.maicraft.behavior.perception.SeenRegistry;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 切磋看对手的生产实现：玩家名在这个世界里找、观察编号按登记找；血、蹲没蹲、手上拿什么都是原版同步给看得见他的人的数据。
 * 兵器按物品的种类认（剑、斧、三叉戟、重锤、弓弩），不列物品 ID。
 */
public final class LiveSparView implements SparView {

    /** 他离我多近（格）算够得着我：玩家近战够 3 格，留半格余量。 */
    static final double REACH_BLOCKS = 3.5;
    /** 暴击是 1.5 倍：估这一刀按暴击算，宁可估高。 */
    static final double CRITICAL = 1.5;

    private final SeenRegistry seen;

    public LiveSparView(SeenRegistry seen) {
        this.seen = Objects.requireNonNull(seen, "seen");
    }

    @Override public Opponent find(TickContext context, Target target) {
        LocalPlayer me = me(context);
        if (me == null) return null;
        Entity found = null;
        if (target instanceof Target.Player named) {
            found = me.clientLevel.players().stream().filter(other -> other != me
                    && other.getGameProfile().getName().equalsIgnoreCase(named.name())).findFirst().orElse(null);
        } else if (target instanceof Target.Seen id) {
            found = seen.gameEntityOf(id.id()).map(me.clientLevel::getEntity).orElse(null);
        }
        if (!(found instanceof Player player) || player == me || player.isRemoved()) return null;
        return new Opponent(player.getId(), player.getUUID(), player.getGameProfile().getName());
    }

    @Override public Reading read(TickContext context, int entityId) {
        LocalPlayer me = me(context);
        Entity found = me == null ? null : me.clientLevel.getEntity(entityId);
        if (!(found instanceof Player him) || him.isRemoved()) return null;
        double distance = me.distanceTo(him);
        boolean armed = weapon(him.getMainHandItem().getItem());
        boolean drawing = him.isUsingItem() && him.getUseItem().getItem() instanceof ProjectileWeaponItem;
        double dx = me.getX() - him.getX();
        double dz = me.getZ() - him.getZ();
        double flat = Math.max(1e-6, Math.hypot(dx, dz));
        return new Reading(him.getHealth(), distance, distance <= REACH_BLOCKS, him.isCrouching() || !armed, drawing,
                dx / flat, dz / flat);
    }

    private static boolean weapon(Item item) {
        return item instanceof SwordItem || item instanceof AxeItem || item instanceof TridentItem
                || item instanceof MaceItem || item instanceof ProjectileWeaponItem;
    }

    @Override public double myHealth(TickContext context) {
        LocalPlayer me = me(context);
        return me == null ? 0 : me.getHealth();
    }

    @Override public double myBlow(TickContext context) {
        LocalPlayer me = me(context);
        return me == null ? 0 : me.getAttributeValue(Attributes.ATTACK_DAMAGE) * CRITICAL;
    }

    private static LocalPlayer me(TickContext context) {
        PlayerContext player = context == null ? null : context.player();
        return player == null ? null : player.localPlayer();
    }
}
