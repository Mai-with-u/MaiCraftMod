// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.part;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.ae2.Ae2Grid;
import org.maiwithu.maicraft.game.serverlink.ServerReads;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;

/**
 * 菜单型配置动作离线测试：覆盖输入/输出/存储总线、ME 接口备料换页、成型面板、
 * 标准/能量发信器阈值、元件工作台 setFuzzyMode 与动态升级槽。
 */
class Ae2MenuConfigActionTest {

    private static final BlockPos HOST = new BlockPos(4, 65, 8);

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 输入总线切换导入类型走独立包且超十八格未解锁时提示容量卡() {
        PartTestFakes.ScriptedSession session = new PartTestFakes.ScriptedSession(Ae2PartKeys.MENU_IMPORT_BUS);
        session.keyTypes.put("item", true);
        session.keyTypes.put("fluid", false);
        session.configSlots.add(new Ae2PartControl.MenuSlotEntry(5, 0, true, null, 0));
        session.configSlots.add(new Ae2PartControl.MenuSlotEntry(25, 20, false, null, 0));
        Ae2PartCache cache = new Ae2PartCache();
        Ae2Compat compat = PartTestFakes.compat(PartTestFakes.cableGrid(HOST, Map.of()), session);
        PlayerServices services = PartTestFakes.services(() -> null, ServerReads.NONE);

        // 切换 fluid 导入类型走 SelectKeyTypePacket，并在关窗前把新值刷入本地缓存。
        Ae2MenuConfigAction.Spec keyTypeSpec = new Ae2MenuConfigAction.Spec(HOST, Direction.NORTH, "输入总线",
                Ae2PartKeys.MENU_IMPORT_BUS, Ae2MenuConfigAction.Kind.KEY_TYPE, "key_type.fluid", "true",
                null, -1, false, null);
        ActionStatus keyStatus = runToEnd(new Ae2MenuConfigAction(compat, session, cache, services, keyTypeSpec,
                () -> PartTestFakes.immediateOpening(session)));
        assertInstanceOf(ActionStatus.Done.class, keyStatus);
        assertTrue(session.log.contains("selectKeyType:fluid:true"));
        assertTrue(session.log.stream().noneMatch(entry -> entry.startsWith("config:")));
        assertEquals("true", cache.settingsFor(HOST, Direction.NORTH, ServerReads.NONE).get("key_type.fluid"));

        // 超出 18 格基础槽且未插容量卡解锁时，如实返回 NOT_POSSIBLE_HERE 并提示容量卡。
        session.closed = false;
        Ae2MenuConfigAction.Spec lockedFilter = new Ae2MenuConfigAction.Spec(HOST, Direction.NORTH, "输入总线",
                Ae2PartKeys.MENU_IMPORT_BUS, Ae2MenuConfigAction.Kind.FILTER_SLOT, "filter.20", "minecraft:iron_ingot",
                null, 20, false, null);
        ActionStatus failStatus = runToEnd(new Ae2MenuConfigAction(compat, session, cache, services, lockedFilter,
                () -> PartTestFakes.immediateOpening(session)));
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, failStatus);
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, failed.problem().kind());
        assertTrue(failed.problem().message().contains("ae2:capacity_card"));
    }

    @Test
    void 存储总线分区清空与优先级子菜单流程正常且动作单次读回消费() {
        PartTestFakes.ScriptedSession session = new PartTestFakes.ScriptedSession(Ae2PartKeys.MENU_STORAGE_BUS);
        session.settings.put("access", "read_write");
        session.cycleOrders.put("access", List.of("read_write", "read", "write"));
        session.priority = 0;
        session.connectedTo = "minecraft:barrel";
        Ae2PartCache cache = new Ae2PartCache();
        Ae2Compat compat = PartTestFakes.compat(PartTestFakes.cableGrid(HOST, Map.of()), session);
        PlayerServices services = PartTestFakes.services(() -> null, ServerReads.NONE);

        // 1. 权限切换：循环点击 ConfigButtonPacket 直到匹配，并刷新 connected_to。
        Ae2MenuConfigAction.Spec accessSpec = new Ae2MenuConfigAction.Spec(HOST, Direction.WEST, "存储总线",
                Ae2PartKeys.MENU_STORAGE_BUS, Ae2MenuConfigAction.Kind.CONFIG_BUTTON, "access", "write",
                "access", -1, false, null);
        assertInstanceOf(ActionStatus.Done.class, runToEnd(new Ae2MenuConfigAction(compat, session, cache, services,
                accessSpec, () -> PartTestFakes.immediateOpening(session))));
        assertEquals("write", cache.peekSettings(HOST, Direction.WEST, ServerReads.NONE).get("access"));
        assertEquals("minecraft:barrel", cache.readingsFor(HOST, Direction.WEST, ServerReads.NONE).get("connected_to"));

        // 2. 分区动作：执行后第一次 settingsFor 读到 partition（供配置任务核对），第二次自动恢复无待消费动作。
        session.closed = false;
        Ae2MenuConfigAction.Spec partitionSpec = new Ae2MenuConfigAction.Spec(HOST, Direction.WEST, "存储总线",
                Ae2PartKeys.MENU_STORAGE_BUS, Ae2MenuConfigAction.Kind.NO_ARG_ACTION, "action", "partition",
                null, -1, false, null);
        assertInstanceOf(ActionStatus.Done.class, runToEnd(new Ae2MenuConfigAction(compat, session, cache, services,
                partitionSpec, () -> PartTestFakes.immediateOpening(session))));
        assertTrue(session.log.contains("action:partition"));
        assertEquals("partition", cache.settingsFor(HOST, Direction.WEST, ServerReads.NONE).get("action"));
        assertFalse(cache.settingsFor(HOST, Direction.WEST, ServerReads.NONE).containsKey("action"));

        // 3. 优先级子菜单：声明换页 -> openSubMenu(ae2:priority) -> setPriority(25) -> returnToParentMenu。
        session.closed = false;
        Ae2MenuConfigAction.Spec prioritySpec = new Ae2MenuConfigAction.Spec(HOST, Direction.WEST, "存储总线",
                Ae2PartKeys.MENU_STORAGE_BUS, Ae2MenuConfigAction.Kind.PRIORITY, "priority", "25",
                null, -1, false, null);
        assertInstanceOf(ActionStatus.Done.class, runToEnd(new Ae2MenuConfigAction(compat, session, cache, services,
                prioritySpec, () -> PartTestFakes.immediateOpening(session))));
        assertTrue(session.swapFollowed);
        assertTrue(session.log.contains("openSub:ae2:priority"));
        assertTrue(session.log.contains("intAction:setPriority:25"));
        assertTrue(session.log.contains("returnParent"));
        assertEquals("25", cache.peekSettings(HOST, Direction.WEST, ServerReads.NONE).get("priority"));
    }

    @Test
    void 接口备料先写过滤再换页设数量并自动返回刷新缓存() {
        PartTestFakes.ScriptedSession session = new PartTestFakes.ScriptedSession(Ae2PartKeys.MENU_INTERFACE);
        session.configSlots.add(new Ae2PartControl.MenuSlotEntry(46, 0, true, null, 0));
        Ae2PartCache cache = new Ae2PartCache();
        Ae2Compat compat = PartTestFakes.compat(PartTestFakes.cableGrid(HOST, Map.of()), session);
        PlayerServices services = PartTestFakes.services(() -> null, ServerReads.NONE);

        Ae2PartKeys.StockValue stock = Ae2PartKeys.parseStockValue("minecraft:iron_ingot 16").orElseThrow();
        Ae2MenuConfigAction.Spec spec = new Ae2MenuConfigAction.Spec(HOST, null, "ME 接口",
                Ae2PartKeys.MENU_INTERFACE, Ae2MenuConfigAction.Kind.STOCK_SLOT, "stock.0", "minecraft:iron_ingot 16",
                null, 0, false, stock);
        assertInstanceOf(ActionStatus.Done.class, runToEnd(new Ae2MenuConfigAction(compat, session, cache, services,
                spec, () -> PartTestFakes.immediateOpening(session))));

        assertTrue(session.swapFollowed);
        assertTrue(session.log.contains("setFilter:46:minecraft:iron_ingot"));
        assertTrue(session.log.contains("intAction:setAmount:0"));
        assertTrue(session.log.contains("intAction:setStockAmount:16"));
        assertEquals("minecraft:iron_ingot 16", cache.peekSettings(HOST, null, ServerReads.NONE).get("stock.0"));
    }

    @Test
    void 发信器与成型面板配置与非法参数拒绝() {
        PartTestFakes.ScriptedSession session = new PartTestFakes.ScriptedSession(Ae2PartKeys.MENU_STORAGE_LEVEL_EMITTER);
        session.reportingValue = 1L;
        Ae2PartCache cache = new Ae2PartCache();
        Ae2Compat compat = PartTestFakes.compat(PartTestFakes.cableGrid(HOST, Map.of()), session);
        PlayerServices services = PartTestFakes.services(() -> null, ServerReads.NONE);

        Ae2MenuConfigAction.Spec emitterSpec = new Ae2MenuConfigAction.Spec(HOST, Direction.SOUTH, "标准发信器",
                Ae2PartKeys.MENU_STORAGE_LEVEL_EMITTER, Ae2MenuConfigAction.Kind.REPORTING_VALUE, "reporting_value",
                "128", null, -1, false, null);
        assertInstanceOf(ActionStatus.Done.class, runToEnd(new Ae2MenuConfigAction(compat, session, cache, services,
                emitterSpec, () -> PartTestFakes.immediateOpening(session))));
        assertTrue(session.log.contains("longAction:setReportingValue:128"));
        assertEquals("128", cache.peekSettings(HOST, Direction.SOUTH, ServerReads.NONE).get("reporting_value"));

        // 负数阈值在入口直接返回 INVALID_PARAMETER。
        Ae2Grid grid = PartTestFakes.cableGrid(HOST,
                Map.of(Direction.SOUTH, new Ae2Grid.PartView("ae2:energy_level_emitter", true, true)));
        Ae2PartCableOps cableOps = new Ae2PartCableOps(PartTestFakes.compat(grid, session), session, cache,
                (level, pos) -> grid.cell(level, pos));
        Action bad = cableOps.settings(HOST, services).orElseThrow()
                .change(HOST, "south.reporting_value", "-5", Permissions.DEFAULT)
                .orElseThrow();
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, bad.tick(PartTestFakes.dummyTick()));
        assertEquals(Problem.Kind.INVALID_PARAMETER, failed.problem().kind());
    }

    @Test
    void 元件工作台模糊模式不走配置按钮且动态升级槽越界报错() {
        PartTestFakes.ScriptedSession session = new PartTestFakes.ScriptedSession(Ae2PartKeys.MENU_CELL_WORKBENCH);
        session.cellSlots.add(new Ae2PartControl.MenuSlotEntry(0, 0, true, "minecraft:chest", 1));
        session.settings.put("fuzzy_mode", "ignore_all");
        session.settings.put("copy_mode", "clear_on_remove");
        session.upgradeSlots.add(new Ae2PartControl.MenuSlotEntry(1, 0, true, null, 0));
        session.upgradeSlots.add(new Ae2PartControl.MenuSlotEntry(6, 5, false, null, 0));
        session.putPlayerItem(100, new ItemStack(Items.REDSTONE, 2));

        Ae2PartCache cache = new Ae2PartCache();
        Ae2Compat compat = PartTestFakes.compat(PartTestFakes.cableGrid(HOST, Map.of()), session);
        PlayerServices services = PartTestFakes.services(() -> null, ServerReads.NONE);

        // 1. 模糊模式走 setFuzzyMode 动作，绝不发 ConfigButtonPacket。
        Ae2MenuConfigAction.Spec fuzzySpec = new Ae2MenuConfigAction.Spec(HOST, null, "元件工作台",
                Ae2PartKeys.MENU_CELL_WORKBENCH, Ae2MenuConfigAction.Kind.WORKBENCH_FUZZY, "fuzzy_mode", "percent_75",
                null, -1, false, null);
        assertInstanceOf(ActionStatus.Done.class, runToEnd(new Ae2MenuConfigAction(compat, session, cache, services,
                fuzzySpec, () -> PartTestFakes.immediateOpening(session))));
        assertTrue(session.log.contains("workbenchFuzzy:percent_75"));
        assertTrue(session.log.stream().noneMatch(line -> line.startsWith("config:")));
        assertEquals("percent_75", cache.peekSettings(HOST, null, ServerReads.NONE).get("fuzzy_mode"));

        // 2. 复制模式走 nextCopyMode 动作。
        session.closed = false;
        Ae2MenuConfigAction.Spec copySpec = new Ae2MenuConfigAction.Spec(HOST, null, "元件工作台",
                Ae2PartKeys.MENU_CELL_WORKBENCH, Ae2MenuConfigAction.Kind.WORKBENCH_COPY_MODE, "copy_mode",
                "keep_on_remove", null, -1, false, null);
        assertInstanceOf(ActionStatus.Done.class, runToEnd(new Ae2MenuConfigAction(compat, session, cache, services,
                copySpec, () -> PartTestFakes.immediateOpening(session))));
        assertTrue(session.log.contains("action:nextCopyMode"));
        assertEquals("keep_on_remove", cache.peekSettings(HOST, null, ServerReads.NONE).get("copy_mode"));

        // 3. 激活升级槽 upgrade.0 正常放入单件物品并把余件退回背包；未激活升级槽 upgrade.5 明确报 NOT_POSSIBLE_HERE。
        session.closed = false;
        Ae2MenuConfigAction.Spec upgradeOk = new Ae2MenuConfigAction.Spec(HOST, null, "元件工作台",
                Ae2PartKeys.MENU_CELL_WORKBENCH, Ae2MenuConfigAction.Kind.ITEM_SLOT, "upgrade.0",
                "minecraft:redstone", null, 0, false, null);
        assertInstanceOf(ActionStatus.Done.class, runToEnd(new Ae2MenuConfigAction(compat, session, cache, services,
                upgradeOk, () -> PartTestFakes.immediateOpening(session))));
        assertEquals("minecraft:redstone", session.upgradeSlots.getFirst().itemId());
        assertEquals(1, session.playerSlots.get(100).count());

        session.closed = false;
        Ae2MenuConfigAction.Spec upgradeDisabled = new Ae2MenuConfigAction.Spec(HOST, null, "元件工作台",
                Ae2PartKeys.MENU_CELL_WORKBENCH, Ae2MenuConfigAction.Kind.ITEM_SLOT, "upgrade.5",
                "minecraft:redstone", null, 5, false, null);
        ActionStatus.Failed disabledFail = assertInstanceOf(ActionStatus.Failed.class,
                runToEnd(new Ae2MenuConfigAction(compat, session, cache, services, upgradeDisabled,
                        () -> PartTestFakes.immediateOpening(session))));
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, disabledFail.problem().kind());
    }

    private static ActionStatus runToEnd(Action action) {
        ActionStatus last = ActionStatus.running();
        for (int i = 0; i < 60; i++) {
            last = action.tick(PartTestFakes.dummyTick());
            if (!(last instanceof ActionStatus.Running)) return last;
        }
        return last;
    }
}
