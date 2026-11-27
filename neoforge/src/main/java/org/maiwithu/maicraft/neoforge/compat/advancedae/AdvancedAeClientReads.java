// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.advancedae;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import appeng.api.config.YesNo;
import appeng.api.inventories.InternalInventory;
import appeng.api.orientation.RelativeSide;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.GenericStack;
import appeng.helpers.externalstorage.GenericStackInv;
import appeng.menu.AEBaseMenu;
import appeng.menu.SlotSemantic;
import appeng.menu.SlotSemantics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarrationThunk;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.pedroksl.advanced_ae.common.blocks.ReactionChamberBlock;
import net.pedroksl.advanced_ae.common.entities.ReactionChamberEntity;
import net.pedroksl.advanced_ae.gui.ReactionChamberMenu;
import net.pedroksl.ae2addonlib.client.screens.OutputDirectionScreen;
import net.pedroksl.ae2addonlib.client.widgets.OutputDirectionButton;
import net.pedroksl.ae2addonlib.gui.OutputDirectionMenu;

import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.compat.advancedae.AdvancedAeReads;

/**
 * 高级 AE（AdvancedAE）客户端读写端（NeoForge）：
 * 零反射直接读取客户端同步的反应仓方块实体（工作态、供电态、双流体槽与输入输出物品槽）、
 * 按 AE2 公开槽位语义表读取当前菜单槽位、读反应仓主菜单与输出方向子菜单按钮提示状态，
 * 并将发包动作委托给 {@link AdvancedAeMenuActions}。
 */
public final class AdvancedAeClientReads implements AdvancedAeReads {

    /** ae2addonlib 输出方向按钮启用态提示翻译键（避免编译期引入可选依赖 guideme）。 */
    private static final String ENABLED_TOOLTIP_KEY = "gui.tooltips.ae2addonlib.Enabled";

    private final AdvancedAeMenuActions menuActions;

    public AdvancedAeClientReads() {
        this(new AdvancedAeMenuActions());
    }

    public AdvancedAeClientReads(AdvancedAeMenuActions menuActions) {
        this.menuActions = menuActions;
    }

    @Override public Optional<ChamberView> chamberAt(ClientLevel level, BlockPos at) {
        if (level == null || at == null || !level.isLoaded(at)) return Optional.empty();
        BlockEntity entity = level.getBlockEntity(at);
        if (!(entity instanceof ReactionChamberEntity chamber)) return Optional.empty();
        // 服务端停工时客户端方块实体内部 working 标志不复位，仅方块状态 WORKING 随 setBlock 同步回 false，故优先读方块状态。
        boolean working = chamber.getBlockState().hasProperty(ReactionChamberBlock.WORKING)
                ? chamber.getBlockState().getValue(ReactionChamberBlock.WORKING)
                : chamber.isWorking();
        GenericStackInv tank = chamber.getTank();
        // 反应仓双储罐约定：槽 1 为输入流体槽，槽 0 为输出流体槽。
        TankSlot inputFluid = readTankSlot(tank, 1);
        TankSlot outputFluid = readTankSlot(tank, 0);
        // AE2 客户端 ManagedGridNode 无真实节点且内部电量不同步，仅正在工作时能确知通电在线，其余情形返回 null 保持未知。
        Boolean powered = working ? Boolean.TRUE : null;
        Boolean active = working ? Boolean.TRUE : null;
        return Optional.of(new ChamberView(
                working,
                powered,
                active,
                chamber.getFront(),
                inputFluid,
                outputFluid,
                readInventory(chamber.getInput()),
                readInventory(chamber.getOutput())));
    }

    @Override public List<Integer> menuSlotsOf(String semanticId) {
        if (semanticId == null || semanticId.isEmpty()) return List.of();
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !(player.containerMenu instanceof AEBaseMenu menu)) return List.of();
        SlotSemantic semantic = SlotSemantics.get(semanticId);
        if (semantic == null) return List.of();
        List<Slot> slots = menu.getSlots(semantic);
        if (slots.isEmpty()) return List.of();
        List<Integer> indices = new ArrayList<>(slots.size());
        for (Slot slot : slots) indices.add(slot.index);
        return List.copyOf(indices);
    }

    @Override public Optional<ChamberMenuView> openChamberMenu() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !(player.containerMenu instanceof ReactionChamberMenu menu)) {
            return Optional.empty();
        }
        // 反应仓菜单构造默认 maxProcessingTime=-1、autoExport=NO，需等服务端 @GuiSync 首包把总刻数同步为正数后才算就绪。
        if (menu.getMaxProgress() <= 0) {
            return Optional.empty();
        }
        String autoExport = menu.getAutoExport() == YesNo.YES ? "yes" : "no";
        GenericStackInv tank = menu.getTank();
        TankSlot inputFluid = readTankSlot(tank, 1);
        TankSlot outputFluid = readTankSlot(tank, 0);
        List<String> upgrades = readUpgradeSlots(menu);
        return Optional.of(new ChamberMenuView(
                autoExport,
                menu.getCurrentProgress(),
                menu.getMaxProgress(),
                menu.getShowWarning(),
                inputFluid,
                outputFluid,
                upgrades));
    }

    // 输出方向子菜单由服务端发 OutputDirectionUpdatePacket 更新各方向按钮的启用态提示；
    // 按钮尚未收到任何提示时表示首包同步未到达，返回空以继续等待。
    @Override public Optional<Set<String>> openOutputDirections() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !(player.containerMenu instanceof OutputDirectionMenu)) return Optional.empty();
        if (!(mc.screen instanceof OutputDirectionScreen screen)) return Optional.empty();
        String enabledText = Component.translatable(ENABLED_TOOLTIP_KEY).getString();
        Set<String> enabled = new LinkedHashSet<>();
        int syncedButtons = 0;
        for (GuiEventListener child : screen.children()) {
            if (!(child instanceof OutputDirectionButton button)) continue;
            RelativeSide side = button.getSide();
            if (side == null) continue;
            Tooltip tooltip = button.getTooltip();
            if (tooltip == null) continue;
            syncedButtons++;
            if (enabledText.equals(readTooltipText(tooltip))) {
                enabled.add(side.name().toLowerCase(Locale.ROOT));
            }
        }
        return syncedButtons == 0 ? Optional.empty() : Optional.of(Set.copyOf(enabled));
    }

    @Override public boolean sendAutoExportToggle() {
        return menuActions.sendAutoExportToggle();
    }

    @Override public boolean sendClearFluidIn() {
        return menuActions.sendClearFluidIn();
    }

    @Override public boolean sendClearFluidOut() {
        return menuActions.sendClearFluidOut();
    }

    @Override public boolean sendOpenOutputDirection() {
        return menuActions.sendOpenOutputDirection();
    }

    @Override public boolean sendToggleOutputSide(String relativeSide) {
        return menuActions.sendToggleOutputSide(relativeSide);
    }

    @Override public boolean sendReturnToParentMenu() {
        return menuActions.sendReturnToParentMenu();
    }

    private static TankSlot readTankSlot(GenericStackInv tank, int slot) {
        if (tank == null || slot < 0 || slot >= tank.size()) return TankSlot.EMPTY;
        GenericStack stack = tank.getStack(slot);
        if (stack == null || !(stack.what() instanceof AEFluidKey fluidKey) || stack.amount() <= 0L) {
            return TankSlot.EMPTY;
        }
        String fluidId = BuiltInRegistries.FLUID.getKey(fluidKey.getFluid()).toString();
        return new TankSlot(fluidId, stack.amount(), TANK_CAPACITY_MB);
    }

    private static List<MachineState.Shown> readInventory(InternalInventory inv) {
        if (inv == null) return List.of();
        List<MachineState.Shown> out = new ArrayList<>();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.isEmpty()) {
                out.add(new MachineState.Shown(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                        stack.getCount()));
            }
        }
        return List.copyOf(out);
    }

    private static List<String> readUpgradeSlots(AEBaseMenu menu) {
        List<Slot> slots = menu.getSlots(SlotSemantics.UPGRADE);
        List<String> out = new ArrayList<>(slots.size());
        for (Slot slot : slots) {
            ItemStack stack = slot.getItem();
            out.add(stack.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        }
        return List.copyOf(out);
    }

    private static String readTooltipText(Tooltip tooltip) {
        StringBuilder captured = new StringBuilder();
        NarrationElementOutput sink = new NarrationElementOutput() {
            @Override public void add(NarratedElementType type, Component contents) {
                if (contents != null) captured.append(contents.getString());
            }

            @Override public void add(NarratedElementType type, String contents) {
                if (contents != null) captured.append(contents);
            }

            @Override public void add(NarratedElementType type, Component... contents) {
                for (Component c : contents) {
                    if (c != null) captured.append(c.getString());
                }
            }

            @Override public void add(NarratedElementType type, NarrationThunk<?> contents) {
                contents.getText(captured::append);
            }

            @Override public NarrationElementOutput nest() {
                return this;
            }
        };
        tooltip.updateNarration(sink);
        return captured.toString();
    }
}
