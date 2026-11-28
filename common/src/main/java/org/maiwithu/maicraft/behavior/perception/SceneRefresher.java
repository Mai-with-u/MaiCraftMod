// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.behavior.inventory.containers.StandingContainers;
import org.maiwithu.maicraft.behavior.perception.spi.ModSight;
import org.maiwithu.maicraft.behavior.perception.spi.ModThing;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.OnlinePlayers;

/**
 * 场景刷新：每刻看一眼周围的感知入口。各路读端读当刻的观察事实，交给场景整理并发观察编号。
 *
 * <p>看的顺序在这里定死：先自身（没有角色基准时整理不出方位，别的什么都不整理），
 * 再环境、实体、声音、在线玩家、俯视网格、设施与告示牌、洞口、营地坑、模组的东西，
 * 最后到期失效与稀有撞见。设施、洞口、营地坑、稀有撞见四路读端跨刻复用一份，构造时传进来：
 * 洞口扫一圈要两秒上下，营地坑两秒或挪过六格一轮，都靠读端自己跨刻接着推进，每刻新建会丢掉扫到一半的进度。
 *
 * <p>没有角色（还没进世界、人接管中）时启动侧这一刻不会调进来；设施观察用的当刻角色现场
 * 也由这里在每刻读设施前先换上（{@link #observingContext}），读端构造时接走这个供给者。
 */
public final class SceneRefresher {

    /** 模组的东西在角色周围多大范围里看（格）。 */
    private static final int THINGS_RADIUS = 64;

    private final Scene scene;
    private final FacilityKinds kinds;
    private final SelfSight selfSight;
    private final EnvironmentSight environmentSight;
    private final EntitySight entitySight;
    private final NearbyBlocksSight facilitySight;
    private final SubtitleEar subtitleEar;
    /** 俯视网格的读数：收当刻的角色上下文，以角色为中心画一张。 */
    private final Function<PlayerContext, OverheadGrid.View> gridViews;
    private final ClientCaveOpeningSight openingSight;
    private final ClientCampPitSight campPitSight;
    private final ClientRareSightingWatch rareSightings;
    private final List<ModSight> modSights;
    /** 这一刻在看的玩家现场：设施观察的读端跨刻复用一个，每刻在读设施前从这里换上现场。 */
    private PlayerContext observing;

    public SceneRefresher(Scene scene, FacilityKinds kinds, SelfSight selfSight, EnvironmentSight environmentSight,
            EntitySight entitySight, NearbyBlocksSight facilitySight, SubtitleEar subtitleEar,
            Function<PlayerContext, OverheadGrid.View> gridViews, ClientCaveOpeningSight openingSight,
            ClientCampPitSight campPitSight, ClientRareSightingWatch rareSightings, List<ModSight> modSights) {
        this.scene = Objects.requireNonNull(scene, "scene");
        this.kinds = Objects.requireNonNull(kinds, "kinds");
        this.selfSight = Objects.requireNonNull(selfSight, "selfSight");
        this.environmentSight = Objects.requireNonNull(environmentSight, "environmentSight");
        this.entitySight = Objects.requireNonNull(entitySight, "entitySight");
        this.facilitySight = Objects.requireNonNull(facilitySight, "facilitySight");
        this.subtitleEar = Objects.requireNonNull(subtitleEar, "subtitleEar");
        this.gridViews = Objects.requireNonNull(gridViews, "gridViews");
        this.openingSight = Objects.requireNonNull(openingSight, "openingSight");
        this.campPitSight = Objects.requireNonNull(campPitSight, "campPitSight");
        this.rareSightings = Objects.requireNonNull(rareSightings, "rareSightings");
        this.modSights = List.copyOf(modSights);
    }

    /** 设施观察读端的当刻现场供给：启动侧把设施读端的现场供给接到这里，每刻先换上这一刻的再用。 */
    public PlayerContext observingContext() {
        return observing;
    }

    /**
     * 看一遍周围，把各路观察整理进场景。调用前启动侧已确认当刻有角色。
     *
     * @param goalUnderway 主目标在推进（含 sequence 一步接一步地做、等回答）：到期但东西还在的观察编号照留
     */
    public void refresh(PlayerContext current, long gameTick, boolean goalUnderway) {
        Instant when = Instant.now();
        var self = selfSight.current();
        if (self == null) {
            return;
        }
        LocalPlayer player = current.localPlayer();
        scene.updateSelf(self);
        scene.updateEnvironment(environmentSight.current());
        scene.updateEntities(gameTick, entitySight.nearby());
        scene.updateSounds(gameTick, subtitleEar.recent());
        // 玩家列表（Tab）：在线但不在实体观察里的玩家只有列表能证明——超出玩家同步半径的
        // 玩家根本不同步实体，不把这条报出来，"附近没有玩家"就会被误读成"服务器上没有玩家"。
        List<Scene.OnlinePlayer> online = new ArrayList<>();
        for (OnlinePlayers.Online listed : OnlinePlayers.online(current)) {
            online.add(new Scene.OnlinePlayer(listed.name(), listed.uuid()));
        }
        scene.updateOnlinePlayers(List.copyOf(online));
        // 俯视网格以角色为中心画一张：地形特征从网格里聚出来，路过成片的树顺路记产地线索。
        scene.updateGrid(gridViews.apply(current), gameTick, when);
        // 设施观察：容器、工作设施与床三类都查——容器与工作设施按方块标签认（模组的箱子、损坏的铁砧都算），
        // 床按方块类型名认；三类都报观察编号，只有容器与工作设施写进世界记忆。
        // 读端跨刻复用一份，先换上当刻的现场再读。
        observing = current;
        scene.updateFacilities(gameTick, when, facilitySight.nearby(kinds.facilityBlockTypes()),
                StandingContainers.ReadsCells.inLevel(current.level())::blockIdAt);
        // 看得见的告示牌连同牌子上的字：主播在基地立的路牌、留言，观察里读得到。
        scene.updateSigns(gameTick, facilitySight.signs());
        // 洞口与峡谷：每刻扫一小段，扫完一圈才换上这一圈看到的；看见的顺路记进世界记忆。
        openingSight.tick(current).ifPresent(found -> scene.updateOpenings(gameTick, when, found));
        // 营地地面坑：两秒或挪过六格扫一轮营地内的地面竖坑与爆炸弹坑，给观察编号方便顺手填平。
        campPitSight.tick(current, gameTick).ifPresent(found -> scene.updateCampPits(gameTick, found));
        // 模组的东西：电梯、车站、列车、物理结构，联动模组各自报角色附近看得到的。
        List<ModThing> things = new ArrayList<>();
        AABB around = new AABB(BlockPos.containing(self.x(), self.y(), self.z())).inflate(THINGS_RADIUS);
        for (ModSight sight : modSights) {
            sight.sight(current.level(), player, around, things::add);
        }
        scene.updateModThings(gameTick, things);
        // 目标在推进时，LLM 排好的后几步点的还是开头看到的编号：到期时先看东西还在不在，还在就留着；
        // 没有目标在推进时到期就失效。
        scene.expire(gameTick, goalUnderway ? entry -> scene.seen().stillThere(current.level(), entry) : null);
        // 实体观察编号刚刷新完，核对眼前视野有没有新撞见的 Boss 或露出来的天然稀有矿脉。
        rareSightings.tick(current, gameTick, when);
    }
}
