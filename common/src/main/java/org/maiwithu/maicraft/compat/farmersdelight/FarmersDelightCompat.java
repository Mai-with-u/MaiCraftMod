// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.farmersdelight;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.CompatRegistry;

/**
 * 农夫乐事（Farmer's Delight）的联动入口。交给登记表：厨锅的机器类型（投料取餐点开界面、
 * 读供热与做好的餐）与厨锅界面的布局证明。每一下读写都经联动入口包一层：
 * 装的版本和编译时用的对不上时停用联动，不让游戏崩。
 */
public final class FarmersDelightCompat extends CompatModule {

    public static final String MOD_ID = "farmersdelight";

    private final FarmersDelightReads reads;

    public FarmersDelightCompat(FarmersDelightReads reads) {
        super(MOD_ID, "农夫乐事");
        this.reads = Objects.requireNonNull(reads, "reads");
    }

    @Override public void contribute(CompatRegistry registry) {
        // 厨锅界面的布局：角色背包 36 格，容器侧九格（六格食材、餐品显示、碗输入、成品输出）全按机器格核对。
        registry.menuLayout(this, new FarmersDelightCookingPotLayout());
        // 厨锅的机器类型：machine_run 对它备料、投料、等餐、收餐。
        for (FarmersDelightMachineCatalog.Kind kind : FarmersDelightMachineCatalog.kinds()) {
            registry.machineType(this, services -> new FarmersDelightMachineType(kind, this, reads, services));
        }
    }

    /** 这一格厨锅里做好的餐；见 {@link FarmersDelightReads#meal}。 */
    public Optional<List<MachineState.Shown>> potMeal(ClientLevel level, BlockPos at) {
        return call("读厨锅里的餐", () -> reads.meal(level, at));
    }

    /** 这一格厨锅下方有没有热源；见 {@link FarmersDelightReads#heated}。 */
    public Optional<Boolean> potHeated(ClientLevel level, BlockPos at) {
        return call("读厨锅的供热", () -> reads.heated(level, at));
    }

    /** 注册 ID 换成方块对象；注册表里没有的不算进来。 */
    Set<Block> blocksOf(List<String> ids) {
        Set<Block> blocks = new HashSet<>();
        for (String id : ids) {
            ResourceLocation key = ResourceLocation.tryParse(id);
            if (key != null) BuiltInRegistries.BLOCK.getOptional(key).ifPresent(blocks::add);
        }
        return Set.copyOf(blocks);
    }
}
