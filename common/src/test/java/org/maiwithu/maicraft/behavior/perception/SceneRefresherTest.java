// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景刷新的顺序与策略：替身读端按脚本回答，钉住十几步看的顺序一字不动、没自身基准不整理、
 * 目标推进中到期的编号还留着（东西不在了照常失效）、洞口扫完一圈才换、设施读端每刻拿到的是当刻上下文。
 */
class SceneRefresherTest {

    private final List<String> trace = new ArrayList<>();
    private final RecordingMemory memory = new RecordingMemory();
    private final Scene scene = new Scene(memory, new FacilityKinds(FakeBlockTags.vanilla()));

    // 没进世界时这一刻不该被调进来；替身只需要回答刻号，其余按"没有"答。
    private static PlayerContext contextAt(long tick) {
        return new PlayerContext() {
            @Override public LocalPlayer localPlayer() { return null; }
            @Override public ClientLevel level() { return null; }
            @Override public ClientPacketListener connection() { return null; }
            @Override public PlayerInput input() { return null; }
            @Override public InteractionSender interactionSender() { return null; }
            @Override public MenuActions menuActions() { return null; }
            @Override public long clientTick() { return tick; }
            @Override public boolean isCurrent() { return true; }
            @Override public boolean canInteractThisTick() { return false; }
            @Override public boolean tryClaimInteraction() { return false; }
            @Override public BackpackView backpack() { return null; }
        };
    }

    /** 自身读端替身：第几步被问到记进轨迹，回答按测试摆。 */
    private final class ScriptedSelfSight implements SelfSight {
        private Facts answer;

        void answers(Facts facts) { this.answer = facts; }

        @Override public Facts current() {
            trace.add("自身");
            return answer;
        }
    }

    private final class ScriptedEnvironmentSight implements EnvironmentSight {
        @Override public SceneEnvironment current() {
            trace.add("环境");
            return new SceneEnvironment("白天", "晴", 15, "minecraft:plains");
        }
    }

    /** 实体读端替身：每次刷新按脚本给一批实体，默认没有。 */
    private final class ScriptedEntitySight implements EntitySight {
        private final List<List<Observation>> script = new ArrayList<>();

        void nextRefreshSees(List<Observation> observations) { script.add(observations); }

        @Override public List<Observation> nearby() {
            trace.add("实体");
            return script.isEmpty() ? List.of() : script.remove(0);
        }
    }

    /** 设施读端替身：读的时候把感知入口给的当刻现场一并记下。 */
    private final class ScriptedFacilitySight implements NearbyBlocksSight {
        Supplier<PlayerContext> observingSource;
        final List<PlayerContext> seenAtRead = new ArrayList<>();

        @Override public List<BlockSighting> nearby(Set<String> blockTypes) {
            trace.add("设施");
            seenAtRead.add(observingSource.get());
            return List.of();
        }

        @Override public List<SignSighting> signs() {
            trace.add("告示牌");
            return List.of();
        }
    }

    private final class ScriptedSubtitleEar implements SubtitleEar {
        @Override public List<Event> recent() {
            trace.add("声音");
            return List.of();
        }
    }

    /** 洞口读端替身：按脚本逐刻回答，没扫完一圈给空。 */
    private final class ScriptedOpeningSight extends ClientCaveOpeningSight {
        private final List<Optional<List<CaveOpenings.Opening>>> script = new ArrayList<>();

        void nextRefreshReturns(Optional<List<CaveOpenings.Opening>> answer) { script.add(answer); }

        @Override public Optional<List<CaveOpenings.Opening>> tick(PlayerContext current) {
            trace.add("洞口");
            return script.isEmpty() ? Optional.empty() : script.remove(0);
        }
    }

    private final class ScriptedCampPitSight extends ClientCampPitSight {
        @Override public Optional<List<CampPits.Pit>> tick(PlayerContext current, long gameTick) {
            trace.add("营地坑");
            return Optional.empty();
        }
    }

    private final class ScriptedRareSightingWatch extends ClientRareSightingWatch {
        @Override public void tick(PlayerContext current, long gameTick, Instant when) {
            trace.add("稀有撞见");
        }
    }

    private final ScriptedSelfSight selfSight = new ScriptedSelfSight();
    private final ScriptedEnvironmentSight environmentSight = new ScriptedEnvironmentSight();
    private final ScriptedEntitySight entitySight = new ScriptedEntitySight();
    private final ScriptedFacilitySight facilitySight = new ScriptedFacilitySight();
    private final ScriptedSubtitleEar subtitleEar = new ScriptedSubtitleEar();
    private final ScriptedOpeningSight openingSight = new ScriptedOpeningSight();
    private final ScriptedCampPitSight campPitSight = new ScriptedCampPitSight();
    private final ScriptedRareSightingWatch rareSightings = new ScriptedRareSightingWatch();

    /** 俯视网格替身：逐刻给画好的网格，脚本用完就给最后一张。 */
    private final List<OverheadGrid.View> gridScript = new ArrayList<>();

    private Function<PlayerContext, OverheadGrid.View> gridViews() {
        return current -> {
            trace.add("网格");
            return gridScript.isEmpty() ? waterGrid() : gridScript.remove(0);
        };
    }

    private SceneRefresher refresherWithGridScript() {
        SceneRefresher refresher = new SceneRefresher(scene, new FacilityKinds(FakeBlockTags.vanilla()),
                selfSight, environmentSight, entitySight, facilitySight, subtitleEar,
                gridViews(), openingSight, campPitSight, rareSightings, List.of());
        // 设施读端的当刻现场接在感知入口上，和启动侧接上读端的方式一样。
        facilitySight.observingSource = refresher::observingContext;
        return refresher;
    }

    private static SelfSight.Facts standingAtOrigin() {
        return new SelfSight.Facts(0.5, 64, 0.5, 180f, 20, 20, 300, 300, null, List.of(), List.of(), true, false);
    }

    private static EntitySight.Observation zombieAt(int z) {
        return new EntitySight.Observation(1, "minecraft:zombie", null, null,
                WorldPosition.here(0, 64, z), true, true, false, new EntitySight.Health(14, 20), Map.of());
    }

    /** 北边三格水聚出一个"水体"地形特征（领 f# 编号的那一路，到期策略拿它当"东西还在"的代表）。 */
    private static OverheadGrid.View waterGrid() {
        char[][] cells = {
                {'~', '~', '~'},
                {'.', '.', '.'},
                {'.', '.', '.'}};
        return new OverheadGrid.View(new BlockPos(0, 64, -4), 1, cells);
    }

    @Test
    void nothingIsOrganizedWithoutSelfBasis() {
        selfSight.answers(null);
        SceneRefresher refresher = refresherWithGridScript();
        refresher.refresh(contextAt(10), 10, false);
        assertNull(scene.self(), "没读到自身基准，场景里不给方位");
        assertTrue(scene.entities().isEmpty() && scene.features().isEmpty(), "别的观察也不整理");
        assertEquals(List.of("自身"), trace, "自身没读到就停，后面的步骤一步都不跑");
    }

    @Test
    void stepsRunInFixedOrder() {
        selfSight.answers(standingAtOrigin());
        SceneRefresher refresher = refresherWithGridScript();
        refresher.refresh(contextAt(10), 10, false);
        assertEquals(List.of("自身", "环境", "实体", "声音", "网格", "设施", "告示牌", "洞口", "营地坑", "稀有撞见"),
                trace, "十几步看的顺序一字不动，稀有撞见在最后");
    }

    @Test
    void idsDueExpireStayWhileGoalUnderwayAndGoWhenThingsAreGone() {
        selfSight.answers(standingAtOrigin());
        SceneRefresher refresher = refresherWithGridScript();
        // 第 100 刻看见一只僵尸和一片水：各领一个编号。
        entitySight.nextRefreshSees(List.of(zombieAt(-8)));
        refresher.refresh(contextAt(100), 100, false);
        assertEquals(1, scene.entities().size());
        assertEquals(1, scene.features().size());
        // 第 500 刻僵尸没再看见（超实体保留期），主目标在推进：实体不在了照常失效，地形特征没到期照留。
        entitySight.nextRefreshSees(List.of());
        refresher.refresh(contextAt(500), 500, true);
        assertTrue(scene.entities().isEmpty(), "东西不在了，目标在推进也不留");
        assertEquals(1, scene.features().size(), "没到期的地形特征照留");
        // 第 5000 刻地形特征也超期了：主目标还在推进，"东西还在"的编号照留。
        refresher.refresh(contextAt(5000), 5000, true);
        assertEquals(1, scene.features().size(), "目标推进中到期的编号还留着");
        // 第 8000 刻水不再出现在网格里（换一张空网格），没有目标在推进：到期就失效。
        char[][] emptyCells = {
                {'.', '.', '.'},
                {'.', '.', '.'},
                {'.', '.', '.'}};
        gridScript.add(new OverheadGrid.View(new BlockPos(0, 64, -4), 1, emptyCells));
        refresher.refresh(contextAt(8000), 8000, false);
        assertTrue(scene.features().isEmpty(), "没有目标在推进，到期就失效");
    }

    @Test
    void openingsSwapInOnlyAfterAFullSweep() {
        selfSight.answers(standingAtOrigin());
        SceneRefresher refresher = refresherWithGridScript();
        // 第一刻没扫完一圈：场景里没有洞口。
        openingSight.nextRefreshReturns(Optional.empty());
        refresher.refresh(contextAt(10), 10, false);
        assertTrue(scene.caveOpenings().isEmpty(), "没扫完一圈，场景里不换");
        // 第二刻扫完一圈：这一圈看到的洞口才换上去。
        List<CaveOpenings.Opening> found = List.of(new CaveOpenings.Opening(CaveOpenings.Kind.CAVE_MOUTH,
                new BlockPos(0, 58, -12), true, 0, 0, 0, -12, 1, -12));
        openingSight.nextRefreshReturns(Optional.of(found));
        refresher.refresh(contextAt(20), 20, false);
        assertEquals(found, scene.caveOpenings());
    }

    @Test
    void facilitySightReadsThisTicksContextEachRefresh() {
        selfSight.answers(standingAtOrigin());
        SceneRefresher refresher = refresherWithGridScript();
        PlayerContext earlier = contextAt(10);
        PlayerContext later = contextAt(20);
        refresher.refresh(earlier, 10, false);
        refresher.refresh(later, 20, false);
        assertEquals(List.of(earlier, later), facilitySight.seenAtRead, "每刻读到的是当刻的上下文，不留到下一刻");
    }
}
