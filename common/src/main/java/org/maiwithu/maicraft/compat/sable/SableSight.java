// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.Objects;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.perception.spi.ModSight;
import org.maiwithu.maicraft.behavior.perception.spi.ModThing;
import org.maiwithu.maicraft.game.world.ObservationVisibility;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 物理结构在观察里的样子：领 s#，场景里列名字、动没动、每秒挪几格、几格方块、几个座位、有没有控制件
 * （有座位又有无线红石打字机、舵轮或油门拉杆的算载具，只有方块的就是结构）、角色是不是在上面；
 * 详情给大小、座位所在、控制件有哪些。没读全的写明。
 */
final class SableSight implements ModSight {

    private final SableCompat compat;

    SableSight(SableCompat compat) {
        this.compat = Objects.requireNonNull(compat, "compat");
    }

    @Override public void sight(ClientLevel level, Player viewer, AABB region, Consumer<ModThing> sink) {
        LocalPlayer local = viewer instanceof LocalPlayer client ? client : null;
        for (SableStructures.Structure structure : compat.structures(level, viewer, region)) {
            // 物理结构未载着角色且整个世界包围盒都被墙挡住时，不再隔墙透视报进场景。
            if (local != null && !structure.carriesPlayer() && !structureVisible(local, structure.worldBounds())) {
                continue;
            }
            StructureGeometry.Scan scan = compat.scan(level, structure);
            JsonObject summary = new JsonObject();
            if (!structure.name().isBlank()) summary.addProperty("name", structure.name());
            summary.addProperty("moving", StructureGeometry.moving(structure));
            summary.addProperty("speed", Math.round(StructureGeometry.velocity(structure).length() * 20 * 10) / 10.0);
            summary.addProperty("blocks", scan.blocks());
            summary.addProperty("seats", scan.seats().size());
            summary.addProperty("vehicle", !scan.seats().isEmpty() && !scan.controls().isEmpty());
            summary.addProperty("riding", structure.carriesPlayer());
            if (!scan.complete()) summary.addProperty("read_complete", false);
            JsonObject detail = new JsonObject();
            BlockPos size = structure.localMax().subtract(structure.localMin()).offset(1, 1, 1);
            detail.addProperty("size", size.getX() + "×" + size.getY() + "×" + size.getZ());
            JsonArray seats = new JsonArray();
            for (BlockPos seat : scan.seats()) {
                Vec3 at = structure.pose().toWorld(Vec3.atCenterOf(seat));
                JsonObject entry = new JsonObject();
                entry.addProperty("x", Math.round(at.x * 10) / 10.0);
                entry.addProperty("y", Math.round(at.y * 10) / 10.0);
                entry.addProperty("z", Math.round(at.z * 10) / 10.0);
                seats.add(entry);
            }
            detail.add("seat_positions", seats);
            JsonArray controls = new JsonArray();
            scan.controls().stream().map(StructureGeometry.Part::id).distinct().forEach(controls::add);
            detail.add("controls", controls);
            Vec3 center = structure.worldBounds().getCenter();
            WorldPosition at = new WorldPosition((int) Math.floor(center.x), (int) Math.floor(center.y),
                    (int) Math.floor(center.z), level.dimension().location().toString());
            sink.accept(new ModThing("structure", ModThing.Identity.STRUCTURE, structure.id(), at, summary, detail));
        }
    }

    // 物理结构世界可见判定：包围盒中心或任一内缩角点在 360° 视线内无遮挡即算看得见。
    private static boolean structureVisible(LocalPlayer local, AABB bounds) {
        if (bounds == null) return false;
        if (ObservationVisibility.point(local, bounds.getCenter())) return true;
        AABB inset = bounds.deflate(Math.min(0.05, bounds.getXsize() / 4),
                Math.min(0.05, bounds.getYsize() / 4), Math.min(0.05, bounds.getZsize() / 4));
        for (double x : new double[]{inset.minX, inset.maxX}) {
            for (double y : new double[]{inset.minY, inset.maxY}) {
                for (double z : new double[]{inset.minZ, inset.maxZ}) {
                    if (ObservationVisibility.point(local, new Vec3(x, y, z))) return true;
                }
            }
        }
        return false;
    }
}
