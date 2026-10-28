// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.enchant;

import org.maiwithu.maicraft.behavior.inventory.ItemsToKeep;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.engine.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.inventory.InventorySpace;
import org.maiwithu.maicraft.behavior.inventory.spi.CarriedBackpack;
import org.maiwithu.maicraft.behavior.inventory.containers.KnownContainers;
import org.maiwithu.maicraft.behavior.inventory.containers.StoresInContainer;
import org.maiwithu.maicraft.behavior.inventory.hands.StackMerger;
import org.maiwithu.maicraft.behavior.permission.ReadsRememberedPlaces;
import org.maiwithu.maicraft.behavior.travel.planning.SceneSeenTargets;
import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 找台的离线场景：找台读端说现场有台就认得（记忆里没有地点也认得）、没扫完不说没有、
 * 现场确实没有时如实报找不到并写实际扫过的范围，记得了地点也不冒充台子还在。
 */
class EnchantTaskTest {

    private static final String SWORD = "minecraft:wooden_sword";
    private static final BlockPos TABLE = new BlockPos(65, 65, -42);

    /** 找台读端替身：剧本给出每次查询的现场结论，记下每次问的范围与核对过的格子。 */
    static final class FakeTables implements TableScans {
        final Deque<TableScans.Found> script = new ArrayDeque<>();
        final List<Integer> chunkRadiusAsked = new ArrayList<>();
        final Map<BlockPos, TableScans.At> cells = new HashMap<>();

        @Override public TableScans.Found nearest(int chunkRadius, int buildBudget) {
            chunkRadiusAsked.add(chunkRadius);
            return script.removeFirst();
        }

        @Override public TableScans.At inspect(BlockPos pos) {
            return cells.getOrDefault(pos, new TableScans.At(false, "空气"));
        }
    }

    /** 拿东西替身：只记收到的请求，动作永远在做（这些场景推进不到它）。 */
    static final class FakeObtain implements ItemNeeds {
        final List<ItemRequest> requests = new ArrayList<>();

        @Override public Action actionFor(ItemRequest request, Permissions permissions) {
            requests.add(request);
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    return ActionStatus.running();
                }

                @Override public String describe() {
                    return "等着拿东西";
                }
            };
        }
    }

    /** 只读记过地点的替身：摆一个记得的地点，看找台失败时会不会拿它冒充台子。 */
    static final class FakePlaces implements ReadsRememberedPlaces {
        String remembered;

        @Override public Optional<WorldPosition> place(String name) {
            return name.equals(remembered)
                    ? Optional.of(new WorldPosition(TABLE.getX(), TABLE.getY(), TABLE.getZ(), null))
                    : Optional.empty();
        }
    }

    /** 附魔菜单事实替身：身上没料、界面没开；这些场景推进不到界面里。 */
    static final class FakeMenuFacts implements EnchantMenuFacts {
        @Override public List<ItemFacts> carriedSlots() {
            return List.of();
        }

        @Override public boolean menuOpen() {
            return false;
        }

        @Override public Snapshot readMenu() {
            return null;
        }
    }

    /** 角色上下文替身：这些判断只读现场读端，不碰真实客户端。 */
    private static final class FakePlayerContext implements PlayerContext {
        @Override public LocalPlayer localPlayer() {
            return null;
        }

        @Override public ClientLevel level() {
            return null;
        }

        @Override public ClientPacketListener connection() {
            return null;
        }

        @Override public PlayerInput input() {
            return null;
        }

        @Override public InteractionSender interactionSender() {
            return null;
        }

        @Override public MenuActions menuActions() {
            return null;
        }

        @Override public long clientTick() {
            return 0;
        }

        @Override public boolean isCurrent() {
            return true;
        }

        @Override public boolean canInteractThisTick() {
            return false;
        }

        @Override public boolean tryClaimInteraction() {
            return false;
        }
    }

    private static EnchantTask task(EnchantInput input, FakeTables tables, FakeObtain obtain, FakePlaces places) {
        var space = new InventorySpace(ItemsToKeep.NOTHING, backpackWithNoFreeSlot(), Optional.empty(), Optional.empty(),
                Optional.<KnownContainers>empty(), Optional.<StoresInContainer.Parts>empty());
        EnchantServices services = new EnchantServices(obtain, space,
                (pos, permissions) -> null, new FakeMenuFacts(),
                new SceneSeenTargets(() -> null), places, tables, itemTags());
        return new EnchantTask(input, services, Permissions.DEFAULT);
    }

    private static BackpackView backpackWithNoFreeSlot() {
        return new BackpackView() {
            @Override public List<BackpackStack> stacks() {
                return List.of();
            }

            @Override public int usedSlots() {
                return 0;
            }

            @Override public int totalSlots() {
                return 0;
            }
        };
    }

    private static ReadsItemTags itemTags() {
        return itemId -> Set.of();
    }

    private long tick;

    private TickContext context() {
        return TickContext.of(tick++, new FakePlayerContext());
    }

    @Test
    void 现场有台而记忆里没有地点也认得那格() {
        FakeTables tables = new FakeTables();
        tables.script.add(new TableScans.Found(List.of(TABLE), true));
        // 选台之后动手前按现场核对：这一格此刻真摆着附魔台。
        tables.cells.put(TABLE, new TableScans.At(true, "附魔台"));
        FakeObtain obtain = new FakeObtain();
        EnchantTask task = task(new EnchantInput(SWORD, 1, 32, null, Permissions.DEFAULT),
                tables, obtain, new FakePlaces());

        TickResult result = task.tick(context());

        assertTrue(result instanceof TickResult.Running);
        assertEquals("GET_ITEM", task.currentProgress().orElseThrow().phase());
        // 认下的就是读端给的那格：身上没料，接下来去拿要附魔的东西。
        assertEquals(1, tables.chunkRadiusAsked.size());
        assertEquals(1, obtain.requests.size());
        assertEquals(SWORD, obtain.requests.get(0).wanted().itemId());
    }

    @Test
    void 分刻扫描没扫完时不说没有() {
        FakeTables tables = new FakeTables();
        tables.script.add(new TableScans.Found(List.of(), false));
        EnchantTask task = task(new EnchantInput(SWORD, 1, 32, null, Permissions.DEFAULT),
                tables, new FakeObtain(), new FakePlaces());

        TickResult result = task.tick(context());

        assertTrue(result instanceof TickResult.Running);
        assertEquals("FIND_TABLE", task.currentProgress().orElseThrow().phase());
    }

    @Test
    void 现场没有台时如实报找不到不拿记得的地点充数() {
        FakeTables tables = new FakeTables();
        tables.script.add(new TableScans.Found(List.of(), true));
        FakePlaces places = new FakePlaces();
        places.remembered = "家";
        FakeObtain obtain = new FakeObtain();
        EnchantTask task = task(new EnchantInput(SWORD, 1, 32, null, Permissions.DEFAULT),
                tables, obtain, places);

        TickResult result = task.tick(context());

        assertTrue(result instanceof TickResult.Finished);
        TaskResult done = ((TickResult.Finished) result).result();
        assertEquals(TaskResult.Status.FAILED, done.status());
        assertEquals(Problem.Kind.NOT_FOUND, done.problem().kind());
        assertTrue(done.problem().message().contains("半径 32 格内"), done.problem().message());
        assertEquals(1, tables.chunkRadiusAsked.size());
        assertNull(done.changes().isEmpty() ? null : "找台不该动世界");
        // 记得「家」也不冒充那里有台子：没有发出任何拿料的请求。
        assertEquals(0, obtain.requests.size());
    }

    @Test
    void 半径超过一次能扫的就照实说只扫了多远() {
        FakeTables tables = new FakeTables();
        tables.script.add(new TableScans.Found(List.of(), true));
        EnchantTask task = task(new EnchantInput(SWORD, 1, 512, null, Permissions.DEFAULT),
                tables, new FakeObtain(), new FakePlaces());

        TickResult result = task.tick(context());

        assertTrue(result instanceof TickResult.Finished);
        String message = ((TickResult.Finished) result).result().problem().message();
        assertTrue(message.contains("只扫了约 128 格内"), message);
        assertTrue(message.contains("512"), message);
        // radius 换算出的区块半径封顶在一次能扫完的范围内。
        assertEquals(List.of(8), tables.chunkRadiusAsked);
    }

    @Test
    void 记得的台子格子被拆了就照实说那格现在是什么() {
        FakeTables tables = new FakeTables();
        tables.cells.put(TABLE, new TableScans.At(false, "石头"));
        // 用坐标点名那格台子，进来直接核对现场。
        EnchantTask named = task(new EnchantInput(SWORD, 1, 32,
                new Target.Position(
                        TABLE.getX(), TABLE.getY(), TABLE.getZ(), null),
                Permissions.DEFAULT), tables, new FakeObtain(), new FakePlaces());

        TickResult result = named.tick(context());

        assertTrue(result instanceof TickResult.Finished);
        TaskResult done = ((TickResult.Finished) result).result();
        assertEquals(Problem.Kind.TARGET_GONE, done.problem().kind());
        assertTrue(done.problem().message().contains("石头"), done.problem().message());
        // 没扫过现场就不该问找台读端要候选：点名的那格只核对。
        assertEquals(0, tables.chunkRadiusAsked.size());
    }
}
