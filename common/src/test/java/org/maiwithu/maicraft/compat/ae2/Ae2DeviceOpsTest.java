// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.FeedPlan;
import org.maiwithu.maicraft.ability.machine.spi.FuelUse;
import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.ability.machine.spi.MachineFeeding;
import org.maiwithu.maicraft.ability.machine.spi.MachineInstallations;
import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineSettings;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.game.interaction.DigToolRule;
import org.maiwithu.maicraft.behavior.inventory.hands.GearChanges;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.serverlink.ServerReads;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * AE2 设备委托接缝测试：验证 Ae2MachineType 按设备编号把读数、投料、设置、按面部件配置、
 * 安装与出口委托给 Ae2DeviceOps，以及 Ae2MachineCatalog 保持现有 18 种并支持按需增补。
 */
class Ae2DeviceOpsTest {

    private static final BlockPos AT = new BlockPos(2, 64, 2);

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
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

    private static PlayerServices offlineServices() {
        Protection nobodyOwns = new Protection((dimension, x, y, z) -> Optional.empty(), List::of,
                name -> Optional.empty(), "self");
        return new PlayerServices(() -> null, (target, permissions) -> null, new Interactions(null),
                itemId -> Optional.empty(), new GearChanges() {
                    @Override public Optional<Action> wear(String itemId, GearSlotName slot) { return Optional.empty(); }
                    @Override public Optional<Action> takeOff(GearSlotName slot) { return Optional.empty(); }
                }, inner -> inner, (request, permissions) -> null, nobodyOwns, itemId -> Set.of(),
                MenuLayouts.VANILLA, new BlockScanService(), ServerReads.NONE, new BackpackView() {
                    @Override public List<BackpackStack> stacks() { return List.of(); }
                    @Override public int usedSlots() { return 0; }
                    @Override public int totalSlots() { return 36; }
                }, DigToolRule.HELD);
    }

    private static Ae2MachineType machineWith(String kindId, Map<String, Ae2DeviceOps> deviceOps) {
        Ae2MachineCatalog.Kind kind = Ae2MachineCatalog.kinds().stream()
                .filter(candidate -> candidate.id().equals(kindId)).findFirst().orElseThrow();
        Ae2Compat compat = new Ae2Compat(NO_TERMINALS, NO_MENU, new NoGrid(), Ae2Patterns.NONE,
                Ae2Transforms.NONE, Ae2Inscribers.NONE);
        return new Ae2MachineType(kind, compat, Ae2Inscribers.NONE, offlineServices(),
                Ae2Compat.blocksOf(kind.blocks()), deviceOps);
    }

    private static Action noopAction(String label) {
        return new Action() {
            @Override public ActionStatus tick(TickContext context) { return ActionStatus.done(); }
            @Override public String describe() { return label; }
        };
    }

    @Test
    void 设备委托接管读数投料设置与按面部件配置() {
        Ae2DeviceOps vibrationOps = new Ae2DeviceOps() {
            @Override public Optional<MachineState> state(BlockPos pos, ClientLevel level) {
                return Optional.of(new MachineState(MachineState.Activity.RUNNING, null, 0.5,
                        List.of(new MachineState.Shown("minecraft:coal", 4)), Map.of("power_per_tick", 20.0), ""));
            }

            @Override public Optional<MachineFeeding> feeding(BlockPos pos, PlayerServices services) {
                return Optional.of(new MachineFeeding() {
                    @Override public Optional<Action> feed(BlockPos at, FeedPlan.Entry item, Permissions permissions) {
                        return Optional.of(noopAction("投入燃料"));
                    }
                    @Override public Optional<Action> take(BlockPos at, String itemId, int count, Permissions permissions) {
                        return Optional.empty();
                    }
                    @Override public FuelUse fuelUse(BlockPos at) {
                        return FuelUse.fuel("把可燃物投入振动室发电");
                    }
                });
            }

            @Override public List<MachineState.Shown> output(BlockPos pos, ClientLevel level) {
                return List.of(new MachineState.Shown("minecraft:bucket", 1));
            }
        };

        Ae2MachineType vibration = machineWith("ae2:vibration_chamber", Map.of("ae2:vibration_chamber", vibrationOps));
        MachineState state = vibration.state(AT);
        assertEquals(MachineState.Activity.RUNNING, state.activity());
        assertEquals(20.0, state.readings().get("power_per_tick"));
        assertEquals(FuelUse.Kind.FUEL, vibration.fuelUse(AT).kind());
        assertEquals("投入燃料", vibration.feed(AT, new FeedPlan.Entry("minecraft:coal", 4, false), Permissions.DEFAULT)
                .orElseThrow().describe());
        assertEquals(1, vibration.output(AT).size());

        // 线缆部件配置委托：验证 settings、change 与按面 partSettings 均可路由出去。
        Ae2DeviceOps cableOps = new Ae2DeviceOps() {
            @Override public Optional<MachineSettings> settings(BlockPos pos, PlayerServices services) {
                return Optional.of(new MachineSettings() {
                    @Override public List<MachineSetting> settings(BlockPos at) {
                        return List.of(new MachineSetting("north.redstone_mode", "ignore",
                                List.of("ignore", "high"), "北面总线红石模式"));
                    }
                    @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
                        return key.equals("north.redstone_mode") ? Optional.of(noopAction("切红石模式")) : Optional.empty();
                    }
                });
            }

            @Override public List<MachineSetting> partSettings(BlockPos host, Direction side, PlayerServices services) {
                return side == Direction.NORTH
                        ? List.of(new MachineSetting("redstone_mode", "ignore", List.of("ignore", "high"), "红石模式"))
                        : List.of();
            }

            @Override public Optional<MachineInstallations> installations(PlayerServices services) {
                return Optional.of(new MachineInstallations() {
                    @Override public Optional<String> installationProblem(Installation installation) { return Optional.empty(); }
                    @Override public Optional<Action> install(Installation installation, Permissions permissions) {
                        return Optional.of(noopAction("委托安装"));
                    }
                    @Override public boolean installed(Installation installation) { return true; }
                });
            }
        };

        Ae2MachineType cable = machineWith(Ae2MachineCatalog.CABLE_BUS, Map.of(Ae2MachineCatalog.CABLE_BUS, cableOps));
        assertEquals(1, cable.settings(AT).size());
        assertEquals("切红石模式", cable.change(AT, "north.redstone_mode", "high", Permissions.DEFAULT).orElseThrow().describe());
        assertEquals(1, cable.partSettings(AT, Direction.NORTH).size());
        assertTrue(cable.partSettings(AT, Direction.SOUTH).isEmpty());
        assertEquals("委托安装", cable.install(new Installation("ae2:fluix_glass_cable", List.of(AT)), Permissions.DEFAULT)
                .orElseThrow().describe());
    }

    @Test
    void 额外读数与展示物可并入通用状态且目录保持十八种并支持增补() {
        Ae2DeviceOps extraOnly = new Ae2DeviceOps() {
            @Override public Map<String, Object> extraReadings(BlockPos pos, ClientLevel level) {
                return Map.of("stored_item", "ae2:charged_certus_quartz_crystal");
            }
            @Override public List<MachineState.Shown> extraShown(BlockPos pos, ClientLevel level) {
                return List.of(new MachineState.Shown("ae2:charged_certus_quartz_crystal", 1));
            }
        };
        Ae2MachineType charger = machineWith("ae2:charger", Map.of("ae2:charger", extraOnly));
        MachineState merged = charger.state(AT);
        assertEquals("ae2:charged_certus_quartz_crystal", merged.readings().get("stored_item"));
        assertEquals(1, merged.shown().size());

        // 基础目录保持现有 18 种行为不变，kinds(extra) 可按需增补设备。
        assertEquals(18, Ae2MachineCatalog.kinds().size());
        Ae2MachineCatalog.Kind tank = Ae2MachineCatalog.of("sky_stone_tank", "陨石储罐", MachineRole.STORAGE, false);
        List<Ae2MachineCatalog.Kind> extended = Ae2MachineCatalog.kinds(List.of(tank));
        assertEquals(19, extended.size());
        assertEquals("ae2:sky_stone_tank", extended.getLast().id());
    }

    @Test
    void 设备委托可覆盖连接口并在未覆写时回退默认六面或单机规则() {
        BlockState dummy = Blocks.STONE.defaultBlockState();
        List<ExchangePoint> customPoints = List.of(
                new ExchangePoint(AT, Direction.NORTH, null, MeNetworkReader.KIND, ExchangePoint.Flow.BOTH, "前侧连网"),
                new ExchangePoint(AT, Direction.SOUTH, null, MeNetworkReader.KIND, ExchangePoint.Flow.BOTH, "后侧连网"));
        Ae2DeviceOps onlyFrontBack = new Ae2DeviceOps() {
            @Override public Optional<List<ExchangePoint>> exchangePoints(BlockState state, BlockPos at, ClientLevel level) {
                return Optional.of(customPoints);
            }
        };

        // 覆写了 exchangePoints 时直接返回委托给出的受限面，不再退回六面。
        Ae2MachineType overridden = machineWith("ae2:charger", Map.of("ae2:charger", onlyFrontBack));
        List<ExchangePoint> actual = overridden.exchangePoints(dummy, AT);
        assertEquals(2, actual.size());
        assertEquals(Direction.NORTH, actual.get(0).side());
        assertEquals(Direction.SOUTH, actual.get(1).side());

        // 未覆写 exchangePoints 的空委托：单机回退为 1 个打开界面换物点，联网设备回退为默认 6 面。
        Ae2DeviceOps emptyOps = new Ae2DeviceOps() {};
        Ae2MachineType standalone = machineWith("ae2:charger", Map.of("ae2:charger", emptyOps));
        List<ExchangePoint> standalonePoints = standalone.exchangePoints(dummy, AT);
        assertEquals(1, standalonePoints.size());
        assertEquals(ExchangePoint.Medium.ITEM, standalonePoints.getFirst().medium());

        Ae2MachineType networked = machineWith("ae2:drive", Map.of("ae2:drive", emptyOps));
        assertEquals(6, networked.exchangePoints(dummy, AT).size());
    }
}
