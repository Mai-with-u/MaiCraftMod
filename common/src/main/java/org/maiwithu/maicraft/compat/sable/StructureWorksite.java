// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.ability.machine.spi.StructureWorksites;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.approach.ProtectedCells;
import org.maiwithu.maicraft.behavior.construction.Blueprint;
import org.maiwithu.maicraft.behavior.construction.ConstructionSeams;
import org.maiwithu.maicraft.behavior.construction.ConstructionServices;
import org.maiwithu.maicraft.behavior.construction.ReadsBlocks;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TaskRecords;

/**
 * 一个 Sable 物理结构上的工地：每次问都按此刻现场读这个结构——在不在、动没动、组装器在哪、方块范围；
 * 施工要的接缝换成结构上的（读地块里的格、上船或站到船边去够、按结构的轴预测放置、对着结构上的格点和挖），
 * 备料、备手、许可、放置规则照用地上那份。结构上不垫临时方块，记账也就不记；没加载的地块不走过去加载，照实记看不到。
 */
final class StructureWorksite implements StructureWorksites.Worksite {

    /** 结构上不垫临时方块：开工不在世界记忆里记工地（地块里的格记进去没有用），也没有要收回的。 */
    private static final ConstructionSeams.Ledger NO_LEDGER = new ConstructionSeams.Ledger() {
        @Override public void siteStarted(Blueprint blueprint) {
        }

        @Override public void temporaryPlaced(BlockPos pos, String blockType, String purpose) {
        }

        @Override public void temporaryRemoved(BlockPos pos) {
        }

        @Override public List<BlockPos> temporaries() {
            return List.of();
        }
    };

    private final SableCompat compat;
    private final PlayerServices services;
    private final String structureId;
    /** 最近一次看到的样子：结构刚被拆光看不到了，方块范围、地块中心照最后看到的答。 */
    private SableStructures.Structure last;

    StructureWorksite(SableCompat compat, PlayerServices services, SableStructures.Structure seen) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
        this.last = Objects.requireNonNull(seen, "seen");
        this.structureId = seen.id();
    }

    @Override public String structureId() {
        return structureId;
    }

    @Override public boolean present() {
        return ship().isPresent();
    }

    @Override public boolean moving() {
        return ship().map(StructureGeometry::moving).orElse(false);
    }

    @Override public String name() {
        return ship().orElse(last).name();
    }

    @Override public Optional<BlockPos> assembler() {
        Optional<SableStructures.Structure> ship = ship();
        ClientLevel level = level();
        if (ship.isEmpty() || level == null) return Optional.empty();
        return compat.scan(level, ship.get()).assemblers().stream().findFirst();
    }

    @Override public BlockPos plotCenter() {
        return ship().orElse(last).plotCenter();
    }

    @Override public BlockPos min() {
        return ship().orElse(last).localMin();
    }

    @Override public BlockPos max() {
        return ship().orElse(last).localMax();
    }

    @Override public BlockPos plotCellAt(Vec3 world) {
        return BlockPos.containing(ship().orElse(last).pose().toLocal(world));
    }

    // 换上结构上的读格、靠近、放置预测、出手、挖；不记临时方块、不走过去加载、拆机器件照挖。
    @Override public ConstructionServices services(ConstructionServices onLand, Permissions permissions) {
        return new ConstructionServices(new StructureSiteReads(compat, services.context(), structureId), close(),
                new StructurePlacements(compat, services.context(), structureId, onLand.placementRules()),
                new StructureClicks(compat, services, structureId),
                new StructureDigs(compat, services, permissions, structureId), onLand.needs(), onLand.hand(),
                onLand.guards(), NO_LEDGER, null, List.of(), onLand.placementRules());
    }

    @Override public BringsPlayerClose close() {
        return new Reach();
    }

    @Override public ReadsBlocks blocks() {
        return new ReadsBlocks() {
            @Override public boolean loaded(BlockPos pos) {
                return read(pos) != null;
            }

            @Override public BlockState state(BlockPos pos) {
                BlockState state = read(pos);
                return state == null ? Blocks.AIR.defaultBlockState() : state;
            }
        };
    }

    @Override public void forgetScan() {
        compat.forget(structureId);
    }

    private BlockState read(BlockPos pos) {
        ClientLevel level = level();
        return level == null ? null : compat.block(level, structureId, pos);
    }

    private Optional<SableStructures.Structure> ship() {
        PlayerContext current = services.context().get();
        ClientLevel level = current == null ? null : current.level();
        LocalPlayer player = current == null ? null : current.localPlayer();
        Optional<SableStructures.Structure> found = StructureScene.find(compat, level, player, structureId);
        found.ifPresent(seen -> last = seen);
        return found;
    }

    private ClientLevel level() {
        PlayerContext current = services.context().get();
        return current == null ? null : current.level();
    }

    /** 靠近结构上的一格：整格目标（挖、开关门、补胶）先挑船上的站位；点某一面放方块按远近挑。 */
    private final class Reach implements BringsPlayerClose {
        @Override public Action toward(ApproachTarget target, Permissions permissions) {
            return reach(target, permissions, cell -> false);
        }

        @Override public Action toward(ApproachTarget target, Permissions permissions, ProtectedCells avoid) {
            return reach(target, permissions, avoid);
        }

        @Override public Action toward(ApproachTarget target, Permissions permissions, TaskRecords records) {
            return reach(target, permissions, cell -> false);
        }

        private Action reach(ApproachTarget target, Permissions permissions, ProtectedCells avoid) {
            return new StructureReach(compat, services, permissions, structureId, target, avoid, target.face() == null);
        }
    }
}
