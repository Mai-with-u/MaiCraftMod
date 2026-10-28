// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.ability.machine.MachineAbilities;
import org.maiwithu.maicraft.ability.vehicle.VehicleArchives;
import org.maiwithu.maicraft.ability.vehicle.VehicleAssembleModule;
import org.maiwithu.maicraft.ability.vehicle.VehicleDriveModule;
import org.maiwithu.maicraft.ability.vehicle.VehicleTravelMode;
import org.maiwithu.maicraft.ability.vehicle.api.VehicleControls;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleDriver;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.behavior.travel.modes.BoatCrossing;
import org.maiwithu.maicraft.behavior.travel.modes.ClientRailScout;
import org.maiwithu.maicraft.behavior.travel.modes.DimensionTravelServices;
import org.maiwithu.maicraft.behavior.travel.modes.MinecartMode;
import org.maiwithu.maicraft.behavior.travel.modes.PearlCrossing;
import org.maiwithu.maicraft.behavior.travel.planning.SceneSeenTargets;
import org.maiwithu.maicraft.behavior.travel.spi.TravelMode;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;

/**
 * 载具相关能力在启动时怎么创建、登记：组装载具与驾驶载具两项能力、出行能力可选的出行方式
 * （联动登记的、开自己的载具、末地折跃门、原版船与矿车），以及机器能力与驾驶共用的操纵表。
 * 从登记表拆出来单独一类，登记表好按能力读。
 */
final class VehicleWiring {

    private VehicleWiring() {}

    /** 组装载具与驾驶载具：联动登记的组装、驾驶、读载具办法，观察编号 s# 从场景的编号表换成结构身份。 */
    static void registerVehicles(AbilityRegistry registry, AbilityCatalog.Deps deps, PlayerServices services) {
        Supplier<BlockPos> feet = () -> deps.character().context().get() == null
                || deps.character().context().get().localPlayer() == null
                ? BlockPos.ZERO : deps.character().context().get().localPlayer().blockPosition();
        Supplier<String> dimension = () -> deps.character().context().get() == null
                || deps.character().context().get().level() == null
                ? "" : deps.character().context().get().level().dimension().location().toString();
        // 组装载具：物理组装器拉一次杆组装或拆回。
        // 档案跟着走：拆回记到世界里组装器那一格（按维度分），再组装跟到新结构，新结构的 s# 从同一张编号表反查。
        // 组装器落在某份机器档案里时拉杆前先补胶：档案从机器能力的同一个库里读，还缺哪几框、谁来粘问联动的机器类型。
        registry.register(new VehicleAssembleModule(deps.compat().vehicleAssemblers(services),
                new SceneSeenTargets(deps.world()::scene), id -> deps.world().scene().seen().structureOf(id),
                deps.world().memory(), feet,
                new VehicleArchives(vehicleControls(deps), deps.stores().worldDocuments(), deps.stores().worldKey(),
                        deps.compat().vehicleReaders(services), dimension,
                        identity -> deps.world().scene().seen().idOfStructure(identity)),
                MachineAbilities.archivedMachines(deps.stores().worldDocuments(), deps.stores().worldKey()),
                deps.compat().machineTypes(services), dimension));
        // 驾驶载具：坐上驾驶座接管控制件试按或开到某处；不在车上先经出行上车，
        // 去记过的地点按世界记忆找，坐标没给 y、按方向开多远从角色脚下算。
        registry.register(new VehicleDriveModule(deps.compat().vehicleDrivers(services),
                deps.compat().vehicleReaders(services), vehicleControls(deps),
                new SceneSeenTargets(deps.world()::scene), id -> deps.world().scene().seen().structureOf(id),
                deps.world().memory(), feet, deps.compat().flightStabilizers(services)));
    }

    /**
     * 出行的方式：联动登记的（电梯、喷气背包、火车、上下船），装了能开载具的联动时再加开自己的载具（via=vehicle）；
     * 原版自带的末地折跃门（via=end_gateway）总在，只在末地用得上；原版船（via=boat）只在主世界用得上，
     * 都用跨维度那一份现场部件读格、走到、换主手；原版矿车（via=minecart）总在，沿铺好的铁轨坐一段，
     * 铁轨扫描、读格、走到、放车也靠这份部件。
     */
    static List<TravelMode> travelModes(AbilityCatalog.Deps deps, PlayerServices services, DimensionTravelServices portals) {
        List<TravelMode> modes = new ArrayList<>(deps.compat().travelModes(services));
        List<VehicleDriver> drivers = deps.compat().vehicleDrivers(services);
        if (!drivers.isEmpty()) {
            modes.add(new VehicleTravelMode(drivers, deps.compat().vehicleReaders(services), vehicleControls(deps),
                    id -> deps.world().scene().seen().structureOf(id), deps.compat().flightStabilizers(services)));
        }
        modes.add(new PearlCrossing(portals));
        // 原版船（via=boat）也是原版自带：隔水时放船划过去，用跨维度那一份现场部件读格、走到、换主手。
        modes.add(new BoatCrossing(portals));
        // 原版矿车（via=minecart）也是原版自带：沿铺好的铁轨坐一段，四种铁轨的扫描挂在方块扫描服务上。
        modes.add(new MinecartMode(new ClientRailScout(deps.character().context(), deps.world().blockScans()),
                portals));
        return modes;
    }

    /**
     * 操纵表：按结构身份存在这个世界的文档库里。它不留状态，机器能力（看、改）与驾驶能力各拿一份读写同一处。
     */
    static VehicleControls vehicleControls(AbilityCatalog.Deps deps) {
        return new VehicleControls(deps.stores().worldDocuments(), deps.stores().worldKey());
    }
}
