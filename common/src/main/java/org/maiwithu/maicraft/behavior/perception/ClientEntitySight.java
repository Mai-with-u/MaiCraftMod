// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.boss.EnderDragonPart;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.behavior.permission.ClientCreatureSituations;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation.TamedBy;
import org.maiwithu.maicraft.behavior.permission.TrustedPlayers;
import org.maiwithu.maicraft.game.world.Hostility;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.LooksAt;
import org.maiwithu.maicraft.game.world.ObservationVisibility;
import org.maiwithu.maicraft.game.world.OnlinePlayers;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 实体观察的读端：把角色周围（原版渲染范围内）的实体逐只读成观察事实。
 *
 * <p>每只实体都报告，"此刻视线有没有被挡住"用与观察同一套遮挡判断
 * （{@code ObservationVisibility}，探索看到的和感知看到的同一条事实）；视线被挡住的
 * 实体仍带 {@code visible=false} 报上来，让场景的编号保留期知道它只是暂时看不见，
 * 而不是把它当成已经走掉。活体生物带血量快照，掉落物也算实体——地上的东西是观察的一部分。
 */
public final class ClientEntitySight implements EntitySight {

    /** 实体形式的战利品容器（Lootr 的箱子矿车等）：挂着该标签的实体在 traits 里标明是战利品容器。 */
    private static final TagKey<EntityType<?>> LOOTR_ENTITY_CONTAINERS =
            TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath("lootr", "containers"));

    private final Supplier<PlayerContext> context;
    private final TrustedPlayers trusted;

    public ClientEntitySight(Supplier<PlayerContext> context) {
        this(context, TrustedPlayers.NOBODY);
    }

    /** @param trusted 自家人名单：驯服的生物写清是你的、自家人的还是别人的 */
    public ClientEntitySight(Supplier<PlayerContext> context, TrustedPlayers trusted) {
        this.context = Objects.requireNonNull(context, "context");
        this.trusted = Objects.requireNonNull(trusted, "trusted");
    }

    @Override
    public List<Observation> nearby() {
        PlayerContext current = context.get();
        if (current == null || current.localPlayer() == null || current.level() == null) {
            return List.of();
        }
        LocalPlayer player = current.localPlayer();
        ClientLevel level = current.level();
        List<Observation> found = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            // 角色自己不在观察里：它是看的那一方，不是被看的。
            if (entity == player || entity.isRemoved()) {
                continue;
            }
            // 末影龙的身子各段不是一条实体：折到龙主体一条，与找东西、战斗认的是同一只。
            if (entity instanceof EnderDragonPart) {
                continue;
            }
            // 活体生物的血量是看得见的观察事实：按原版的LivingEntity读当前值与上限，
            // 末影龙这类boss也走同一条路；掉落物、矿车这些不是活体，不报血量。
            EntitySight.Health health = entity instanceof LivingEntity living
                    ? new EntitySight.Health(living.getHealth(), living.getMaxHealth())
                    : null;
            found.add(new Observation(
                    entity.getId(),
                    BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                    entityName(entity),
                    entity.getStringUUID(),
                    WorldPosition.here((int) Math.floor(entity.getX()),
                            (int) Math.floor(entity.getY()), (int) Math.floor(entity.getZ())),
                    ObservationVisibility.entity(player, entity),
                    Hostility.hostileNow(entity),
                    targetsPlayer(entity, player),
                    health,
                    traits(entity, player, trusted),
                    // 谁驯服的都算有主：场景里带上它的 UUID，宿主才认得出主播身边的女仆、狗是哪一只。
                    ClientCreatureSituations.tamedBy(entity, player, trusted) != TamedBy.NOBODY));
        }
        return found;
    }

    /**
     * 实体的观察名字：别的玩家始终带游戏名（与玩家列表、聊天里的名字一致），认人不再只靠 UUID；
     * 其他实体只带名字牌、重命名这类自定义名。
     */
    static String entityName(Entity entity) {
        if (entity instanceof Player other) {
            String profileName = other.getGameProfile() == null ? null : other.getGameProfile().getName();
            return profileName != null && !profileName.isBlank() ? profileName : other.getName().getString();
        }
        return entity.hasCustomName() ? entity.getCustomName().getString() : null;
    }

    // 只有怪物会"把攻击目标放在角色身上"；其他实体挨得再近也不算盯着我。
    private static boolean targetsPlayer(Entity entity, LocalPlayer player) {
        return entity instanceof Mob mob && mob.getTarget() == player;
    }

    /**
     * 看得出来的特征：着火、幼年、羊的毛与剪毛、驯服的是谁的、坐着没有、掉落物是什么几件、展示框里放着什么、
     * 手持物、身上穿的盔甲，别的玩家蹲没蹲着、看没看着角色。看不出来就不写。观察与找东西共用这一份。
     */
    public static Map<String, String> traits(Entity entity, Player viewer, TrustedPlayers trusted) {
        Map<String, String> traits = new LinkedHashMap<>();
        if (entity.isOnFire()) {
            traits.put("着火", "是");
        }
        if (entity.getType().is(LOOTR_ENTITY_CONTAINERS)) {
            traits.put("战利品容器", "是");
        }
        // 中立生物：没惹它不打人，惹毛了才算敌对（僵尸猪灵打一只整群上）。
        if (Hostility.neutral(entity) && entity instanceof Mob mob) {
            traits.put("中立", mob.isAggressive() ? "已惹毛，正在进攻" : "没惹它不会打人");
        }
        if (entity instanceof AgeableMob ageable && ageable.isBaby()) {
            traits.put("幼年", "是");
        }
        if (entity instanceof Sheep sheep) {
            if (sheep.isSheared()) {
                traits.put("剪过毛", "是");
            } else {
                traits.put("毛色", sheep.getColor().getName());
            }
        }
        // 驯服了的写清是谁的：角色自己的能喂、能骑、能开界面不用点名，别人的要点名；没驯服的不写。
        TamedBy tamedBy = ClientCreatureSituations.tamedBy(entity, viewer, trusted);
        if (tamedBy.word() != null) {
            traits.put("驯服", tamedBy.word());
        }
        // 自家人、别人驯服的再写上驯服者是谁：主播说"我家女仆""他的狗"时，她才对得上是哪一个人的。
        if ((tamedBy == TamedBy.TRUSTED || tamedBy == TamedBy.SOMEONE_ELSE) && entity instanceof OwnableEntity ownable
                && ownable.getOwnerUUID() != null) {
            traits.put("驯服者", OnlinePlayers.nameOrId(viewer, ownable.getOwnerUUID()));
        }
        // 狼、猫、鹦鹉、女仆这类听令坐下的：坐着的不跟人走，让它站起来要再潜行右键一次。
        if (entity instanceof TamableAnimal tamable && tamable.isInSittingPose()) {
            traits.put("坐着", "是");
        }
        // 地上的掉落物：是什么、几件，LLM 才分得清先捡哪个、往哪走。
        if (entity instanceof ItemEntity item && !item.getItem().isEmpty()) {
            traits.put("物品", itemTypeId(item.getItem()));
            traits.put("数量", String.valueOf(item.getItem().getCount()));
        }
        // 展示框里放着什么（末地城的鞘翅、别人挂的东西）：LLM 才知道打哪一个取出来，空框写明是空的。
        if (entity instanceof ItemFrame frame) {
            traits.put("框里", frame.getItem().isEmpty() ? "空" : itemTypeId(frame.getItem()));
        }
        if (entity instanceof LivingEntity living) {
            if (!living.getMainHandItem().isEmpty()) {
                traits.put("手持物", itemTypeId(living.getMainHandItem()));
            }
            List<String> armor = new ArrayList<>();
            for (ItemStack piece : living.getArmorSlots()) {
                if (!piece.isEmpty()) {
                    armor.add(itemTypeId(piece));
                }
            }
            if (!armor.isEmpty()) {
                traits.put("盔甲", String.join("，", armor));
            }
        }
        // 别的玩家蹲着（按住潜行）、看着角色（视线离角色身体不到 1 格）：LLM 认"他在跟你打招呼"要看这两样。
        if (entity instanceof Player other && other != viewer) {
            if (other.isCrouching()) {
                traits.put("蹲着", "是");
            }
            if (LooksAt.looksAt(other, viewer)) {
                traits.put("看着你", "是");
            }
        }
        return traits;
    }

    private static String itemTypeId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
