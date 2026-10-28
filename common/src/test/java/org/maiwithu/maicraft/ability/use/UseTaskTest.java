// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import org.maiwithu.maicraft.behavior.inventory.ItemsToKeep;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.behavior.inventory.InventorySpace;
import org.maiwithu.maicraft.behavior.approach.ProtectedCells;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.interaction.InteractionResult;
import org.maiwithu.maicraft.behavior.interaction.InteractionVerdict;
import org.maiwithu.maicraft.behavior.interaction.ItemUseAim;
import org.maiwithu.maicraft.behavior.interaction.SignEditor;
import org.maiwithu.maicraft.behavior.inventory.drops.PicksUpDrops;
import org.maiwithu.maicraft.behavior.perception.FacilityKinds;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.ReadsBlockTags;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.engine.ReadsBlockTypes;
import org.maiwithu.maicraft.behavior.acquire.engine.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.acquire.mining.CollectsBlocks;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.acquire.sources.ReclaimSource;
import org.maiwithu.maicraft.behavior.permission.GuessesPlayerMade;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsBlockOwnership;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用东西的任务：找目标、备手、靠近、出手、结论怎么变成结果，都对着离线替身跑。 */
class UseTaskTest {

    private static final BlockPos LEVER = new BlockPos(1, 64, 1);
    private static final String OVERWORLD = "minecraft:overworld";

    /** 世界记忆存盘用的临时目录：只有补账的测试真正用到。 */
    @TempDir
    Path temp;

    @Test
    void 放下一张床_世界记忆补上自己放下的账_收回来源认得领() {
        Rig rig = new Rig();
        rig.world.blocks.put(LEVER, "minecraft:grass_block");
        // 与实机现场一致：床放在被点草地上面的那格，确认生效靠手上少了一张。
        rig.interactions.placed("minecraft:white_bed", "床放下了",
                () -> rig.world.blocks.put(LEVER.above(), "minecraft:white_bed"));
        rig.memory = new WorldMemory(new DocumentStore(temp.resolve("state")), "world-1");
        TaskResult result = rig.run(rig.input(position(LEVER), "minecraft:white_bed", "minecraft:grass_block", null, 1));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        WorldPosition at = new WorldPosition(LEVER.above().getX(), LEVER.above().getY(), LEVER.above().getZ(),
                OVERWORLD);
        assertTrue(rig.memory.recordAt(MemoryKind.WORKSTATION, at).isPresent(), "放下的床该记成自己放下的账");
        // 收回来源认的就是这条账：现场还是那张床、不在家附近，按路程报价去挖，不再说"没有记得的"。
        ReclaimSource reclaim = reclaimOver(rig);
        SourceQuote quote = reclaim.quote(new ItemRequest(WantedItem.ofTag("minecraft:beds"), 1, "睡觉"),
                SourceContext.unscoped(WorldPosition.here(3, 64, 3), Permissions.DEFAULT));
        assertInstanceOf(SourceQuote.Offer.class, quote);
    }

    @Test
    void 放下火把这类不补账() {
        Rig rig = new Rig();
        rig.world.blocks.put(LEVER, "minecraft:grass_block");
        rig.interactions.placed("minecraft:torch", "火把插上了",
                () -> rig.world.blocks.put(LEVER.above(), "minecraft:torch"));
        rig.memory = new WorldMemory(new DocumentStore(temp.resolve("state-torch")), "world-1");
        TaskResult result = rig.run(rig.input(position(LEVER), "minecraft:torch", "minecraft:grass_block", null, 1));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertTrue(rig.memory.allRecords().isEmpty(), "火把不是放下来用的设施，不往世界记忆补账");
    }

    @Test
    void 判断_放下的床落在被点面的邻居格() {
        Rig rig = new Rig();
        rig.world.blocks.put(LEVER, "minecraft:grass_block");
        rig.world.blocks.put(LEVER.above(), "minecraft:white_bed");
        var ready = readyHolding(new UseSeams.ReadsWorld.Held("minecraft:white_bed", 1));
        var placed = PlacedFacility.judge(rig.world, ready,
                ResolvedTarget.block(LEVER, "minecraft:grass_block", "草地"), rig.facilities);
        assertEquals(LEVER.above(), placed.orElseThrow().cell());
    }

    @Test
    void 判断_放进可替换的目标格就落在目标格本身() {
        Rig rig = new Rig();
        rig.world.blocks.put(LEVER, "minecraft:white_bed");
        var ready = readyHolding(new UseSeams.ReadsWorld.Held("minecraft:white_bed", 1));
        var placed = PlacedFacility.judge(rig.world, ready,
                ResolvedTarget.block(LEVER, "minecraft:short_grass", "草"), rig.facilities);
        assertEquals(LEVER, placed.orElseThrow().cell());
    }

    @Test
    void 判断_现场找不到放下的方块就不记() {
        Rig rig = new Rig();
        rig.world.blocks.put(LEVER, "minecraft:grass_block");
        var ready = readyHolding(new UseSeams.ReadsWorld.Held("minecraft:white_bed", 1));
        assertTrue(PlacedFacility.judge(rig.world, ready,
                ResolvedTarget.block(LEVER, "minecraft:grass_block", "草地"), rig.facilities).isEmpty());
    }

    @Test
    void 判断_手上不是设施不找落格() {
        Rig rig = new Rig();
        rig.world.blocks.put(LEVER, "minecraft:grass_block");
        rig.world.blocks.put(LEVER.above(), "minecraft:dirt");
        var ready = readyHolding(new UseSeams.ReadsWorld.Held("minecraft:bone_meal", 1));
        assertTrue(PlacedFacility.judge(rig.world, ready,
                ResolvedTarget.block(LEVER, "minecraft:grass_block", "草地"), rig.facilities).isEmpty());
    }

    /** 只带出手前那只手的交互结论，给判断与放置测试用。 */
    private static UseSeams.Built.Ready readyHolding(UseSeams.ReadsWorld.Held heldBefore) {
        return new UseSeams.Built.Ready(
                new Scripted(1, ActionStatus.done(), () -> { }), () -> null, ItemUseAim.Gesture.EMPTY_HAND,
                heldBefore, 0, null);
    }

    /** 在替身世界那张床的位置上拼一个收回来源：归属记录空着（睡过的床这条路不看归属）。 */
    private ReclaimSource reclaimOver(Rig rig) {
        ReadsBlockTypes blocks = at -> {
            String type = rig.world.blocks.get(new BlockPos(at.x(), at.y(), at.z()));
            return type == null || type.equals("minecraft:air") ? Optional.empty() : Optional.of(type);
        };
        ReadsBlockOwnership ownership = new ReadsBlockOwnership() {
            @Override public Optional<PlacedBy> whoPlaced(String dimension, int x, int y, int z) {
                return Optional.empty();
            }
            @Override public boolean known(String dimension, int x, int y, int z) {
                return true;
            }
        };
        Protection protection = new Protection(ownership, () -> List.of(), name -> Optional.empty(),
                GuessesPlayerMade.NOTHING, "self");
        PermissionCheck check = new PermissionCheck(protection, new ReadsCreatureSituation() {
            @Override public Optional<CreatureSituation> situationOf(UUID entityId) {
                return Optional.empty();
            }
        });
        CollectsBlocks collects = (cell, permissions) -> Optional.of(new Action() {
            @Override public ActionStatus tick(TickContext context) {
                return ActionStatus.done();
            }
            @Override public String describe() {
                return "收 " + cell.toShortString();
            }
        });
        ReadsItemTags tags = item -> item.endsWith("_bed") ? Set.of("minecraft:beds") : Set.of();
        return new ReclaimSource(rig.memory, blocks, protection, check, collects, tags);
    }

    @Test
    void 对拉杆用一下_确认生效记变化后完成() {
        Rig rig = new Rig();
        rig.world.blocks.put(LEVER, "minecraft:lever");
        rig.interactions.applied("拉杆扳动了");
        TaskResult result = rig.run(rig.input(position(LEVER), null, "minecraft:lever", null, 1));
        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(1L, ((UseDetails) result.details()).appliedTimes());
        assertTrue(result.changes().stream().anyMatch(change -> change.kind() == Change.Kind.BLOCK_CHANGED
                && change.what().equals("minecraft:lever")));
    }

    @Test
    void 指定点哪一面_出手只认那一面() {
        // 用扳手转机器：点哪一面决定绕哪根轴转，交给出手的目标带上这一面。
        Rig rig = new Rig();
        rig.world.blocks.put(LEVER, "minecraft:lever");
        rig.interactions.applied("拉杆扳动了");
        TaskResult result = rig.run(new UseInput(position(LEVER), null, "minecraft:lever", null, 1, 32,
                List.of(), Permissions.DEFAULT, Direction.EAST));
        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(Direction.EAST, rig.interactions.lastTarget.face());
    }

    @Test
    void 要求连着点时_没点中就接着点_点中就停() {
        // 抓传送带上跑着的东西：前两下没点中（没反应、说不清），第三下手里有了。
        Rig rig = new Rig();
        rig.world.blocks.put(LEVER, "minecraft:lever");
        rig.interactions.outcome(InteractionResult.notApplied("没反应"), () -> { });
        rig.interactions.outcome(InteractionResult.unconfirmed("说不清"), () -> { });
        rig.interactions.applied("手里有了");
        TaskResult result = rig.run(new UseInput(position(LEVER), null, "minecraft:lever", null, 1, 32,
                List.of(), Permissions.DEFAULT, null, 3));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(1L, ((UseDetails) result.details()).appliedTimes());
        assertEquals(3, rig.interactions.builds);
        assertTrue(rig.interactions.lastTarget.quick(), "连着点的每一下只等几刻");
    }

    @Test
    void 不要求连着点时_没反应就停() {
        Rig rig = new Rig();
        rig.world.blocks.put(LEVER, "minecraft:lever");
        rig.interactions.outcome(InteractionResult.notApplied("没反应"), () -> { });
        rig.interactions.applied("不该点到这一下");
        rig.run(rig.input(position(LEVER), null, "minecraft:lever", null, 1));
        assertEquals(1, rig.interactions.builds);
    }

    @Test
    void 方块不对_按目标没了并写明应为与现为() {
        Rig rig = new Rig();
        rig.world.blocks.put(LEVER, "minecraft:barrel");
        TaskResult result = rig.run(rig.input(position(LEVER), null, "minecraft:chest", null, 1));
        assertProblem(result, Problem.Kind.TARGET_GONE);
        assertTrue(result.problem().message().contains("minecraft:chest")
                && result.problem().message().contains("minecraft:barrel"));
        assertEquals(0, rig.interactions.builds);
    }

    @Test
    void 观察编号指的实体类型对不上_按目标没了() {
        Rig rig = new Rig();
        rig.seen.put("e1", new ResolvesSeen.Resolved(WorldPosition.here(2, 64, 2), "minecraft:sheep", 7));
        rig.world.entities.put(7, new UseSeams.ReadsWorld.SeenEntity("minecraft:sheep", new BlockPos(2, 64, 2), true));
        TaskResult result = rig.run(rig.input(new Target.Seen("e1"), null, null, "minecraft:cow", 1));
        assertProblem(result, Problem.Kind.TARGET_GONE);
        assertTrue(result.problem().message().contains("现在是 minecraft:sheep"));
    }

    @Test
    void 掉落物准星选不中_如实说用不了() {
        Rig rig = new Rig();
        rig.seen.put("e3", new ResolvesSeen.Resolved(WorldPosition.here(2, 64, 2), "minecraft:item", 5));
        rig.world.entities.put(5, new UseSeams.ReadsWorld.SeenEntity("minecraft:item", new BlockPos(2, 64, 2), false));
        TaskResult result = rig.run(rig.input(new Target.Seen("e3"), null, null, null, 1));
        assertProblem(result, Problem.Kind.UNSUPPORTED);
        assertTrue(result.problem().suggestion().contains("gather"));
    }

    @Test
    void 范围内没有_按没找到并写明范围() {
        Rig rig = new Rig();
        TaskResult result = rig.run(rig.input(new Target.Here(), null, "minecraft:lever", null, 1));
        assertProblem(result, Problem.Kind.NOT_FOUND);
        assertTrue(result.problem().message().contains("32 格"));
    }

    @Test
    void 已经骑在马上_直接完成() {
        Rig rig = new Rig();
        rig.seen.put("e2", new ResolvesSeen.Resolved(WorldPosition.here(2, 64, 2), "minecraft:horse", 9));
        rig.world.entities.put(9, new UseSeams.ReadsWorld.SeenEntity("minecraft:horse", new BlockPos(2, 64, 2), true));
        rig.world.ridingId = 9;
        TaskResult result = rig.run(rig.input(new Target.Seen("e2"), null, null, null, 1));
        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals("minecraft:horse", ((UseDetails) result.details()).riding());
        assertEquals(0, rig.interactions.builds);
    }

    @Test
    void 下界的床_不点按危险() {
        Rig rig = new Rig();
        rig.world.dimension = "minecraft:the_nether";
        rig.world.blocks.put(LEVER, "minecraft:red_bed");
        TaskResult result = rig.run(rig.input(position(LEVER), null, null, null, 1));
        assertProblem(result, Problem.Kind.DANGER);
        assertEquals(0, rig.interactions.builds);
    }

    @Test
    void 带维度的坐标就在本维度_照常去用() {
        Rig rig = new Rig();
        rig.world.blocks.put(LEVER, "minecraft:lever");
        rig.interactions.applied("拉杆扳动了");
        TaskResult result = rig.run(rig.input(new Target.Position(1, 64, 1, OVERWORLD), null, null, null, 1));
        assertEquals(TaskResult.Status.DONE, result.status());
    }

    @Test
    void 身上没有_先去拿一件再用() {
        Rig rig = new Rig();
        rig.world.blocks.put(LEVER, "minecraft:dirt");
        rig.hand.plans.add(new UseSeams.HandPlan.NotCarried());
        rig.interactions.applied("耕成了耕地");
        TaskResult result = rig.run(rig.input(position(LEVER), "minecraft:wooden_hoe", null, null, 1));
        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(1, rig.needs.requests.size());
        assertEquals(1, rig.needs.requests.get(0).count());
    }

    @Test
    void 有提示语的没生效_按游戏拒绝附原话() {
        Rig rig = new Rig();
        rig.world.blocks.put(LEVER, "minecraft:chest");
        rig.refusal.message = "上一次的旧话";
        rig.interactions.outcome(InteractionResult.notApplied("箱子没开"), () -> rig.refusal.message = "箱子已上锁");
        TaskResult result = rig.run(rig.input(position(LEVER), null, null, null, 1));
        assertProblem(result, Problem.Kind.REFUSED_BY_GAME);
        assertTrue(result.problem().message().contains("箱子已上锁"));
    }

    @Test
    void 游戏什么都没说的没生效_按这样用没有效果() {
        Rig rig = new Rig();
        rig.world.blocks.put(LEVER, "minecraft:stone");
        rig.refusal.message = "出手前就挂着的话";
        rig.interactions.outcome(InteractionResult.notApplied("石头没变"), () -> { });
        TaskResult result = rig.run(rig.input(position(LEVER), null, null, null, 1));
        assertProblem(result, Problem.Kind.NOT_POSSIBLE_HERE);
    }

    @Test
    void 没能确认_部分完成且不再出手() {
        Rig rig = new Rig();
        rig.world.blocks.put(LEVER, "minecraft:lever");
        rig.interactions.outcome(InteractionResult.unconfirmed("确认没等到"), () -> { });
        TaskResult result = rig.run(rig.input(position(LEVER), null, null, null, 3));
        assertEquals(TaskResult.Status.PARTIAL, result.status());
        assertEquals(1, result.unconfirmed().size());
        assertEquals(1, rig.interactions.builds);
    }

    @Test
    void 做三次第二次没效果_部分完成写明做成一次() {
        Rig rig = new Rig();
        rig.world.blocks.put(LEVER, "minecraft:lever");
        rig.interactions.applied("拉杆扳动了");
        rig.interactions.outcome(InteractionResult.notApplied("拉杆没动"), () -> { });
        TaskResult result = rig.run(rig.input(position(LEVER), null, null, null, 3));
        assertEquals(TaskResult.Status.PARTIAL, result.status());
        assertTrue(result.summary().contains("确认生效了 1 次"));
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, result.problem().kind());
        assertTrue(result.remaining().stream().anyMatch(left -> left.contains("2 次")));
    }

    @Test
    void 空桶点到流动的水_改舀附近的源格() {
        Rig rig = new Rig();
        BlockPos source = new BlockPos(3, 64, 3);
        rig.world.blocks.put(LEVER, "minecraft:water");
        rig.world.fluids.put(LEVER, UseSeams.ReadsWorld.Fluid.FLOWING);
        rig.world.sources.put(LEVER, source);
        rig.world.held = new UseSeams.ReadsWorld.Held("minecraft:bucket", 1);
        rig.interactions.applied("舀满了");
        TaskResult result = rig.run(rig.input(position(LEVER), "minecraft:bucket", null, null, 1));
        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(source, rig.interactions.lastTarget.cell());
        assertEquals(source, rig.close.lastTarget.block());
    }

    @Test
    void 够不着_换个站位再试一次() {
        Rig rig = new Rig();
        rig.world.blocks.put(LEVER, "minecraft:lever");
        rig.interactions.aimFails(Problem.of(Problem.Kind.UNREACHABLE, "被挡住了", null));
        rig.interactions.applied("拉杆扳动了");
        TaskResult result = rig.run(rig.input(position(LEVER), null, null, null, 1));
        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(2, rig.close.calls);
        assertTrue(result.attempts().stream().anyMatch(attempt -> attempt.result().contains("换个站位")));
        // 换站位时把被挡的那一格排除：不然靠近一核对就原地不动、又被挡。
        assertTrue(rig.close.lastAvoid != null && rig.close.lastAvoid.contains(BlockPos.ZERO), "换站位不再站被挡的那一格");
    }

    @Test
    void 要空手却背包满了_先腾一格再备手() {
        // 从机械手上拿东西要空手，快捷栏和背包都满：先腾一格，再回头腾空手，照常出手。
        Rig rig = new Rig();
        rig.world.blocks.put(LEVER, "minecraft:lever");
        rig.hand.plans.add(new UseSeams.HandPlan.Cannot(Problem.of(Problem.Kind.INVENTORY_FULL, "腾不出空手")));
        rig.space = new InventorySpace(ItemsToKeep.NOTHING, new BackpackView() {
            @Override public List<BackpackStack> stacks() { return List.of(); }
            @Override public int usedSlots() { return 35; }
            @Override public int totalSlots() { return 36; }
        }, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        rig.interactions.applied("拉杆扳动了");
        TaskResult result = rig.run(rig.input(position(LEVER), null, null, null, 1));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
    }

    @Test
    void 被打断与被取消时_交给基类的交互动作跟着暂停与收尾() {
        Rig rig = new Rig();
        rig.world.blocks.put(LEVER, "minecraft:lever");
        Scripted pending = rig.interactions.forever();
        UseTask task = rig.task(rig.input(position(LEVER), null, null, null, 1));
        for (int i = 0; i < 5; i++) task.tick(rig.tick());
        task.pause();
        assertTrue(pending.paused);
        task.close(CloseReason.CANCELLED);
        assertTrue(pending.closed);
    }

    @Test
    void 坐标没加载_先走过去_加载出来再找() {
        Rig rig = new Rig();
        rig.world.blocks.put(LEVER, "minecraft:lever");
        rig.world.unloaded.add(LEVER);
        rig.interactions.applied("拉杆扳动了");
        UseTask task = rig.task(rig.input(position(LEVER), null, null, null, 1));
        task.tick(rig.tick());
        task.tick(rig.tick());
        assertTrue(rig.travel.walk != null && !rig.travel.walk.closed);
        rig.world.unloaded.clear();
        TaskResult result = rig.finish(task);
        assertEquals(TaskResult.Status.DONE, result.status());
        assertTrue(rig.travel.walk.closed);
    }

    @Test
    void 点开了箱子_列出里面有什么再关上() {
        Rig rig = new Rig();
        rig.world.blocks.put(LEVER, "minecraft:chest");
        rig.interactions.applied("界面开了");
        rig.menus.look = new FakeLook(Map.of("minecraft:apple", 3));
        TaskResult result = rig.run(rig.input(position(LEVER), null, null, null, 1));
        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals("minecraft:apple ×3", ((UseDetails) result.details()).opened());
        assertTrue(rig.menus.look.closed);
    }

    @Test
    void 写告示牌_字写上去才算做成一次() {
        Rig rig = new Rig();
        rig.world.blocks.put(LEVER, "minecraft:oak_sign");
        rig.world.signs.put(LEVER, UseSeams.ReadsWorld.Sign.WRITABLE);
        rig.interactions.applied("编辑界面开了");
        TaskResult result = rig.run(new UseInput(position(LEVER), null, null, null, 1, 32,
                List.of("你好", "世界"), Permissions.DEFAULT));
        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(List.of("你好", "世界"), ((UseDetails) result.details()).signText());
        assertEquals(1L, ((UseDetails) result.details()).appliedTimes());
    }

    @Test
    void 写告示牌_靠近目标只带正面那一侧_普通用法不带() {
        // 面朝西的告示牌（正面水平朝向角 90）：写字路径给靠近的目标带上正面那一侧，站位因此只挑西边；
        // 不写字的普通用法不带这个约束，站位不受正背限制。
        Rig rig = new Rig();
        rig.world.blocks.put(LEVER, "minecraft:oak_sign");
        rig.world.signs.put(LEVER, UseSeams.ReadsWorld.Sign.WRITABLE);
        rig.world.signFronts.put(LEVER, 90.0);
        rig.interactions.applied("编辑界面开了");
        TaskResult result = rig.run(new UseInput(position(LEVER), null, null, null, 1, 32,
                List.of("你好"), Permissions.DEFAULT));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(90.0, rig.close.lastTarget.frontYaw());
        Rig plain = new Rig();
        plain.world.blocks.put(LEVER, "minecraft:lever");
        plain.interactions.applied("拉杆扳动了");
        TaskResult plainResult = plain.run(plain.input(position(LEVER), null, "minecraft:lever", null, 1));
        assertEquals(TaskResult.Status.DONE, plainResult.status());
        assertNull(plain.close.lastTarget.frontYaw());
    }

    @Test
    void 上过蜡的告示牌_不点按游戏拒绝() {
        Rig rig = new Rig();
        rig.world.blocks.put(LEVER, "minecraft:oak_sign");
        rig.world.signs.put(LEVER, UseSeams.ReadsWorld.Sign.WAXED);
        TaskResult result = rig.run(new UseInput(position(LEVER), null, null, null, 1, 32,
                List.of("你好"), Permissions.DEFAULT));
        assertProblem(result, Problem.Kind.REFUSED_BY_GAME);
        assertEquals(0, rig.interactions.builds);
    }

    @Test
    void 只捡这一下新掉出来的东西() {
        Rig rig = new Rig();
        rig.seen.put("e4", new ResolvesSeen.Resolved(WorldPosition.here(2, 64, 2), "minecraft:sheep", 11));
        rig.world.entities.put(11, new UseSeams.ReadsWorld.SeenEntity("minecraft:sheep", new BlockPos(2, 64, 2), true));
        rig.drops.before = Set.of(1);
        rig.interactions.applied("剪下了羊毛");
        TaskResult result = rig.run(rig.input(new Target.Seen("e4"), "minecraft:shears", null, null, 1));
        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(Set.of(1), rig.drops.askedSince);
        assertTrue(rig.drops.pickUp.closed);
        assertTrue(result.changes().stream().anyMatch(change -> change.kind() == Change.Kind.ENTITY_AFFECTED));
    }

    @Test
    void 坐标落在别人的方块上_会改动它的用法要点名() {
        // 拿打火石对着别人房子的地板点：不是点名的目标，不碰，说清要用观察编号点名。
        Rig rig = new Rig();
        rig.world.blocks.put(LEVER, "minecraft:oak_planks");
        rig.others.owned.add(LEVER);
        TaskResult result = rig.run(rig.input(position(LEVER), "minecraft:flint_and_steel", null, null, 1));
        assertProblem(result, Problem.Kind.NEED_APPROVAL);
        assertTrue(result.problem().suggestion().contains("观察编号"));
        assertEquals(0, rig.interactions.builds);
    }

    @Test
    void 别人的拉杆空手扳一下_不算改动照常用() {
        // 按按钮、扳拉杆不算改动世界：别人的也可以。
        Rig rig = new Rig();
        rig.world.blocks.put(LEVER, "minecraft:lever");
        rig.others.owned.add(LEVER);
        rig.interactions.applied("拉杆扳动了");
        TaskResult result = rig.run(rig.input(position(LEVER), null, "minecraft:lever", null, 1));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
    }

    @Test
    void 按类型找到的都是别人的_要点名而不是说没有() {
        Rig rig = new Rig();
        rig.world.blocks.put(LEVER, "minecraft:grass_block");
        rig.others.owned.add(LEVER);
        rig.nearestBlocks.add(LEVER);
        TaskResult result = rig.run(rig.input(new Target.Here(), "minecraft:wooden_hoe", "minecraft:grass_block", null, 1));
        assertProblem(result, Problem.Kind.NEED_APPROVAL);
        assertTrue(result.problem().message().contains("都是别人的"), result.problem().message());
    }

    private static Target position(BlockPos cell) {
        return new Target.Position(cell.getX(), cell.getY(), cell.getZ(), null);
    }

    private static void assertProblem(TaskResult result, Problem.Kind kind) {
        assertEquals(TaskResult.Status.FAILED, result.status(), result.summary());
        assertEquals(kind, result.problem().kind(), result.problem().message());
    }

    /** 一套离线替身：世界、交互、靠近、备手、去拿、观察编号、搜索、提示语、告示牌、捡东西、出行、看界面。 */
    private static final class Rig {
        final FakeWorld world = new FakeWorld();
        final FakeInteractions interactions = new FakeInteractions();
        final FakeClose close = new FakeClose();
        final FakeHand hand = new FakeHand();
        /** 背包空间：替身背包总是有一格空着，腾一格立刻做完。 */
        InventorySpace space;
        final FakeNeeds needs = new FakeNeeds();
        final Map<String, ResolvesSeen.Resolved> seen = new HashMap<>();
        final FakeRefusal refusal = new FakeRefusal();
        final FakeDrops drops = new FakeDrops();
        final FakeTravel travel = new FakeTravel();
        final FakeMenus menus = new FakeMenus();
        final FakeOthers others = new FakeOthers();
        /** 世界记忆：默认没接；补账的测试自己摆一份。 */
        WorldMemory memory;
        /** 设施分类：默认空标签的一套（床按种类认，原版工作台按 ID 表认）。 */
        FacilityKinds facilities = new FacilityKinds(new ReadsBlockTags() {
            @Override public Set<String> tagsOf(String blockType) {
                return Set.of();
            }
            @Override public Set<String> blocksIn(String tag) {
                return Set.of();
            }
        });
        /** 附近搜索按由近到远给出的方块格子。 */
        final List<BlockPos> nearestBlocks = new ArrayList<>();
        private long tick;

        UseInput input(Target target, String item, String block, String entity, long count) {
            return new UseInput(target, item, block, entity, count, 32, List.of(), Permissions.DEFAULT);
        }

        UseTask task(UseInput input) {
            SearchesNearby search = new SearchesNearby() {
                @Override public BlockResult nearestBlock(String blockOrTag, BlockPos center, int radius,
                        Predicate<BlockPos> skip) {
                    return new BlockResult(nearestBlocks.stream().filter(cell -> !skip.test(cell)).findFirst(), true);
                }

                @Override public EntityResult nearestEntity(String entityTypeId, BlockPos center, int radius,
                        IntPredicate skip) {
                    return new EntityResult(OptionalInt.empty(), true);
                }
            };
            UseServices services = new UseServices(world, interactions, close, hand, needs,
                    id -> Optional.ofNullable(seen.get(id)), search, refusal, FakeSignEditor::open,
                    drops, travel, menus, memory, others, space, facilities);
            UseTask task = new UseTask(input, services);
            task.start(tick());
            return task;
        }

        TaskResult run(UseInput input) {
            return finish(task(input));
        }

        TaskResult finish(UseTask task) {
            for (int i = 0; i < 400; i++) {
                if (task.tick(tick()) instanceof TickResult.Finished finished) {
                    return finished.result();
                }
            }
            throw new AssertionError("任务四百刻都没结束：" + task.describe());
        }

        TickContext tick() {
            long now = ++tick;
            return new TickContext() {
                @Override public long gameTick() { return now; }
                @Override public PlayerContext player() { return null; }
            };
        }
    }

    /** 跑几刻后按给定结论收场的动作；记下有没有被暂停、被收尾。 */
    private static final class Scripted implements Action {
        private final int ticks;
        private final ActionStatus end;
        private final Runnable onEnd;
        private int ran;
        boolean paused;
        boolean closed;

        Scripted(int ticks, ActionStatus end, Runnable onEnd) {
            this.ticks = ticks;
            this.end = end;
            this.onEnd = onEnd;
        }

        @Override public ActionStatus tick(TickContext context) {
            if (ticks >= 0 && ++ran >= ticks) {
                onEnd.run();
                return end;
            }
            return ActionStatus.progressed();
        }

        @Override public void pause() { paused = true; }
        @Override public void close() { closed = true; }
        @Override public String describe() { return "替身动作"; }
    }

    private static final class FakeWorld implements UseSeams.ReadsWorld {
        String dimension = OVERWORLD;
        final Map<BlockPos, String> blocks = new HashMap<>();
        final Set<BlockPos> unloaded = new HashSet<>();
        final Map<BlockPos, Fluid> fluids = new HashMap<>();
        final Map<BlockPos, BlockPos> sources = new HashMap<>();
        final Map<BlockPos, Sign> signs = new HashMap<>();
        /** 还能写的告示牌正面的水平朝向角（原版旋转角，南为 0）；没记的面朝南算。 */
        final Map<BlockPos, Double> signFronts = new HashMap<>();
        final Map<Integer, SeenEntity> entities = new HashMap<>();
        Integer ridingId;
        Held held;

        @Override public String dimension() { return dimension; }
        @Override public BlockPos feet() { return BlockPos.ZERO; }
        @Override public boolean loaded(BlockPos cell) { return !unloaded.contains(cell); }
        @Override public Optional<String> blockId(BlockPos cell) {
            return loaded(cell) ? Optional.of(blocks.getOrDefault(cell, "minecraft:air")) : Optional.empty();
        }
        @Override public boolean blockIs(BlockPos cell, String blockOrTag) {
            return blockId(cell).map(blockOrTag::equals).orElse(false);
        }
        @Override public Optional<BlockPos> ground(int x, int z) { return Optional.empty(); }
        @Override public Fluid fluid(BlockPos cell) { return fluids.getOrDefault(cell, Fluid.NONE); }
        @Override public Optional<BlockPos> nearestSource(BlockPos near, int radius) {
            return Optional.ofNullable(sources.get(near));
        }
        @Override public Sign sign(BlockPos cell) { return signs.getOrDefault(cell, Sign.NOT_A_SIGN); }
        @Override public Optional<SeenEntity> entity(int entityId) { return Optional.ofNullable(entities.get(entityId)); }
        @Override public boolean riding(int entityId) { return ridingId != null && ridingId == entityId; }
        @Override public Optional<Held> heldItem() { return Optional.ofNullable(held); }
        // 与生产一致：写字的目标得是还能写的告示牌才带"只站正面那一侧"，不是告示牌给空，任务按目标没了收场；
        // 不写字的普通用法不带这个约束。
        @Override public Optional<ApproachTarget> approachTarget(ResolvedTarget target, boolean writesSign) {
            if (target.isEntity()) {
                return Optional.of(ApproachTarget.ofEntity(new AABB(target.cell())));
            }
            Double front = signs.getOrDefault(target.cell(), Sign.NOT_A_SIGN) == Sign.WRITABLE
                    ? signFronts.getOrDefault(target.cell(), 0.0) : null;
            if (writesSign && front == null) return Optional.empty();
            return Optional.of(front == null ? ApproachTarget.ofBlock(target.cell())
                    : ApproachTarget.onFrontSideOf(target.cell(), front));
        }
    }

    /** 按脚本一次次给出交互：生效、没生效、没能确认、瞄不准、一直等。 */
    private static final class FakeInteractions implements UseSeams.BuildsInteraction {
        private final Deque<UseSeams.Built> script = new ArrayDeque<>();
        int builds;
        ResolvedTarget lastTarget;

        void applied(String scene) {
            outcome(InteractionResult.applied(scene), () -> { });
        }

        /** 一次放东西的生效：出手前手上拿着它，动作收场时世界出现放下的方块。 */
        void placed(String itemId, String scene, Runnable onWorldChanged) {
            script(InteractionResult.applied(scene), onWorldChanged, new UseSeams.ReadsWorld.Held(itemId, 1));
        }

        // 动作结束时给出结论；没生效、没能确认的按交互动作的惯例报失败，结论照样读得到。
        void outcome(InteractionResult result, Runnable onEnd) {
            script(result, onEnd, null);
        }

        private void script(InteractionResult result, Runnable onEnd, UseSeams.ReadsWorld.Held heldBefore) {
            ActionStatus end = result.verdict() == InteractionVerdict.APPLIED
                    ? ActionStatus.done() : ActionStatus.failed(Problem.of(Problem.Kind.STUCK, result.scene(), null));
            InteractionResult[] settled = {null};
            Scripted action = new Scripted(2, end, () -> {
                settled[0] = result;
                onEnd.run();
            });
            script.add(new UseSeams.Built.Ready(action, () -> settled[0], ItemUseAim.Gesture.EMPTY_HAND,
                    heldBefore, 0, null));
        }

        void aimFails(Problem problem) {
            script.add(new UseSeams.Built.Ready(new Scripted(1, ActionStatus.failed(problem), () -> { }),
                    () -> null, ItemUseAim.Gesture.EMPTY_HAND, null, 0, null));
        }

        Scripted forever() {
            Scripted action = new Scripted(-1, ActionStatus.done(), () -> { });
            script.add(new UseSeams.Built.Ready(action, () -> null, ItemUseAim.Gesture.EMPTY_HAND, null, 0, null));
            return action;
        }

        @Override public UseSeams.Built build(ResolvedTarget target, boolean writesSign) {
            builds++;
            lastTarget = target;
            return script.isEmpty() ? new UseSeams.Built.CannotAim(Problem.of(Problem.Kind.STUCK, "脚本用完了", null))
                    : script.poll();
        }
    }

    private static final class FakeClose implements BringsPlayerClose {
        int calls;
        ApproachTarget lastTarget;

        ProtectedCells lastAvoid;

        @Override public Action toward(ApproachTarget target, Permissions permissions) {
            calls++;
            lastTarget = target;
            return new Scripted(1, ActionStatus.done(), () -> { });
        }

        @Override public Action toward(ApproachTarget target, Permissions permissions, ProtectedCells avoid) {
            lastAvoid = avoid;
            return toward(target, permissions);
        }
    }

    /** 备手：按排好的打算依次回答，排完了就是已经拿好。 */
    private static final class FakeHand implements UseSeams.PreparesHand {
        final Deque<UseSeams.HandPlan> plans = new ArrayDeque<>();

        @Override public UseSeams.HandPlan hold(String item) {
            return plans.isEmpty() ? new UseSeams.HandPlan.Ready() : plans.poll();
        }
    }

    private static final class FakeNeeds implements ItemNeeds {
        final List<ItemRequest> requests = new ArrayList<>();

        @Override public Action actionFor(ItemRequest request, Permissions permissions) {
            requests.add(request);
            return new Scripted(2, ActionStatus.done(), () -> { });
        }
    }

    private static final class FakeRefusal implements UseSeams.ReadsGameRefusal {
        String message;

        @Override public Optional<String> latestMessage() { return Optional.ofNullable(message); }
    }

    private static final class FakeDrops implements PicksUpDrops {
        Set<Integer> before = Set.of();
        Set<Integer> askedSince;
        Scripted pickUp;

        @Override public Set<Integer> nearby() { return before; }

        @Override public Action pickUpNewSince(Set<Integer> since) {
            askedSince = since;
            pickUp = new Scripted(2, ActionStatus.done(), () -> { });
            return pickUp;
        }
    }

    /** 别人的东西：拿着东西用算改动世界、空手不算；owned 里的格子是别人的。 */
    private static final class FakeOthers implements UseSeams.OthersThings {
        final Set<BlockPos> owned = new HashSet<>();

        @Override public boolean changesWorld(String heldItem, ResolvedTarget target) {
            return heldItem != null;
        }

        @Override public boolean someoneElses(ResolvedTarget target, Set<String> protectedLandmarks) {
            return owned.contains(target.cell());
        }
    }

    private static final class FakeTravel implements UseSeams.TravelsTo {
        Scripted walk;

        @Override public Optional<Action> toward(WorldPosition where, boolean heightKnown, Permissions permissions) {
            walk = new Scripted(-1, ActionStatus.done(), () -> { });
            return Optional.of(walk);
        }
    }

    private static final class FakeMenus implements UseSeams.LooksInMenus {
        FakeLook look;

        @Override public Optional<UseSeams.MenuLook> opened(int menuBefore) {
            return Optional.ofNullable(look);
        }
    }

    private static final class FakeLook implements UseSeams.MenuLook {
        private final Map<String, Integer> contents;
        boolean closed;
        private int ran;

        FakeLook(Map<String, Integer> contents) {
            this.contents = contents;
        }

        @Override public Optional<Map<String, Integer>> contents() {
            return ran >= 2 ? Optional.of(contents) : Optional.empty();
        }
        @Override public ActionStatus tick(TickContext context) {
            return ++ran >= 2 ? ActionStatus.done() : ActionStatus.progressed();
        }
        @Override public void close() { closed = true; }
        @Override public String describe() { return "看界面替身"; }
    }

    /** 告示牌编辑界面替身：一直开着，写什么就留下什么。 */
    private static final class FakeSignEditor implements SignEditor {
        private final List<String> lines = new ArrayList<>(List.of("", "", "", ""));
        private boolean done;

        static Optional<SignEditor> open() {
            return Optional.of(new FakeSignEditor());
        }

        @Override public boolean editScreenOpen() { return true; }
        @Override public void typeLine(int lineIndex, String text) { lines.set(lineIndex, text); }
        @Override public void pressDone() { done = true; }
        @Override public Optional<List<String>> frontText() {
            return done ? Optional.of(lines.subList(0, 2)) : Optional.empty();
        }
    }
}
