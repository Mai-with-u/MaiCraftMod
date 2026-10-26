// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.obtain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.ability.obtain.ObtainItems;
import org.maiwithu.maicraft.ability.obtain.ObtainTask;
import org.maiwithu.maicraft.behavior.acquire.FakeTags;
import org.maiwithu.maicraft.behavior.acquire.StubTick;
import org.maiwithu.maicraft.behavior.acquire.engine.ItemAcquisition;
import org.maiwithu.maicraft.behavior.acquire.engine.OffhandContents;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.acquire.sources.ContainerSource;
import org.maiwithu.maicraft.behavior.inventory.containers.ContainerChooser;
import org.maiwithu.maicraft.behavior.inventory.containers.KnownContainer;
import org.maiwithu.maicraft.behavior.inventory.containers.SpotsContainers;
import org.maiwithu.maicraft.behavior.inventory.containers.StandingContainers;
import org.maiwithu.maicraft.behavior.permission.GuessesPlayerMade;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 坐标早已不对的记得箱：内层做法引用它时，现场对不上就把这条记忆划掉、这一条路如实失败换下一种，
 * 临时任务在有限步内带结论收场，不再奔着一个不存在的格子反复走（实机备床被一条 y 为负的记得箱
 * 拖住二十多分钟）；正常记得箱的路线照旧能走通。
 */
class StaleRememberedChestTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    /** 实机那条坏记忆：地面上不存在这一格，加载后是石头。 */
    private static final WorldPosition PHANTOM = new WorldPosition(104, -43, 19, null);
    private static final WorldPosition GOOD_CHEST = new WorldPosition(147, 63, 78, null);

    @TempDir
    Path temp;

    private final FakeTags tags = new FakeTags();
    /** 开箱取物的接缝被谁动过：坏记忆不该走到这一步。 */
    private final AtomicReference<KnownContainer> taken = new AtomicReference<>();

    /** 背包替身：一张可以改的格子表。 */
    static final class FakeBackpack implements BackpackView {
        final List<BackpackStack> stacks = new ArrayList<>();

        @Override public List<BackpackStack> stacks() {
            return List.copyOf(stacks);
        }

        @Override public int usedSlots() {
            return stacks.size();
        }

        @Override public int totalSlots() {
            return 36;
        }
    }

    /** 现场替身：表里登记的格子给登记的方块，其余一律没加载。 */
    private static StandingContainers.ReadsCells cellsAt(Map<WorldPosition, String> loaded) {
        return at -> Optional.ofNullable(loaded.get(at));
    }

    /**
     * 找容器读端替身，记忆那一半与生产同一套现场核对（StandingContainers）：
     * 现场对不上的记忆不再成为候选，这正是生产端找容器读端的并法。
     */
    private SpotsContainers spots(WorldMemory memory, Map<WorldPosition, String> loaded) {
        StandingContainers standing = new StandingContainers(memory, cellsAt(loaded), "minecraft:chest"::equals);
        return new SpotsContainers() {
            @Override public List<ContainerChooser.Candidate> around(BlockPos center, int radius) {
                List<ContainerChooser.Candidate> found = new ArrayList<>();
                WorldPosition from = WorldPosition.here(center.getX(), center.getY(), center.getZ());
                for (var record : standing.near(from, radius)) {
                    found.add(candidate(record.position(), record.blockType(),
                            record.openedBefore() ? record.contents() : null, from));
                }
                return found;
            }

            @Override public boolean scanComplete() {
                return true;
            }

            @Override public Optional<ContainerChooser.Candidate> at(WorldPosition position) {
                return standing.at(position).map(record -> candidate(record.position(), record.blockType(),
                        record.openedBefore() ? record.contents() : null,
                        WorldPosition.here(position.x(), position.y(), position.z())));
            }
        };
    }

    // 记忆里的一条容器读成候选，离出发点多远算多远；归属与盖子这些由来源那边再问。
    private static ContainerChooser.Candidate candidate(WorldPosition at, String blockType, List<String> contents,
            WorldPosition from) {
        double distance = Math.sqrt((long) (at.x() - from.x()) * (at.x() - from.x())
                + (long) (at.y() - from.y()) * (at.y() - from.y())
                + (long) (at.z() - from.z()) * (at.z() - from.z()));
        return new ContainerChooser.Candidate(
                blockType.substring(blockType.indexOf(':') + 1) + "（" + at.x() + ", " + at.y() + ", " + at.z() + "）",
                blockType, at, contents, true, distance, false, true, ContainerChooser.Lid.CLEAR, false);
    }

    private WorldMemory memoryWith(WorldPosition at, List<String> contents) {
        WorldMemory memory = new WorldMemory(new DocumentStore(temp.resolve("state.sqlite")),
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        memory.rememberContainerOpened(at, "minecraft:chest", contents, NOW);
        return memory;
    }

    /** 真引擎加真容器来路的拿东西任务：只有记得的箱子一条来路。 */
    private ObtainTask task(WorldMemory memory, Map<WorldPosition, String> loaded, FakeBackpack backpack) {
        ContainerSource source = new ContainerSource(spots(memory, loaded), tags,
                new Protection((dimension, x, y, z) -> Optional.empty(), List::of,
                        name -> Optional.empty(), GuessesPlayerMade.NOTHING, "self"),
                (container, request, permissions) -> {
                    taken.set(container);
                    return Optional.of(new Action() {
                        @Override public ActionStatus tick(TickContext context) {
                            backpack.stacks.add(new BackpackStack(
                                    "minecraft:wheat", 1, 64, false, false, false, false));
                            return ActionStatus.done();
                        }

                        @Override public String describe() {
                            return "开箱取物";
                        }
                    });
                });
        ItemAcquisition engine = new ItemAcquisition(List.of(source), backpack, (OffhandContents) null, tags,
                () -> WorldPosition.here(0, 63, 0), Optional.empty(), ItemAcquisition.DEFAULT_MAX_DEPTH);
        ObtainItems input = new ObtainItems(WantedItem.ofItem("minecraft:wheat"), 1, new ItemAcquisition.Scope(Set.of(), null, 200),
                Permissions.DEFAULT, "按需要拿取", "再拿 1 个 minecraft:wheat");
        return new ObtainTask(input, engine, backpack, null, tags);
    }

    private TaskResult runToFinish(ObtainTask task) {
        TickResult result = TickResult.RUNNING;
        for (int i = 1; i <= 100 && result instanceof TickResult.Running; i++) {
            result = task.tick(new StubTick(i));
        }
        return ((TickResult.Finished) result).result();
    }

    @Test
    void 坐标坏的记得箱_临时任务有限步收场且记忆划掉() {
        WorldMemory memory = memoryWith(PHANTOM, List.of("minecraft:wheat"));
        Map<WorldPosition, String> loaded = new HashMap<>();
        loaded.put(PHANTOM, "minecraft:stone");
        ObtainTask task = task(memory, loaded, new FakeBackpack());
        task.start(new StubTick(0));

        TaskResult finished = runToFinish(task);
        assertEquals(TaskResult.Status.FAILED, finished.status(), "临时任务要收场，不无限占体");
        Problem problem = finished.problem();
        assertEquals(Problem.Kind.NEED_ITEM, problem.kind());
        assertTrue(problem.message().contains("记得的箱子"), "结论里要有走不通的那条路：" + problem.message());
        assertNull(taken.get(), "坏记忆只该在问价时被淘汰，不该真去开一个不存在的箱子");
        // 事实记下：与现场对不上的记忆划掉了，下一条路线不会再撞它。
        assertTrue(memory.recordAt(MemoryKind.CONTAINER, PHANTOM).isEmpty());
    }

    @Test
    void 正常记得箱_照旧走通开箱拿货() {
        WorldMemory memory = memoryWith(GOOD_CHEST, List.of("minecraft:wheat"));
        Map<WorldPosition, String> loaded = new HashMap<>();
        loaded.put(GOOD_CHEST, "minecraft:chest");
        FakeBackpack backpack = new FakeBackpack();
        ObtainTask task = task(memory, loaded, backpack);
        task.start(new StubTick(0));

        TaskResult finished = runToFinish(task);
        assertEquals(TaskResult.Status.DONE, finished.status());
        assertEquals(GOOD_CHEST.x(), taken.get().at().x());
        assertEquals(GOOD_CHEST.z(), taken.get().at().z());
        assertEquals(1, backpack.stacks().size());
        assertEquals("minecraft:wheat", backpack.stacks().getFirst().itemId());
    }
}
