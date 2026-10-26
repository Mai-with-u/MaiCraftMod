// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.perception.FacilitySighting;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.perception.SceneEntity;
import org.maiwithu.maicraft.behavior.perception.SceneSign;
import org.maiwithu.maicraft.behavior.perception.SceneSighting;
import org.maiwithu.maicraft.behavior.perception.SceneThing;
import org.maiwithu.maicraft.behavior.perception.TerrainFeature;

/**
 * 观察编号解析的生产实现：编号在哪、是什么，从感知的场景表里读。
 *
 * <p>类型从场景的统一查询里认：设施与告示牌给方块类型、实体给实体类型、地形特征给特征说法、
 * 模组的东西给种类；实体编号从观察登记换回游戏实体编号，交互前按它找回当刻的那一只。
 * 编号已失效、场景没建好、或场景里认不出的地点与设施，都返回空，按"不在了"结束。
 */
public final class LiveSeenResolver implements ResolvesSeen {

    private final Supplier<Scene> scene;

    public LiveSeenResolver(Supplier<Scene> scene) {
        this.scene = Objects.requireNonNull(scene, "scene");
    }

    @Override
    public Optional<Resolved> resolve(String seenId) {
        Scene current = scene.get();
        if (current == null || seenId == null) {
            return Optional.empty();
        }
        return current.lookup(seenId).flatMap(entry -> {
            Integer gameEntity = current.seen().gameEntityOf(seenId).orElse(null);
            Optional<String> type = typeOf(current, seenId);
            // 实体的类型交互前按游戏实体编号从世界里读：这一轮场景没列出它不等于它不在了。
            if (type.isEmpty() && gameEntity == null) return Optional.empty();
            return Optional.of(new Resolved(entry.position(), type.orElse(null), gameEntity));
        });
    }

    // 地点名没记过时按牌子认地方：读的是 observe 里同一份看得见的告示牌。
    @Override
    public List<SceneSign> signs() {
        Scene current = scene.get();
        return current == null ? List.of() : current.signs();
    }

    // 编号在场景哪一路、条目是什么就按那一路说它是什么；场景里没有就是这一轮已经看不到了。
    private static Optional<String> typeOf(Scene current, String seenId) {
        return current.sighting(seenId).stream().findFirst().map(sighting -> switch (sighting) {
            case FacilitySighting facility -> facility.blockType();
            case SceneEntity entity -> entity.type();
            case TerrainFeature feature -> feature.kind();
            case SceneSign sign -> sign.blockType();
            case SceneThing thing -> thing.category();
        });
    }
}
