// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.burning;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import org.maiwithu.maicraft.behavior.interaction.FirstPersonScene;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.interaction.SustainedUse;
import org.maiwithu.maicraft.behavior.inventory.ItemsToKeep;
import org.maiwithu.maicraft.behavior.inventory.hands.ClientMovesToMainhand;
import org.maiwithu.maicraft.behavior.navigation.FillerPlacement;
import org.maiwithu.maicraft.behavior.navigation.GroundSteering;
import org.maiwithu.maicraft.behavior.survival.burning.FireResistancePotions.CarriedPotion;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.ClientBackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 着火自救的动手实现：找岸看当刻的客户端世界；走、游是朝那一格转身按键（不经寻路，路只有几格、一眼看得到）；
 * 喝药是把那瓶换到主手再按住喝完，和吃东西同一份按住使用；垫块和出行垫临时方块同一份许可、同一份放方块的原生交互。
 */
public final class LiveBurningMoves implements BurningTask.Moves {

    private static final Direction[] SUPPORT_SIDES = {Direction.DOWN, Direction.NORTH, Direction.EAST, Direction.SOUTH,
            Direction.WEST};

    private final Interactions interactions;
    private final ClientMovesToMainhand toMainhand;
    private final ItemsToKeep keeps;
    private final Supplier<Permissions.BlockChanges> blockChanges;

    /**
     * @param blockChanges 主目标许可能动哪些方块；手上没有目标时按默认许可
     */
    public LiveBurningMoves(Interactions interactions, ClientMovesToMainhand toMainhand, ItemsToKeep keeps,
                            Supplier<Permissions.BlockChanges> blockChanges) {
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.toMainhand = Objects.requireNonNull(toMainhand, "toMainhand");
        this.keeps = Objects.requireNonNull(keeps, "keeps");
        this.blockChanges = Objects.requireNonNull(blockChanges, "blockChanges");
    }

    @Override
    public List<BlockPos> lavaExits(TickContext context) {
        Level level = level(context);
        LocalPlayer self = self(context);
        if (level == null || self == null) return List.of();
        return LavaExits.find(new LevelCells(level), self.blockPosition(), LavaExits.NEAR);
    }

    @Override
    public Optional<BlockPos> farShore(TickContext context) {
        Level level = level(context);
        LocalPlayer self = self(context);
        if (level == null || self == null) return Optional.empty();
        return LavaExits.find(new LevelCells(level), self.blockPosition(), LavaExits.FAR).stream().findFirst();
    }

    // 朝那一格中心按键：不管镜头此刻朝哪都朝着它挪，镜头同时平着转过去；指令每刻续发，不续就自己松开。
    // 报回去的是到那一格脚位的距离，上下也算：贴着岸往上蹿时水平不动，脚在升高也是在靠近，不能当成挪不过去。
    @Override
    public double steer(TickContext context, BlockPos cell, boolean jump) {
        PlayerContext player = context.player();
        LocalPlayer self = self(context);
        if (self == null || player.input() == null) return Double.MAX_VALUE;
        double dx = cell.getX() + 0.5 - self.getX();
        double dz = cell.getZ() + 0.5 - self.getZ();
        double flat = Math.hypot(dx, dz);
        long tick = player.clientTick();
        PlayerInput input = player.input();
        if (flat > 0.2) input.requestLook((float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f, self.getXRot(), tick);
        input.applySteering(yaw -> GroundSteering.keys(dx, dz, yaw, jump), self.getYRot(), tick);
        return BurningSteps.toStand(self.getX(), self.getY(), self.getZ(), cell);
    }

    @Override
    public Optional<Action> drink(TickContext context, CarriedPotion potion) {
        return self(context) == null ? Optional.empty() : Optional.of(new DrinkPotion(potion));
    }

    // 垫一块：主目标许可动方块、身上有能垫的废料、身边的岩浆格旁边或下面有能贴的实心面，三样都有才垫。
    @Override
    public BurningTask.Foothold foothold(TickContext context) {
        Level level = level(context);
        LocalPlayer self = self(context);
        if (level == null || self == null) return new BurningTask.Foothold.Unavailable("这一刻掌握不到角色");
        if (blockChanges.get() == Permissions.BlockChanges.NONE) {
            return new BurningTask.Foothold.Unavailable("主目标的许可不让动方块（change_blocks=none）");
        }
        List<String> fillers = FillerPlacement.carriedFillers(self, keeps);
        if (fillers.isEmpty()) return new BurningTask.Foothold.Unavailable("身上没有能拿来垫的方块");
        for (BlockPos spot : LavaExits.footholds(new LevelCells(level), self.blockPosition())) {
            List<BlockPos> supports = supports(level, spot);
            if (!supports.isEmpty()) {
                String filler = fillers.get(0);
                return new BurningTask.Foothold.Placing(
                        new FillerPlacement(interactions, toMainhand, spot, filler, supports), filler, spot);
            }
        }
        return new BurningTask.Foothold.Unavailable("身边的岩浆格旁边、下面都没有能贴着放的实心面");
    }

    // 对着哪一面垫：岩浆格下面那格的顶面先，再是四周邻格朝它的那一面；顶得住才算。
    private static List<BlockPos> supports(Level level, BlockPos spot) {
        List<BlockPos> supports = new ArrayList<>();
        for (Direction side : SUPPORT_SIDES) {
            BlockPos support = spot.relative(side);
            if (level.isLoaded(support) && level.getBlockState(support).isFaceSturdy(level, support, side.getOpposite())) {
                supports.add(support);
            }
        }
        return supports;
    }

    private static LocalPlayer self(TickContext context) {
        return context.player() == null ? null : context.player().localPlayer();
    }

    private static Level level(TickContext context) {
        return context.player() == null ? null : context.player().level();
    }

    /** 喝一瓶药：先把那一格换到主手，再按住使用直到主手那瓶变了（喝完变成空瓶）；换完手主手不是它就如实失败。 */
    private final class DrinkPotion implements Action {

        private final CarriedPotion potion;
        private Action moving;
        private boolean moved;
        private SustainedUse drinking;

        DrinkPotion(CarriedPotion potion) {
            this.potion = potion;
        }

        @Override
        public ActionStatus tick(TickContext context) {
            if (moving != null) {
                ActionStatus status = moving.tick(context);
                if (!(status instanceof ActionStatus.Done)) return status;
                moving.close();
                moving = null;
            }
            if (drinking != null) return drinking.tick(context);
            LocalPlayer self = self(context);
            if (self == null) {
                return ActionStatus.failed(Problem.of(Problem.Kind.WRONG_TIME, "这一刻掌握不到角色，喝不了药"));
            }
            // 那瓶不在手上：换一次手；已经是选中的那一格就不用换。
            if (!moved && self.getInventory().selected != potion.slot()) {
                moved = true;
                Optional<Action> move = toMainhand.actionToMainhandFrom(potion.slot(), "把 " + potion.itemId() + " 换到主手");
                if (move.isEmpty()) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, "背包第 " + potion.slot()
                            + " 格的 " + potion.itemId() + " 换不到主手"));
                }
                moving = move.get();
                return ActionStatus.running();
            }
            ItemStack held = self.getMainHandItem();
            if (!protects(held)) {
                return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "换完手主手不是那瓶防火药水，没喝"));
            }
            drinking = new SustainedUse(InteractionHand.MAIN_HAND,
                    InteractionConfirmation.heldItemChanged(InteractionHand.MAIN_HAND, held),
                    interactions.heldProjection(), 0, FirstPersonScene::of);
            return ActionStatus.progressed();
        }

        // 主手这件是不是喝了能防火的药：物品对得上、内容里有防火。
        private boolean protects(ItemStack held) {
            return !held.isEmpty()
                    && potion.itemId().equals(BuiltInRegistries.ITEM.getKey(held.getItem()).toString())
                    && ClientBackpackView.potionEffectsOf(held).contains(FireResistancePotions.FIRE_RESISTANCE);
        }

        @Override
        public void pause() {
            if (moving != null) moving.pause();
            if (drinking != null) drinking.pause();
        }

        @Override
        public void close() {
            if (moving != null) moving.close();
            if (drinking != null) drinking.close();
        }

        @Override
        public String describe() {
            return "喝下 " + potion.itemId();
        }
    }
}
