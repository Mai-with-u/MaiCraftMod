// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.touhoulittlemaid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.maid.spi.MaidSettings;
import org.maiwithu.maicraft.behavior.menu.MenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.kernel.event.ChatEventLog;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 改女仆的设置：已是目标值不开界面；值不认识附候选；按顺序改、每项等同步下来的值变了再改下一项；
 * 聊天栏回应、日程点确认屏、灰着的工作按钮按游戏拒绝停下；等满没变按没能确认停下；她睡着界面打不开；
 * 装备从背包点进她的装备格、空字符串拿回背包、背包里没有按缺东西。
 */
class MaidConfigureTest {

    private static final int ID = 7;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private final MaidFakes.Reads reads = new MaidFakes.Reads();
    private final MaidFakes.Actions actions = new MaidFakes.Actions(reads);
    private final ChatEventLog chat = new ChatEventLog();
    private final Entity maid = MaidFakes.creature(ID);
    private final TouhouLittleMaidCompat compat = new TouhouLittleMaidCompat(reads, actions);
    private final FakeMenu menu = new FakeMenu();
    private int opened;
    private Problem openFails;
    private List<String> carried = List.of();

    MaidConfigureTest() {
        reads.states.put(ID, MaidFakes.maid(ID, "小雪", List.of()));
    }

    /** 她的界面替身：0–35 角色背包，36–41 她的装备格；左键拿起、放下、交换，快速移动把装备格的东西放回第一个空格。 */
    private final class FakeMenu implements OpenedMenu {
        final Map<Integer, ItemStack> slots = new HashMap<>();
        ItemStack cursor = ItemStack.EMPTY;
        boolean closed;
        /** 这些格子不收光标上的东西（盔甲格只收能穿在那个位置的）。 */
        final Set<Integer> refusing = new HashSet<>();

        ItemStack at(int slot) {
            return slots.getOrDefault(slot, ItemStack.EMPTY);
        }

        @Override public Optional<MenuContent.Reading> reading() {
            List<Integer> playerIds = new ArrayList<>();
            List<SlotSnapshot> playerSnapshots = new ArrayList<>();
            for (int i = 0; i < 36; i++) {
                playerIds.add(i);
                playerSnapshots.add(at(i).isEmpty() ? SlotSnapshot.empty() : SlotSnapshot.of(at(i)));
            }
            MenuChannel channel = new MenuChannel() {
                @Override public boolean stillOpen() { return !closed; }
                @Override public boolean cursorCarrying() { return !cursor.isEmpty(); }
                @Override public SlotSnapshot cursor() { return SlotSnapshot.empty(); }
                @Override public boolean click(int slot, int button) { return true; }
                @Override public boolean quickMove(int slot) { return true; }
                @Override public boolean selectTradeOffer(int offerIndex) { return true; }
                @Override public void requestClose() {}
            };
            MenuSlots menuSlots = new MenuSlots() {
                @Override public String menuTypeId() { return "touhou_little_maid:maid_empty_backpack_container"; }
                @Override public int slotCount() { return 49; }
                @Override public boolean playerBacked(int slot) { return slot < 36; }
                @Override public int playerInventoryIndex(int slot) { return slot; }
            };
            return Optional.of(new MenuContent.Reading(channel, menuSlots, List.of(), playerIds, playerIds, List.of(),
                    playerSnapshots));
        }

        @Override public boolean busy() { return false; }

        @Override public boolean cursorEmpty() { return cursor.isEmpty(); }

        @Override public SlotSnapshot cursor() { return cursor.isEmpty() ? SlotSnapshot.empty() : SlotSnapshot.of(cursor); }

        @Override public boolean quickMove(int slotId) {
            ItemStack moving = at(slotId);
            for (int i = 0; i < 36 && !moving.isEmpty(); i++) {
                if (at(i).isEmpty()) {
                    slots.put(i, moving);
                    slots.remove(slotId);
                    break;
                }
            }
            return true;
        }

        @Override public boolean click(int slotId, int button) {
            if (!cursor.isEmpty() && refusing.contains(slotId)) return true;
            ItemStack inSlot = at(slotId);
            slots.put(slotId, cursor);
            cursor = inSlot;
            return true;
        }

        @Override public void noteCursorTakenFrom(int slotId) {}

        @Override public Action closing() {
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    closed = true;
                    return ActionStatus.done();
                }

                @Override public String describe() {
                    return "关上";
                }
            };
        }

        @Override public void abandon() {
            closed = true;
        }
    }

    private final MaidConfigure.MenuSteps steps = new MaidConfigure.MenuSteps() {
        @Override public MenuOpening open(int entityId, Permissions permissions) {
            opened++;
            return new MenuOpening() {
                @Override public Optional<OpenedMenu> opened() {
                    return Optional.of(menu);
                }

                @Override public ActionStatus tick(TickContext context) {
                    return openFails == null ? ActionStatus.done() : ActionStatus.failed(openFails);
                }

                @Override public String describe() {
                    return "打开她的界面";
                }
            };
        }

        @Override public Action submit(OpenedMenu on, String what, Runnable send, BooleanSupplier confirmed,
                int timeoutTicks) {
            return new Action() {
                private boolean sent;
                private int waited;

                @Override public ActionStatus tick(TickContext context) {
                    if (!sent) {
                        sent = true;
                        send.run();
                        return ActionStatus.progressed();
                    }
                    if (confirmed.getAsBoolean()) return ActionStatus.done();
                    if (++waited >= timeoutTicks) {
                        return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, what + "，没能确认", null));
                    }
                    return ActionStatus.running();
                }

                @Override public String describe() {
                    return what;
                }
            };
        }
    };

    // 她身上穿戴的读的是界面替身里的装备格（真实游戏里两边由服务器同步成一样）。
    private ItemStack worn(Entity entity, EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> menu.at(36);
            case CHEST -> menu.at(37);
            case LEGS -> menu.at(38);
            case FEET -> menu.at(39);
            case MAINHAND -> menu.at(40);
            case OFFHAND -> menu.at(41);
            default -> ItemStack.EMPTY;
        };
    }

    private MaidSettings.Configure configure(String... keyValues) {
        MaidConfigure configure = new MaidConfigure(compat, MaidFakes.services(chat, carried), steps, this::worn);
        MaidSettings.Maid found = configure.maid(null, maid).orElseThrow();
        List<MaidSettings.Setting> settings = new ArrayList<>();
        for (int i = 0; i < keyValues.length; i += 2) settings.add(new MaidSettings.Setting(keyValues[i], keyValues[i + 1]));
        return found.configure(settings, Permissions.DEFAULT);
    }

    private ActionStatus run(MaidSettings.Configure configure) {
        MaidFakes.Tick tick = new MaidFakes.Tick();
        for (int i = 0; i < 500; i++) {
            ActionStatus status = configure.tick(tick);
            if (status instanceof ActionStatus.Done || status instanceof ActionStatus.Failed) return status;
            tick.gameTick++;
        }
        throw new AssertionError("改设置没有按时收场");
    }

    @Test
    void 都已是目标值_不开界面也不发包() {
        MaidSettings.Configure configure = configure("stay", "with_you", "pickup", "true", "skin", "geckolib:winefox");
        assertInstanceOf(ActionStatus.Done.class, run(configure));
        assertEquals(0, opened);
        assertTrue(actions.sent.isEmpty());
        assertEquals(List.of("stay", "pickup", "skin"), configure.report().skipped());
    }

    @Test
    void 按给的顺序一项项改_改完关上界面() {
        MaidSettings.Configure configure = configure("skin", "geckolib:winefox_blue", "stay", "in_area",
                "work_shift", "night");
        assertInstanceOf(ActionStatus.Done.class, run(configure));
        assertEquals(1, opened);
        assertEquals("skin geckolib:winefox_blue", actions.sent.get(0));
        assertTrue(actions.sent.get(1).startsWith("config true true"), "驻守那一包其余三项照她此刻的值带上");
        assertTrue(actions.sent.get(2).startsWith("config true true true NIGHT"), "改班次时驻守已经是新的");
        assertEquals(3, configure.report().applied().size());
        assertEquals("with_you", configure.report().applied().get(1).from());
        assertEquals("in_area", configure.report().applied().get(1).to());
        assertEquals(3, configure.report().changes().size());
        assertTrue(menu.closed);
    }

    @Test
    void 皮肤不认识_附最相近的三个且一项都不改() {
        MaidSettings.Configure configure = configure("pickup", "false", "skin", "winefox_hanfu");
        Problem problem = assertInstanceOf(ActionStatus.Failed.class, run(configure)).problem();
        assertEquals(Problem.Kind.INVALID_PARAMETER, problem.kind());
        assertTrue(problem.suggestion().contains("geckolib:winefox_hanfu"));
        assertEquals(0, opened);
    }

    @Test
    void 工作不认识_列出能切的工作() {
        Problem problem = assertInstanceOf(ActionStatus.Failed.class, run(configure("work", "fishing"))).problem();
        assertEquals(Problem.Kind.INVALID_PARAMETER, problem.kind());
        assertTrue(problem.suggestion().contains("touhou_little_maid:farm（种田）"));
    }

    @Test
    void 换工作_省了命名空间也认_带界面写的条件与此刻活动() {
        MaidSettings.Configure configure = configure("work", "farm");
        assertInstanceOf(ActionStatus.Done.class, run(configure));
        assertEquals("work touhou_little_maid:farm", actions.sent.getFirst());
        assertEquals(List.of("主手需要锄：不满足"), configure.report().workHint());
        assertEquals("work", configure.report().shiftNow());
    }

    @Test
    void 工作按钮是灰的_不点并附条件() {
        reads.works.set(1, new MaidReads.Work("touhou_little_maid:farm", "种田", false, List.of("要有锄：不满足")));
        Problem problem = assertInstanceOf(ActionStatus.Failed.class, run(configure("work", "farm"))).problem();
        assertEquals(Problem.Kind.REFUSED_BY_GAME, problem.kind());
        assertTrue(problem.message().contains("要有锄：不满足"));
        assertTrue(actions.sent.isEmpty());
        assertTrue(menu.closed, "停下前关上界面");
    }

    @Test
    void 服务器关了换皮肤_聊天栏回应按游戏拒绝附原话() {
        actions.skinAllowed = false;
        MaidFakes.systemMessage(chat, "出手前的旧话");
        MaidSettings.Configure configure = configure("skin", "geckolib:winefox_blue");
        MaidFakes.Tick tick = new MaidFakes.Tick();
        ActionStatus status = null;
        for (int i = 0; i < 200; i++) {
            status = configure.tick(tick);
            if (actions.sent.size() == 1 && i == 3) MaidFakes.systemMessage(chat, "服务器禁止更换女仆模型");
            if (status instanceof ActionStatus.Failed || status instanceof ActionStatus.Done) break;
            tick.gameTick++;
        }
        Problem problem = assertInstanceOf(ActionStatus.Failed.class, status).problem();
        assertEquals(Problem.Kind.REFUSED_BY_GAME, problem.kind());
        assertTrue(problem.message().contains("服务器禁止更换女仆模型"));
        assertFalse(problem.message().contains("出手前的旧话"), "出手前的聊天不算");
    }

    @Test
    void 开驻守弹日程点确认屏_关掉按游戏拒绝() {
        actions.schedulePosPops = true;
        Problem problem = assertInstanceOf(ActionStatus.Failed.class, run(configure("stay", "in_area"))).problem();
        assertEquals(Problem.Kind.REFUSED_BY_GAME, problem.kind());
        assertTrue(problem.message().contains("日程点"));
        assertFalse(reads.schedulePos, "确认屏关掉了");
    }

    @Test
    void 等满没变_没能确认并停在这一项() {
        actions.ignored = true;
        MaidSettings.Configure configure = configure("pickup", "false", "work_shift", "night");
        assertInstanceOf(ActionStatus.Done.class, run(configure));
        assertEquals(1, configure.report().unconfirmed().size());
        assertEquals(1, actions.sent.size(), "停在拾取这一项，不往下改班次");
        assertTrue(configure.report().applied().isEmpty());
    }

    @Test
    void 她在睡觉_界面打不开如实交回() {
        openFails = Problem.of(Problem.Kind.REFUSED_BY_GAME, "那只生物（实体 7）没打开界面：它在睡觉", null);
        Problem problem = assertInstanceOf(ActionStatus.Failed.class, run(configure("stay", "in_area"))).problem();
        assertTrue(problem.message().contains("睡觉"));
    }

    @Test
    void 装备_从背包点进她的主手_原来拿的换回背包() {
        carried = List.of("minecraft:iron_hoe");
        menu.slots.put(5, new ItemStack(Items.IRON_HOE));
        menu.slots.put(40, new ItemStack(Items.WOODEN_SWORD));
        MaidSettings.Configure configure = configure("mainhand", "#minecraft:hoes");
        assertInstanceOf(ActionStatus.Done.class, run(configure));
        assertEquals(Items.IRON_HOE, menu.at(40).getItem());
        assertEquals(Items.WOODEN_SWORD, menu.at(5).getItem(), "她原来拿的放回刚才那一格");
        assertTrue(menu.cursor.isEmpty());
        assertEquals("minecraft:wooden_sword", configure.report().applied().getFirst().from());
    }

    @Test
    void 装备_她那一格不收_放回原格按游戏拒绝() {
        carried = List.of("minecraft:iron_hoe");
        menu.slots.put(5, new ItemStack(Items.IRON_HOE));
        menu.refusing.add(36);
        Problem problem = assertInstanceOf(ActionStatus.Failed.class, run(configure("head", "minecraft:iron_hoe")))
                .problem();
        assertEquals(Problem.Kind.REFUSED_BY_GAME, problem.kind());
        assertEquals(Items.IRON_HOE, menu.at(5).getItem(), "放回刚才那一格");
        assertTrue(menu.cursor.isEmpty());
        assertTrue(menu.closed);
    }

    @Test
    void 装备_空字符串拿回角色背包() {
        menu.slots.put(36, new ItemStack(Items.IRON_HELMET));
        MaidSettings.Configure configure = configure("head", "");
        assertInstanceOf(ActionStatus.Done.class, run(configure));
        assertTrue(menu.at(36).isEmpty());
        assertEquals(Items.IRON_HELMET, menu.at(0).getItem());
    }

    @Test
    void 装备_背包里没有_缺东西且不开界面() {
        Problem problem = assertInstanceOf(ActionStatus.Failed.class, run(configure("mainhand", "minecraft:iron_hoe")))
                .problem();
        assertEquals(Problem.Kind.NEED_ITEM, problem.kind());
        assertEquals(0, opened);
    }

    @Test
    void 不是女仆_不给改设置的办法() {
        MaidConfigure configure = new MaidConfigure(compat, MaidFakes.services(chat), steps, this::worn);
        assertTrue(configure.maid(null, MaidFakes.creature(99)).isEmpty());
    }
}
