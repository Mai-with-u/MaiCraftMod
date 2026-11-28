// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.deposit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.acquire.sources.ContainerBatchTakes;
import org.maiwithu.maicraft.behavior.acquire.sources.MenuContainerTakes;
import org.maiwithu.maicraft.behavior.inventory.ItemsToKeep;
import org.maiwithu.maicraft.behavior.inventory.containers.ContainerChooser;
import org.maiwithu.maicraft.behavior.inventory.containers.RareLootNotice;
import org.maiwithu.maicraft.behavior.inventory.containers.SpotsContainers;
import org.maiwithu.maicraft.behavior.inventory.containers.StoresInContainer;
import org.maiwithu.maicraft.behavior.inventory.spi.NetworkStorage;
import org.maiwithu.maicraft.behavior.menu.MenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.permission.OthersThingsNotice;
import org.maiwithu.maicraft.behavior.permission.OthersThingsTouched;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsBlockOwnership;
import org.maiwithu.maicraft.behavior.permission.ReadsOwnHome;
import org.maiwithu.maicraft.behavior.permission.SeesVillagers;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.event.EventFacts;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * {@code deposit} 带 {@code source} 参数从指定容器或区域箱子搬出并转存进终端或目标容器的单元测试。
 */
class DepositSourceTaskTest {

    private static final String DIRT = "minecraft:dirt";
    private static final String SAND = "minecraft:sand";
    private static final String STONE = "minecraft:stone";
    private static final String COBBLE = "minecraft:cobblestone";
    private static final String OAK_LOG = "minecraft:oak_log";
    private static final String STICK = "minecraft:stick";
    private static final String PICKAXE = "minecraft:iron_pickaxe";
    private static final String SWORD = "minecraft:iron_sword";
    private static final String BREAD = "minecraft:bread";

    private static final WorldPosition SOURCE_AT = WorldPosition.here(2, 64, 0);
    private static final WorldPosition TARGET_AT = WorldPosition.here(9, 64, 0);

    @TempDir
    Path tempDir;

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void singleChestSourceEmptiesMultiRoundIntoNetworkWhilePreservingCarriedItems() {
        SourceRig rig = new SourceRig(tempDir);
        // 角色原本随身带铁镐、面包、圆石和 31 组木棍（占 34 格，只留 2 个空槽中转），副手还拿着沙子。
        rig.carry(PICKAXE, 1).carry(BREAD, 5).carry(COBBLE, 64);
        for (int i = 0; i < 31; i++) {
            rig.carry(STICK, 64);
        }
        rig.offhand = stack(SAND, 5);

        // 来源箱子里有 2 组泥土、1 把铁剑、3 个面包（共 4 格，超过角色背包仅剩的 2 个中转空格）。
        BidirectionalChest sourceChest = rig.chest(SOURCE_AT, "minecraft:chest", false);
        sourceChest.put(0, DIRT, 64);
        sourceChest.put(1, DIRT, 64);
        sourceChest.put(2, SWORD, 1);
        sourceChest.put(3, BREAD, 3);

        FakeNetwork network = new FakeNetwork(rig);
        network.wireless = true;
        rig.network = network;

        DepositInput input = new DepositInput(
                null, new Target.Position(SOURCE_AT.x(), SOURCE_AT.y(), SOURCE_AT.z(), null),
                List.of(), null, 32, Permissions.DEFAULT, true);
        TaskResult result = rig.run(input);

        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(0, sourceChest.totalAll(), "来源箱子分两轮被彻底搬空");
        assertEquals(2, rig.opens, "只剩 2 个空格装 4 格物品，自动分两轮打开来源箱子");
        assertEquals(2, network.used.size(), "每一轮拿出的货都及时存进终端腾出空格");
        assertEquals(128, network.stored.getOrDefault(DIRT, 0));
        assertEquals(1, network.stored.getOrDefault(SWORD, 0), "来源箱子里的剑在整箱搬空时也照实存入网络");
        assertEquals(3, network.stored.getOrDefault(BREAD, 0), "来源箱子里的面包在整箱搬空时也照实存入网络");

        // 角色原本随身带的所有物品与副手物品一件不少。
        assertEquals(1, rig.carried(PICKAXE));
        assertEquals(5, rig.carried(BREAD));
        assertEquals(64, rig.carried(COBBLE));
        assertEquals(31 * 64, rig.carried(STICK));
        assertEquals(5, rig.offhand.getCount());

        DepositDetails details = assertInstanceOf(DepositDetails.class, result.details());
        assertNotNull(details.takenFrom());
        assertEquals(1, details.takenFrom().size());
        assertTrue(details.takenFrom().getFirst().contains(DIRT + " ×128"), details.takenFrom().toString());
    }

    @Test
    void areaLandmarkSourceEmptiesChestsInDistanceOrderIntoNetworkAndSkipsFurnace() {
        SourceRig rig = new SourceRig(tempDir);
        rig.carry(PICKAXE, 1).carry(COBBLE, 64);
        WorldPosition home = WorldPosition.here(10, 64, 10);
        rig.landmarks.put("家", home);

        // 熔炉离得最近（距离 1），近箱子距离 2，远箱子距离 8：熔炉必须跳过，两只箱子按由近到远依次搬空。
        BidirectionalChest furnace = rig.chest(WorldPosition.here(11, 64, 10), "minecraft:furnace", false);
        furnace.put(0, "minecraft:coal", 16);

        BidirectionalChest nearChest = rig.chest(WorldPosition.here(12, 64, 10), "minecraft:chest", false);
        nearChest.put(0, DIRT, 32);

        BidirectionalChest farBarrel = rig.chest(WorldPosition.here(18, 64, 10), "minecraft:barrel", false);
        farBarrel.put(0, SAND, 20);

        FakeNetwork network = new FakeNetwork(rig);
        network.wireless = true;
        rig.network = network;

        DepositInput input = new DepositInput(
                null, new Target.Landmark("家"), List.of(), null, 16, Permissions.DEFAULT, true);
        TaskResult result = rig.run(input);

        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(16, furnace.total("minecraft:coal"), "熔炉是加工台子，按区域整理时不碰它");
        assertEquals(0, nearChest.totalAll());
        assertEquals(0, farBarrel.totalAll());
        assertEquals(List.of(nearChest.at, farBarrel.at), rig.openedPositions, "按离地标由近到远依次打开两只储物容器");
        assertEquals(32, network.stored.getOrDefault(DIRT, 0));
        assertEquals(20, network.stored.getOrDefault(SAND, 0));
        assertEquals(64, rig.carried(COBBLE), "角色原本随身带的圆石保留不动");
    }

    @Test
    void sourceWithItemsTagAndCountMovesOnlyMatchingItemsUpToCount() {
        SourceRig rig = new SourceRig(tempDir);
        rig.carry(DIRT, 10);

        BidirectionalChest sourceChest = rig.chest(SOURCE_AT, "minecraft:chest", false);
        sourceChest.put(0, OAK_LOG, 32);
        sourceChest.put(1, DIRT, 32);
        sourceChest.put(2, STONE, 64);

        BidirectionalChest targetChest = rig.chest(TARGET_AT, "minecraft:chest", false);

        DepositInput input = new DepositInput(
                new Target.Position(TARGET_AT.x(), TARGET_AT.y(), TARGET_AT.z(), null),
                new Target.Position(SOURCE_AT.x(), SOURCE_AT.y(), SOURCE_AT.z(), null),
                List.of("#minecraft:logs", DIRT), 40, 32, Permissions.DEFAULT, false);
        TaskResult result = rig.run(input);

        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(32, targetChest.total(OAK_LOG));
        assertEquals(8, targetChest.total(DIRT));
        assertEquals(0, targetChest.total(STONE));
        assertEquals(24, sourceChest.total(DIRT), "来源箱子里超出 count=40 的 24 件泥土留在原箱");
        assertEquals(64, sourceChest.total(STONE), "未匹配的石头留在原箱");
        assertEquals(10, rig.carried(DIRT), "角色原本随身带的 10 件泥土不混入搬运账");
    }

    @Test
    void targetFullMidwayStopsFurtherRoundsAndReportsRemainingInBackpack() {
        SourceRig rig = new SourceRig(tempDir);
        // 留 2 个空槽给中转，其余 34 格装满木棍。
        for (int i = 0; i < 34; i++) {
            rig.carry(STICK, 64);
        }

        BidirectionalChest sourceChest = rig.chest(SOURCE_AT, "minecraft:chest", false);
        sourceChest.put(0, DIRT, 64);
        sourceChest.put(1, DIRT, 64);
        sourceChest.put(2, DIRT, 64);

        // 目标箱子只留 1 格空位（只能装下 64 件），其余 26 格塞满石头。
        BidirectionalChest targetChest = rig.chest(TARGET_AT, "minecraft:chest", false);
        targetChest.fill(STONE);
        targetChest.slots.set(0, ItemStack.EMPTY);

        DepositInput input = new DepositInput(
                new Target.Position(TARGET_AT.x(), TARGET_AT.y(), TARGET_AT.z(), null),
                new Target.Position(SOURCE_AT.x(), SOURCE_AT.y(), SOURCE_AT.z(), null),
                List.of(), null, 32, Permissions.DEFAULT, false);
        TaskResult result = rig.run(input);

        assertEquals(TaskResult.Status.PARTIAL, result.status(), result.summary());
        assertEquals(64, stored(result));
        assertEquals(64, targetChest.total(DIRT));
        assertEquals(64, sourceChest.total(DIRT), "第一轮没存完就立即停止，不再回头开来源箱子搬第三组");
        assertEquals(64, rig.carried(DIRT), "第一轮没塞进目标箱子的 64 件留在背包并如实报余量");
        assertTrue(result.remaining().stream().anyMatch(line -> line.contains(DIRT) && line.contains("64")),
                result.remaining().toString());
    }

    @Test
    void fullBackpackCannotTransitAndFailsWithInventoryFull() {
        SourceRig rig = new SourceRig(tempDir);
        for (int i = 0; i < 36; i++) {
            rig.carry(STICK, 64);
        }
        BidirectionalChest sourceChest = rig.chest(SOURCE_AT, "minecraft:chest", false);
        sourceChest.put(0, DIRT, 32);

        FakeNetwork network = new FakeNetwork(rig);
        network.wireless = true;
        rig.network = network;

        DepositInput input = new DepositInput(
                null, new Target.Position(SOURCE_AT.x(), SOURCE_AT.y(), SOURCE_AT.z(), null),
                List.of(), null, 32, Permissions.DEFAULT, true);
        TaskResult result = rig.run(input);

        assertEquals(TaskResult.Status.FAILED, result.status(), result.summary());
        assertEquals(Problem.Kind.INVENTORY_FULL, result.problem().kind());
        assertEquals(32, sourceChest.total(DIRT));
    }

    @Test
    void rejectsMalformedSourceAreaToAreaShufflingAndSameContainerSelfMove() {
        SourceRig rig = new SourceRig(tempDir);
        DepositModule module = new DepositModule(rig.services(), rig.backpack());

        // 1. 模块层拒绝残缺坐标写法。
        ParamValues badParams = module.spec().paramSpecs()
                .parse(JsonParser.parseString("{\"source\":\"[1,2]\"}").getAsJsonObject())
                .params();
        StepDecision malformed = module.decide(stepOf(Goal.of("maicraft:deposit", null, badParams), rig.tick()));
        StepDecision.Finish malformedFinish = assertInstanceOf(StepDecision.Finish.class, malformed);
        assertEquals(Problem.Kind.INVALID_PARAMETER, malformedFinish.result().problem().kind());

        // 2. 区域 source 未配 into=network 也未点名单个目标容器：拒绝在同一片箱子之间倒腾。
        rig.landmarks.put("家", SOURCE_AT);
        rig.chest(SOURCE_AT, "minecraft:chest", false).put(0, DIRT, 16);
        TaskResult areaShuffle = rig.run(new DepositInput(
                null, new Target.Landmark("家"), List.of(), null, 32, Permissions.DEFAULT, false));
        assertEquals(TaskResult.Status.FAILED, areaShuffle.status());
        assertEquals(Problem.Kind.INVALID_PARAMETER, areaShuffle.problem().kind());

        // 3. source 与 target 指向同一格容器：拒绝自搬。
        Target sameCell = new Target.Position(SOURCE_AT.x(), SOURCE_AT.y(), SOURCE_AT.z(), null);
        TaskResult selfMove = rig.run(new DepositInput(
                sameCell, sameCell, List.of(), null, 32, Permissions.DEFAULT, false));
        assertEquals(TaskResult.Status.FAILED, selfMove.status());
        assertEquals(Problem.Kind.INVALID_PARAMETER, selfMove.problem().kind());
    }

    @Test
    void namedForeignSourceChestReportsOthersTookAndUpdatesWorldMemory() {
        SourceRig rig = new SourceRig(tempDir);
        rig.foreignOwnerUuid = "00000000-0000-0000-0000-000000000099";
        rig.foreignOwnerName = "玩家甲";

        BidirectionalChest sourceChest = rig.chest(SOURCE_AT, "minecraft:chest", true);
        sourceChest.put(0, DIRT, 16);

        FakeNetwork network = new FakeNetwork(rig);
        network.wireless = true;
        rig.network = network;

        DepositInput input = new DepositInput(
                null, new Target.Position(SOURCE_AT.x(), SOURCE_AT.y(), SOURCE_AT.z(), null),
                List.of(), null, 32, Permissions.DEFAULT, true);
        TaskResult result = rig.run(input);

        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(16, network.stored.getOrDefault(DIRT, 0));
        assertTrue(rig.memory.recordAt(MemoryKind.CONTAINER, SOURCE_AT)
                .map(record -> record.openedBefore() && record.contents().isEmpty())
                .orElse(false), "搬空后关箱前把空箱状态更新进世界记忆");
        assertEquals(1, rig.touchedEvents.size(), "点名搬空别人放的箱子时发一条 others_things_touched 事件");
        assertEquals(OthersThingsTouched.Action.TOOK, rig.touchedEvents.getFirst().action());
        assertEquals("玩家甲", rig.touchedEvents.getFirst().owner());
    }

    private static StepContext stepOf(Goal goal, TickContext tick) {
        return new StepContext() {
            @Override public Goal goal() { return goal; }
            @Override public int stepIndex() { return 0; }
            @Override public TickContext tick() { return tick; }
        };
    }

    private static int stored(TaskResult result) {
        return result.changes().stream()
                .filter(change -> change.kind() == Change.Kind.ITEM_STORED)
                .mapToInt(Change::count)
                .sum();
    }

    private static ItemStack stack(String id, int count) {
        return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)), count);
    }

    /** 离线网络存储替身：把待存批次从角色背包扣掉并记入网络存货账本。 */
    private static final class FakeNetwork implements NetworkStorage {
        private final SourceRig rig;
        boolean wireless;
        final List<String> used = new ArrayList<>();
        final Map<String, Integer> stored = new LinkedHashMap<>();

        FakeNetwork(SourceRig rig) {
            this.rig = rig;
        }

        @Override public boolean reachable(TerminalSearch search) {
            return wireless;
        }

        @Override public TerminalPick store(List<BackpackStack> stacks, String purpose, TerminalSearch search,
                TaskRecords records) {
            if (!wireless) {
                return new TerminalPick.NotFound("没有可用的 ME 终端");
            }
            return new TerminalPick.Chosen(new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    used.add("无线终端");
                    for (BackpackStack item : stacks) {
                        int moved = Math.min(item.count(), rig.carried(item.itemId()));
                        rig.takeFromPlayer(item.itemId(), moved);
                        stored.merge(item.itemId(), moved, Integer::sum);
                        records.change(new Change(Change.Kind.ITEM_STORED, item.itemId(), moved,
                                purpose + "：存进了网络"));
                    }
                    return ActionStatus.done();
                }

                @Override public String describe() {
                    return "替身存进网络";
                }
            });
        }
    }

    /** 离线测试台：包含角色 36 格背包、支持双向搬运的箱子、世界记忆与别人容器通报收集。 */
    private static final class SourceRig {
        final List<ItemStack> player = new ArrayList<>();
        final Map<WorldPosition, BidirectionalChest> chests = new LinkedHashMap<>();
        final Map<String, WorldPosition> landmarks = new HashMap<>();
        final List<WorldPosition> openedPositions = new ArrayList<>();
        final List<OthersThingsTouched> touchedEvents = new ArrayList<>();
        final WorldMemory memory;
        int opens;
        NetworkStorage network;
        ItemStack offhand = ItemStack.EMPTY;
        String foreignOwnerUuid;
        String foreignOwnerName;
        private long tick;

        SourceRig(Path tempDir) {
            for (int i = 0; i < 36; i++) {
                player.add(ItemStack.EMPTY);
            }
            this.memory = new WorldMemory(new DocumentStore(tempDir.resolve("deposit-source.sqlite")), "w".repeat(64));
        }

        SourceRig carry(String id, int count) {
            player.set(player.indexOf(ItemStack.EMPTY), stack(id, count));
            return this;
        }

        void takeFromPlayer(String id, int amount) {
            for (int i = 0; i < player.size() && amount > 0; i++) {
                ItemStack current = player.get(i);
                if (current.isEmpty() || !itemIdOf(current).equals(id)) continue;
                int delta = Math.min(amount, current.getCount());
                current.shrink(delta);
                amount -= delta;
                if (current.isEmpty()) player.set(i, ItemStack.EMPTY);
            }
        }

        int carried(String id) {
            return player.stream()
                    .filter(item -> !item.isEmpty() && itemIdOf(item).equals(id))
                    .mapToInt(ItemStack::getCount)
                    .sum();
        }

        BidirectionalChest chest(WorldPosition at, String blockTypeId, boolean ownedByOther) {
            BidirectionalChest chest = new BidirectionalChest(this, at, blockTypeId, ownedByOther);
            chests.put(at, chest);
            return chest;
        }

        DepositServices services() {
            SpotsContainers spots = new SpotsContainers() {
                @Override public List<ContainerChooser.Candidate> around(BlockPos center, int radius) {
                    return chests.values().stream()
                            .filter(c -> Math.sqrt(new BlockPos(c.at.x(), c.at.y(), c.at.z()).distSqr(center)) <= radius)
                            .map(c -> c.candidate(center))
                            .toList();
                }

                @Override public boolean scanComplete() {
                    return true;
                }

                @Override public Optional<ContainerChooser.Candidate> at(WorldPosition position) {
                    return Optional.ofNullable(chests.get(position))
                            .map(c -> c.candidate(new BlockPos(position.x(), position.y(), position.z())));
                }
            };
            DepositSeams.FindsPlaces places = new DepositSeams.FindsPlaces() {
                @Override public Optional<WorldPosition> seen(String seenId) { return Optional.empty(); }
                @Override public Optional<WorldPosition> landmark(String name) {
                    return Optional.ofNullable(landmarks.get(name));
                }
                @Override public BlockPos feet() { return BlockPos.ZERO; }
            };
            OthersThingsNotice notice = buildOthersNotice();
            StoresInContainer.Parts storing = new StoresInContainer.Parts((at, permissions) -> {
                opens++;
                WorldPosition pos = WorldPosition.here(at.getX(), at.getY(), at.getZ());
                openedPositions.add(pos);
                return new ImmediateOpening(chests.get(pos));
            }, memory, notice);
            // 来源取货走真的行为层开箱搬货：打开动作换成这里那只替身箱子，别的（取货一轮、结算、通报）都是真的。
            ContainerBatchTakes takes = new MenuContainerTakes(
                    itemId -> itemId.equals(OAK_LOG) ? Set.of("minecraft:logs") : Set.of(),
                    memory, () -> null,
                    (at, permissions) -> {
                        opens++;
                        WorldPosition pos = WorldPosition.here(at.getX(), at.getY(), at.getZ());
                        openedPositions.add(pos);
                        return new ImmediateOpening(chests.get(pos));
                    },
                    at -> false,
                    notice, RareLootNotice.NONE);
            DepositSeams.ClearsOffhand offhandSeam = new DepositSeams.ClearsOffhand() {
                @Override public Optional<BackpackStack> held() {
                    return offhand.isEmpty() ? Optional.empty() : Optional.of(toBackpackStack(offhand));
                }

                @Override public Optional<Action> intoBackpack() {
                    return Optional.of(new Action() {
                        @Override public ActionStatus tick(TickContext context) {
                            player.set(player.indexOf(ItemStack.EMPTY), offhand);
                            offhand = ItemStack.EMPTY;
                            return ActionStatus.done();
                        }

                        @Override public String describe() {
                            return "副手收回背包";
                        }
                    });
                }
            };
            return new DepositServices(
                    spots, storing, places, null,
                    itemId -> itemId.equals(OAK_LOG) ? Set.of("minecraft:logs") : Set.of(),
                    () -> Optional.ofNullable(network), offhandSeam, ItemsToKeep.NOTHING, null, takes);
        }

        private OthersThingsNotice buildOthersNotice() {
            if (foreignOwnerUuid == null) {
                return OthersThingsNotice.NONE;
            }
            Protection protection = new Protection(
                    (dim, x, y, z) -> Optional.of(new ReadsBlockOwnership.PlacedBy(foreignOwnerUuid)),
                    memory,
                    name -> Optional.empty(),
                    "00000000-0000-0000-0000-000000000001",
                    SeesVillagers.NOBODY,
                    ReadsOwnHome.NOWHERE,
                    uuid -> foreignOwnerName);
            TaskEventSink sink = new TaskEventSink() {
                @Override public void publish(TaskEvent.Kind kind, String message) {}

                @Override public void publish(TaskEvent.Kind kind, String message, EventFacts facts) {
                    if (facts instanceof OthersThingsTouched touched) {
                        touchedEvents.add(touched);
                    }
                }
            };
            return new OthersThingsNotice(protection, id -> false, sink);
        }

        BackpackView backpack() {
            return new BackpackView() {
                @Override public List<BackpackStack> stacks() {
                    List<BackpackStack> out = new ArrayList<>();
                    for (ItemStack item : player) {
                        if (!item.isEmpty()) out.add(toBackpackStack(item));
                    }
                    return out;
                }

                @Override public int usedSlots() { return stacks().size(); }
                @Override public int totalSlots() { return 36; }
            };
        }

        TaskResult run(DepositInput input) {
            DepositTask task = new DepositTask(input, services(), backpack());
            task.start(tick());
            for (int i = 0; i < 1200; i++) {
                if (task.tick(tick()) instanceof TickResult.Finished finished) {
                    return finished.result();
                }
            }
            throw new AssertionError("任务超时未结束：" + task.describe());
        }

        TickContext tick() {
            long now = ++tick;
            return new TickContext() {
                @Override public long gameTick() { return now; }
                @Override public PlayerContext player() { return null; }
            };
        }

        private static BackpackStack toBackpackStack(ItemStack item) {
            String id = itemIdOf(item);
            return new BackpackStack(id, item.getCount(), item.getMaxStackSize(),
                    id.endsWith("_pickaxe") || id.endsWith("_sword"), id.equals(BREAD), false, id.equals(COBBLE));
        }

        private static String itemIdOf(ItemStack item) {
            return BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
        }
    }

    /** 支持双向快速移动与拆堆点击的 27 格替身容器：既能当来源箱搬出，也能当目标箱存入。 */
    private static final class BidirectionalChest implements OpenedMenu {
        private final SourceRig rig;
        final WorldPosition at;
        private final String blockTypeId;
        private final boolean ownedByOther;
        final List<ItemStack> slots = new ArrayList<>();
        ItemStack cursor = ItemStack.EMPTY;
        boolean open;

        BidirectionalChest(SourceRig rig, WorldPosition at, String blockTypeId, boolean ownedByOther) {
            this.rig = rig;
            this.at = at;
            this.blockTypeId = blockTypeId;
            this.ownedByOther = ownedByOther;
            for (int i = 0; i < 27; i++) {
                slots.add(ItemStack.EMPTY);
            }
        }

        void put(int slotIndex, String itemId, int count) {
            slots.set(slotIndex, stack(itemId, count));
        }

        void fill(String itemId) {
            for (int i = 0; i < slots.size(); i++) {
                slots.set(i, stack(itemId, 64));
            }
        }

        int total(String itemId) {
            return slots.stream()
                    .filter(s -> !s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(itemId))
                    .mapToInt(ItemStack::getCount)
                    .sum();
        }

        int totalAll() {
            return slots.stream().filter(s -> !s.isEmpty()).mapToInt(ItemStack::getCount).sum();
        }

        ContainerChooser.Candidate candidate(BlockPos from) {
            double dist = Math.sqrt(new BlockPos(at.x(), at.y(), at.z()).distSqr(from));
            boolean free = slots.stream().anyMatch(ItemStack::isEmpty);
            return new ContainerChooser.Candidate(
                    "容器" + at.x(), blockTypeId, at, null, free, dist,
                    ownedByOther, true, ContainerChooser.Lid.CLEAR, false);
        }

        @Override public Optional<MenuContent.Reading> reading() {
            if (!open) return Optional.empty();
            List<Integer> playerIds = new ArrayList<>();
            List<Integer> playerIndices = new ArrayList<>();
            List<SlotSnapshot> playerSnapshots = new ArrayList<>();
            for (int i = 0; i < 36; i++) {
                playerIds.add(i);
                playerIndices.add(i);
                ItemStack p = rig.player.get(i);
                playerSnapshots.add(p.isEmpty() ? SlotSnapshot.empty() : SlotSnapshot.of(p.copy()));
            }
            List<Integer> chestIds = new ArrayList<>();
            List<SlotSnapshot> chestSnapshots = new ArrayList<>();
            for (int i = 0; i < slots.size(); i++) {
                chestIds.add(36 + i);
                ItemStack c = slots.get(i);
                chestSnapshots.add(c.isEmpty() ? SlotSnapshot.empty() : SlotSnapshot.of(c.copy()));
            }
            return Optional.of(new MenuContent.Reading(
                    CHANNEL, SLOTS, chestIds, playerIds, playerIndices, chestSnapshots, playerSnapshots));
        }

        @Override public boolean busy() { return false; }
        @Override public boolean cursorEmpty() { return cursor.isEmpty(); }
        @Override public SlotSnapshot cursor() {
            return cursor.isEmpty() ? SlotSnapshot.empty() : SlotSnapshot.of(cursor);
        }

        @Override public boolean quickMove(int slotId) {
            if (slotId < 36) {
                moveInto(rig.player, slotId, slots);
            } else {
                moveInto(slots, slotId - 36, rig.player);
            }
            return true;
        }

        private static void moveInto(List<ItemStack> fromSide, int fromIndex, List<ItemStack> toSide) {
            ItemStack moving = fromSide.get(fromIndex);
            if (moving.isEmpty()) return;
            for (ItemStack dest : toSide) {
                if (!dest.isEmpty() && ItemStack.isSameItemSameComponents(dest, moving)) {
                    int room = dest.getMaxStackSize() - dest.getCount();
                    int moved = Math.min(room, moving.getCount());
                    dest.grow(moved);
                    moving.shrink(moved);
                }
            }
            for (int i = 0; i < toSide.size() && !moving.isEmpty(); i++) {
                if (toSide.get(i).isEmpty()) {
                    toSide.set(i, moving.copy());
                    moving.setCount(0);
                }
            }
            if (moving.isEmpty()) {
                fromSide.set(fromIndex, ItemStack.EMPTY);
            }
        }

        @Override public boolean click(int slotId, int button) {
            List<ItemStack> side = slotId < 36 ? rig.player : slots;
            int index = slotId < 36 ? slotId : slotId - 36;
            ItemStack slot = side.get(index);
            if (cursor.isEmpty()) {
                int take = button == 0 ? slot.getCount() : (slot.getCount() + 1) / 2;
                cursor = slot.split(take);
                if (slot.isEmpty()) side.set(index, ItemStack.EMPTY);
                return true;
            }
            int give = button == 0 ? cursor.getCount() : 1;
            if (slot.isEmpty()) {
                side.set(index, cursor.split(give));
            } else if (ItemStack.isSameItemSameComponents(slot, cursor)) {
                int moved = Math.min(give, slot.getMaxStackSize() - slot.getCount());
                slot.grow(moved);
                cursor.shrink(moved);
            }
            if (cursor.isEmpty()) cursor = ItemStack.EMPTY;
            return true;
        }

        @Override public void noteCursorTakenFrom(int slotId) {}

        @Override public Action closing() {
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    open = false;
                    return ActionStatus.done();
                }

                @Override public String describe() {
                    return "关上替身容器";
                }
            };
        }

        @Override public void abandon() {
            open = false;
        }
    }

    private record ImmediateOpening(BidirectionalChest chest) implements MenuOpening {
        @Override public Optional<OpenedMenu> opened() {
            return chest != null && chest.open ? Optional.of(chest) : Optional.empty();
        }

        @Override public ActionStatus tick(TickContext context) {
            if (chest == null) {
                return ActionStatus.failed(Problem.of(Problem.Kind.NOT_FOUND, "那一格没有容器", null));
            }
            chest.open = true;
            return ActionStatus.done();
        }

        @Override public String describe() {
            return "打开替身容器";
        }
    }

    private static final MenuChannel CHANNEL = new MenuChannel() {
        @Override public boolean stillOpen() { return true; }
        @Override public boolean cursorCarrying() { return false; }
        @Override public SlotSnapshot cursor() { return SlotSnapshot.empty(); }
        @Override public boolean click(int slot, int button) { return true; }
        @Override public boolean quickMove(int slot) { return true; }
        @Override public boolean selectTradeOffer(int offerIndex) { return true; }
        @Override public void requestClose() {}
    };

    private static final MenuSlots SLOTS = new MenuSlots() {
        @Override public String menuTypeId() { return "minecraft:generic_9x3"; }
        @Override public int slotCount() { return 63; }
        @Override public boolean playerBacked(int slot) { return slot < 36; }
        @Override public int playerInventoryIndex(int slot) { return slot; }
    };
}
