// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.gather;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;

import org.maiwithu.maicraft.behavior.interaction.HitsItemFrames;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.perception.SceneEntity;
import org.maiwithu.maicraft.behavior.perception.SeenRegistry;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 采集目标的观察读端：观察编号最后看到的方位与种类，从感知的场景表与当前世界实体里查。
 *
 * <p>编号已失效、场景还没建好都查不到，采集按"目标不在了"如实收场。
 * 实体编号先核对是不是展示框（是就改成打一下取出框里物品），再核对是不是真正的地上掉落物（{@code ItemEntity}），
 * 生物、载具、装置等非掉落物实体不再被误标成掉落物尝试拾取。
 * 实体已被移除（走近时被原版自动拾取吸进背包）时活体与场景表都核对不到，
 * 登记时的实体类型与装的物品随 {@link SeenTarget} 带出，采集据此把"已被捡走"与"不是掉落物"分开说。
 */
public final class LiveSceneTargets implements GatherAbility.SeesTargets {

    /** 原版掉落物的实体类型注册 ID：登记时按它认出"这是个掉落物"。 */
    private static final String DROPPED_ITEM_TYPE = "minecraft:item";

    private final Supplier<Scene> scene;
    private final HitsItemFrames frames;
    private final Supplier<PlayerContext> context;

    public LiveSceneTargets(Supplier<Scene> scene, HitsItemFrames frames) {
        this(scene, frames, () -> null);
    }

    public LiveSceneTargets(Supplier<Scene> scene, HitsItemFrames frames, Supplier<PlayerContext> context) {
        this.scene = Objects.requireNonNull(scene, "scene");
        this.frames = Objects.requireNonNull(frames, "frames");
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public Optional<SeenTarget> lookup(String id) {
        Scene current = scene.get();
        if (current == null || id == null) {
            return Optional.empty();
        }
        return current.lookup(id).map(entry -> {
            if (entry.kind() == SeenRegistry.Kind.FACILITY || entry.kind() == SeenRegistry.Kind.FEATURE) {
                return new SeenTarget(entry.position(), true, false, -1);
            }
            if (entry.kind() == SeenRegistry.Kind.ENTITY) {
                // 实体编号对应的是展示框（看得出框里放着什么）就记下它的游戏实体编号，采集改成打一下。
                int frame = current.seen().gameEntityOf(id)
                        .filter(gameId -> frames.contents(gameId).isPresent()).orElse(-1);
                if (frame >= 0) {
                    return new SeenTarget(entry.position(), false, false, frame);
                }
                // 只有真正的掉落物实体才按掉落物走过去拾取；生物、载具、技术实体等非掉落物绝不标为 drop。
                // 同时保留目标掉落物的游戏实体编号与物品种类，供采集精确捡起这一件并核验目标物品到手。
                boolean registeredDrop = DROPPED_ITEM_TYPE.equals(entry.entityType());
                boolean drop = isDroppedItem(current, id);
                int dropEntityId = drop ? current.seen().gameEntityOf(id).orElse(-1) : -1;
                String droppedItem = liveDroppedItem(current, id)
                        .orElse(registeredDrop ? entry.itemType() : null);
                return new SeenTarget(entry.position(), false, drop, -1,
                        registeredDrop, droppedItem, dropEntityId);
            }
            // 物理结构既不是单格可挖方块，也不是地上掉落物或展示框。
            return new SeenTarget(entry.position(), false, false, -1);
        });
    }

    // 这个编号该不该按掉落物对待：活体在场按真实类型认；活体不在场（被移除、走远卸载）时，
    // 场景表里最后一次看到它还是掉落物也算——等下一次感知刷新把它抹掉之前，采集照常按掉落物走。
    private boolean isDroppedItem(Scene current, String id) {
        PlayerContext playerContext = context.get();
        if (playerContext != null && playerContext.level() != null) {
            Optional<Entity> live = current.seen().entityIn(playerContext.level(), id);
            if (live.isPresent()) {
                return live.get() instanceof ItemEntity;
            }
        }
        return current.sighting(id).stream()
                .anyMatch(sighting -> sighting instanceof SceneEntity entity
                        && DROPPED_ITEM_TYPE.equals(entity.type()));
    }

    // 活体掉落物还在世界里时直接读它当前承载的物品 ID，保证与现场实体一致。
    private Optional<String> liveDroppedItem(Scene current, String id) {
        PlayerContext playerContext = context.get();
        if (playerContext == null || playerContext.level() == null) {
            return Optional.empty();
        }
        return current.seen().entityIn(playerContext.level(), id)
                .filter(entity -> entity instanceof ItemEntity item && !item.getItem().isEmpty())
                .map(entity -> BuiltInRegistries.ITEM.getKey(((ItemEntity) entity).getItem().getItem()).toString());
    }
}
