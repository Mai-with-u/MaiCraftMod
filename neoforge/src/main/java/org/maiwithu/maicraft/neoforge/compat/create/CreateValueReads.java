// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.create;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.simibubi.create.content.kinetics.motor.KineticScrollValueBehaviour;
import com.simibubi.create.content.redstone.diodes.BrassDiodeScrollValueBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsPacket;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.SidedFilteringBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollOptionBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.compat.create.CreateValues;

/**
 * 机械动力设置框的读写端：方块实体上每个带设置框的行为——过滤格（含黄铜隧道每一面的）、滚轮数值与选项——
 * 读出现值、名字、上限，过滤格再算出能点的位置（Create 画框的那一处，点在框的半径以内才算点中）。
 * 改数值与选项发 Create 设置界面关上时发的那一包。只翻译，不判断。
 */
public final class CreateValueReads implements CreateValues {

    @Override public Boxes boxes(ClientLevel level, BlockPos at) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !level.isLoaded(at) || !(level.getBlockEntity(at) instanceof SmartBlockEntity smart)) {
            return Boxes.none();
        }
        BlockState state = level.getBlockState(at);
        List<FilterBox> filters = new ArrayList<>();
        List<ValueBox> values = new ArrayList<>();
        for (BlockEntityBehaviour behaviour : smart.getAllBehaviours()) {
            if (behaviour instanceof SidedFilteringBehaviour sided) {
                // 每一面一格的过滤格：哪一面真画了框才读哪一面，点的时候点那一面。
                for (Direction side : Direction.values()) {
                    FilteringBehaviour face = sided.get(side);
                    if (face == null || !face.isActive()) continue;
                    FilterBox box = filter(sided, face, side, level, at, state, List.of(side));
                    if (!box.spots().isEmpty()) filters.add(box);
                }
            } else if (behaviour instanceof FilteringBehaviour filtering && filtering.isActive()) {
                // 水平放置的动力锯虽然挂着 FilteringBehaviour，但槽位偏移返回 null、现场没有激活的过滤框，不虚报设置项。
                FilterBox box = filter(filtering, filtering, null, level, at, state, List.of(Direction.values()));
                if (!box.spots().isEmpty()) filters.add(box);
            } else if (behaviour instanceof ScrollValueBehaviour scroll && scroll.isActive()) {
                values.add(value(scroll, at, player));
            }
        }
        return new Boxes(filters, values);
    }

    @Override public void sendValue(BlockPos at, int netId, Direction side, int row, int value) {
        CatnipServices.NETWORK.sendToServer(new ValueSettingsPacket(at, row, value, null, null, side, false, netId));
    }

    // 一个过滤格：放着什么、限多少，再挨个面试 Create 画框的位置，框画在那一面上才算能点；
    // 不分侧面的单槽（如朝上动力锯顶面过滤槽）按槽位相对方块中心的方位定它落在哪一面。
    private static FilterBox filter(FilteringBehaviour owner, FilteringBehaviour face, Direction side, ClientLevel level,
            BlockPos at, BlockState state, List<Direction> faces) {
        List<Spot> spots = new ArrayList<>();
        ValueBoxTransform slot = owner.getSlotPositioning();
        for (Direction candidate : faces) {
            if (slot instanceof ValueBoxTransform.Sided sidedSlot) sidedSlot.fromSide(candidate);
            Vec3 local = slot.getLocalOffset(level, at, state);
            if (local == null || !slot.testHit(level, at, state, local)) continue;
            double radius = slot.getScale() / (slot instanceof ValueBoxTransform.Dual ? 3.5 : 2.0);
            Direction hitFace = slot instanceof ValueBoxTransform.Sided ? candidate
                    : Direction.getNearest(local.x - 0.5, local.y - 0.5, local.z - 0.5);
            spots.add(new Spot(hitFace, Vec3.atLowerCornerOf(at).add(local), radius));
            if (!(slot instanceof ValueBoxTransform.Sided)) break;
        }
        String label = filterLabel(face);
        return new FilterBox(owner.netId(), side, label, face.getFilter().copy(), face.isCountVisible(), face.upTo,
                face.getAmount(), face.getMaxStackSize(), spots);
    }

    // 一个数值或选项框：转速分正反、计时按刻、选项按枚举名，别的是普通数。
    private static ValueBox value(ScrollValueBehaviour scroll, BlockPos at, LocalPlayer player) {
        ValueSettingsBoard board = scroll.createBoard(player, centerHit(at));
        String label = board.title().getString();
        if (scroll instanceof ScrollOptionBehaviour<?> options) {
            List<String> names = new ArrayList<>();
            // 枚举常量带方法体时 getClass 是匿名子类，要从声明它的枚举取全部常量。
            for (Enum<?> option : options.get().getDeclaringClass().getEnumConstants()) {
                names.add(option.name().toLowerCase(Locale.ROOT));
            }
            return new ValueBox(scroll.netId(), Kind.OPTION, label, scroll.getValue(), board.maxValue(), names);
        }
        Kind kind = scroll instanceof KineticScrollValueBehaviour ? Kind.SIGNED
                : scroll instanceof BrassDiodeScrollValueBehaviour ? Kind.TICKS : Kind.NUMBER;
        return new ValueBox(scroll.netId(), kind, label, scroll.getValue(), board.maxValue(), List.of());
    }

    // 过滤格自己的标题（如"配方过滤器"、"流体过滤器"、"过滤器"）：createBoard().title() 恒返回提取数量，不能拿来当过滤格名。
    private static String filterLabel(FilteringBehaviour behaviour) {
        try {
            return behaviour.getLabel().getString();
        } catch (RuntimeException unavailable) {
            return "过滤格";
        }
    }

    private static BlockHitResult centerHit(BlockPos at) {
        return new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false);
    }
}
