// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonParser;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.behavior.inventory.hands.GearChanges;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.menu.MenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuOutputLook;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.OutputLook;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.recipe.ShownIngredient;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.behavior.recipe.ShownStack;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.CompatMenuFeed;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.serverlink.ServerReads;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.game.interaction.DigToolRule;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 压印器认领了就要投取得动：投料给经界面的动作、收产物给点开界面取的动作、输出槽读数经读写接缝转发；
 * 不收料的 ME 设备照旧给空，不是压印器读不出输出。
 */
class Ae2MachineFeedTest {

    private static final BlockPos AT = new BlockPos(1, 64, 1);
    private static final String INGOT = "minecraft:iron_ingot";

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 终端查找的空替身：压印器的测试用不到终端。 */
    private static final Ae2Terminals NO_TERMINALS = new Ae2Terminals() {
        @Override public List<Ae2Terminals.Terminal> near(ClientLevel level, BlockPos center, int radius) {
            return List.of();
        }

        @Override public boolean stillThere(ClientLevel level, BlockPos block, Direction side) {
            return false;
        }
    };

    /** 终端菜单的空替身：压印器的测试用不到终端界面。 */
    private static final Ae2TerminalMenu NO_MENU = new Ae2TerminalMenu() {
        @Override public boolean belongsTo(AbstractContainerMenu menu, ClientLevel level, BlockPos block,
                Direction side) {
            return false;
        }

        @Override public Optional<Boolean> linked(AbstractContainerMenu menu) {
            return Optional.empty();
        }

        @Override public Optional<List<StockEntry>> stock(AbstractContainerMenu menu) {
            return Optional.empty();
        }

        @Override public void takeStack(AbstractContainerMenu menu, long serial) {
        }

        @Override public void takeOne(AbstractContainerMenu menu, long serial) {
        }

        @Override public void putBack(AbstractContainerMenu menu) {
        }
    };

    /** 压印器读写端替身：固定一格报输出槽里有两件印刷硅。 */
    private static final Ae2Inscribers STUB_INSCRIBERS = new Ae2Inscribers() {
        @Override public Optional<List<MachineState.Shown>> output(ClientLevel level, BlockPos at) {
            return at.equals(AT) ? Optional.of(List.of(new MachineState.Shown("ae2:printed_silicon", 2)))
                    : Optional.empty();
        }
    };

    private static Ae2MachineType typeOf(String kindId) {
        Ae2MachineCatalog.Kind kind = Ae2MachineCatalog.kinds().stream()
                .filter(candidate -> candidate.id().equals(kindId)).findFirst().orElseThrow();
        Ae2Compat compat = new Ae2Compat(NO_TERMINALS, NO_MENU, new NoGrid(), Ae2Patterns.NONE,
                Ae2Transforms.NONE, STUB_INSCRIBERS);
        return new Ae2MachineType(kind, compat, STUB_INSCRIBERS, offlineServices(),
                Ae2Compat.blocksOf(kind.blocks()));
    }

    /** 不在世界里的玩家行为：角色为空，靠近与交互都碰不到；够建出机器类型、判投取动作给不给。 */
    private static PlayerServices offlineServices() {
        Protection nobodyOwns = new Protection((dimension, x, y, z) -> Optional.empty(), List::of,
                name -> Optional.empty(), "self");
        return new PlayerServices(() -> null, (target, permissions) -> null, new Interactions(null),
                itemId -> Optional.empty(), new GearChanges() {
                    @Override public Optional<Action> wear(String itemId, GearSlotName slot) {
                        return Optional.empty();
                    }
                    @Override public Optional<Action> takeOff(GearSlotName slot) {
                        return Optional.empty();
                    }
                }, inner -> inner, (request, permissions) -> null, nobodyOwns, itemId -> Set.of(),
                MenuLayouts.VANILLA, new BlockScanService(), ServerReads.NONE, new BackpackView() {
                    @Override public List<BackpackStack> stacks() { return List.of(); }
                    @Override public int usedSlots() { return 0; }
                    @Override public int totalSlots() { return 36; }
                }, DigToolRule.HELD);
    }

    @Test
    void 压印器给经界面的投料动作_不收料的设备照旧给空() {
        assertTrue(typeOf("ae2:inscriber").feed(AT, INGOT, 4, Permissions.DEFAULT).orElseThrow()
                instanceof CompatMenuFeed, "压印器的投料走点开界面放槽");
        assertTrue(typeOf("ae2:charger").feed(AT, INGOT, 1, Permissions.DEFAULT).isEmpty(),
                "充能器这类没接投料方法的设备不给动作");
    }

    @Test
    void 压印器给点开界面收产物的动作_别的设备不给() {
        assertTrue(typeOf("ae2:inscriber").take(AT, "ae2:printed_silicon", 2, Permissions.DEFAULT).orElseThrow()
                instanceof MenuOutputLook, "收产物是点开界面把产物搬进背包");
        assertTrue(typeOf("ae2:condenser").take(AT, "ae2:matter", 1, Permissions.DEFAULT).isEmpty());
    }

    @Test
    void 输出槽读数经联动入口转发读写接缝() {
        Ae2Compat compat = new Ae2Compat(NO_TERMINALS, NO_MENU, new NoGrid(), Ae2Patterns.NONE,
                Ae2Transforms.NONE, STUB_INSCRIBERS);
        assertEquals(Optional.of(List.of(new MachineState.Shown("ae2:printed_silicon", 2))),
                compat.inscriberOutput(null, AT));
        assertEquals(Optional.empty(), compat.inscriberOutput(null, AT.north()),
                "替身只认一格：别的格没有压印器，读不出输出");
    }

    @Test
    void 压印器才报输出_别的设备没有输出槽() {
        assertEquals(List.of(), typeOf("ae2:controller").output(AT), "控制器没有输出槽");
        assertEquals(List.of(), typeOf("ae2:charger").output(AT));
    }

    private static Ae2MachineType inscriberWithInputs(Map<BlockPos, Ae2Inscribers.Inputs> slotsByPos) {
        Ae2Inscribers stub = new Ae2Inscribers() {
            @Override public Optional<List<MachineState.Shown>> output(ClientLevel level, BlockPos at) {
                return Optional.of(List.of());
            }
            @Override public Optional<Inputs> inputs(ClientLevel level, BlockPos at) {
                return Optional.ofNullable(slotsByPos.get(at));
            }
        };
        Ae2MachineCatalog.Kind kind = Ae2MachineCatalog.kinds().stream()
                .filter(candidate -> candidate.id().equals("ae2:inscriber")).findFirst().orElseThrow();
        Ae2Compat compat = new Ae2Compat(NO_TERMINALS, NO_MENU, new NoGrid(), Ae2Patterns.NONE,
                Ae2Transforms.NONE, stub);
        return new Ae2MachineType(kind, compat, stub, offlineServices(), Ae2Compat.blocksOf(kind.blocks()));
    }

    private static ShownRecipe siliconPrintEmiRecipe() {
        String json = """
                {"type":"ae2:inscriber","mode":"inscribe",
                 "ingredients":{"top":{"item":"ae2:silicon_press"},"middle":{"tag":"c:silicon"}},
                 "result":{"id":"ae2:printed_silicon","count":1}}
                """;
        return new ShownRecipe("ae2:inscriber/silicon_print", "ae2:inscriber", "压印器",
                List.of(ShownStack.item("ae2:inscriber", "压印器", 1)),
                List.of(ShownIngredient.of(ShownStack.item("ae2:silicon_press", "硅压印模板", 1)),
                        new ShownIngredient("c:silicon", List.of(ShownStack.item("ae2:silicon", "硅", 1)), 1)),
                List.of(),
                List.of(ShownStack.item("ae2:printed_silicon", "印制硅", 1)),
                JsonParser.parseString(json).getAsJsonObject());
    }

    private static ShownRecipe siliconPrintJeiRecipe() {
        String json = """
                {"type":"ae2:inscriber","mode":"inscribe",
                 "ingredients":{"top":{"item":"ae2:silicon_press"},"middle":{"tag":"c:silicon"}},
                 "result":{"id":"ae2:printed_silicon","count":1}}
                """;
        return new ShownRecipe("ae2:inscriber/silicon_print", "ae2:inscriber", "压印器",
                List.of(ShownStack.item("ae2:inscriber", "压印器", 1)),
                List.of(new ShownIngredient(null, List.of(ShownStack.item("ae2:silicon", "硅", 1)), 1)),
                List.of(ShownIngredient.of(ShownStack.item("ae2:silicon_press", "硅压印模板", 1))),
                List.of(ShownStack.item("ae2:printed_silicon", "印制硅", 1)),
                JsonParser.parseString(json).getAsJsonObject());
    }

    private static ShownRecipe logicProcessorRecipe() {
        String json = """
                {"type":"ae2:inscriber","mode":"press",
                 "ingredients":{"top":{"item":"ae2:printed_logic_processor"},
                                "middle":{"item":"minecraft:redstone"},
                                "bottom":{"item":"ae2:printed_silicon"}},
                 "result":{"id":"ae2:logic_processor","count":1}}
                """;
        return new ShownRecipe("ae2:inscriber/logic_processor", "ae2:inscriber", "压印器",
                List.of(ShownStack.item("ae2:inscriber", "压印器", 1)),
                List.of(ShownIngredient.of(ShownStack.item("ae2:printed_logic_processor", "印制逻辑电路", 1)),
                        ShownIngredient.of(ShownStack.item("minecraft:redstone", "红石粉", 1)),
                        ShownIngredient.of(ShownStack.item("ae2:printed_silicon", "印制硅", 1))),
                List.of(),
                List.of(ShownStack.item("ae2:logic_processor", "逻辑处理器", 1)),
                JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void 压印模式的不消耗模板跑多批也只投一张_机内已有匹配模板时投零张_压制模式照常乘批数() {
        Map<BlockPos, Ae2Inscribers.Inputs> slots = new HashMap<>();
        slots.put(AT, new Ae2Inscribers.Inputs("", "", ""));
        Ae2MachineType inscriber = inscriberWithInputs(slots);
        assertTrue(inscriber.feedsCatalysts());

        // 空压印器做 4 批印制硅：EMI（模板在 inputs）与 JEI（模板在 catalysts）都只要 1 张硅压印模板、4 份硅，且模板排在硅前面。
        ShownRecipe emi = siliconPrintEmiRecipe();
        assertEquals(1L, inscriber.feedCount(AT, emi, emi.inputs().get(0), 4, false));
        assertEquals(4L, inscriber.feedCount(AT, emi, emi.inputs().get(1), 4, false));
        assertTrue(inscriber.feedsFirst(emi, emi.inputs().get(0)));

        ShownRecipe jei = siliconPrintJeiRecipe();
        assertEquals(1L, inscriber.feedCount(AT, jei, jei.catalysts().get(0), 4, true));
        assertEquals(4L, inscriber.feedCount(AT, jei, jei.inputs().get(0), 4, false));

        // 机内顶格或底格原本就放着硅压印模板时，不再索要第 2 张（返回 0），且无槽位冲突。
        slots.put(AT, new Ae2Inscribers.Inputs("", "ae2:silicon_press", ""));
        assertEquals(0L, inscriber.feedCount(AT, emi, emi.inputs().get(0), 4, false));
        assertEquals(0L, inscriber.feedCount(AT, jei, jei.catalysts().get(0), 4, true));
        assertTrue(inscriber.whyCannotFeed(AT, "#c:silicon").isEmpty());

        // 压制处理器（mode=press）三格都是消耗品，跑 4 批时三样全要 4 件，且顶、底印制板排在红石前。
        slots.put(AT, new Ae2Inscribers.Inputs("", "", ""));
        ShownRecipe press = logicProcessorRecipe();
        assertEquals(4L, inscriber.feedCount(AT, press, press.inputs().get(0), 4, false));
        assertEquals(4L, inscriber.feedCount(AT, press, press.inputs().get(1), 4, false));
        assertEquals(4L, inscriber.feedCount(AT, press, press.inputs().get(2), 4, false));
        assertTrue(inscriber.feedsFirst(press, press.inputs().get(0)));
        assertTrue(inscriber.feedsFirst(press, press.inputs().get(2)));
    }

    @Test
    void 机内占着别的配方留下的不兼容模板时直接给出清楚中文原因_自己放进的模板收产物时顺手收回() {
        Map<BlockPos, Ae2Inscribers.Inputs> slots = new HashMap<>();
        slots.put(AT, new Ae2Inscribers.Inputs("ae2:logic_processor_press", "", ""));
        Ae2MachineType inscriber = inscriberWithInputs(slots);
        ShownRecipe siliconPrint = siliconPrintEmiRecipe();

        // 压印器顶格正占着逻辑压印模板，要做印制硅时冲突拦截直接点名占用物品。
        inscriber.feedCount(AT, siliconPrint, siliconPrint.inputs().get(1), 2, false);
        String why = inscriber.whyCannotFeed(AT, "#c:silicon").orElseThrow();
        assertTrue(why.contains("ae2:logic_processor_press") && why.contains("不兼容"), why);

        // 机内原有硅压印模板（非本会话放进）时，take 走普通 MenuOutputLook，不动原有模板。
        slots.put(AT, new Ae2Inscribers.Inputs("ae2:silicon_press", "", ""));
        Ae2MachineType withPreexisting = inscriberWithInputs(slots);
        assertEquals(0L, withPreexisting.feedCount(AT, siliconPrint, siliconPrint.inputs().get(0), 2, false));
        assertInstanceOf(MenuOutputLook.class,
                withPreexisting.take(AT, "ae2:printed_silicon", 2, Permissions.DEFAULT).orElseThrow());

        // 端到端验证 Ae2InscriberTake：顶格 0 放着本趟放进的模板（用铁锭模拟），底格 1 放着机内原有的另一张模板（用金锭模拟），
        // 输出格 3 放着 2 件产物（用钻石模拟）——收完后产物与自家模板都进背包，机内原有模板纹丝不动。
        FakeInscriberMenu menu = new FakeInscriberMenu();
        menu.container.set(0, new ItemStack(Items.IRON_INGOT, 1));
        menu.container.set(1, new ItemStack(Items.GOLD_INGOT, 1));
        menu.container.set(3, new ItemStack(Items.DIAMOND, 2));
        Set<String> ownPlates = new LinkedHashSet<>(List.of("minecraft:iron_ingot"));
        Ae2InscriberTake take = new Ae2InscriberTake("minecraft:diamond", ownPlates, new MenuOpening() {
            private boolean opened;
            @Override public ActionStatus tick(TickContext context) {
                opened = true;
                return ActionStatus.done();
            }
            @Override public Optional<OpenedMenu> opened() {
                return opened ? Optional.of(menu) : Optional.empty();
            }
            @Override public String describe() { return "打开测试压印器界面"; }
        }, () -> null);

        ActionStatus last = ActionStatus.running();
        for (int i = 0; i < 40 && !(last instanceof ActionStatus.Done); i++) {
            last = take.tick(TickContext.of(i, null));
        }
        assertInstanceOf(ActionStatus.Done.class, last);
        assertEquals(2, take.seen());
        assertTrue(menu.container.get(3).isEmpty(), "输出格产物已收走");
        assertTrue(menu.container.get(0).isEmpty(), "本趟自己放进顶格的模板已顺手收回");
        assertEquals(1, menu.container.get(1).getCount(), "机内底格原有的模板保留不动");
        assertTrue(ownPlates.isEmpty(), "收回后待回收记录清零");
    }

    /** 模拟压印器 8 格容器侧（0 顶、1 底、2 中间、3 输出、4..7 升级）与 36 格角色背包的快速移动界面。 */
    private static final class FakeInscriberMenu implements OpenedMenu {
        final List<ItemStack> container = new ArrayList<>();
        final List<ItemStack> player = new ArrayList<>();
        boolean closed;

        FakeInscriberMenu() {
            for (int i = 0; i < 8; i++) container.add(ItemStack.EMPTY);
            for (int i = 0; i < 36; i++) player.add(ItemStack.EMPTY);
        }

        private final MenuChannel channel = new MenuChannel() {
            @Override public boolean stillOpen() { return !closed; }
            @Override public boolean cursorCarrying() { return false; }
            @Override public SlotSnapshot cursor() { return SlotSnapshot.empty(); }
            @Override public boolean click(int slot, int button) { return true; }
            @Override public boolean quickMove(int slot) { return FakeInscriberMenu.this.quickMove(slot); }
            @Override public boolean selectTradeOffer(int offerIndex) { return false; }
            @Override public void requestClose() { closed = true; }
        };

        private static final MenuSlots SLOTS = new MenuSlots() {
            @Override public String menuTypeId() { return "ae2:inscriber"; }
            @Override public int slotCount() { return 44; }
            @Override public boolean playerBacked(int slot) { return slot >= 8; }
            @Override public int playerInventoryIndex(int slot) { return slot - 8; }
        };

        @Override public Optional<MenuContent.Reading> reading() {
            if (closed) return Optional.empty();
            List<Integer> cIds = new ArrayList<>();
            List<SlotSnapshot> cSnaps = new ArrayList<>();
            for (int i = 0; i < container.size(); i++) {
                cIds.add(i);
                cSnaps.add(container.get(i).isEmpty() ? SlotSnapshot.empty() : SlotSnapshot.of(container.get(i)));
            }
            List<Integer> pIds = new ArrayList<>();
            List<Integer> pIdx = new ArrayList<>();
            List<SlotSnapshot> pSnaps = new ArrayList<>();
            for (int i = 0; i < player.size(); i++) {
                pIds.add(8 + i);
                pIdx.add(i);
                pSnaps.add(player.get(i).isEmpty() ? SlotSnapshot.empty() : SlotSnapshot.of(player.get(i)));
            }
            return Optional.of(new MenuContent.Reading(channel, SLOTS, cIds, pIds, pIdx, cSnaps, pSnaps));
        }

        @Override public boolean busy() { return false; }
        @Override public boolean cursorEmpty() { return true; }
        @Override public SlotSnapshot cursor() { return SlotSnapshot.empty(); }

        @Override public boolean quickMove(int slotId) {
            if (slotId >= 8) return true;
            ItemStack moving = container.get(slotId);
            if (moving.isEmpty()) return true;
            for (int i = 0; i < player.size(); i++) {
                if (player.get(i).isEmpty()) {
                    player.set(i, moving.copy());
                    container.set(slotId, ItemStack.EMPTY);
                    return true;
                }
            }
            return true;
        }

        @Override public boolean click(int slotId, int button) { return true; }
        @Override public void noteCursorTakenFrom(int slotId) {}

        @Override public Action closing() {
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    closed = true;
                    return ActionStatus.done();
                }
                @Override public String describe() { return "关上压印器界面"; }
            };
        }

        @Override public void abandon() { closed = true; }
    }
}
