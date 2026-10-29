// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.interaction.AimCheck;
import org.maiwithu.maicraft.behavior.interaction.FirstPersonScene;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 挖掉物理结构上的一格：先把最合适的工具换到主手（没有就空手挖），转头看着那一格此刻在世界里的位置，
 * 准星第一下打中那一格才按住左键，一直挖到游戏确认那一格没了。交给游戏的是地块里的格与面，和真人对着船上的方块挖一样。
 * 站在地上挖船上的方块，Sable 会把船推一下（站在船上挖不推）：挑站位那边先挑船上，这里只管挖。
 * 已经是空气算挖完；够不着、看不见、准星总打到别的照实说，由施工换站位或记"够不着"。
 */
final class StructureBreak implements Action {

    /** 挖一格最多等几刻（硬方块空手挖要很久）。 */
    static final int BREAK_TICKS = 1200;
    /** 转头最多等几刻。 */
    static final int AIM_TICKS = 40;

    private final StructureReads reads;
    private final PlayerServices services;
    private final String structureId;
    private final BlockPos target;

    private Action toMainhand;
    private boolean toolChosen;
    private Vec3 aimPoint;
    private int aimed;
    private PendingInteraction pending;
    private PlayerContext last;

    StructureBreak(StructureReads reads, PlayerServices services, String structureId, BlockPos target) {
        this.reads = Objects.requireNonNull(reads, "reads");
        this.services = Objects.requireNonNull(services, "services");
        this.structureId = Objects.requireNonNull(structureId, "structureId");
        this.target = target.immutable();
    }

    @Override public ActionStatus tick(TickContext tick) {
        PlayerContext context = tick.player();
        last = context;
        if (context == null || !context.isCurrent() || context.interactionSender() == null) return ActionStatus.running();
        LocalPlayer player = context.localPlayer();
        FirstPersonScene scene = StructureScene.of(reads, structureId).apply(context);
        BlockState state = scene.blockAt(target);
        if (pending != null) return breaking(context, scene);
        if (state == null) {
            return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "那个结构看不到了，或那一格没加载，挖不了"));
        }
        if (state.isAir()) return ActionStatus.done();
        if (!toolChosen) return chooseTool(tick, player, state);
        if (!context.canInteractThisTick()) return ActionStatus.running();
        return aimAndStart(context, scene, player);
    }

    // 挖之前照挑工具把那把换到主手（和地上挖格同一份：最省优先、改掉落的附魔与预留的不掺和）；
    // 挑工具说不换、或身上没有更合适的就拿手上的挖。
    private ActionStatus chooseTool(TickContext tick, LocalPlayer player, BlockState state) {
        if (toMainhand == null) {
            int slot = services.digTools().slotFor(player, state, 0, 1);
            ItemStack best = slot < 0 ? ItemStack.EMPTY : player.getInventory().getItem(slot);
            if (best.isEmpty() || ItemStack.isSameItem(best, player.getMainHandItem())) {
                toolChosen = true;
                return ActionStatus.progressed();
            }
            Optional<Action> move = services.toMainhand().moveToMainhand(BuiltInRegistries.ITEM.getKey(best.getItem()).toString());
            if (move.isEmpty()) {
                toolChosen = true;
                return ActionStatus.progressed();
            }
            toMainhand = move.get();
        }
        ActionStatus status = toMainhand.tick(tick);
        if (status instanceof ActionStatus.Running) return status;
        toMainhand.close();
        toMainhand = null;
        // 工具没换过来也照样挖：慢一点，掉不掉东西由游戏结算。
        toolChosen = true;
        return ActionStatus.progressed();
    }

    // 看着那一格此刻在世界里看得见的那一点，转到位后准星第一下打中它才开挖；交给游戏的是地块里的格与面。
    private ActionStatus aimAndStart(PlayerContext context, FirstPersonScene scene, LocalPlayer player) {
        if (aimPoint == null) {
            BlockHitResult visible = scene.visibleHit(target);
            if (visible == null) {
                return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE, "从这里看不到、够不着船上那一格，要换站位"));
            }
            aimPoint = visible.getLocation();
        }
        context.input().halt(player);
        context.input().lookAt(player, aimPoint);
        if (!AimCheck.settled(scene.viewVector(), scene.eyePosition(), aimPoint) && ++aimed < AIM_TICKS) {
            return ActionStatus.running();
        }
        HitResult hit = scene.sightRay();
        if (!AimCheck.hitsBlock(hit, target)) {
            return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE,
                    "看着船上那一格，准星却打到" + AimCheck.describeHit(hit) + "，要换站位"));
        }
        PendingInteraction started = context.interactionSender().startBreaking(context, (BlockHitResult) hit, BREAK_TICKS);
        // 提交入口这一刻不收：下一刻原样再开挖。
        if (started == null) return ActionStatus.running();
        pending = started;
        return pending.terminal() ? settle() : ActionStatus.progressed();
    }

    // 按住挖：每刻接着挖、一直看着那一格（船停着，位置不变），到游戏确认那一格没了为止。
    private ActionStatus breaking(PlayerContext context, FirstPersonScene scene) {
        if (aimPoint != null) context.input().lookAt(context.localPlayer(), aimPoint);
        pending = context.interactionSender().continueBreaking(context, pending);
        if (!pending.terminal()) return ActionStatus.running();
        return settle();
    }

    private ActionStatus settle() {
        return switch (pending.status()) {
            case CONFIRMED_APPLIED -> ActionStatus.done();
            case CONFIRMED_NOT_APPLIED -> ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                    "挖船上那一格，游戏没让挖掉" + detail()));
            default -> ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "挖了船上那一格，但没能确认挖掉" + detail()));
        };
    }

    private String detail() {
        return pending.detail() == null || pending.detail().isBlank() ? "" : "：" + pending.detail();
    }

    @Override public void pause() {
        stop("挖船上那一格被打断");
    }

    @Override public void close() {
        if (toMainhand != null) {
            toMainhand.close();
            toMainhand = null;
        }
        stop("挖船上那一格结束");
    }

    // 停手：没挖完的真的松开左键（游戏接口层最迟下一刻停），不把挖到一半的格冒充挖掉。
    private void stop(String why) {
        if (pending != null && !pending.terminal() && last != null && last.interactionSender() != null) {
            pending = last.interactionSender().cancelBreakingForTaskBoundary(last, pending, why);
        }
        // 已经确认挖掉的留着（下一刻照挖完收场）；停掉的、没确认的作废，接着做时重新瞄准再挖。
        if (pending != null && pending.status() != PendingInteraction.Status.CONFIRMED_APPLIED) pending = null;
        aimPoint = null;
        aimed = 0;
        if (last != null && last.isCurrent() && last.input() != null) last.input().halt(last.localPlayer());
    }

    @Override public Interruptibility interruptibility() {
        return Interruptibility.WORKING;
    }

    @Override public String describe() {
        return pending == null ? "看着船上那一格准备挖" : "挖船上那一格";
    }
}
