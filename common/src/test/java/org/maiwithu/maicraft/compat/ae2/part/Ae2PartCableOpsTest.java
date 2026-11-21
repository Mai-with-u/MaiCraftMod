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
import java.util.Optional;
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
import org.maiwithu.maicraft.compat.ExternalKeyWrappers;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.ae2.Ae2Grid;
import org.maiwithu.maicraft.compat.ae2.Ae2MachineCatalog;
import org.maiwithu.maicraft.compat.ae2.Ae2MenuSlots;
import org.maiwithu.maicraft.compat.ae2.Ae2SemanticsLayout;
import org.maiwithu.maicraft.game.serverlink.ServerReads;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;

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
    void 服务端权威嵌套结构与键名反向映射全量对齐() {
        Ae2PartCache cache = new Ae2PartCache();

        // 1. 线缆 ME 接口：settings 键名反向映射 + readings.config_slots -> stock.N + upgrades 完整下标。
        JsonObject ifaceReply = new JsonObject();
        ifaceReply.addProperty("found", true);
        ifaceReply.addProperty("kind", "cable_part");
        ifaceReply.addProperty("part_id", "ae2:cable_interface");
        JsonObject ifaceSettings = new JsonObject();
        ifaceSettings.addProperty("fuzzy_mode", "PERCENT_75");
        ifaceReply.add("settings", ifaceSettings);
        JsonArray ifaceUpgrades = new JsonArray();
        ifaceUpgrades.add("ae2:crafting_card");
        ifaceReply.add("upgrades", ifaceUpgrades);
        JsonObject ifaceReadings = new JsonObject();
        ifaceReadings.addProperty("priority", 12);
        JsonObject stockSlots = new JsonObject();
        stockSlots.addProperty("0", "minecraft:iron_ingot x16");
        ifaceReadings.add("config_slots", stockSlots);
        ifaceReply.add("readings", ifaceReadings);
        cache.acceptServerDevice(HOST, Direction.NORTH, ifaceReply);

        Map<String, String> ifaceOut = cache.peekSettings(HOST, Direction.NORTH, ServerReads.NONE);
        assertEquals("percent_75", ifaceOut.get("fuzzy_mode"));
        assertEquals("12", ifaceOut.get("priority"));
        assertEquals("minecraft:iron_ingot 16", ifaceOut.get("stock.0"));
        assertEquals("ae2:crafting_card", ifaceOut.get("upgrade.0"));

        // 2. 成型面板 + 发信器：place_block -> place_mode (yes->block / no->drop)，redstone_emitter -> redstone_mode，filter.N 去 x 后缀。
        JsonObject planeReply = new JsonObject();
        planeReply.addProperty("found", true);
        planeReply.addProperty("kind", "cable_part");
        planeReply.addProperty("part_id", "ae2:formation_plane");
        JsonObject planeSettings = new JsonObject();
        planeSettings.addProperty("place_block", "no");
        planeSettings.addProperty("redstone_emitter", "low_signal");
        planeReply.add("settings", planeSettings);
        JsonObject planeReadings = new JsonObject();
        JsonObject filterSlots = new JsonObject();
        filterSlots.addProperty("0", "minecraft:cobblestone x1");
        planeReadings.add("config_slots", filterSlots);
        planeReply.add("readings", planeReadings);
        cache.acceptServerDevice(HOST, Direction.EAST, planeReply);

        Map<String, String> planeOut = cache.peekSettings(HOST, Direction.EAST, ServerReads.NONE);
        assertEquals("drop", planeOut.get("place_mode"));
        assertEquals("low_signal", planeOut.get("redstone_mode"));
        assertEquals("minecraft:cobblestone", planeOut.get("filter.0"));

        // 3. P2P（readings.p2p_output & p2p_frequency）、破坏面板（readings.enchantments 对象）与元件工作台（readings.cell_item & fuzzy_mode）。
        JsonObject p2pReply = new JsonObject();
        p2pReply.addProperty("found", true);
        JsonObject p2pReadings = new JsonObject();
        p2pReadings.addProperty("p2p_output", true);
        p2pReadings.addProperty("p2p_frequency", 0xABCD);
        p2pReply.add("readings", p2pReadings);
        cache.acceptServerDevice(HOST, Direction.SOUTH, p2pReply);
        assertEquals("output", cache.peekSettings(HOST, Direction.SOUTH, ServerReads.NONE).get("p2p_role"));
        assertEquals(0xABCD, cache.readingsFor(HOST, Direction.SOUTH, ServerReads.NONE).get("p2p_frequency"));

        JsonObject annReply = new JsonObject();
        annReply.addProperty("found", true);
        JsonObject annReadings = new JsonObject();
        JsonObject enchObj = new JsonObject();
        enchObj.addProperty("minecraft:silk_touch", 1);
        annReadings.add("enchantments", enchObj);
        annReply.add("readings", annReadings);
        cache.acceptServerDevice(HOST, Direction.UP, annReply);
        assertEquals("minecraft:silk_touch 1",
                cache.readingsFor(HOST, Direction.UP, ServerReads.NONE).get("enchantments"));

        JsonObject wbReply = new JsonObject();
        wbReply.addProperty("found", true);
        JsonObject wbReadings = new JsonObject();
        wbReadings.addProperty("cell_item", "ae2:item_storage_cell_4k");
        wbReadings.addProperty("copy_mode", "KEEP_ON_REMOVE");
        wbReadings.addProperty("fuzzy_mode", "PERCENT_50");
        wbReply.add("readings", wbReadings);
        JsonArray wbUpgrades = new JsonArray();
        wbUpgrades.add("");
        wbUpgrades.add("ae2:fuzzy_card");
        wbReply.add("upgrades", wbUpgrades);
        cache.acceptServerDevice(HOST, null, wbReply);
        Map<String, String> wbOut = cache.peekSettings(HOST, null, ServerReads.NONE);
        assertEquals("in:ae2:item_storage_cell_4k", wbOut.get("cell"));
        assertEquals("keep_on_remove", wbOut.get("copy_mode"));
        assertEquals("percent_50", wbOut.get("fuzzy_mode"));
        assertEquals("empty", wbOut.get("upgrade.0"));
        assertEquals("ae2:fuzzy_card", wbOut.get("upgrade.1"));
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

    @Test
    void 化学品键仅在外部包装可用时暴露给输入总线与化学品点对点并解析服务端介质类型() {
        Map<Direction, Ae2Grid.PartView> parts = Map.of(
                Direction.NORTH, new Ae2Grid.PartView("ae2:import_bus", true, true),
                Direction.SOUTH, new Ae2Grid.PartView("ae2:me_p2p_tunnel", true, true),
                Direction.EAST, new Ae2Grid.PartView("appmek:chemical_p2p_tunnel", true, true));
        Ae2Grid grid = PartTestFakes.cableGrid(HOST, parts);
        PartTestFakes.ScriptedSession control = new PartTestFakes.ScriptedSession(Ae2PartKeys.MENU_IMPORT_BUS);
        PlayerServices services = PartTestFakes.services(() -> null, ServerReads.NONE);

        // 未注册 appmek:chemical 时：输入总线不暴露 key_type.chemical，P2P 可选值也不含 chemical。
        Ae2PartCableOps withoutChem = new Ae2PartCableOps(
                PartTestFakes.compat(grid, control, ExternalKeyWrappers.NONE),
                control, new Ae2PartCache(), (level, pos) -> grid.cell(level, pos));
        assertFalse(toMap(withoutChem.partSettings(HOST, Direction.NORTH, services)).containsKey("key_type.chemical"));
        assertTrue(withoutChem.buildPartChange(HOST, Direction.NORTH, Ae2PartKeys.PartKind.IMPORT_BUS,
                "key_type.chemical", "true", services, Permissions.DEFAULT).isEmpty());
        MachineSetting p2pWithout = withoutChem.partSettings(HOST, Direction.SOUTH, services).stream()
                .filter(s -> s.key().equals("attunement")).findFirst().orElseThrow();
        assertFalse(p2pWithout.choices().contains("chemical"));
        Action rejectChemP2p = withoutChem.buildPartChange(HOST, Direction.SOUTH, Ae2PartKeys.PartKind.P2P_TUNNEL,
                "attunement", "chemical", services, Permissions.DEFAULT).orElseThrow();
        assertInstanceOf(ActionStatus.Failed.class, rejectChemP2p.tick(PartTestFakes.dummyTick()));

        // 注册 appmek:chemical 后：输入总线暴露并接受 key_type.chemical，P2P 支持 attunement=chemical 与化学品 P2P 识别。
        ExternalKeyWrappers wrappers = new ExternalKeyWrappers();
        wrappers.register("appmek:chemical", (id, amt) -> Optional.empty());
        Ae2PartCache cache = new Ae2PartCache();
        Ae2PartCableOps withChem = new Ae2PartCableOps(
                PartTestFakes.compat(grid, control, wrappers),
                control, cache, (level, pos) -> grid.cell(level, pos));
        assertTrue(toMap(withChem.partSettings(HOST, Direction.NORTH, services)).containsKey("key_type.chemical"));
        assertTrue(withChem.buildPartChange(HOST, Direction.NORTH, Ae2PartKeys.PartKind.IMPORT_BUS,
                "key_type.chemical", "true", services, Permissions.DEFAULT).isPresent());
        MachineSetting p2pWith = withChem.partSettings(HOST, Direction.SOUTH, services).stream()
                .filter(s -> s.key().equals("attunement")).findFirst().orElseThrow();
        assertTrue(p2pWith.choices().contains("chemical"));
        assertInstanceOf(Ae2DirectPartAction.class,
                withChem.buildPartChange(HOST, Direction.SOUTH, Ae2PartKeys.PartKind.P2P_TUNNEL,
                        "attunement", "chemical", services, Permissions.DEFAULT).orElseThrow());
        assertEquals("chemical", toMap(withChem.partSettings(HOST, Direction.EAST, services)).get("attunement"));

        // 服务端 ae2.device 返回 config_slot_types 时，化学槽自动带 chemical: 前缀，物品/流体保持原样。
        JsonObject serverReply = new JsonObject();
        serverReply.addProperty("part_id", "ae2:cable_interface");
        JsonObject readings = new JsonObject();
        JsonObject configSlots = new JsonObject();
        configSlots.addProperty("0", "mekanism:sulfuric_acid x16000");
        configSlots.addProperty("1", "minecraft:water x1000");
        JsonObject slotTypes = new JsonObject();
        slotTypes.addProperty("0", "chemical");
        slotTypes.addProperty("1", "fluid");
        readings.add("config_slots", configSlots);
        readings.add("config_slot_types", slotTypes);
        serverReply.add("readings", readings);
        cache.acceptServerDevice(HOST, Direction.WEST, serverReply);
        Map<String, String> merged = cache.peekSettings(HOST, Direction.WEST, ServerReads.NONE);
        assertEquals("chemical:mekanism:sulfuric_acid 16000", merged.get("stock.0"));
        assertEquals("minecraft:water 1000", merged.get("stock.1"));

        // 监视器化学品读回：默认回显 chemical:<id>；缓存记了对应裸 <id> 时回显裸 <id>；同名流体不冒充化学品。
        Ae2PartControl.ClientPartReading liveChem = Ae2PartControl.ClientPartReading.monitor(
                "chemical:mekanism:hydrogen", 32000L, false, false);
        assertEquals("chemical:mekanism:hydrogen",
                toMap(Ae2PartKeys.partSettings(Ae2PartKeys.PartKind.STORAGE_MONITOR, "ae2:storage_monitor",
                        Map.of(), liveChem, true)).get("displayed"));
        assertEquals("chemical:mekanism:hydrogen",
                toMap(Ae2PartKeys.partSettings(Ae2PartKeys.PartKind.STORAGE_MONITOR, "ae2:storage_monitor",
                        Map.of("displayed", "chemical:mekanism:hydrogen"), liveChem, true)).get("displayed"));
        assertEquals("mekanism:hydrogen",
                toMap(Ae2PartKeys.partSettings(Ae2PartKeys.PartKind.STORAGE_MONITOR, "ae2:storage_monitor",
                        Map.of("displayed", "mekanism:hydrogen"), liveChem, true)).get("displayed"));
        Ae2PartControl.ClientPartReading liveFluid = Ae2PartControl.ClientPartReading.monitor(
                "mekanism:sulfuric_acid", 4000L, false, false);
        assertEquals("mekanism:sulfuric_acid",
                toMap(Ae2PartKeys.partSettings(Ae2PartKeys.PartKind.STORAGE_MONITOR, "ae2:storage_monitor",
                        Map.of("displayed", "chemical:mekanism:sulfuric_acid"), liveFluid, true)).get("displayed"));
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
