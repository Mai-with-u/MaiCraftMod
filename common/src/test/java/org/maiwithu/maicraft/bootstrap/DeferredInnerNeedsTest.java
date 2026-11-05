// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.FakeBackpack;
import org.maiwithu.maicraft.behavior.acquire.FakeOffhand;
import org.maiwithu.maicraft.behavior.acquire.FakeTags;
import org.maiwithu.maicraft.behavior.acquire.FixedSpot;
import org.maiwithu.maicraft.behavior.acquire.StubTick;
import org.maiwithu.maicraft.behavior.acquire.engine.ItemAcquisition;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 内需入口：引擎建好前问什么都问不出（远近、最省代价、看一眼都为空），建好后每一问都原样转给引擎——
 * 合成来源"缺料一样多按原料远近排""分不出远近先看一眼 AE"靠它在游戏里生效。
 */
class DeferredInnerNeedsTest {

    private static final ItemRequest PLANKS = new ItemRequest(WantedItem.ofTag("minecraft:planks"), 4, "做箱子");

    private static final Action LOOK = new Action() {
        @Override public ActionStatus tick(TickContext context) {
            return ActionStatus.done();
        }
        @Override public String describe() {
            return "看一眼 AE";
        }
    };

    private static final Action MAKE_TOOL = new Action() {
        @Override public ActionStatus tick(TickContext context) {
            return ActionStatus.done();
        }
        @Override public String describe() {
            return "备一把斧头";
        }
    };

    /** 引擎替身：每一问都给一个认得出的答案。 */
    private static final ItemNeeds ENGINE = new ItemNeeds() {
        @Override public Action actionFor(ItemRequest request, Permissions permissions) {
            return LOOK;
        }
        @Override public Action actionForTool(ItemRequest request, Permissions permissions, TaskRecords records) {
            return MAKE_TOOL;
        }
        @Override public boolean knownUnobtainable(WantedItem wanted) {
            return true;
        }
        @Override public Optional<Double> nearestDistanceBlocks(ItemRequest request, Permissions permissions) {
            return Optional.of(7.0);
        }
        @Override public Optional<Action> lookAround(ItemRequest request, Permissions permissions) {
            return Optional.of(LOOK);
        }
        @Override public Optional<Double> cheapestCost(ItemRequest request, Permissions permissions,
                boolean includeMaking) {
            return Optional.of(includeMaking ? 50.0 : 118.0);
        }
    };

    @Test
    void 引擎建好前问不出() {
        DeferredInnerNeeds needs = new DeferredInnerNeeds();
        assertTrue(needs.nearestDistanceBlocks(PLANKS, Permissions.DEFAULT).isEmpty());
        assertTrue(needs.lookAround(PLANKS, Permissions.DEFAULT).isEmpty());
        assertTrue(needs.cheapestCost(PLANKS, Permissions.DEFAULT, true).isEmpty());
        assertFalse(needs.knownUnobtainable(PLANKS.wanted()));
    }

    @Test
    void 引擎建好后每一问都转给它() {
        DeferredInnerNeeds needs = new DeferredInnerNeeds();
        needs.attach(ENGINE);
        assertEquals(Optional.of(7.0), needs.nearestDistanceBlocks(PLANKS, Permissions.DEFAULT));
        assertSame(LOOK, needs.lookAround(PLANKS, Permissions.DEFAULT).orElseThrow());
        assertEquals(Optional.of(118.0), needs.cheapestCost(PLANKS, Permissions.DEFAULT, false));
        assertTrue(needs.knownUnobtainable(PLANKS.wanted()));
        assertSame(LOOK, needs.actionFor(PLANKS, Permissions.DEFAULT));
        // 实机 11-05：备斧头没转给引擎，引擎不知道这一层是备工具，为斧头做木板时不肯用身上的原木。
        assertSame(MAKE_TOOL, needs.actionForTool(PLANKS, Permissions.DEFAULT, null), "备工具原样转给引擎");
    }

    @Test
    void 内需的每一问都由入口自己转给引擎_不落回默认实现() {
        // 默认实现会丢信息（备工具那一层、顺手一起拿的几样）：实机 11-05 备斧头没转，离线测引擎是绿的，游戏里没生效。
        // 以后内需接口加了新问法，入口没跟着转，这里先红。
        List<String> notForwarded = new ArrayList<>();
        for (Method method : ItemNeeds.class.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())) continue;
            try {
                if (DeferredInnerNeeds.class.getMethod(method.getName(), method.getParameterTypes())
                        .getDeclaringClass() != DeferredInnerNeeds.class) {
                    notForwarded.add(method.getName());
                }
            } catch (NoSuchMethodException impossible) {
                notForwarded.add(method.getName());
            }
        }
        assertEquals(List.of(), notForwarded);
    }

    @Test
    void 接上真引擎_备工具那一层和顺手一起拿的几样都到得了引擎() {
        // 和游戏里一样：来源手里拿的是内需入口，入口接上真的拿东西引擎。要原木先备斧头（备工具），
        // 做斧头要木板、顺手带上木棍；最里层看得见的"做法不吃的外层"应当停在斧头那一层，木棍也在里面。
        FakeBackpack backpack = new FakeBackpack(36);
        DeferredInnerNeeds needs = new DeferredInnerNeeds();
        List<List<String>> outerSeen = new ArrayList<>();
        ItemAcquisition[] engine = new ItemAcquisition[1];
        engine[0] = new ItemAcquisition(List.of(new NestingSource(needs, engine, backpack, outerSeen)), backpack,
                new FakeOffhand(), new FakeTags(), new FixedSpot(0, 64, 0), Optional.empty(),
                ItemAcquisition.DEFAULT_MAX_DEPTH);
        needs.attach(engine[0]);

        Action fetch = engine[0].need(new ItemRequest(WantedItem.ofItem(NestingSource.LOG), 6, "砍树"), Permissions.DEFAULT);
        ActionStatus status = ActionStatus.running();
        for (int tick = 1; tick <= 200 && !(status instanceof ActionStatus.Done); tick++) {
            status = fetch.tick(new StubTick(tick));
        }
        assertEquals(ActionStatus.done(), status);
        assertEquals(List.of(List.of(NestingSource.AXE, NestingSource.PLANKS, NestingSource.STICK)), outerSeen);
    }

    @Test
    void 接上真引擎_估算按引擎算不再回引擎没接上() {
        FakeBackpack backpack = new FakeBackpack(36);
        DeferredInnerNeeds needs = new DeferredInnerNeeds();
        ItemAcquisition[] engine = new ItemAcquisition[1];
        engine[0] = new ItemAcquisition(List.of(new NestingSource(needs, engine, backpack, new ArrayList<>())),
                backpack, new FakeOffhand(), new FakeTags(), new FixedSpot(0, 64, 0), Optional.empty(),
                ItemAcquisition.DEFAULT_MAX_DEPTH);
        ItemRequest planks = new ItemRequest(WantedItem.ofItem(NestingSource.PLANKS), 3, "斧头的木板");
        ItemNeeds bare = (request, permissions) -> LOOK;
        assertEquals(bare.estimate(planks, Permissions.DEFAULT), needs.estimate(planks, Permissions.DEFAULT),
                "引擎还没接上时估不了");
        needs.attach(engine[0]);
        assertEquals(engine[0].estimate(planks, Permissions.DEFAULT), needs.estimate(planks, Permissions.DEFAULT));
        assertNotEquals(bare.estimate(planks, Permissions.DEFAULT), needs.estimate(planks, Permissions.DEFAULT));
    }

    /**
     * 一层套一层要东西的来源替身，缺什么都经内需入口回引擎：要原木先备一把斧头（备工具），要斧头先要木板并顺手带上木棍，
     * 要木板那一刻记下引擎此刻"做法不吃的外层"。
     */
    private static final class NestingSource implements ItemSource {
        static final String LOG = "minecraft:oak_log";
        static final String AXE = "minecraft:wooden_axe";
        static final String PLANKS = "minecraft:oak_planks";
        static final String STICK = "minecraft:stick";
        private final ItemNeeds needs;
        private final ItemAcquisition[] engine;
        private final FakeBackpack backpack;
        private final List<List<String>> outerSeen;

        NestingSource(ItemNeeds needs, ItemAcquisition[] engine, FakeBackpack backpack, List<List<String>> outerSeen) {
            this.needs = needs;
            this.engine = engine;
            this.backpack = backpack;
            this.outerSeen = outerSeen;
        }

        @Override public String describe() {
            return "替身";
        }

        @Override public AcquireVia via() {
            return AcquireVia.CRAFT;
        }

        @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
            return new SourceQuote.Offer("替身", request.count(), new AcquisitionCost(1, 1), null);
        }

        @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
            String wanted = request.wanted().itemId();
            return Optional.of(new Action() {
                private Action fetching;

                // 先经内需入口把里层要的弄到，再把自己这一样放进背包；最里层（木板）放进去之前记下外层。
                @Override public ActionStatus tick(TickContext tick) {
                    if (fetching == null) {
                        fetching = switch (wanted) {
                            case LOG -> needs.actionForTool(
                                    new ItemRequest(WantedItem.ofItem(AXE), 1, "成批砍树前备斧头"), Permissions.DEFAULT, null);
                            case AXE -> needs.actionFor(new ItemRequest(WantedItem.ofItem(PLANKS), 3, "斧头的木板"),
                                    Permissions.DEFAULT, null,
                                    List.of(new ItemRequest(WantedItem.ofItem(STICK), 2, "斧头的木棍")));
                            default -> null;
                        };
                    }
                    if (fetching != null) {
                        ActionStatus status = fetching.tick(tick);
                        if (!(status instanceof ActionStatus.Done)) return status;
                    } else if (wanted.equals(PLANKS)) {
                        outerSeen.add(engine[0].outerWanted().stream().map(r -> r.wanted().itemId()).toList());
                    }
                    backpack.add(wanted, request.count());
                    return ActionStatus.done();
                }

                @Override public String describe() {
                    return "弄" + wanted;
                }
            });
        }
    }
}
