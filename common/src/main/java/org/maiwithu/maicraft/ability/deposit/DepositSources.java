// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.deposit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.inventory.containers.ContainerChooser;
import org.maiwithu.maicraft.behavior.inventory.containers.StoresInContainer;
import org.maiwithu.maicraft.behavior.inventory.spi.NetworkStorage;
import org.maiwithu.maicraft.behavior.perception.FacilityKinds;
import org.maiwithu.maicraft.behavior.travel.planning.LandmarkResolver;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 存东西的目标与来源落点解析、前置防呆核对与来源容器排队：
 * 统一解析 {@code target} 与 {@code source}，拦截区域倒腾同片箱子与同格自搬，
 * 并在按区域整理时只筛出箱子、木桶、潜影盒等储物容器按由近到远排队搬空，跳过熔炉与酿造台等加工台子。
 */
final class DepositSources {

    /** 不当普通储物箱整理的红石传输或陈列方块：按区域搬空箱子时不碰它们。 */
    private static final Set<String> NON_STORAGE_BLOCKS = Set.of(
            "minecraft:hopper", "minecraft:dropper", "minecraft:dispenser",
            "minecraft:chiseled_bookshelf", "minecraft:lectern", "minecraft:jukebox", "minecraft:decorated_pot");

    private DepositSources() {}

    /** 位置参数落在哪：一片区域、点名的一格、点名的一只储物生物，或落不了（带着原因）。 */
    sealed interface Place {
        record Area(BlockPos center) implements Place {}
        record Cell(WorldPosition at) implements Place {}
        record Creature(DepositSeams.CreatureStorage.Creature creature) implements Place {}
        record Missing(String why) implements Place {}
    }

    /** 一只排进来源队列的容器及其对应的现场打开部件（方块容器与储物生物各走各的部件）。 */
    record SourceEntry(ContainerChooser.Entry entry, StoresInContainer.Parts parts) {}

    /** 来源容器挑选结果：还在扫时返回空。 */
    sealed interface SourcePick {
        record Chosen(List<SourceEntry> ordered) implements SourcePick {
            public Chosen {
                ordered = List.copyOf(ordered);
            }
        }
        record Failed(Problem problem) implements SourcePick {}
    }

    /** 把一个目标对象（target 或 source；null 视为角色脚下）落成位置描述。 */
    static Place resolve(Target target, DepositServices services) {
        return switch (target) {
            case null -> new Place.Area(services.places().feet());
            case Target.Here here -> new Place.Area(services.places().feet());
            case Target.Seen seen when seen.id().startsWith("e") -> creaturePlace(seen.id(), services);
            case Target.Seen seen -> services.places().seen(seen.id())
                    .<Place>map(at -> seen.id().startsWith("f") ? new Place.Area(cellOf(at)) : new Place.Cell(at))
                    .orElseGet(() -> new Place.Missing("观察编号 " + seen.id() + " 对应的东西已经不在了"));
            case Target.Position position -> position.y() == null
                    ? new Place.Missing("点名容器要给全坐标，包括 y")
                    : new Place.Cell(new WorldPosition(position.x(), position.y(), position.z(), position.dimension()));
            case Target.Landmark landmark -> landmarkPlace(landmark.name(), services);
            default -> new Place.Missing("存东西不接受这种目标：" + target.kind());
        };
    }

    /**
     * 点名的那一格没有普通存储容器、却在同一格装着可用的 ME 终端或面板部件时，才按存进它连着的网络处理；
     * 若目标格是压印器、熔炉等非容器方块，严禁跨格静默劫持到紧挨着的隔壁 ME 终端。
     */
    static boolean terminalInsteadOfContainer(Place place, DepositServices services, Permissions permissions) {
        if (!(place instanceof Place.Cell cell) || services.spots().at(cell.at()).isPresent()) {
            return false;
        }
        return services.network().get()
                .map(storage -> storage.reachable(exactCellSearch(cell.at(), permissions)))
                .orElse(false);
    }

    /** 未写 {@code into="network"} 却点名具体坐标时，只认目标坐标本格上的终端部件（半径 0），不跨格搜隔壁。 */
    static NetworkStorage.TerminalSearch exactCellSearch(WorldPosition at, Permissions permissions) {
        return NetworkStorage.TerminalSearch.around(cellOf(at), 0, permissions);
    }

    /** 显式写了 {@code into="network"} 并给位置时，认那一格及紧贴一圈的终端。 */
    static NetworkStorage.TerminalSearch cellSearch(WorldPosition at, Permissions permissions) {
        return NetworkStorage.TerminalSearch.around(cellOf(at), 1, permissions);
    }

    /** 点名的坐标既不是存储容器也不是终端部件时，点名说清目标方块不是存储容器并提示存网络改用 {@code into="network"}。 */
    static Problem nonContainerTargetProblem(WorldPosition at, DepositServices services) {
        String coords = "（" + at.x() + ", " + at.y() + ", " + at.z() + "）";
        Optional<String> blockId = services.places() == null ? Optional.empty() : services.places().blockIdAt(at);
        String message = blockId
                .map(id -> coords + "处的 " + id + " 不是存储容器（那里没有容器）；要存进 ME 网络请改用 into=\"network\"")
                .orElseGet(() -> coords + "那里没有容器（目标方块不是存储容器；要存进 ME 网络请改用 into=\"network\"）");
        return Problem.of(Problem.Kind.NOT_FOUND, message,
                "改指箱子、木桶等存储容器，或加上 into=\"network\" 存进 ME 网络");
    }

    /**
     * 给了 {@code source} 时的开工前置防呆校验：
     * 拦截找不到的位置、点名非容器目标方块、区域来源配区域自动挑箱子（会在同一片箱子之间来回倒腾），以及同一个容器既当来源又当存放处。
     */
    static Optional<Problem> validateUpfront(Place sourcePlace, Place targetPlace, boolean intoNetwork,
            DepositServices services, Permissions permissions) {
        if (sourcePlace instanceof Place.Missing missing) {
            return Optional.of(Problem.of(Problem.Kind.NOT_FOUND, missing.why(), null));
        }
        boolean goesToNetwork = intoNetwork || terminalInsteadOfContainer(targetPlace, services, permissions);
        if (targetPlace instanceof Place.Missing missing) {
            return Optional.of(Problem.of(Problem.Kind.NOT_FOUND, missing.why(), null));
        }
        if (!goesToNetwork && targetPlace instanceof Place.Cell targetCell
                && services.spots().at(targetCell.at()).isEmpty()) {
            return Optional.of(nonContainerTargetProblem(targetCell.at(), services));
        }
        if (sourcePlace instanceof Place.Area && !goesToNetwork
                && !(targetPlace instanceof Place.Cell || targetPlace instanceof Place.Creature)) {
            return Optional.of(Problem.of(Problem.Kind.INVALID_PARAMETER,
                    "从一片区域的箱子往外整理时，请配合 into=network 存进 ME 网络，或用 target 点名具体的目标容器，免得在同一片箱子之间来回倒腾",
                    "加上 into=\"network\" 存进 ME 网络，或用 target 点名目标容器（如 b#、[x,y,z]）"));
        }
        if (!goesToNetwork && sameContainer(sourcePlace, targetPlace)) {
            return Optional.of(Problem.of(Problem.Kind.INVALID_PARAMETER,
                    "不能把同一个容器既当来源又当存放处",
                    "换一只目标容器，或改用 into=\"network\" 存进 ME 网络"));
        }
        return Optional.empty();
    }

    /**
     * 挑选来源容器队列：单格或储物生物只取那一只（点名即允许动别人放的箱子）；
     * 区域则扫描周围储物容器（未扫完给空），跳过熔炉/酿造台等加工台子与目标容器自身，按由近到远排队。
     */
    static Optional<SourcePick> pickSources(Place sourcePlace, Place targetPlace, boolean goesToNetwork,
            int radius, boolean mayDigLid, DepositServices services) {
        return switch (sourcePlace) {
            case Place.Creature(var creature) -> Optional.of(new SourcePick.Chosen(List.of(
                    new SourceEntry(creatureEntry(creature), services.creatures().parts(creature.entityId())))));
            case Place.Cell(var at) -> Optional.of(pickSingleCell(at, mayDigLid, services));
            case Place.Area(var center) -> pickAreaSources(center, targetPlace, goesToNetwork, radius, mayDigLid, services);
            case Place.Missing(var why) -> Optional.of(new SourcePick.Failed(
                    Problem.of(Problem.Kind.NOT_FOUND, why, null)));
        };
    }

    static ContainerChooser.Entry creatureEntry(DepositSeams.CreatureStorage.Creature creature) {
        String name = (creature.name() == null ? creature.typeId() : creature.name() + "（" + creature.typeId() + "）")
                + "身上";
        return new ContainerChooser.Entry(new ContainerChooser.Candidate(
                name, creature.typeId(), creature.at(), null, true, 0, false, true, ContainerChooser.Lid.CLEAR, false),
                false);
    }

    static BlockPos cellOf(WorldPosition at) {
        return new BlockPos(at.x(), at.y(), at.z());
    }

    private static SourcePick pickSingleCell(WorldPosition at, boolean mayDigLid, DepositServices services) {
        Optional<ContainerChooser.Candidate> found = services.spots().at(at);
        if (found.isEmpty()) {
            return new SourcePick.Failed(Problem.of(Problem.Kind.NOT_FOUND,
                    "（" + at.x() + ", " + at.y() + ", " + at.z() + "）那里没有来源容器", null));
        }
        ContainerChooser.Candidate raw = found.get();
        ContainerChooser.Candidate named = new ContainerChooser.Candidate(raw.name(), raw.blockTypeId(), raw.at(),
                raw.knownContents(), raw.hasFreeSpace(), raw.walkCost(), false, raw.layoutKnown(), raw.lid(),
                raw.catSitting());
        return toSourcePick(ContainerChooser.choose(List.of(named), null, mayDigLid), services.storing(), false);
    }

    private static Optional<SourcePick> pickAreaSources(BlockPos center, Place targetPlace, boolean goesToNetwork,
            int radius, boolean mayDigLid, DepositServices services) {
        if (center == null) {
            return Optional.of(new SourcePick.Failed(
                    Problem.of(Problem.Kind.NOT_FOUND, "这一刻角色不在世界里，找不了来源容器", null)));
        }
        List<ContainerChooser.Candidate> around = services.spots().around(center, radius);
        if (around.isEmpty() && !services.spots().scanComplete()) {
            return Optional.empty();
        }
        WorldPosition excludedCell = !goesToNetwork && targetPlace instanceof Place.Cell cell ? cell.at() : null;
        List<ContainerChooser.Candidate> storageOnly = new ArrayList<>();
        for (ContainerChooser.Candidate candidate : around) {
            if (!isStorageContainerBlock(candidate.blockTypeId())) {
                continue;
            }
            if (excludedCell != null && sameCell(candidate.at(), excludedCell)) {
                continue;
            }
            storageOnly.add(candidate);
        }
        if (storageOnly.isEmpty()) {
            return Optional.of(new SourcePick.Failed(
                    Problem.of(Problem.Kind.NOT_FOUND, "附近没有可整理的来源箱子", null)));
        }
        return Optional.of(toSourcePick(
                ContainerChooser.choose(storageOnly, null, mayDigLid), services.storing(), true));
    }

    private static SourcePick toSourcePick(ContainerChooser.Pick pick, StoresInContainer.Parts parts,
            boolean sortByDistanceOnly) {
        return switch (pick) {
            case ContainerChooser.Pick.Chosen chosen -> {
                List<ContainerChooser.Entry> ordered = new ArrayList<>(chosen.ordered());
                if (sortByDistanceOnly) {
                    ordered.sort(Comparator.comparingDouble((ContainerChooser.Entry entry) -> entry.candidate().walkCost())
                            .thenComparing(entry -> entry.candidate().at().toString()));
                }
                yield new SourcePick.Chosen(ordered.stream().map(entry -> new SourceEntry(entry, parts)).toList());
            }
            case ContainerChooser.Pick.NeedApproval approval -> {
                List<String> reasons = new ArrayList<>(approval.others());
                reasons.addAll(approval.blockedLids());
                yield new SourcePick.Failed(Problem.of(Problem.Kind.NEED_APPROVAL,
                        "只挑得到这些来源容器：" + String.join("；", reasons), "点名其中的容器，或允许动它们"));
            }
            case ContainerChooser.Pick.NotFound notFound ->
                    new SourcePick.Failed(Problem.of(Problem.Kind.NOT_FOUND, notFound.searched(), null));
        };
    }

    private static boolean isStorageContainerBlock(String blockTypeId) {
        if (blockTypeId == null) {
            return false;
        }
        return !FacilityKinds.isWorkstationId(blockTypeId) && !NON_STORAGE_BLOCKS.contains(blockTypeId);
    }

    private static boolean sameContainer(Place first, Place second) {
        if (first instanceof Place.Cell a && second instanceof Place.Cell b) {
            return sameCell(a.at(), b.at());
        }
        if (first instanceof Place.Creature a && second instanceof Place.Creature b) {
            return a.creature().entityId() == b.creature().entityId();
        }
        return false;
    }

    static boolean sameCell(WorldPosition a, WorldPosition b) {
        if (a.x() != b.x() || a.y() != b.y() || a.z() != b.z()) {
            return false;
        }
        return a.dimension() == null || b.dimension() == null || Objects.equals(a.dimension(), b.dimension());
    }

    private static Place creaturePlace(String seenId, DepositServices services) {
        if (services.creatures() == null) {
            return new Place.Missing("存东西还认不了生物身上的储物（" + seenId + "）");
        }
        return services.creatures().creature(seenId).<Place>map(Place.Creature::new)
                .orElseGet(() -> new Place.Missing("观察编号 " + seenId + " 对应的生物已经不在了"));
    }

    private static Place landmarkPlace(String name, DepositServices services) {
        var located = LandmarkResolver.resolve(name, services.places()::landmark, services.places()::signs);
        if (located instanceof LandmarkResolver.Resolution.Found found) {
            return new Place.Area(cellOf(found.position()));
        }
        return new Place.Missing(((LandmarkResolver.Resolution.Missing) located).sentence());
    }
}
