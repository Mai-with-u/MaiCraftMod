// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.part;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineSettings;
import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.ae2.Ae2Grid;
import org.maiwithu.maicraft.compat.ae2.Ae2MachineCatalog;
import org.maiwithu.maicraft.compat.ae2.Ae2MenuSlots;
import org.maiwithu.maicraft.compat.ae2.Ae2SemanticsLayout;
import org.maiwithu.maicraft.game.serverlink.ServerReads;

/**
 * 线缆格多面部件按面配置路由、去前缀 partSettings、缓存与服务端读数合并、
 * 以及 Ae2Parts 统一汇编入口与语义布局规则离线测试。
 */
class Ae2PartCableOpsTest {

    private static final BlockPos HOST = new BlockPos(10, 64, -4);

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 线缆六面多部件按面加前缀列出且按面读取不含前缀() {
        Map<Direction, Ae2Grid.PartView> parts = new EnumMap<>(Direction.class);
        parts.put(Direction.NORTH, new Ae2Grid.PartView("ae2:cable_interface", true, true));
        parts.put(Direction.EAST, new Ae2Grid.PartView("ae2:export_bus", true, true));
        parts.put(Direction.WEST, new Ae2Grid.PartView("ae2:storage_bus", true, true));
        parts.put(Direction.SOUTH, new Ae2Grid.PartView("ae2:level_emitter", true, true));
        parts.put(Direction.UP, new Ae2Grid.PartView("ae2:annihilation_plane", true, true));
        parts.put(Direction.DOWN, new Ae2Grid.PartView("ae2:storage_monitor", true, true));

        Ae2Grid grid = PartTestFakes.cableGrid(HOST, parts);
        PartTestFakes.ScriptedSession control = new PartTestFakes.ScriptedSession(Ae2PartKeys.MENU_INTERFACE);
        control.clientParts.put(Direction.DOWN,
                Ae2PartControl.ClientPartReading.monitor("minecraft:iron_ingot", 128L, true, true));
        Ae2Compat compat = PartTestFakes.compat(grid, control);
        Ae2PartCache cache = new Ae2PartCache();
        Ae2PartCableOps cableOps = new Ae2PartCableOps(compat, control, cache, (level, pos) -> grid.cell(level, pos));
        PlayerServices services = PartTestFakes.services(() -> null, ServerReads.NONE);

        MachineSettings cableSettings = cableOps.settings(HOST, services).orElseThrow();
        List<MachineSetting> all = cableSettings.settings(HOST);
        Map<String, String> allByKey = toMap(all);

        // 带面名前缀的合法键全部存在，未读项标 unknown，客户端同步的监视器直出真值。
        assertEquals("unknown", allByKey.get("north.priority"));
        assertEquals("unknown", allByKey.get("north.stock.0"));
        assertEquals("unknown", allByKey.get("east.craft_only"));
        assertEquals("unknown", allByKey.get("east.scheduling_mode"));
        assertEquals("unknown", allByKey.get("west.access"));
        assertEquals("none", allByKey.get("west.action"));
        assertEquals("unknown", allByKey.get("south.reporting_value"));
        assertEquals("true", allByKey.get("down.locked"));
        assertEquals("minecraft:iron_ingot", allByKey.get("down.displayed"));
        // 破坏面板只读，不暴露任何带 up. 前缀的配置键。
        assertTrue(all.stream().noneMatch(s -> s.key().startsWith("up.")));

        // partSettings 返回不带前缀的局部设置列表，供查看机器填进部件 DeviceView.settings。
        List<MachineSetting> westLocal = cableOps.partSettings(HOST, Direction.WEST, services);
        assertTrue(westLocal.stream().noneMatch(s -> s.key().startsWith("west.")));
        assertTrue(toMap(westLocal).containsKey("access"));
        assertTrue(toMap(westLocal).containsKey("priority"));
        assertTrue(cableOps.partSettings(HOST, Direction.UP, services).isEmpty());
    }

    @Test
    void 服务端权威读数刷新部件配置与补充读数且不冲掉刚改好的本地值() {
        AtomicLong now = new AtomicLong(10_000L);
        Ae2PartCache cache = new Ae2PartCache(now::get);
        Map<Direction, Ae2Grid.PartView> parts = Map.of(
                Direction.WEST, new Ae2Grid.PartView("ae2:storage_bus", true, true),
                Direction.UP, new Ae2Grid.PartView("ae2:annihilation_plane", true, true),
                Direction.NORTH, new Ae2Grid.PartView("ae2:me_p2p_tunnel", true, true));
        Ae2Grid grid = PartTestFakes.cableGrid(HOST, parts);
        PartTestFakes.ScriptedSession control = new PartTestFakes.ScriptedSession(Ae2PartKeys.MENU_STORAGE_BUS);
        control.clientParts.put(Direction.NORTH, Ae2PartControl.ClientPartReading.p2p(0x2A3F));
        Ae2PartCableOps cableOps = new Ae2PartCableOps(PartTestFakes.compat(grid, control), control, cache,
                (level, pos) -> grid.cell(level, pos));
        PlayerServices services = PartTestFakes.services(() -> null, ServerReads.NONE);

        // 注入服务端 ae2.device 读数：存储总线目标与权限、破坏面板附魔、P2P 出入端。
        JsonObject storageReply = new JsonObject();
        JsonObject storageSettings = new JsonObject();
        storageSettings.addProperty("access", "read");
        storageReply.add("settings", storageSettings);
        storageReply.addProperty("priority", 15);
        storageReply.addProperty("connected_to", "minecraft:chest");
        cache.acceptServerDevice(HOST, Direction.WEST, storageReply);

        JsonObject planeReply = new JsonObject();
        JsonArray enchants = new JsonArray();
        enchants.add("minecraft:silk_touch 1");
        planeReply.add("enchantments", enchants);
        cache.acceptServerDevice(HOST, Direction.UP, planeReply);

        JsonObject p2pReply = new JsonObject();
        p2pReply.addProperty("is_output", true);
        cache.acceptServerDevice(HOST, Direction.NORTH, p2pReply);

        Map<String, String> westSettings = toMap(cableOps.partSettings(HOST, Direction.WEST, services));
        assertEquals("read", westSettings.get("access"));
        assertEquals("15", westSettings.get("priority"));
        assertEquals("minecraft:chest", cableOps.partReadings(HOST, Direction.WEST, null, ServerReads.NONE).get("connected_to"));
        assertEquals("minecraft:silk_touch 1",
                cableOps.partReadings(HOST, Direction.UP, null, ServerReads.NONE).get("enchantments"));

        Map<String, Object> p2pRead = cableOps.partReadings(HOST, Direction.NORTH, null, ServerReads.NONE);
        assertEquals("2A3F", p2pRead.get("frequency"));
        assertEquals(true, p2pRead.get("is_output"));
        Map<String, String> p2pSettings = toMap(cableOps.partSettings(HOST, Direction.NORTH, services));
        assertEquals("me", p2pSettings.get("attunement"));
        assertEquals("output", p2pSettings.get("p2p_role"));

        // 刚在本地写入新值后，保护窗口内旧的服务端回包不能覆盖本地刚改好的值。
        now.addAndGet(500L);
        cache.recordSetting(HOST, Direction.WEST, "access", "write");
        cache.acceptServerDevice(HOST, Direction.WEST, storageReply);
        assertEquals("write", toMap(cableOps.partSettings(HOST, Direction.WEST, services)).get("access"));

        // 保护窗口过期后，服务端权威读数重新生效。
        now.addAndGet(2_000L);
        cache.acceptServerDevice(HOST, Direction.WEST, storageReply);
        assertEquals("read", toMap(cableOps.partSettings(HOST, Direction.WEST, services)).get("access"));
    }

    @Test
    void 汇编入口暴露全部增补目录设备委托与合规语义布局规则() {
        List<Ae2MachineCatalog.Kind> merged = Ae2MachineCatalog.kinds(Ae2Parts.extraKinds());
        assertTrue(merged.stream().anyMatch(k -> k.id().equals("ae2:cell_workbench")));
        assertEquals("ME IO 传输站", merged.stream()
                .filter(k -> k.id().equals("ae2:io_port")).findFirst().orElseThrow().name());

        Ae2Compat compat = PartTestFakes.compat(PartTestFakes.cableGrid(HOST, Map.of()), Ae2MenuSlots.NONE);
        Ae2Parts parts = new Ae2Parts(compat, Ae2PartControl.NONE);
        assertEquals(Set.of("ae2:interface", "ae2:cell_workbench", "ae2:io_port", "ae2:wireless_access_point"),
                parts.deviceOps().keySet());
        assertInstanceOf(Ae2PartCableOps.class, parts.cablePartOps());

        // 核对语义布局规则：全部 9 个菜单均来自 AE2 源码，严格排除既有专属证明与无 36 格背包子菜单。
        Map<String, Ae2SemanticsLayout.Rule> rules = Ae2Parts.layoutRules();
        assertEquals(Set.of(
                "ae2:import_bus", "ae2:export_bus", "ae2:storagebus", "ae2:interface", "ae2:formationplane",
                "ae2:storage_level_emitter", "ae2:energy_level_emitter", "ae2:cellworkbench", "ae2:ioport"),
                rules.keySet());
        assertFalse(rules.containsKey("ae2:wireless_access_point"));
        assertFalse(rules.containsKey("ae2:pattern_provider"));
        assertFalse(rules.containsKey("ae2:priority"));
        assertFalse(rules.containsKey("ae2:set_stock_amount"));

        // 携带网络工具箱时（前 9 格 TOOLBOX），布局证明精确剔除 TOOLBOX 与 CONFIG，只保留真存取槽。
        Ae2MenuSlots toolboxMenu = new Ae2MenuSlots() {
            @Override public boolean isAe2MenuOpen() { return true; }
            @Override public List<Integer> slotsOf(String semanticId) {
                return switch (semanticId) {
                    case "TOOLBOX" -> range(0, 8);
                    case "UPGRADE" -> List.of(9);
                    case "CONFIG" -> range(46, 54);
                    case "STORAGE" -> range(55, 63);
                    default -> List.of();
                };
            }
            @Override public boolean sendConfigButton(String settingName, boolean backwards) { return false; }
            @Override public boolean sendSetFilter(int slotIndex, ItemStack stack) { return false; }
            @Override public boolean sendNoArgAction(String actionName) { return false; }
            @Override public boolean sendIntAction(String actionName, int value) { return false; }
            @Override public boolean sendLongAction(String actionName, long value) { return false; }
            @Override public boolean sendBooleanAction(String actionName, boolean value) { return false; }
            @Override public boolean sendStringAction(String actionName, String value) { return false; }
            @Override public boolean sendRawJsonAction(String actionName, String jsonPayload) { return false; }
            @Override public boolean openSubMenu(String menuTypeId) { return false; }
            @Override public boolean returnToParentMenu() { return false; }
        };
        Ae2SemanticsLayout layout = new Ae2SemanticsLayout(toolboxMenu, rules);
        List<Integer> player = range(10, 45);
        List<Integer> other = new ArrayList<>();
        other.addAll(range(0, 9));
        other.addAll(range(46, 63));
        MenuSlots slots = new MenuSlots() {
            @Override public String menuTypeId() { return "ae2:interface"; }
            @Override public int slotCount() { return 64; }
            @Override public boolean playerBacked(int slot) { return slot >= 10 && slot <= 45; }
            @Override public int playerInventoryIndex(int slot) { return slot - 10; }
        };
        MenuLayout.Supported supported = assertInstanceOf(MenuLayout.Supported.class,
                layout.classify(slots, player, other));
        assertEquals(10, supported.containerSlots().size());
        assertEquals(Set.of(9), supported.machineSlots());
        assertFalse(supported.containerSlots().contains(0));
        assertFalse(supported.containerSlots().contains(46));
    }

    private static Map<String, String> toMap(List<MachineSetting> list) {
        Map<String, String> map = new LinkedHashMap<>();
        for (MachineSetting setting : list) {
            map.put(setting.key(), setting.value());
        }
        return map;
    }

    private static List<Integer> range(int from, int toInclusive) {
        List<Integer> list = new ArrayList<>();
        for (int i = from; i <= toInclusive; i++) list.add(i);
        return list;
    }
}
