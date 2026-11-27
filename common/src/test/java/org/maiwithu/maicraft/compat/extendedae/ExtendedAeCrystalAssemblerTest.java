// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.extendedae;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.FeedPlan;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.behavior.menu.MenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.recipe.ShownIngredient;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.behavior.recipe.ShownStack;
import org.maiwithu.maicraft.game.menu.MenuConfirmation;
import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.game.serverlink.ClientRequest;
import org.maiwithu.maicraft.game.serverlink.ServerReads;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 水晶组装器（extendedae:crystal_assembler）核心逻辑离线单元测试：
 * 覆盖非顶面五面连网、流体按储罐余量换算标准桶物品、余量已够跳过备桶、异种流体冲突报错、
 * 界面倒桶 EMPTY_ITEM 发包与空桶放回、wait=false 对应的 outputVisible=false，以及 auto_export 与 set_side 配置。
 */
class ExtendedAeCrystalAssemblerTest {

    private static final BlockPos POS = new BlockPos(10, 64, 20);

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static ShownRecipe fluixRecipe(long waterMb) {
        return new ShownRecipe(
                "extendedae:assembler/fluix_crystal",
                "extendedae:crystal_assembler",
                "水晶组装器",
                List.of(ShownStack.item("extendedae:crystal_assembler", "水晶组装器", 1)),
                List.of(
                        ShownIngredient.of(ShownStack.item("ae2:charged_certus_quartz_crystal", "充能石英", 4)),
                        ShownIngredient.of(ShownStack.item("minecraft:redstone", "红石", 4)),
                        ShownIngredient.of(ShownStack.item("minecraft:quartz", "下界石英", 4)),
                        ShownIngredient.of(new ShownStack(ShownStack.Kind.FLUID, "minecraft:water", "水", waterMb, 1))),
                List.of(),
                List.of(ShownStack.item("ae2:fluix_crystal", "福鲁伊克斯水晶", 8)),
                null);
    }

    @Test
    void fluidPlanComputesBucketsFromDeficitSkipsWhenEnoughAndFlagsConflict() {
        ExtendedAeCompat compat = new ExtendedAeCompat(ExtendedAeReads.NONE);
        ExtendedAeDeviceReadings readings = new ExtendedAeDeviceReadings(ServerReads.NONE);
        CrystalAssemblerOps ops = new CrystalAssemblerOps(compat, readings,
                id -> "minecraft:water".equals(id) ? Optional.of("minecraft:water_bucket")
                        : "minecraft:lava".equals(id) ? Optional.of("minecraft:lava_bucket")
                        : Optional.empty(),
                null);

        // 场景 1：储罐为空，2 批 × 600 mB = 1200 mB 水 -> 需要 2 桶水且排在固体主料之前。
        FeedPlan emptyTankPlan = ops.buildPlan(POS, fluixRecipe(600), 2);
        assertTrue(emptyTankPlan.conflict().isEmpty());
        assertEquals("minecraft:water_bucket", emptyTankPlan.entries().getFirst().item());
        assertEquals(2, emptyTankPlan.entries().getFirst().count());
        assertEquals(4, emptyTankPlan.entries().size());

        // 场景 2：储罐已有 500 mB 水，需要 1200 mB -> 差 700 mB 只需 1 桶水。
        readings.putCrystalAssembler(POS, new ExtendedAeReads.CrystalAssemblerMenuView(
                0, 200, "no", List.of(), 9, "minecraft:water", 500L, 16000L, List.of()));
        FeedPlan partialPlan = ops.buildPlan(POS, fluixRecipe(600), 2);
        assertTrue(partialPlan.conflict().isEmpty());
        assertEquals("minecraft:water_bucket", partialPlan.entries().getFirst().item());
        assertEquals(1, partialPlan.entries().getFirst().count());

        // 场景 3：储罐已有 1500 mB 水，刚好满足 1200 mB -> 不再备水桶，给出说明。
        readings.putCrystalAssembler(POS, new ExtendedAeReads.CrystalAssemblerMenuView(
                0, 200, "no", List.of(), 9, "minecraft:water", 1500L, 16000L, List.of()));
        FeedPlan enoughPlan = ops.buildPlan(POS, fluixRecipe(600), 2);
        assertTrue(enoughPlan.conflict().isEmpty());
        assertTrue(enoughPlan.entries().stream().noneMatch(e -> "minecraft:water_bucket".equals(e.item())));
        assertTrue(enoughPlan.notes().stream().anyMatch(n -> n.contains("已有足够流体")));

        // 场景 4：储罐装着 400 mB 岩浆，但配方要水 -> 返回 FeedPlan.conflict 拒绝静默覆盖。
        readings.putCrystalAssembler(POS, new ExtendedAeReads.CrystalAssemblerMenuView(
                0, 200, "no", List.of(), 9, "minecraft:lava", 400L, 16000L, List.of()));
        FeedPlan conflictPlan = ops.buildPlan(POS, fluixRecipe(100), 1);
        assertTrue(conflictPlan.conflict().isPresent());
        assertTrue(conflictPlan.conflict().get().contains("minecraft:lava"));
    }

    @Test
    void tankFeedSendsEmptyItemPacketAndReturnsEmptyBucketToBackpack() {
        List<String> packets = new ArrayList<>();
        long[] tankMb = {0L};
        FakeTankMenu fakeMenu = new FakeTankMenu(new ItemStack(Items.WATER_BUCKET));
        ExtendedAeReads reads = new ExtendedAeReads() {
            @Override public boolean isMenuOpen() { return true; }
            @Override public Optional<CrystalAssemblerMenuView> openCrystalAssemblerMenu() {
                return Optional.of(new CrystalAssemblerMenuView(
                        0, 200, "no", List.of(), 10,
                        tankMb[0] > 0 ? "minecraft:water" : "", tankMb[0], 16000L, List.of()));
            }
            @Override public boolean sendEmptyItemToTank(int tankSlotIndex) {
                packets.add("EMPTY_ITEM:" + tankSlotIndex);
                tankMb[0] += 1000L;
                fakeMenu.cursor = SlotSnapshot.of(new ItemStack(Items.BUCKET));
                return true;
            }
        };
        ExtendedAeCompat compat = new ExtendedAeCompat(reads);
        ExtendedAeDeviceReadings readings = new ExtendedAeDeviceReadings(ServerReads.NONE);
        CrystalAssemblerTankFeed action = new CrystalAssemblerTankFeed(
                POS, "minecraft:water_bucket", "minecraft:water", 1000L, 1,
                instantOpening(fakeMenu), compat, readings);

        ActionStatus status = runUntilDone(action);
        assertInstanceOf(ActionStatus.Done.class, status);
        assertEquals(1, action.delivered());
        assertEquals(List.of("EMPTY_ITEM:10"), packets);
        assertEquals(1000L, tankMb[0]);
        assertTrue(fakeMenu.cursorEmpty(), "空桶必须放回背包，不得留在光标上");
        assertEquals("minecraft:bucket", fakeMenu.playerSlot0.key());
        assertTrue(fakeMenu.closed, "倒桶完成后必须关窗");
    }

    @Test
    void stateExchangePointsAndSetSideConfigurationWork() {
        List<String> sent = new ArrayList<>();
        List<String> sides = new ArrayList<>();
        String[] autoExport = {"no"};
        ExtendedAeReads reads = new ExtendedAeReads() {
            @Override public Optional<CrystalAssemblerView> crystalAssemblerAt(ClientLevel level, BlockPos at) {
                return Optional.of(new CrystalAssemblerView(true, true, true, Direction.UP));
            }
            @Override public Optional<CrystalAssemblerMenuView> openCrystalAssemblerMenu() {
                return Optional.of(new CrystalAssemblerMenuView(
                        40, 200, autoExport[0], List.copyOf(sides), 10, "minecraft:water", 2000L, 16000L,
                        List.of("ae2:speed_card", "empty", "empty", "empty")));
            }
            @Override public boolean sendConfigButton(String settingName, boolean backwards) {
                sent.add("cfg:" + settingName);
                autoExport[0] = "yes";
                return true;
            }
            @Override public boolean sendGenericAction(String action, Object... args) {
                sent.add(action + ":" + args[0] + ":" + args[1]);
                if ("set_side".equals(action) && Boolean.TRUE.equals(args[1])) {
                    sides.add(String.valueOf(args[0]));
                }
                return true;
            }
        };
        ExtendedAeCompat compat = new ExtendedAeCompat(reads);
        ExtendedAeDeviceReadings readings = new ExtendedAeDeviceReadings(ServerReads.NONE);
        FakeTankMenu fakeMenu = new FakeTankMenu(ItemStack.EMPTY);
        CrystalAssemblerOps ops = new CrystalAssemblerOps(compat, readings,
                CrystalAssemblerOps::registryBucketForFluid, (at, perm) -> instantOpening(fakeMenu));

        // 1. 仅非顶面 5 面连网，且 outputVisible=false 兼容 wait=false。
        List<ExchangePoint> points = ops.exchangePoints(null, POS, null).orElseThrow();
        assertEquals(5, points.size());
        assertTrue(points.stream().noneMatch(p -> p.side() == Direction.UP));
        assertFalse(ops.outputVisible(POS));

        // 2. 配置 auto_export=yes 与 output_side.east=true 均发对应包并更新缓存。
        Action autoAction = ops.settings(POS, null).orElseThrow()
                .change(POS, "auto_export", "yes", Permissions.DEFAULT).orElseThrow();
        assertInstanceOf(ActionStatus.Done.class, runUntilDone(autoAction));
        Action sideAction = ops.settings(POS, null).orElseThrow()
                .change(POS, "output_side.east", "true", Permissions.DEFAULT).orElseThrow();
        assertInstanceOf(ActionStatus.Done.class, runUntilDone(sideAction));
        assertEquals(List.of("cfg:auto_export", "set_side:east:true"), sent);

        // 3. 状态读数与设置项列表均反映最新缓存。
        MachineState state = CrystalAssemblerOps.buildState(POS,
                new ExtendedAeReads.CrystalAssemblerView(true, true, true, Direction.UP),
                readings.crystalAssembler(POS), Optional.empty());
        assertEquals(MachineState.Activity.RUNNING, state.activity());
        assertEquals("minecraft:water", state.readings().get("tank_fluid"));
        assertEquals(2000L, state.readings().get("tank_amount_mb"));
        assertEquals("yes", state.readings().get("auto_export"));
        List<MachineSetting> settings = CrystalAssemblerOps.assemblerSettings(
                readings.crystalAssembler(POS), Optional.empty());
        assertTrue(settings.stream().anyMatch(s -> s.key().equals("output_side.east") && s.value().equals("true")));
    }

    @Test
    void serverTankReadingOverridesStaleMenuCacheAndSuppliesOutputSidesFallback() {
        JsonObject reply = new JsonObject();
        reply.addProperty("found", true);
        JsonObject r = new JsonObject();
        r.addProperty("tank_fluid", "empty");
        r.addProperty("tank_amount_mb", 0L);
        r.addProperty("tank_capacity_mb", 16000L);
        JsonArray sides = new JsonArray();
        sides.add("north");
        sides.add("down");
        r.add("output_sides", sides);
        reply.add("readings", r);

        // 先写入旧开窗缓存（1000 mB 水），再提供服务端最新读数（0 mB 空槽）；投料计划必须采信服务端并仍请求水桶。
        ExtendedAeDeviceReadings readings = new ExtendedAeDeviceReadings(stubServerReads(reply), () -> 1000L);
        readings.putCrystalAssembler(POS, new ExtendedAeReads.CrystalAssemblerMenuView(
                0, 200, "no", List.of(), 9, "minecraft:water", 1000L, 16000L, List.of()));
        readings.latest(POS); // 触发首次问询并接收服务端权威回答。

        ExtendedAeCompat compat = new ExtendedAeCompat(ExtendedAeReads.NONE);
        CrystalAssemblerOps ops = new CrystalAssemblerOps(compat, readings,
                id -> "minecraft:water".equals(id) ? Optional.of("minecraft:water_bucket") : Optional.empty(), null);
        FeedPlan plan = ops.buildPlan(POS, fluixRecipe(600), 1);
        assertTrue(plan.conflict().isEmpty());
        assertEquals("minecraft:water_bucket", plan.entries().getFirst().item(),
                "服务端已报空罐时绝不能被上一轮开窗旧缓存误导而跳过备桶");
        assertEquals(1, plan.entries().getFirst().count());

        // 未开窗缓存时，output_side.* 必须能回落读取服务端 output_sides 数组。
        List<MachineSetting> settings = CrystalAssemblerOps.assemblerSettings(Optional.empty(), Optional.of(reply));
        assertTrue(settings.stream().anyMatch(s -> s.key().equals("output_side.north") && s.value().equals("true")));
        assertTrue(settings.stream().anyMatch(s -> s.key().equals("output_side.down") && s.value().equals("true")));
        assertTrue(settings.stream().anyMatch(s -> s.key().equals("output_side.east") && s.value().equals("false")));
    }

    private static ServerReads stubServerReads(JsonObject reply) {
        UUID id = UUID.randomUUID();
        return new ServerReads() {
            @Override public boolean available(String operation) {
                return ExtendedAeDeviceReadings.DEVICE_OPERATION.equals(operation);
            }
            @Override public Optional<UUID> ask(String operation, JsonObject body) {
                return Optional.of(id);
            }
            @Override public Optional<ClientRequest.Snapshot> answer(UUID requestId) {
                if (!id.equals(requestId)) return Optional.empty();
                return Optional.of(new ClientRequest.Snapshot(id, ExtendedAeDeviceReadings.DEVICE_OPERATION,
                        1, false, ClientRequest.Status.SUCCEEDED, ClientRequest.Effect.APPLIED, false,
                        "ok", "", 1L, reply));
            }
        };
    }

    private static ActionStatus runUntilDone(Action action) {
        TickContext tick = TickContext.of(1L, null);
        for (int i = 0; i < 60; i++) {
            ActionStatus status = action.tick(tick);
            if (!(status instanceof ActionStatus.Running)) return status;
        }
        return ActionStatus.running();
    }

    private static MenuOpening instantOpening(OpenedMenu menu) {
        return new MenuOpening() {
            @Override public Optional<OpenedMenu> opened() { return Optional.of(menu); }
            @Override public ActionStatus tick(TickContext tick) { return ActionStatus.done(); }
            @Override public String describe() { return "假开窗"; }
        };
    }

    private static final class FakeTankMenu implements OpenedMenu {
        SlotSnapshot playerSlot0;
        SlotSnapshot cursor = SlotSnapshot.empty();
        boolean closed;

        FakeTankMenu(ItemStack slot0) {
            this.playerSlot0 = SlotSnapshot.of(slot0);
        }

        @Override public Optional<MenuContent.Reading> reading() {
            MenuChannel channel = new MenuChannel() {
                @Override public boolean stillOpen() { return !closed; }
                @Override public boolean cursorCarrying() { return !cursor.isEmpty(); }
                @Override public SlotSnapshot cursor() { return cursor; }
                @Override public boolean click(int slot, int button) { return FakeTankMenu.this.click(slot, button); }
                @Override public boolean quickMove(int slot) { return true; }
                @Override public boolean selectTradeOffer(int offerIndex) { return false; }
                @Override public void requestClose() { closed = true; }
            };
            MenuSlots slots = new MenuSlots() {
                @Override public String menuTypeId() { return "extendedae:crystal_assembler"; }
                @Override public int slotCount() { return 2; }
                @Override public boolean playerBacked(int slot) { return slot == 0; }
                @Override public int playerInventoryIndex(int slot) { return 0; }
            };
            return Optional.of(new MenuContent.Reading(channel, slots,
                    List.of(1), List.of(0), List.of(0),
                    List.of(SlotSnapshot.empty()), List.of(playerSlot0)));
        }

        @Override public boolean busy() { return false; }
        @Override public boolean cursorEmpty() { return cursor.isEmpty(); }
        @Override public SlotSnapshot cursor() { return cursor; }
        @Override public boolean quickMove(int slotId) { return true; }

        @Override public boolean click(int slotId, int button) {
            if (slotId != 0) return false;
            SlotSnapshot tmp = cursor;
            cursor = playerSlot0;
            playerSlot0 = tmp;
            return true;
        }

        @Override public Optional<PendingMenuAction> submitModAction(String what, Runnable send,
                MenuConfirmation confirmation, int timeoutTicks) {
            send.run();
            return Optional.empty();
        }

        @Override public void noteCursorTakenFrom(int slotId) {}

        @Override public Action closing() {
            return new Action() {
                @Override public ActionStatus tick(TickContext tick) {
                    closed = true;
                    return ActionStatus.done();
                }
                @Override public String describe() { return "假关窗"; }
            };
        }

        @Override public void abandon() { closed = true; }
    }
}
