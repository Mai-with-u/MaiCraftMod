/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 only.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.precompute;

import baritone.pathing.movement.MovementHelper;
import baritone.utils.BlockStateInterface;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.behavior.navigation.util.BlockPassability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 一次算路里按方块状态缓存的能不能站、能不能穿过；第一次把模组方块认作矮平顶时在日志里记一行。 */
public class PrecomputedData {

    private static final Logger LOG = LoggerFactory.getLogger(PrecomputedData.class);

    private final byte[] data = new byte[Block.BLOCK_STATE_REGISTRY.size()];
    /** 已经在日志里记过的矮平顶模组方块，跟着这名角色的寻路走，整局只记一次。 */
    private final Set<Block> loggedHalfHigh;

    public PrecomputedData(Set<Block> loggedHalfHigh) {
        this.loggedHalfHigh = loggedHalfHigh;
    }

    /**
     * byte layout
     *
     *          7              6             5              4              3              2              1             0
     *          |              |             |              |              |              |              |             |
     *      unused         canWalkOn       maybe       canWalkThrough    maybe        fullyPassable    maybe       completed
     */

    private static final byte COMPLETED_MASK = (byte) 1 << 0;
    private static final byte FULLY_PASSABLE_MAYBE_MASK = (byte) 1 << 1;
    private static final byte FULLY_PASSABLE_MASK = (byte) 1 << 2;
    private static final byte CAN_WALK_THROUGH_MAYBE_MASK = (byte) 1 << 3;
    private static final byte CAN_WALK_THROUGH_MASK = (byte) 1 << 4;
    private static final byte CAN_WALK_ON_MAYBE_MASK = (byte) 1 << 5;
    private static final byte CAN_WALK_ON_MASK = (byte) 1 << 6;

    private int fillData(int id, BlockState state) {
        byte blockData = 0;

        Ternary canWalkOnState = MovementHelper.canWalkOnBlockState(state);
        switch (canWalkOnState) {
            case YES -> {
                blockData |= CAN_WALK_ON_MASK;
                noteModHalfHigh(state);
            }
            case MAYBE -> blockData |= CAN_WALK_ON_MAYBE_MASK;
        }

        Ternary canWalkThroughState = MovementHelper.canWalkThroughBlockState(state);
        switch (canWalkThroughState) {
            case YES -> blockData |= CAN_WALK_THROUGH_MASK;
            case MAYBE -> blockData |= CAN_WALK_THROUGH_MAYBE_MASK;
        }

        Ternary fullyPassableState = MovementHelper.fullyPassableBlockState(state);
        switch (fullyPassableState) {
            case YES -> blockData |= FULLY_PASSABLE_MASK;
            case MAYBE -> blockData |= FULLY_PASSABLE_MAYBE_MASK;
        }

        blockData |= COMPLETED_MASK;

        data[id] = blockData; // in theory, this is thread "safe" because every thread should compute the exact same int to write?
        return blockData;
    }

    // 模组方块第一次被认作矮平顶能站时，在日志里记一行方块名：模组里这种形状的方块离线扫不出来，
    // 事后翻日志就知道实例里认进来了哪些，站上去有问题再按属性加排除。
    private void noteModHalfHigh(BlockState state) {
        if (state.getBlock() instanceof SlabBlock) return;
        double top = BlockPassability.halfHighTop(state);
        if (Double.isNaN(top)) return;
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if ("minecraft".equals(id.getNamespace()) || !loggedHalfHigh.add(state.getBlock())) return;
        LOG.info("寻路把模组方块认作能站的矮平顶：{}（顶高 {}/16）", id, Math.round(top * 16));
    }

    public boolean canWalkOn(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        int blockData = data[id];

        if ((blockData & COMPLETED_MASK) == 0) { // we need to fill in the data
            blockData = fillData(id, state);
        }

        if ((blockData & CAN_WALK_ON_MAYBE_MASK) != 0) {
            return MovementHelper.canWalkOnPosition(bsi, x, y, z, state);
        } else {
            return (blockData & CAN_WALK_ON_MASK) != 0;
        }
    }

    public boolean canWalkThrough(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        int blockData = data[id];

        if ((blockData & COMPLETED_MASK) == 0) { // we need to fill in the data
            blockData = fillData(id, state);
        }

        if ((blockData & CAN_WALK_THROUGH_MAYBE_MASK) != 0) {
            return MovementHelper.canWalkThroughPosition(bsi, x, y, z, state);
        } else {
            return (blockData & CAN_WALK_THROUGH_MASK) != 0;
        }
    }

    public boolean fullyPassable(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        int blockData = data[id];

        if ((blockData & COMPLETED_MASK) == 0) { // we need to fill in the data
            blockData = fillData(id, state);
        }

        if ((blockData & FULLY_PASSABLE_MAYBE_MASK) != 0) {
            return MovementHelper.fullyPassablePosition(bsi, x, y, z, state);
        } else {
            return (blockData & FULLY_PASSABLE_MASK) != 0;
        }
    }
}
