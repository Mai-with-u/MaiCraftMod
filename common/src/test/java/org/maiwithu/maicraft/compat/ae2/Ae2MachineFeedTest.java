// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.maiwithu.maicraft.ability.machine.spi.FeedPlan;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.inventory.hands.GearChanges;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.menu.MenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuOutputLook;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.perception.FacilityKinds;
import org.maiwithu.maicraft.behavior.perception.FakeBlockTags;
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
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
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
        assertTrue(typeOf("ae2:inscriber").feed(AT, new FeedPlan.Entry(INGOT, 4, false), Permissions.DEFAULT)
                .orElseThrow() instanceof CompatMenuFeed, "压印器的投料走点开界面放槽");
        assertTrue(typeOf("ae2:charger").feed(AT, new FeedPlan.Entry(INGOT, 1, false), Permissions.DEFAULT).isEmpty(),
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

        // 空压印器做 4 批印制硅：EMI（模板在 inputs）与 JEI（模板在 catalysts）的计划都是
        // 1 张硅压印模板、4 份 #c:silicon，模板排在硅前面，且模板标明收产物时一并收回。
        ShownRecipe emi = siliconPrintEmiRecipe();
        FeedPlan emiPlan = inscriberWithInputs(slots).plan(AT, emi, 4, Permissions.DEFAULT);
        assertEquals(List.of(new FeedPlan.Entry("ae2:silicon_press", 1, true),
                new FeedPlan.Entry("#c:silicon", 4, false)), emiPlan.entries());
        assertTrue(emiPlan.conflict().isEmpty(), emiPlan.conflict().toString());
        // JEI 把硅写成具体物品（没有标签）：计划照它的写法给，模板照旧排在前、标明收回。
        ShownRecipe jei = siliconPrintJeiRecipe();
        assertEquals(List.of(new FeedPlan.Entry("ae2:silicon_press", 1, true),
                new FeedPlan.Entry("ae2:silicon", 4, false)),
                inscriberWithInputs(slots).plan(AT, jei, 4, Permissions.DEFAULT).entries());

        // 机内顶格或底格原本就放着硅压印模板时，不再索要第 2 张，说明里写明不用再放，且无槽位冲突。
        slots.put(AT, new Ae2Inscribers.Inputs("", "ae2:silicon_press", ""));
        FeedPlan withTemplate = inscriberWithInputs(slots).plan(AT, emi, 4, Permissions.DEFAULT);
        assertEquals(List.of(new FeedPlan.Entry("#c:silicon", 4, false)), withTemplate.entries());
        assertTrue(withTemplate.notes().stream().anyMatch(note -> note.contains("ae2:silicon_press")
                && note.contains("不用再放")), withTemplate.notes().toString());
        assertTrue(withTemplate.conflict().isEmpty());

        // 压制处理器（mode=press）三格都是消耗品，跑 4 批时三样全要 4 件，且顶、底印制板排在红石前。
        slots.put(AT, new Ae2Inscribers.Inputs("", "", ""));
        ShownRecipe press = logicProcessorRecipe();
        FeedPlan pressPlan = inscriberWithInputs(slots).plan(AT, press, 4, Permissions.DEFAULT);
        assertEquals(List.of(new FeedPlan.Entry("ae2:printed_logic_processor", 4, false),
                new FeedPlan.Entry("ae2:printed_silicon", 4, false),
                new FeedPlan.Entry("minecraft:redstone", 4, false)), pressPlan.entries());
    }

    @Test
    void 机内占着别的配方留下的不兼容模板时直接给出清楚中文原因_自己放进的模板收产物时顺手收回() {
        Map<BlockPos, Ae2Inscribers.Inputs> slots = new HashMap<>();
        slots.put(AT, new Ae2Inscribers.Inputs("ae2:logic_processor_press", "", ""));
        Ae2MachineType inscriber = inscriberWithInputs(slots);
        ShownRecipe siliconPrint = siliconPrintEmiRecipe();

        // 压印器顶格正占着逻辑压印模板，要做印制硅时计划直接给冲突与腾空动作，点名占用物品。
        FeedPlan blocked = inscriber.plan(AT, siliconPrint, 2, Permissions.DEFAULT);
        String why = blocked.conflict().orElseThrow();
        assertTrue(why.contains("ae2:logic_processor_press") && why.contains("不兼容"), why);
        assertTrue(blocked.clearing().isPresent(), "有冲突就给腾空动作");

        // 机内原有硅压印模板（非本会话放进）时，take 走普通 MenuOutputLook，不动原有模板。
        slots.put(AT, new Ae2Inscribers.Inputs("ae2:silicon_press", "", ""));
        Ae2MachineType withPreexisting = inscriberWithInputs(slots);
        assertTrue(inscriberWithInputs(slots).plan(AT, siliconPrint, 2, Permissions.DEFAULT).conflict().isEmpty());
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

    @Test
    void 压印器状态透出顶底中间输出全部四槽物品与数量() {
        Ae2Inscribers.Slots synced = new Ae2Inscribers.Slots(
                new MachineState.Shown("ae2:silicon_press", 1),
                null,
                new MachineState.Shown("ae2:silicon", 4),
                new MachineState.Shown("ae2:printed_silicon", 2));
        Ae2Inscribers stub = new Ae2Inscribers() {
            @Override public Optional<List<MachineState.Shown>> output(ClientLevel level, BlockPos at) {
                return Optional.of(List.of(new MachineState.Shown("ae2:printed_silicon", 2)));
            }
            @Override public Optional<Slots> slots(ClientLevel level, BlockPos at) {
                return at.equals(AT) ? Optional.of(synced) : Optional.empty();
            }
        };
        Ae2MachineCatalog.Kind kind = Ae2MachineCatalog.kinds().stream()
                .filter(candidate -> candidate.id().equals("ae2:inscriber")).findFirst().orElseThrow();
        Ae2Compat compat = new Ae2Compat(NO_TERMINALS, NO_MENU, new NoGrid(), Ae2Patterns.NONE,
                Ae2Transforms.NONE, stub);
        Ae2MachineType inscriber = new Ae2MachineType(kind, compat, stub, offlineServices(),
                Ae2Compat.blocksOf(kind.blocks()));

        MachineState state = inscriber.state(AT);
        assertEquals(3, state.shown().size(), "非空的顶模板、中间输入、输出三格全部写入 shown");
        assertEquals("ae2:silicon_press ×1", state.readings().get("top_slot"));
        assertEquals("empty", state.readings().get("bottom_slot"));
        assertEquals("ae2:silicon ×4", state.readings().get("input_slot"));
        assertEquals("ae2:printed_silicon ×2", state.readings().get("output_slot"));
    }

    @Test
    void 投料前自动清理顶底中间输出冲突占槽物_保留兼容模板与升级卡不动() {
        // 用原版注册物品构造一条压印配方：底格或顶格需要铁锭模板，中间格需要红石，产出金锭。
        String json = """
                {"type":"ae2:inscriber","mode":"inscribe",
                 "ingredients":{"top":{"item":"minecraft:iron_ingot"},"middle":{"item":"minecraft:redstone"}},
                 "result":{"id":"minecraft:gold_ingot","count":1}}
                """;
        ShownRecipe recipe = new ShownRecipe("test:inscriber/gold", "ae2:inscriber", "压印器",
                List.of(ShownStack.item("ae2:inscriber", "压印器", 1)),
                List.of(ShownIngredient.of(ShownStack.item("minecraft:iron_ingot", "铁模板", 1)),
                        ShownIngredient.of(ShownStack.item("minecraft:redstone", "红石", 1))),
                List.of(),
                List.of(ShownStack.item("minecraft:gold_ingot", "印制金", 1)),
                JsonParser.parseString(json).getAsJsonObject());

        // 现场菜单：0 顶格占着异种模板（铜锭），1 底格放着匹配的铁锭模板，2 中间格占着残留半成品（煤炭），
        // 3 输出格占着异种旧产物（绿宝石），4 升级格插着加速卡（钻石代指）。
        FakeInscriberMenu menu = new FakeInscriberMenu();
        menu.container.set(0, new ItemStack(Items.COPPER_INGOT, 1));
        menu.container.set(1, new ItemStack(Items.IRON_INGOT, 1));
        menu.container.set(2, new ItemStack(Items.COAL, 3));
        menu.container.set(3, new ItemStack(Items.EMERALD, 2));
        menu.container.set(4, new ItemStack(Items.DIAMOND, 4));

        Ae2Inscribers stub = new Ae2Inscribers() {
            @Override public Optional<List<MachineState.Shown>> output(ClientLevel level, BlockPos at) {
                return Optional.of(List.of());
            }
            @Override public Optional<Slots> slots(ClientLevel level, BlockPos at) {
                return Optional.of(new Slots(
                        new MachineState.Shown("minecraft:copper_ingot", 1),
                        new MachineState.Shown("minecraft:iron_ingot", 1),
                        new MachineState.Shown("minecraft:coal", 3),
                        new MachineState.Shown("minecraft:emerald", 2)));
            }
        };
        Ae2MachineCatalog.Kind kind = Ae2MachineCatalog.kinds().stream()
                .filter(candidate -> candidate.id().equals("ae2:inscriber")).findFirst().orElseThrow();
        Ae2Compat compat = new Ae2Compat(NO_TERMINALS, NO_MENU, new NoGrid(), Ae2Patterns.NONE,
                Ae2Transforms.NONE, stub);
        Ae2MachineType inscriber = new Ae2MachineType(kind, compat, stub, offlineServices(),
                Ae2Compat.blocksOf(kind.blocks()), (pos, perms) -> immediateOpening(menu));

        Action clear = inscriber.plan(AT, recipe, 2, Permissions.DEFAULT).clearing().orElseThrow();
        ActionStatus status = ActionStatus.running();
        for (int i = 0; i < 40 && !(status instanceof ActionStatus.Done); i++) {
            status = clear.tick(TickContext.of(i, null));
        }
        assertInstanceOf(ActionStatus.Done.class, status);
        assertTrue(menu.closed, "清理完毕后自动关上压印器界面");
        assertTrue(menu.container.get(0).isEmpty(), "冲突的顶格异种模板已取回背包");
        assertEquals(1, menu.container.get(1).getCount(), "底格兼容的铁模板原封保留供本趟复用");
        assertTrue(menu.container.get(2).isEmpty(), "中间格残留半成品已取回背包");
        assertTrue(menu.container.get(3).isEmpty(), "输出格异种阻塞产物已取回背包");
        assertEquals(4, menu.container.get(4).getCount(), "第 4 格升级卡槽纹丝不动");
    }

    @Test
    void 单取通路从附近压印器槽位取出指定物品回背包_且压印器不标为通用储物容器() {
        FacilityKinds kinds = new FacilityKinds(FakeBlockTags.vanilla());
        assertFalse(kinds.isContainer("ae2:inscriber"), "压印器绝不能标成通用容器，防止存东西把杂物塞进去");

        FakeInscriberMenu menu = new FakeInscriberMenu();
        menu.container.set(0, new ItemStack(Items.IRON_INGOT, 1));
        menu.container.set(4, new ItemStack(Items.DIAMOND, 4));
        Ae2Inscribers stub = new Ae2Inscribers() {
            @Override public Optional<List<MachineState.Shown>> output(ClientLevel level, BlockPos at) {
                return Optional.of(List.of());
            }
            @Override public Optional<Slots> slots(ClientLevel level, BlockPos at) {
                return at.equals(AT)
                        ? Optional.of(new Slots(new MachineState.Shown("minecraft:iron_ingot", 1), null, null, null))
                        : Optional.empty();
            }
            @Override public List<BlockPos> near(ClientLevel level, BlockPos center, int radius) {
                return List.of(AT);
            }
        };
        Ae2Compat compat = new Ae2Compat(NO_TERMINALS, NO_MENU, new NoGrid(), Ae2Patterns.NONE,
                Ae2Transforms.NONE, stub);
        Ae2InscriberSource source = new Ae2InscriberSource(compat, offlineServices(),
                (pos, perms) -> immediateOpening(menu));
        assertEquals(AcquireVia.CONTAINER, source.via());

        ItemRequest wantPlate = new ItemRequest(WantedItem.ofItem("minecraft:iron_ingot"), 1, "取回压印模板");
        SourceContext ctx = SourceContext.unscoped(WorldPosition.here(0, 64, 0), Permissions.DEFAULT);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source.quote(wantPlate, ctx));
        assertEquals(1, offer.obtainableCount());

        Action take = source.begin(wantPlate, offer, ctx).orElseThrow();
        ActionStatus status = ActionStatus.running();
        for (int i = 0; i < 40 && !(status instanceof ActionStatus.Done); i++) {
            status = take.tick(TickContext.of(i, null));
        }
        assertInstanceOf(ActionStatus.Done.class, status);
        assertTrue(menu.container.get(0).isEmpty(), "指定占槽物品已从压印器取出进角色背包");
        assertTrue(menu.player.stream().anyMatch(stack -> stack.is(Items.IRON_INGOT) && stack.getCount() == 1));
        assertEquals(4, menu.container.get(4).getCount(), "升级卡槽不受影响");
    }

    private static MenuOpening immediateOpening(FakeInscriberMenu menu) {
        return new MenuOpening() {
            private boolean opened;
            @Override public ActionStatus tick(TickContext context) {
                opened = true;
                return ActionStatus.done();
            }
            @Override public Optional<OpenedMenu> opened() {
                return opened ? Optional.of(menu) : Optional.empty();
            }
            @Override public String describe() { return "打开测试压印器界面"; }
        };
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
            @Override public boolean playerBacked(int slot) { return slot >= 4 && slot < 40; }
            @Override public int playerInventoryIndex(int slot) { return slot - 4; }
        };

        @Override public Optional<MenuContent.Reading> reading() {
            if (closed) return Optional.empty();
            List<Integer> pIds = new ArrayList<>();
            List<Integer> pIdx = new ArrayList<>();
            List<SlotSnapshot> pSnaps = new ArrayList<>();
            for (int i = 0; i < player.size(); i++) {
                pIds.add(4 + i);
                pIdx.add(i);
                pSnaps.add(player.get(i).isEmpty() ? SlotSnapshot.empty() : SlotSnapshot.of(player.get(i)));
            }
            // 经真实 Ae2InscriberLayout 将实机 [0..3 升级, 40..43 工作] 重排为 [40..43 工作, 0..3 升级]。
            var layout = assertInstanceOf(MenuLayout.Supported.class,
                    new Ae2InscriberLayout().classify(SLOTS, pIds, List.of(0, 1, 2, 3, 40, 41, 42, 43)));
            List<Integer> cIds = layout.containerSlots();
            List<SlotSnapshot> cSnaps = new ArrayList<>(cIds.size());
            for (int rawSlotId : cIds) {
                ItemStack stack = stackAtRawSlot(rawSlotId);
                cSnaps.add(stack.isEmpty() ? SlotSnapshot.empty() : SlotSnapshot.of(stack));
            }
            return Optional.of(new MenuContent.Reading(channel, SLOTS, cIds, pIds, pIdx, cSnaps, pSnaps));
        }

        private int logicalContainerIndex(int rawSlotId) {
            if (rawSlotId >= 40 && rawSlotId < 44) return rawSlotId - 40;
            if (rawSlotId >= 0 && rawSlotId < 4) return rawSlotId + 4;
            return -1;
        }

        private ItemStack stackAtRawSlot(int rawSlotId) {
            int idx = logicalContainerIndex(rawSlotId);
            return idx < 0 ? ItemStack.EMPTY : container.get(idx);
        }

        @Override public boolean busy() { return false; }
        @Override public boolean cursorEmpty() { return true; }
        @Override public SlotSnapshot cursor() { return SlotSnapshot.empty(); }

        @Override public boolean quickMove(int slotId) {
            int idx = logicalContainerIndex(slotId);
            if (idx < 0) return true;
            ItemStack moving = container.get(idx);
            if (moving.isEmpty()) return true;
            for (int i = 0; i < player.size(); i++) {
                if (player.get(i).isEmpty()) {
                    player.set(i, moving.copy());
                    container.set(idx, ItemStack.EMPTY);
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
