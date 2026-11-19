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
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.boss.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.PushReaction;

import org.maiwithu.maicraft.behavior.permission.ClientCreatureSituations;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation.TamedBy;
import org.maiwithu.maicraft.behavior.permission.TrustedPlayers;
import org.maiwithu.maicraft.game.world.FieldOfView;
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
    /** 禁传送的动态装置实体（如机械动力的旋转装置、列车车厢、电梯轿厢）：虽不可直接准星选中，但属于在场动态装置，不得当技术标记过滤。 */
    private static final TagKey<EntityType<?>> TELEPORTING_NOT_SUPPORTED =
            TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath("c", "teleporting_not_supported"));

    /** 屏幕前方视野判定的最大有效距离（格），与挨打片段、玩家视野查询共用同一口径。 */
    private static final double ENTITY_VIEW_RANGE = 64.0;

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
            // 角色自己不在观察里；末影龙分段与强力胶、技术标记这类无交互入口的附着实体也不发观察编号。
            if (entity == player || !observable(entity)) {
                continue;
            }
            // 活体生物的血量是看得见的观察事实：按原版的LivingEntity读当前值与上限，
            // 末影龙这类boss也走同一条路；掉落物、矿车这些不是活体，不报血量。
            EntitySight.Health health = entity instanceof LivingEntity living
                    ? new EntitySight.Health(living.getHealth(), living.getMaxHealth())
                    : null;
            // 世界可见（360° 无遮挡）与屏幕前方视野（前方视野锥且无遮挡）分开记录：先算一次视线遮挡，再叠加前方视角锥。
            boolean visible = ObservationVisibility.entity(player, entity);
            boolean inView = visible && FieldOfView.inFront(player.getEyePosition(), player.getViewVector(1.0f),
                    entity.getBoundingBox(), ENTITY_VIEW_RANGE);
            found.add(new Observation(
                    entity.getId(),
                    BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                    entityName(entity),
                    entity.getStringUUID(),
                    WorldPosition.here((int) Math.floor(entity.getX()),
                            (int) Math.floor(entity.getY()), (int) Math.floor(entity.getZ())),
                    visible,
                    Hostility.hostileNow(entity),
                    targetsPlayer(entity, player),
                    health,
                    traits(entity, player, trusted),
                    // 谁驯服的都算有主：场景里带上它的 UUID，宿主才认得出主播身边的女仆、狗是哪一只。
                    ClientCreatureSituations.tamedBy(entity, player, trusted) != TamedBy.NOBODY,
                    inView));
        }
        return found;
    }

    /**
     * 判断一只世界实体是否属于角色可观察、可认知的场景实物。
     *
     * <p>原版技术标记（{@code Marker}）、隐形触发盒（{@code Interaction}）、投影展示实体（{@code Display}），
     * 以及已涂抹在方块上的强力胶选区这类不可选中交互的静态附着实体，在游戏里没有实体交互入口，
     * 过滤后不再发观察编号或标到画面上；动态结构实体、座椅、生物、掉落物与各类载具（含战利品矿车）全部保留。
     */
    public static boolean observable(Entity entity) {
        if (entity == null || entity.isRemoved()) {
            return false;
        }
        // 末影龙的身子各段不是独立实体：折到龙主体一条，与找东西、战斗认的是同一只。
        if (entity instanceof EnderDragonPart) {
            return false;
        }
        // 原版技术标记、隐形交互触发盒与投影展示实体没有生存模式实体实物语义，不进观察列表。
        if (entity instanceof Marker || entity instanceof Interaction || entity instanceof Display) {
            return false;
        }
        // 生物（含盔甲架与包裹）、掉落物、经验球、船与矿车（含战利品矿车）、悬挂展示物、水晶、下落方块、点燃的炸药、弹射物与雷电等原版常见实体一律保留。
        if (entity instanceof LivingEntity
                || entity instanceof ArmorStand
                || entity instanceof ItemEntity
                || entity instanceof ExperienceOrb
                || entity instanceof Boat
                || entity instanceof AbstractMinecart
                || entity instanceof HangingEntity
                || entity instanceof EndCrystal
                || entity instanceof FallingBlockEntity
                || entity instanceof PrimedTnt
                || entity instanceof Projectile
                || entity instanceof EyeOfEnder
                || entity instanceof EvokerFangs
                || entity instanceof LightningBolt) {
            return true;
        }
        // 准星可选中、有硬碰撞、正在载客或正挂载在别的载具上的实体，以及战利品容器与动态装置标签实体一律保留。
        if (entity.isPickable()
                || entity.canBeCollidedWith()
                || entity.isVehicle()
                || entity.isPassenger()
                || entity.getType().is(LOOTR_ENTITY_CONTAINERS)
                || entity.getType().is(TELEPORTING_NOT_SUPPORTED)) {
            return true;
        }
        // 剩余不可选中、无碰撞、未载客的裸实体中，若同时忽略方块触发且不受活塞推动，
        // 又没有周期位移同步或视线高度清零（如已放置在方块间的强力胶选区），则属于隐形技术附着实体，予以过滤；
        // 机械动力座椅（常规活塞反应且不忽略触发）与有周期同步及正视线高度的动态装置实体不会命中此分支，安全保留。
        return !(entity.isIgnoringBlockTriggers()
                && entity.getPistonPushReaction() == PushReaction.IGNORE
                && (entity.getEyeHeight() <= 0.0f || entity.getType().updateInterval() == Integer.MAX_VALUE));
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
