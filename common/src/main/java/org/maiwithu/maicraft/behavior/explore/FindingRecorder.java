// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

import java.time.Instant;
import java.util.List;
import org.maiwithu.maicraft.behavior.perception.DirectionWords;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryOrigin;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 探索途中的发现落账：一条发现先记进这次探索的发现账，新的或从远望升级成到访的落进世界记忆，
 * 新的再发一条 discovered 事件（是什么、在身体哪个方向多远）；留意清单的结构线索与实体按间隔扫一轮也从这里记。
 * 角色不停不问，照常跑图。
 */
final class FindingRecorder {

    private final ExploreFindings findings;
    private final ExplorationWorld world;
    private final ExploreObjective.Interests interests;
    private final WorldMemory memory;
    private final TaskEventSink events;

    FindingRecorder(ExploreFindings findings, ExplorationWorld world, ExploreObjective.Interests interests,
            WorldMemory memory, TaskEventSink events) {
        this.findings = findings;
        this.world = world;
        this.interests = interests;
        this.memory = memory;
        this.events = events;
    }

    /** 留意清单的结构线索与实体：按间隔扫一轮；走到结构跟前的线索升级成到访，不降级。 */
    void scanInterests() {
        for (ExplorationWorld.Spotted spotted : world.structureClues(interests.structures())) {
            if (world.sightClear(spotted.position().x(), spotted.position().y(), spotted.position().z())) {
                record(new ExploreFinding(ExploreFinding.Kind.STRUCTURE_CLUE, spotted.id(),
                        spotted.position(), MemoryOrigin.SEEN));
            }
        }
        for (ExplorationWorld.Spotted spotted : world.entitySightings(interests.entities())) {
            record(new ExploreFinding(ExploreFinding.Kind.ENTITY, spotted.id(),
                    spotted.position(), MemoryOrigin.SEEN));
        }
        WorldPosition spot = world.currentSpot();
        for (ExploreFinding finding : findings.all()) {
            if (finding.kind() != ExploreFinding.Kind.STRUCTURE_CLUE
                    || finding.origin() != MemoryOrigin.SEEN) {
                continue;
            }
            StructureClues.resolve(finding.targetId()).ifPresent(resolved -> {
                int reach = resolved.profile().clusterRadius();
                if (Math.abs(spot.x() - finding.position().x()) <= reach
                        && Math.abs(spot.z() - finding.position().z()) <= reach) {
                    record(new ExploreFinding(ExploreFinding.Kind.STRUCTURE_CLUE, finding.targetId(),
                            finding.position(), MemoryOrigin.VISITED));
                }
            });
        }
    }

    /** 记一条发现：落进世界记忆，新发现发一条事件；角色不停不问，照常跑图。 */
    void record(ExploreFinding finding) {
        if (finding.targetId() == null) {
            return;
        }
        ExploreFindings.Result result = findings.record(finding);
        if (result.entry() == ExploreFindings.Entry.KNOWN) {
            return;
        }
        List<String> contents = List.of(result.effective().targetId());
        if (result.effective().origin() == MemoryOrigin.VISITED) {
            memory.rememberSiteVisited(result.effective().position(), contents, Instant.now());
        } else {
            memory.rememberSite(result.effective().position(), contents, Instant.now());
        }
        if (result.entry() == ExploreFindings.Entry.NEW) {
            events.publish(TaskEvent.Kind.DISCOVERED, describe(result.effective()));
        }
    }

    /** 发现的一句话：是什么、在身体哪个方向多远。 */
    private String describe(ExploreFinding finding) {
        WorldPosition spot = world.currentSpot();
        String word = switch (finding.kind()) {
            case BIOME -> "群系";
            case TERRAIN -> "地形要素";
            case STRUCTURE_CLUE -> "结构线索";
            case BLOCK -> "留意方块";
            case ENTITY -> "留意实体";
        };
        if (spot == null) {
            return "发现" + word + " " + finding.targetId();
        }
        int dx = finding.position().x() - spot.x();
        int dz = finding.position().z() - spot.z();
        int distance = (int) Math.round(Math.sqrt((double) dx * dx + dz * dz));
        String place = distance <= 2 ? "脚下" : DirectionWords.compassOf(dx, dz) + " " + distance + " 格";
        return "发现" + word + " " + finding.targetId() + "（" + place + "）";
    }
}
