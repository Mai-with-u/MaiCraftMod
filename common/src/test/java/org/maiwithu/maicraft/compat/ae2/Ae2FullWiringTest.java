// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineSettings;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.hands.GearChanges;
import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.CompatRegistry;
import org.maiwithu.maicraft.compat.SupportedMod;
import org.maiwithu.maicraft.compat.VerifiedVersions;
import org.maiwithu.maicraft.compat.ae2.advanced.Ae2Advanced;
import org.maiwithu.maicraft.compat.ae2.advanced.Ae2AdvancedReads;
import org.maiwithu.maicraft.compat.ae2.inscriber.Ae2Inscribers;
import org.maiwithu.maicraft.compat.ae2.machine.Ae2MachineReads;
import org.maiwithu.maicraft.compat.ae2.machine.Ae2Machines;
import org.maiwithu.maicraft.compat.ae2.machine.Ae2Patterns;
import org.maiwithu.maicraft.compat.ae2.part.Ae2PartControl;
import org.maiwithu.maicraft.compat.ae2.part.Ae2Parts;
import org.maiwithu.maicraft.compat.ae2.tool.Ae2ToolReads;
import org.maiwithu.maicraft.compat.ae2.tool.Ae2Tools;
import org.maiwithu.maicraft.game.interaction.DigToolRule;
import org.maiwithu.maicraft.game.loader.LoaderEnvironment;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.serverlink.ClientOperation;
import org.maiwithu.maicraft.game.serverlink.ServerReads;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.game.world.FurnaceFuels;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.server.ServerOperationRegistry;
import org.maiwithu.maicraft.server.compat.ServerCompatRegistry;
import org.maiwithu.maicraft.server.compat.ae2.Ae2DeviceServerReads;
import org.maiwithu.maicraft.server.compat.ae2.Ae2NetworkServerExtras;
import org.maiwithu.maicraft.server.compat.ae2.Ae2ServerCompat;

/**
 * AE2 四路全量汇总接线验证：覆盖全量登记零回滚、全部菜单布局零碰撞、25 种设备目录完整性、
 * 线缆格组合部件委托双路路由、客户端与服务端只读操作登记及旧构造器向后兼容。
 */
class Ae2FullWiringTest {

    private static final BlockPos CABLE_AT = new BlockPos(6, 64, 6);

    @BeforeAll
    static void 初始化注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 全量登记零回滚且全部能力槽完整接入() {
        CompatRegistry registry = loadRegistry(fullCompat(new NoGrid(), Ae2MenuSlots.NONE));
        assertEquals(List.of(Ae2Compat.MOD_ID), registry.installedModIds());
        assertTrue(registry.whyNotWorking(Ae2Compat.MOD_ID).isEmpty());

        PlayerServices services = offlineServices(ServerReads.NONE);
        assertEquals(25, registry.machineTypes(services).size());
        assertEquals(1, registry.networkReaders(services).size());
        assertEquals(6, registry.itemSources(services).size());
        assertEquals(1, registry.carriedBackpacks(services).size());
        assertEquals(1, registry.networkStorages(services).size());
        assertEquals(1, registry.dismantleTools(services).size());
        assertEquals(1, registry.itemSettings(services).size());
        assertEquals(2, registry.placementRules().size());
        assertEquals(1, registry.rangedWeapons().size());
        assertEquals(1, registry.modSights().size());
        assertEquals(3, registry.knowledgeSources().size());
        assertEquals(1, registry.landingMaterials().size());
        assertEquals(1, registry.fluidTransforms().size());

        // 客户端同时登记 ae2.network 与 ae2.device 两项版本 1 只读操作声明。
        Map<String, Integer> reads = registry.serverReads().stream()
                .collect(Collectors.toMap(ClientOperation::id, ClientOperation::version));
        assertEquals(1, reads.get(Ae2Compat.NETWORK_OPERATION));
        assertEquals(Ae2Advanced.DEVICE_OPERATION_VERSION, reads.get(Ae2Advanced.DEVICE_OPERATION));
    }

    @Test
    void 全部菜单布局零碰撞且21个新语义规则均能识别() {
        Ae2MenuSlots fakeSemantics = fakeMenuSlots();
        Ae2Compat compat = fullCompat(new NoGrid(), fakeSemantics);
        CompatRegistry registry = loadRegistry(compat);
        MenuLayouts layouts = registry.menuLayouts();

        // 校验 4 路共 21 个新菜单 ID 彼此无重复且与既有 6 份专属布局（10 个菜单 ID）零碰撞。
        Map<String, Ae2SemanticsLayout.Rule> rules = Ae2DeviceOpsRouter.allLayoutRules(
                new Ae2Machines(compat, Ae2MachineReads.NONE), new Ae2Tools(compat, Ae2ToolReads.NONE));
        assertEquals(21, rules.size());

        Set<String> legacyMenus = Set.of(
                "ae2:item_terminal", "ae2:craftingterm", "ae2:patternterm",
                "ae2:wirelessterm", "ae2:wirelesscraftingterm", "ae2:pattern_provider",
                "ae2:wireless_access_point", "ae2:inscriber", "ae2:drive", "ae2:skychest");
        for (String legacy : legacyMenus) {
            assertFalse(rules.containsKey(legacy), "新语义规则不应碰撞既有布局 ID：" + legacy);
        }
        // 验证每个新语义规则对应的菜单在 36 格玩家槽下都能被 MenuLayouts 正确识别为 Supported。
        for (String menuId : rules.keySet()) {
            MenuLayout.Layout classified = layouts.classify(fakeMenu(menuId, 1, 36));
            assertInstanceOf(MenuLayout.Supported.class, classified, "未能识别语义菜单：" + menuId);
        }
    }

    @Test
    void 全部25种设备完整登记且IO传输站改名生效() {
        CompatRegistry registry = loadRegistry(fullCompat(new NoGrid(), Ae2MenuSlots.NONE));
        Map<String, MachineType> byId = registry.machineTypes(offlineServices(ServerReads.NONE)).stream()
                .collect(Collectors.toMap(MachineType::id, type -> type, (a, b) -> a, LinkedHashMap::new));

        assertEquals(25, byId.size());
        assertEquals("ME IO 传输站", byId.get("ae2:io_port").name());
        assertTrue(byId.containsKey("ae2:crank"));
        assertTrue(byId.containsKey("ae2:sky_stone_tank"));
        assertTrue(byId.containsKey("ae2:growth_accelerator"));
        assertTrue(byId.containsKey("ae2:cell_workbench"));
        assertTrue(byId.containsKey("ae2:spatial_pylon"));
        assertTrue(byId.containsKey("ae2:spatial_io_port"));
        assertTrue(byId.containsKey("ae2:spatial_anchor"));
    }

    @Test
    void 线缆格组合部件委托同时路由两路部件读数设置与改配置() {
        Ae2DeviceOps partSideOps = new Ae2DeviceOps() {
            @Override public Map<String, Object> extraReadings(BlockPos pos, ClientLevel level) {
                return Map.of("north.priority", 5);
            }

            @Override public List<MachineSetting> partSettings(BlockPos host, Direction side, PlayerServices services) {
                return side == Direction.NORTH
                        ? List.of(new MachineSetting("redstone_mode", "ignore", List.of("ignore", "high_signal"), ""))
                        : List.of();
            }

            @Override public Optional<MachineSettings> settings(BlockPos pos, PlayerServices services) {
                return Optional.of(new MachineSettings() {
                    @Override public List<MachineSetting> settings(BlockPos at) {
                        return List.of(new MachineSetting("north.redstone_mode", "ignore",
                                List.of("ignore", "high_signal"), ""));
                    }

                    @Override public Optional<Action> change(BlockPos at, String key, String value,
                            Permissions permissions) {
                        return "north.redstone_mode".equals(key) ? Optional.of(noopAction("改输入总线红石"))
                                : Optional.empty();
                    }
                });
            }
        };
        Ae2DeviceOps machineSideOps = new Ae2DeviceOps() {
            @Override public Map<String, Object> extraReadings(BlockPos pos, ClientLevel level) {
                return Map.of("east.encode_mode", "crafting");
            }

            @Override public List<MachineSetting> partSettings(BlockPos host, Direction side, PlayerServices services) {
                return side == Direction.EAST
                        ? List.of(new MachineSetting("encode_mode", "crafting", List.of("crafting", "processing"), ""))
                        : List.of();
            }

            @Override public Optional<MachineSettings> settings(BlockPos pos, PlayerServices services) {
                return Optional.of(new MachineSettings() {
                    @Override public List<MachineSetting> settings(BlockPos at) {
                        return List.of(new MachineSetting("east.encode_mode", "crafting",
                                List.of("crafting", "processing"), ""));
                    }

                    @Override public Optional<Action> change(BlockPos at, String key, String value,
                            Permissions permissions) {
                        return "east.encode_mode".equals(key) ? Optional.of(noopAction("改编码终端模式"))
                                : Optional.empty();
                    }
                });
            }
        };

        Ae2DeviceOps combined = Ae2DeviceOpsRouter.composeCableOps(partSideOps, machineSideOps);
        PlayerServices services = offlineServices(ServerReads.NONE);

        Map<String, Object> readings = combined.extraReadings(CABLE_AT, null);
        assertEquals(5, readings.get("north.priority"));
        assertEquals("crafting", readings.get("east.encode_mode"));
        assertEquals(1, combined.partSettings(CABLE_AT, Direction.NORTH, services).size());
        assertEquals(1, combined.partSettings(CABLE_AT, Direction.EAST, services).size());

        MachineSettings settings = combined.settings(CABLE_AT, services).orElseThrow();
        assertEquals(Set.of("north.redstone_mode", "east.encode_mode"),
                settings.settings(CABLE_AT).stream().map(MachineSetting::key).collect(Collectors.toSet()));
        assertEquals("改输入总线红石",
                settings.change(CABLE_AT, "north.redstone_mode", "high_signal", Permissions.DEFAULT)
                        .orElseThrow().describe());
        assertEquals("改编码终端模式",
                settings.change(CABLE_AT, "east.encode_mode", "processing", Permissions.DEFAULT)
                        .orElseThrow().describe());
        assertTrue(settings.change(CABLE_AT, "south.unknown", "x", Permissions.DEFAULT).isEmpty());
    }

    @Test
    void 服务端登记ae2点device且客户端旧构造器保持向后兼容() {
        ServerOperationRegistry serverOps = new ServerOperationRegistry();
        Ae2ServerCompat serverCompat = new Ae2ServerCompat(
                (level, at) -> Optional.empty(),
                Ae2NetworkServerExtras.NONE,
                Ae2DeviceServerReads.Reader.NONE);
        new ServerCompatRegistry(serverOps).add(serverCompat);
        assertTrue(serverOps.registered(Ae2ServerCompat.OPERATION));
        assertTrue(serverOps.registered(Ae2ServerCompat.DEVICE_OPERATION));

        // 保留旧 3 参构造重载，不破坏任何既有测试调用方。
        CompatRegistry legacy = loadRegistry(new Ae2Compat(NO_TERMINALS, NO_MENU, new NoGrid()));
        assertEquals(List.of(Ae2Compat.MOD_ID), legacy.installedModIds());
        assertEquals(25, legacy.machineTypes(offlineServices(ServerReads.NONE)).size());
    }

    private static Ae2MenuSlots fakeMenuSlots() {
        return new Ae2MenuSlots() {
            @Override public boolean isAe2MenuOpen() { return true; }
            @Override public List<Integer> slotsOf(String semanticId) {
                return ("TOOLBOX".equals(semanticId) || "CONFIG".equals(semanticId)) ? List.of() : List.of(0);
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
    }

    private static Ae2Compat fullCompat(Ae2Grid grid, Ae2MenuSlots menuSlots) {
        return new Ae2Compat(NO_TERMINALS, NO_MENU, grid, Ae2Patterns.NONE, Ae2Transforms.NONE,
                Ae2Inscribers.NONE, menuSlots, Ae2MachineReads.NONE, Ae2PartControl.NONE,
                Ae2ToolReads.NONE, Ae2AdvancedReads.NONE);
    }

    private static CompatRegistry loadRegistry(Ae2Compat compat) {
        SupportedMod<CompatModule> mod = new SupportedMod<>(Ae2Compat.MOD_ID, "应用能源2",
                new VerifiedVersions("19.2.17", "19.3"), () -> compat);
        return CompatRegistry.load(List.of(mod), loaderWith("19.2.17"));
    }

    private static MenuSlots fakeMenu(String menuTypeId, int otherSlots, int playerSlots) {
        return new MenuSlots() {
            @Override public String menuTypeId() { return menuTypeId; }
            @Override public int slotCount() { return otherSlots + playerSlots; }
            @Override public boolean playerBacked(int slot) { return slot >= otherSlots; }
            @Override public int playerInventoryIndex(int slot) { return slot - otherSlots; }
        };
    }

    private static Action noopAction(String label) {
        return new Action() {
            @Override public ActionStatus tick(TickContext context) { return ActionStatus.done(); }
            @Override public String describe() { return label; }
        };
    }

    private static PlayerServices offlineServices(ServerReads serverReads) {
        Protection nobodyOwns = new Protection((dimension, x, y, z) -> Optional.empty(), List::of,
                name -> Optional.empty(), "self");
        return new PlayerServices(() -> null, (target, permissions) -> null, new Interactions(null),
                itemId -> Optional.empty(), new GearChanges() {
                    @Override public Optional<Action> wear(String itemId, GearSlotName slot) { return Optional.empty(); }
                    @Override public Optional<Action> takeOff(GearSlotName slot) { return Optional.empty(); }
                }, inner -> inner, (request, permissions) -> null, nobodyOwns, itemId -> Set.of(),
                MenuLayouts.VANILLA, new BlockScanService(), serverReads, new BackpackView() {
                    @Override public List<BackpackStack> stacks() { return List.of(); }
                    @Override public int usedSlots() { return 0; }
                    @Override public int totalSlots() { return 36; }
                }, DigToolRule.HELD);
    }

    private static final Ae2Terminals NO_TERMINALS = new Ae2Terminals() {
        @Override public List<Ae2Terminals.Terminal> near(ClientLevel level, BlockPos center, int radius) {
            return List.of();
        }
        @Override public boolean stillThere(ClientLevel level, BlockPos block, Direction side) { return false; }
    };

    private static final Ae2TerminalMenu NO_MENU = new Ae2TerminalMenu() {
        @Override public boolean belongsTo(AbstractContainerMenu menu, ClientLevel level, BlockPos block, Direction side) {
            return false;
        }
        @Override public Optional<Boolean> linked(AbstractContainerMenu menu) { return Optional.empty(); }
        @Override public Optional<List<StockEntry>> stock(AbstractContainerMenu menu) { return Optional.empty(); }
        @Override public void takeStack(AbstractContainerMenu menu, long serial) {}
        @Override public void takeOne(AbstractContainerMenu menu, long serial) {}
        @Override public void putBack(AbstractContainerMenu menu) {}
    };

    private static LoaderEnvironment loaderWith(String version) {
        return new LoaderEnvironment() {
            @Override public String loaderName() { return "test"; }
            @Override public boolean isModLoaded(String modId) { return Ae2Compat.MOD_ID.equals(modId); }
            @Override public Optional<String> modVersion(String modId) {
                return isModLoaded(modId) ? Optional.of(version) : Optional.empty();
            }
            @Override public Path gameDirectory() { return Path.of("."); }
            @Override public Path configDirectory() { return Path.of("."); }
            @Override public boolean isDevelopment() { return true; }
            @Override public FurnaceFuels furnaceFuels() { return stack -> 0; }
        };
    }

    @Test
    void 服务端设备读数未找到返回标准JSON() {
        JsonObject none = Ae2DeviceServerReads.notFound();
        assertFalse(none.get("found").getAsBoolean());
    }
}
