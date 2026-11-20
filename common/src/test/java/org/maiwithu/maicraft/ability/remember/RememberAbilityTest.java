// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.remember;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.acquire.engine.ReadsCharacterPosition;
import org.maiwithu.maicraft.behavior.perception.SceneSign;
import org.maiwithu.maicraft.behavior.perception.SignLines;
import org.maiwithu.maicraft.behavior.travel.planning.ReadsSeenTargets;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.RememberedRegion;
import org.maiwithu.maicraft.behavior.worldmemory.RememberedStation;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.param.ParseResult;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 记地点能力：目标对象落成带维度的位置、同名覆盖、忘掉没记过的名字如实失败。 */
class RememberAbilityTest {

    /** 能决定阶段用的上下文：只有目标。 */
    private static StepContext step(Goal goal) {
        return new StepContext() {
            @Override public Goal goal() {
                return goal;
            }

            @Override public int stepIndex() {
                return 0;
            }

            @Override public TickContext tick() {
                throw new IllegalStateException("决定阶段不应碰到每刻上下文");
            }
        };
    }

    /** 本刻上下文替身：刻号由测试推进。 */
    private static final class TestTick implements TickContext {
        private long gameTick = 1000;

        @Override public long gameTick() {
            return gameTick;
        }

        @Override public PlayerContext player() {
            throw new IllegalStateException("记地点测试不应碰到角色对象");
        }

        void advance() {
            gameTick++;
        }
    }

    @TempDir
    Path temp;

    private WorldMemory memory;
    private RememberAbility ability;

    @BeforeEach
    void world() {
        memory = new WorldMemory(new DocumentStore(temp.resolve("state.sqlite")), "a".repeat(64));
        // 角色站在主世界 (12, 64, -8)；e7 带维度，b3/b4 与实机观察编号一致不带维度（WorldPosition.here）。
        ability = new RememberAbility(memory,
                () -> new WorldPosition(12, 64, -8, "minecraft:overworld"),
                id -> switch (id) {
                    case "e7" -> Optional.of(new WorldPosition(0, 64, -12, "minecraft:overworld"));
                    case "b3" -> Optional.of(WorldPosition.here(0, 64, -12));
                    case "b4" -> Optional.of(WorldPosition.here(4, 64, -12));
                    default -> Optional.empty();
                });
    }

    private ParamValues params(String json) {
        ParseResult result = ability.spec().paramSpecs()
                .parse(JsonParser.parseString(json).getAsJsonObject());
        assertTrue(result.ok(), "参数应能解析：" + result.errors());
        return result.params();
    }

    private Goal goal(Target target, String parametersJson) {
        return new Goal("maicraft:remember", null, target, params(parametersJson), null, List.of(), null);
    }

    /** 推进决定开出的任务到结束，让记忆真的写进去。 */
    private TaskResult runToFinish(StepDecision.Run run) {
        RememberPlaceTask task = new RememberPlaceTask(
                (RememberPlaceInput) run.input(), memory);
        TestTick tick = new TestTick();
        task.start(tick);
        TickResult result = task.tick(tick);
        return assertInstanceOf(TickResult.Finished.class, result).result();
    }

    @Test
    void specIsMemoryOnlyAndRequiresNameForPlaceOperations() {
        assertEquals("maicraft:remember", ability.spec().id());
        assertEquals(ExecutionMode.MEMORY_ONLY, ability.spec().mode());
        StepDecision.Finish missingName = assertInstanceOf(StepDecision.Finish.class,
                ability.decide(step(goal(null, "{}"))));
        assertEquals(Problem.Kind.INVALID_PARAMETER, missingName.result().problem().kind());
    }

    @Test
    void hereIsRecordedWithDimension() {
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class,
                ability.decide(step(goal(null, "{\"name\":\"家\"}"))));
        RememberPlaceInput input = assertInstanceOf(RememberPlaceInput.class, run.input());
        assertEquals(new WorldPosition(12, 64, -8, "minecraft:overworld"), input.position());

        TaskResult result = runToFinish(run);
        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(Optional.of(input.position()), memory.place("家"));
    }

    @Test
    void outOfWorldSaysSoInsteadOfCrashing() {
        // 角色不在世界里读不到自己的位置：记 here、记没写维度的坐标都如实说，不冒成程序错误。
        RememberAbility away = new RememberAbility(memory, () -> null, id -> Optional.empty());
        StepDecision.Finish here = assertInstanceOf(StepDecision.Finish.class,
                away.decide(step(goal(null, "{\"name\":\"家\"}"))));
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, here.result().problem().kind());
        StepDecision.Finish position = assertInstanceOf(StepDecision.Finish.class,
                away.decide(step(goal(new Target.Position(1, 64, 2, null), "{\"name\":\"矿口\"}"))));
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, position.result().problem().kind());
    }

    @Test
    void recordingTheSameSpotAgainFinishesWithoutWriting() {
        memory.remember("家", new WorldPosition(12, 64, -8, "minecraft:overworld"));

        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                ability.decide(step(goal(null, "{\"name\":\" 家 \"}"))));
        assertEquals(TaskResult.Status.DONE, finish.result().status());
        assertTrue(finish.result().summary().contains("本来就这样记着"));
    }

    @Test
    void overwritingReportsWhereItWasBefore() {
        memory.remember("家", new WorldPosition(0, 64, 0, "minecraft:overworld"));

        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class,
                ability.decide(step(goal(new Target.Position(12, 64, -8, null),
                        "{\"name\":\"家\"}"))));
        RememberPlaceInput input = assertInstanceOf(RememberPlaceInput.class, run.input());
        assertEquals(new WorldPosition(0, 64, 0, "minecraft:overworld"), input.previous());

        runToFinish(run);
        assertEquals(Optional.of(new WorldPosition(12, 64, -8, "minecraft:overworld")),
                memory.place("家"));
    }

    @Test
    void coordinateWithoutHeightIsRefused() {
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                ability.decide(step(goal(new Target.Position(12, null, -8, null),
                        "{\"name\":\"矿洞\"}"))));
        assertEquals(TaskResult.Status.FAILED, finish.result().status());
        assertEquals(Problem.Kind.INVALID_PARAMETER, finish.result().problem().kind());
        assertTrue(memory.place("矿洞").isEmpty(), "没记上就不该有这条");
    }

    @Test
    void seenTargetUsesTheLastSeenPosition() {
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class,
                ability.decide(step(goal(new Target.Seen("e7"), "{\"name\":\"僵尸出现的地儿\"}"))));
        assertEquals(new WorldPosition(0, 64, -12, "minecraft:overworld"),
                assertInstanceOf(RememberPlaceInput.class, run.input()).position());
    }

    @Test
    void aGoneObservationIsReportedAsSuch() {
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                ability.decide(step(goal(new Target.Seen("e9"), "{\"name\":\"没了的\"}"))));
        assertEquals(Problem.Kind.TARGET_GONE, finish.result().problem().kind());
    }

    @Test
    void landmarkRenamesAnAlreadyRememberedPlace() {
        memory.remember("家", new WorldPosition(12, 64, -8, "minecraft:overworld"));

        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class,
                ability.decide(step(goal(new Target.Landmark("家"), "{\"name\":\"门口\"}"))));
        runToFinish(run);
        assertEquals(memory.place("家"), memory.place("门口"));
    }

    @Test
    void landmarkNotRememberedIsReadOffTheOnlySignSayingIt() {
        // 没记过"矿洞"，但看得见一块写着"矿洞"的路牌：把牌子那一格记下来，往后不看见牌子也找得到。
        WorldPosition signAt = new WorldPosition(3, 65, -20, "minecraft:overworld");
        RememberAbility withSign = new RememberAbility(memory,
                () -> new WorldPosition(12, 64, -8, "minecraft:overworld"),
                new ReadsSeenTargets() {
                    @Override public Optional<WorldPosition> positionOf(String observationId) {
                        return Optional.empty();
                    }

                    @Override public List<SceneSign> signs() {
                        return List.of(new SceneSign("b2", "minecraft:oak_wall_sign", signAt, "前方", "北", 14,
                                new SignLines(List.of("→ 矿洞 ←", "", "", ""), List.of("", "", "", ""))));
                    }
                });

        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class,
                withSign.decide(step(goal(new Target.Landmark("矿洞"), "{\"name\":\"矿洞\"}"))));
        runToFinish(run);
        assertEquals(Optional.of(signAt), memory.place("矿洞"));

        // 牌子上写的是别的名字：照旧说没记过，句子里说清看得见的牌子上也没有。
        StepDecision.Finish missing = assertInstanceOf(StepDecision.Finish.class,
                withSign.decide(step(goal(new Target.Landmark("粮仓"), "{\"name\":\"粮仓\"}"))));
        assertEquals(Problem.Kind.NOT_FOUND, missing.result().problem().kind());
        assertTrue(missing.result().problem().message().contains("告示牌上也没有写着「粮仓」"),
                missing.result().problem().message());
    }

    @Test
    void forgettingRemovesOnlyTheNamedPlace() {
        memory.remember("家", new WorldPosition(12, 64, -8, "minecraft:overworld"));
        memory.remember("矿洞", new WorldPosition(0, 64, -12, "minecraft:overworld"));

        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class,
                ability.decide(step(goal(null, "{\"name\":\"家\",\"operation\":\"forget\"}"))));
        runToFinish(run);
        assertTrue(memory.place("家").isEmpty());
        assertEquals(Optional.of(new WorldPosition(0, 64, -12, "minecraft:overworld")),
                memory.place("矿洞"), "只忘名字对得上的那一个");
    }

    @Test
    void forgettingAnUnknownNameFailsWithAllRememberedNames() {
        memory.remember("矿洞", new WorldPosition(0, 64, -12, "minecraft:overworld"));

        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                ability.decide(step(goal(null, "{\"name\":\"家\",\"operation\":\"forget\"}"))));
        assertEquals(TaskResult.Status.FAILED, finish.result().status());
        assertEquals(Problem.Kind.NOT_FOUND, finish.result().problem().kind());
        assertTrue(finish.result().problem().message().contains("矿洞"),
                "失败要说清记得的地点都有什么：" + finish.result().problem().message());
    }

    @Test
    void regionAroundHereUsesTheDefaultRadius() {
        // 站在基地门口说"这一片是我的"：以脚下为中心圈 8 格，圈下立刻能查到，片里的格子算在地盘里。
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class,
                ability.decide(step(goal(null, "{\"name\":\"基地\",\"operation\":\"region\"}"))));
        TaskResult result = runToFinish(run);
        assertEquals(TaskResult.Status.DONE, result.status());
        RememberedRegion region = memory.region("基地").orElseThrow();
        assertEquals(new WorldPosition(12, 64, -8, "minecraft:overworld"), region.center());
        assertEquals(RememberAbility.DEFAULT_REGION_RADIUS, region.radiusBlocks());
        assertTrue(region.contains(new WorldPosition(15, 64, -5, "minecraft:overworld")));
        assertTrue(memory.place("基地").isEmpty(), "圈地盘不顺手记成地点：两本账");
    }

    @Test
    void regionCanBeCenteredOnACoordinateWithAGivenRadius() {
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class,
                ability.decide(step(goal(new Target.Position(100, 70, 100, null),
                        "{\"name\":\"农场\",\"operation\":\"region\",\"radius\":20}"))));
        runToFinish(run);
        RememberedRegion region = memory.region("农场").orElseThrow();
        assertEquals(new WorldPosition(100, 70, 100, "minecraft:overworld"), region.center(), "没写维度按此刻所在维度");
        assertEquals(20, region.radiusBlocks());
    }

    @Test
    void radiusOutOfRangeIsRejectedByTheParameterCheck() {
        assertFalse(ability.spec().paramSpecs().parse(JsonParser.parseString(
                "{\"operation\":\"forget_area\",\"radius\":513}").getAsJsonObject()).ok());
        assertFalse(ability.spec().paramSpecs().parse(JsonParser.parseString(
                "{\"name\":\"小地盘\",\"operation\":\"region\",\"radius\":0}").getAsJsonObject()).ok());
        // 圈地盘超过 64 格在决定阶段拒绝，512 只开放给 forget_area。
        StepDecision.Finish regionTooBig = assertInstanceOf(StepDecision.Finish.class,
                ability.decide(step(goal(null, "{\"name\":\"大地盘\",\"operation\":\"region\",\"radius\":65}"))));
        assertEquals(Problem.Kind.INVALID_PARAMETER, regionTooBig.result().problem().kind());
    }

    @Test
    void radiusWithoutRegionIsAParameterError() {
        // 给了半径却没写 region：只记一个点会让 LLM 以为那片受保护了，如实报参数错，什么也不记。
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                ability.decide(step(goal(null, "{\"name\":\"基地\",\"radius\":12}"))));
        assertEquals(Problem.Kind.INVALID_PARAMETER, finish.result().problem().kind());
        assertTrue(memory.place("基地").isEmpty());
        assertTrue(memory.region("基地").isEmpty());
    }

    @Test
    void regionOverwriteReportsTheOldShape() {
        memory.rememberRegion("基地", new WorldPosition(0, 64, 0, "minecraft:overworld"), 8);

        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class,
                ability.decide(step(goal(null, "{\"name\":\"基地\",\"operation\":\"region\",\"radius\":16}"))));
        TaskResult result = runToFinish(run);
        RememberPlaceTask.RememberDetails details =
                assertInstanceOf(RememberPlaceTask.RememberDetails.class, result.details());
        assertEquals(new WorldPosition(0, 64, 0, "minecraft:overworld"), details.previousPosition());
        assertEquals(8, details.previousRadius());
        assertEquals(16, details.radius());
        assertEquals(16, memory.region("基地").orElseThrow().radiusBlocks());
    }

    @Test
    void theSameRegionAgainFinishesWithoutWriting() {
        memory.rememberRegion("基地", new WorldPosition(12, 64, -8, "minecraft:overworld"), 8);

        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                ability.decide(step(goal(null, "{\"name\":\"基地\",\"operation\":\"region\"}"))));
        assertEquals(TaskResult.Status.DONE, finish.result().status());
        assertTrue(finish.result().summary().contains("本来就这样记着"));
    }

    @Test
    void forgettingRemovesThePlaceAndTheRegionOfTheSameName() {
        memory.remember("基地", new WorldPosition(12, 64, -8, "minecraft:overworld"));
        memory.rememberRegion("基地", new WorldPosition(12, 64, -8, "minecraft:overworld"), 8);
        memory.rememberRegion("农场", new WorldPosition(100, 70, 100, "minecraft:overworld"), 20);

        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class,
                ability.decide(step(goal(null, "{\"name\":\"基地\",\"operation\":\"forget\"}"))));
        TaskResult result = runToFinish(run);
        assertTrue(result.summary().contains("地点和地盘"), result.summary());
        assertTrue(memory.place("基地").isEmpty());
        assertTrue(memory.region("基地").isEmpty());
        assertTrue(memory.region("农场").isPresent(), "只忘名字对得上的那一块");
        RememberPlaceTask.RememberDetails details =
                assertInstanceOf(RememberPlaceTask.RememberDetails.class, result.details());
        assertEquals(8, details.forgottenRegion().radius());
    }

    @Test
    void forgettingOnlyARegionWorksWithoutAPlace() {
        memory.rememberRegion("农场", new WorldPosition(100, 70, 100, "minecraft:overworld"), 20);

        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class,
                ability.decide(step(goal(null, "{\"name\":\"农场\",\"operation\":\"forget\"}"))));
        TaskResult result = runToFinish(run);
        assertTrue(result.summary().contains("地盘「农场」"), result.summary());
        assertTrue(memory.regions().isEmpty());
    }

    @Test
    void forgettingAnUnknownNameAlsoListsRegions() {
        memory.rememberRegion("农场", new WorldPosition(100, 70, 100, "minecraft:overworld"), 20);

        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                ability.decide(step(goal(null, "{\"name\":\"基地\",\"operation\":\"forget\"}"))));
        assertEquals(Problem.Kind.NOT_FOUND, finish.result().problem().kind());
        assertTrue(finish.result().problem().message().contains("农场"),
                "失败要说清圈过哪些地盘：" + finish.result().problem().message());
    }

    @Test
    void forgetAreaClearsPlacesAndAutoRecordsWhileKeepingRegionsArchivesAndTemporaryBlocks() {
        Instant now = Instant.ofEpochMilli(1000);
        WorldPosition near = new WorldPosition(15, 64, -10, "minecraft:overworld");
        WorldPosition far = new WorldPosition(300, 64, 300, "minecraft:overworld");
        WorldPosition nether = new WorldPosition(12, 64, -8, "minecraft:the_nether");
        memory.remember("老树场", near);
        memory.remember("远处矿洞", far);
        memory.remember("下界门", nether);
        memory.rememberRegion("营地地盘", near, 10);
        memory.rememberContainerSeen(near, "minecraft:chest", now);
        memory.workstationSeen(near, "minecraft:crafting_table", now);
        memory.rememberSite(near, List.of("minecraft:oak_log"), now);
        memory.rememberBuilding(near, "小木屋", now);
        memory.rememberMachine(near, "刷铁机", now);
        memory.rememberTemporaryBlock(near, "minecraft:dirt", "施工", now);

        // 缺 radius 或误传 name 都按参数错拒绝。
        assertEquals(Problem.Kind.INVALID_PARAMETER, assertInstanceOf(StepDecision.Finish.class,
                ability.decide(step(goal(null, "{\"operation\":\"forget_area\"}")))).result().problem().kind());
        assertEquals(Problem.Kind.INVALID_PARAMETER, assertInstanceOf(StepDecision.Finish.class,
                ability.decide(step(goal(null, "{\"operation\":\"forget_area\",\"name\":\"老树场\",\"radius\":32}"))))
                .result().problem().kind());

        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class,
                ability.decide(step(goal(null, "{\"operation\":\"forget_area\",\"radius\":32}"))));
        TaskResult result = runToFinish(run);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        RememberPlaceTask.RememberDetails details =
                assertInstanceOf(RememberPlaceTask.RememberDetails.class, result.details());
        assertEquals(1, details.forgotten().places().size());
        assertEquals("老树场", details.forgotten().places().getFirst().name());
        assertEquals(near, details.forgotten().places().getFirst().position());
        assertEquals(1, details.forgotten().container());
        assertEquals(1, details.forgotten().workstation());
        assertEquals(1, details.forgotten().site());

        // 范围内命名地点与自动记录已清；远处、异维度、地盘、建筑机器档案、临时方块都在。
        assertTrue(memory.places().get("老树场") == null);
        assertTrue(memory.place("远处矿洞").isPresent());
        assertTrue(memory.place("下界门").isPresent());
        assertTrue(memory.region("营地地盘").isPresent());
        assertTrue(memory.place("小木屋").isPresent());
        assertTrue(memory.place("刷铁机").isPresent());
        assertEquals(1, memory.temporaryBlocks().size());
        assertTrue(memory.recordAt(MemoryKind.CONTAINER, near).isEmpty());
    }

    @Test
    void stationOperationRecordsUpdatesSkipsUnchangedAndForgetsCleanly() {
        memory.remember("取货箱地标", new WorldPosition(20, 64, -8, "minecraft:overworld"));

        // 标注箱式加工站：input 用无维度方块观察编号 b3（应自动补齐当前维度）、output 用 landmark，via 默认 smelt。
        StepDecision.Run create = assertInstanceOf(StepDecision.Run.class,
                ability.decide(step(goal(null,
                        "{\"operation\":\"station\",\"name\":\"鼓风熔炼站\",\"input\":\"b3\",\"output\":\"取货箱地标\","
                                + "\"exclude\":[\"#maicraft:foods/edible\",\"minecraft:kelp\"]}"))));
        TaskResult created = runToFinish(create);
        assertEquals(TaskResult.Status.DONE, created.status(), created.summary());
        RememberedStation saved = memory.station("鼓风熔炼站").orElseThrow();
        assertEquals("smelt", saved.via());
        assertEquals(new WorldPosition(0, 64, -12, "minecraft:overworld"), saved.input());
        assertEquals(new WorldPosition(20, 64, -8, "minecraft:overworld"), saved.output());
        assertEquals(List.of("#maicraft:foods/edible", "minecraft:kelp"), saved.exclude());

        // 同配置再次标注直接返回"本来就这样记着"，不启动写入任务。
        StepDecision.Finish unchanged = assertInstanceOf(StepDecision.Finish.class,
                ability.decide(step(goal(null,
                        "{\"operation\":\"station\",\"name\":\"鼓风熔炼站\",\"input\":\"b3\",\"output\":\"取货箱地标\","
                                + "\"exclude\":[\"#maicraft:foods/edible\",\"minecraft:kelp\"]}"))));
        assertEquals(TaskResult.Status.DONE, unchanged.result().status());
        assertTrue(unchanged.result().summary().contains("本来就这样记着"), unchanged.result().summary());

        // 允许进出料口为同一容器坐标，并同名覆盖记下旧配置。
        StepDecision.Run overwrite = assertInstanceOf(StepDecision.Run.class,
                ability.decide(step(goal(null,
                        "{\"operation\":\"station\",\"name\":\"鼓风熔炼站\",\"input\":\"[5,64,5]\",\"output\":\"[5,64,5]\"}"))));
        TaskResult updated = runToFinish(overwrite);
        RememberPlaceTask.RememberDetails updateDetails =
                assertInstanceOf(RememberPlaceTask.RememberDetails.class, updated.details());
        assertEquals(new WorldPosition(0, 64, -12, "minecraft:overworld"), updateDetails.previousStation().input());
        assertTrue(memory.station("鼓风熔炼站").orElseThrow().sameContainer());

        // forget_area 不误删人工标注的箱式加工站。
        StepDecision.Run areaClear = assertInstanceOf(StepDecision.Run.class,
                ability.decide(step(goal(null, "{\"operation\":\"forget_area\",\"radius\":64}"))));
        runToFinish(areaClear);
        assertTrue(memory.station("鼓风熔炼站").isPresent());

        // forget 查不到名字时把标注的加工站一并列出来。
        StepDecision.Finish missing = assertInstanceOf(StepDecision.Finish.class,
                ability.decide(step(goal(null, "{\"operation\":\"forget\",\"name\":\"不存在的站\"}"))));
        assertEquals(Problem.Kind.NOT_FOUND, missing.result().problem().kind());
        assertTrue(missing.result().problem().message().contains("鼓风熔炼站"),
                missing.result().problem().message());

        // forget 按名删除已标注的加工站。
        StepDecision.Run forget = assertInstanceOf(StepDecision.Run.class,
                ability.decide(step(goal(null, "{\"operation\":\"forget\",\"name\":\"鼓风熔炼站\"}"))));
        TaskResult forgotten = runToFinish(forget);
        assertEquals(TaskResult.Status.DONE, forgotten.status());
        assertTrue(forgotten.summary().contains("加工站「鼓风熔炼站」"), forgotten.summary());
        assertTrue(memory.station("鼓风熔炼站").isEmpty());
    }

    @Test
    void stationParameterValidationRejectsMissingOrMisusedFields() {
        // 缺 input/output 或给 here 均按参数错误拒绝。
        assertEquals(Problem.Kind.INVALID_PARAMETER, assertInstanceOf(StepDecision.Finish.class,
                ability.decide(step(goal(null, "{\"operation\":\"station\",\"name\":\"空站\",\"input\":\"[1,64,1]\"}"))))
                .result().problem().kind());
        assertEquals(Problem.Kind.INVALID_PARAMETER, assertInstanceOf(StepDecision.Finish.class,
                ability.decide(step(goal(null,
                        "{\"operation\":\"station\",\"name\":\"脚下站\",\"input\":\"here\",\"output\":\"[1,64,1]\"}"))))
                .result().problem().kind());
        // 非方块观察编号（e#/f#/s#）按参数错误拒绝。
        assertEquals(Problem.Kind.INVALID_PARAMETER, assertInstanceOf(StepDecision.Finish.class,
                ability.decide(step(goal(null,
                        "{\"operation\":\"station\",\"name\":\"实体站\",\"input\":\"e7\",\"output\":\"b4\"}"))))
                .result().problem().kind());
        // 无维度 b# 补齐当前维度后，与跨维度地标混用应命中跨维度拦截。
        memory.remember("下界取货箱", new WorldPosition(10, 64, 10, "minecraft:the_nether"));
        assertEquals(Problem.Kind.INVALID_PARAMETER, assertInstanceOf(StepDecision.Finish.class,
                ability.decide(step(goal(null,
                        "{\"operation\":\"station\",\"name\":\"跨维站\",\"input\":\"b3\",\"output\":\"下界取货箱\"}"))))
                .result().problem().kind());
        // 角色不在世界时，无维度 b# 无法补齐维度应报 NOT_POSSIBLE_HERE。
        RememberAbility offWorld = new RememberAbility(memory, () -> null,
                id -> "b3".equals(id) ? Optional.of(WorldPosition.here(0, 64, -12)) : Optional.empty());
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, assertInstanceOf(StepDecision.Finish.class,
                offWorld.decide(step(goal(null,
                        "{\"operation\":\"station\",\"name\":\"离线站\",\"input\":\"b3\",\"output\":\"[1,64,1]\"}"))))
                .result().problem().kind());
        // 非 station 操作不允许携带 input/output/via/exclude。
        assertEquals(Problem.Kind.INVALID_PARAMETER, assertInstanceOf(StepDecision.Finish.class,
                ability.decide(step(goal(null, "{\"name\":\"家\",\"input\":\"[1,64,1]\"}"))))
                .result().problem().kind());
    }
}
