// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.FallingBlock;

import org.maiwithu.maicraft.behavior.interaction.AimAndInteract;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.ItemsToKeep;
import org.maiwithu.maicraft.behavior.inventory.hands.ClientMovesToMainhand;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 垫一块：废料换到主手，挨个对着能用的邻格那一面点，空洞那一格有了实心方块才算；都点不成如实失败。 */
public final class FillerPlacement implements Action {

    private final Interactions interactions;
    private final ClientMovesToMainhand toMainhand;
    private final BlockPos cell;
    private final String filler;
    private final List<BlockPos> supports;
    private Action moving;
    private AimAndInteract clicking;
    private int tried;
    private Problem lastProblem;

    /**
     * @param cell     要垫上的那一格
     * @param filler   拿什么垫（物品 ID）
     * @param supports 挨个对着点的邻格实地，先点的在前
     */
    public FillerPlacement(Interactions interactions, ClientMovesToMainhand toMainhand, BlockPos cell, String filler,
                           List<BlockPos> supports) {
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.toMainhand = Objects.requireNonNull(toMainhand, "toMainhand");
        this.cell = cell.immutable();
        this.filler = filler;
        this.supports = supports;
    }

    /**
     * 能拿去垫的废料：和走路垫脚同一份先后（泥土、废石先，圆石最后），沙砾这类会往下掉的不要——垫在空洞上面会掉下去；
     * 预留的、正要用的留够数，只垫多出来的。
     */
    public static List<String> carriedFillers(LocalPlayer self, ItemsToKeep keeps) {
        Map<String, Integer> carried = new HashMap<>();
        for (ItemStack stack : self.getInventory().items) {
            if (!stack.isEmpty()) {
                carried.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
            }
        }
        List<String> fillers = new ArrayList<>();
        for (String itemId : ScaffoldBlocks.usable(id -> carried.getOrDefault(id, 0), keeps)) {
            if (!(BuiltInRegistries.BLOCK.get(ResourceLocation.parse(itemId)) instanceof FallingBlock)) fillers.add(itemId);
        }
        return List.copyOf(fillers);
    }

    @Override
    public ActionStatus tick(TickContext context) {
        PlayerContext player = context.player();
        if (player == null || player.localPlayer() == null) {
            return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE, "不在世界里，垫不了"));
        }
        // 先换手：废料已经在主手就不换。
        String held = BuiltInRegistries.ITEM.getKey(player.localPlayer().getMainHandItem().getItem()).toString();
        if (!filler.equals(held) && moving == null && clicking == null) {
            Optional<Action> move = toMainhand.actionToMainhand(filler);
            if (move.isEmpty()) {
                return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, "身上没有 " + filler + "，垫不了"));
            }
            moving = move.get();
        }
        if (moving != null) {
            ActionStatus status = moving.tick(context);
            if (!(status instanceof ActionStatus.Done)) return status;
            moving.close();
            moving = null;
        }
        if (clicking == null) {
            if (tried >= supports.size()) {
                return ActionStatus.failed(lastProblem != null ? lastProblem
                        : Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "对着旁边的实地都没垫成"));
            }
            BlockPos support = supports.get(tried);
            Direction face = Direction.getNearest(cell.getX() - support.getX(), cell.getY() - support.getY(),
                    cell.getZ() - support.getZ());
            clicking = interactions.useBlockFace(support, face, filled(cell));
            return ActionStatus.progressed();
        }
        ActionStatus status = clicking.tick(context);
        if (status instanceof ActionStatus.Failed failed) {
            // 这一面点不到、点了没放上：换下一个邻格再点。
            clicking.close();
            clicking = null;
            tried++;
            lastProblem = failed.problem();
            return ActionStatus.progressed();
        }
        return status;
    }

    @Override
    public void pause() {
        if (moving != null) moving.pause();
        if (clicking != null) clicking.pause();
    }

    @Override
    public void close() {
        if (moving != null) moving.close();
        if (clicking != null) clicking.close();
    }

    @Override
    public String describe() {
        return "在 " + cell.toShortString() + " 垫一块 " + filler;
    }

    // 垫成了：那一格有了实心方块，并等服务端确认这次放置。
    private static InteractionConfirmation filled(BlockPos cell) {
        return new InteractionConfirmation() {
            @Override
            public Verdict observe(PlayerContext context) {
                if (!context.level().isLoaded(cell)) return Verdict.PENDING;
                return context.level().getBlockState(cell).getCollisionShape(context.level(), cell).isEmpty()
                        ? Verdict.PENDING : Verdict.APPLIED;
            }

            @Override
            public boolean requiresBlockAcknowledgement() {
                return true;
            }
        };
    }
}
