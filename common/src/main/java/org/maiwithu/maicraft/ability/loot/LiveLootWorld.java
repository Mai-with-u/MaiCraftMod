// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.loot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;

import org.maiwithu.maicraft.behavior.acquire.mining.CollectsBlocks;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.interaction.HoldsSneak;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.ItemsToKeep;
import org.maiwithu.maicraft.behavior.inventory.containers.ContainerChooser;
import org.maiwithu.maicraft.behavior.inventory.containers.LootContainerChooser;
import org.maiwithu.maicraft.behavior.inventory.containers.LootsContainer;
import org.maiwithu.maicraft.behavior.inventory.containers.RareLootNotice;
import org.maiwithu.maicraft.behavior.inventory.containers.SpotsContainers;
import org.maiwithu.maicraft.behavior.inventory.hands.MovesToMainhand;
import org.maiwithu.maicraft.behavior.menu.ClientMenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.perception.FacilityKinds;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.perception.SceneSign;
import org.maiwithu.maicraft.behavior.permission.ContainerWhose;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 搜刮现场读端的生产实现：复用全仓找容器读端、归属与保护判断、打开容器界面动作及稀罕战利品通报器。
 */
final class LiveLootWorld implements LootSeams.World {

    private static final TagKey<EntityType<?>> LOOTR_ENTITIES =
            TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath("lootr", "containers"));

    private final Supplier<PlayerContext> contexts;
    private final Supplier<Scene> scene;
    private final WorldMemory memory;
    private final SpotsContainers spots;
    private final Protection protection;
    private final FacilityKinds facilities;
    private final CollectsBlocks collects;
    private final MenuLayouts layouts;
    private final BringsPlayerClose close;
    private final Interactions interactions;
    private final MovesToMainhand toMainhand;
    private final HoldsSneak sneaks;
    private final ItemsToKeep kept;
    private final RareLootNotice rareLootNotice;

    LiveLootWorld(Supplier<PlayerContext> contexts, Supplier<Scene> scene, WorldMemory memory,
            SpotsContainers spots, Protection protection, FacilityKinds facilities, CollectsBlocks collects,
            MenuLayouts layouts, BringsPlayerClose close, Interactions interactions, MovesToMainhand toMainhand,
            HoldsSneak sneaks, ItemsToKeep kept, RareLootNotice rareLootNotice) {
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.scene = Objects.requireNonNull(scene, "scene");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.spots = Objects.requireNonNull(spots, "spots");
        this.protection = Objects.requireNonNull(protection, "protection");
        this.facilities = Objects.requireNonNull(facilities, "facilities");
        this.collects = Objects.requireNonNull(collects, "collects");
        this.layouts = Objects.requireNonNull(layouts, "layouts");
        this.close = Objects.requireNonNull(close, "close");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.toMainhand = Objects.requireNonNull(toMainhand, "toMainhand");
        this.sneaks = Objects.requireNonNull(sneaks, "sneaks");
        this.kept = Objects.requireNonNull(kept, "kept");
        this.rareLootNotice = Objects.requireNonNull(rareLootNotice, "rareLootNotice");
    }

    @Override
    public BlockPos feet() {
        PlayerContext current = contexts.get();
        return current == null || current.localPlayer() == null ? null : current.localPlayer().blockPosition();
    }

    @Override
    public Optional<WorldPosition> seen(String seenId) {
        Scene current = scene.get();
        return current == null ? Optional.empty() : current.lookup(seenId).map(entry -> entry.position());
    }

    @Override
    public Optional<LootSeams.EntityContainer> entityContainer(String seenId) {
        Scene current = scene.get();
        PlayerContext player = contexts.get();
        ClientLevel level = player == null ? null : player.level();
        if (current == null || level == null) return Optional.empty();
        Optional<Entity> found = current.seen().entityIn(level, seenId);
        if (found.isEmpty()) return Optional.empty();
        Entity entity = found.get();
        String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        WorldPosition at = new WorldPosition(entity.getBlockX(), entity.getBlockY(), entity.getBlockZ(),
                level.dimension().location().toString());
        boolean natural = entity.getType().is(LOOTR_ENTITIES);
        boolean minecart = entity instanceof AbstractMinecartContainer;
        ContainerWhose origin = new ContainerWhose(natural ? ContainerWhose.Kind.NATURAL_LOOT
                : minecart ? ContainerWhose.Kind.NO_RECORD : ContainerWhose.Kind.PLAYER, null, false);
        return Optional.of(new LootSeams.EntityContainer(entity.getId(), typeId, at, origin, natural || minecart));
    }

    @Override
    public Optional<WorldPosition> landmark(String name) {
        return memory.place(name);
    }

    @Override
    public List<SceneSign> signs() {
        Scene current = scene.get();
        return current == null ? List.of() : current.signs();
    }

    @Override
    public List<LootContainerChooser.Candidate> around(BlockPos center, int radius, Set<String> protectedLandmarks) {
        List<ContainerChooser.Candidate> raw = spots.around(center, radius);
        List<LootContainerChooser.Candidate> enriched = new ArrayList<>(raw.size());
        for (ContainerChooser.Candidate item : raw) {
            enriched.add(enrich(item, protectedLandmarks));
        }
        return List.copyOf(enriched);
    }

    @Override
    public boolean scanComplete() {
        return spots.scanComplete();
    }

    @Override
    public Optional<LootContainerChooser.Candidate> containerAt(WorldPosition position,
            Set<String> protectedLandmarks) {
        return spots.at(withDimension(position)).map(item -> enrich(item, protectedLandmarks));
    }

    private LootContainerChooser.Candidate enrich(ContainerChooser.Candidate item, Set<String> protectedLandmarks) {
        WorldPosition pos = withDimension(item.at());
        boolean natural = facilities.isNaturalLootContainer(item.blockTypeId());
        ContainerWhose origin = ContainerWhose.of(pos, natural, protection, item.knownContents() != null);
        return new LootContainerChooser.Candidate(item, origin,
                protection.storageUse(pos, item.blockTypeId(), protectedLandmarks));
    }

    private WorldPosition withDimension(WorldPosition pos) {
        if (pos.dimension() != null) return pos;
        PlayerContext current = contexts.get();
        ClientLevel level = current == null ? null : current.level();
        return level == null ? pos : new WorldPosition(pos.x(), pos.y(), pos.z(),
                level.dimension().location().toString());
    }

    @Override
    public Optional<Action> digLid(BlockPos lidCell, Permissions permissions) {
        return collects.collect(lidCell, permissions);
    }

    @Override
    public LootsContainer lootBlock(LootContainerChooser.Entry entry, Permissions permissions) {
        WorldPosition pos = withDimension(entry.container().at());
        BlockPos cell = new BlockPos(pos.x(), pos.y(), pos.z());
        ClientMenuOpening opening = new ClientMenuOpening(cell, permissions, layouts, close, interactions, contexts);
        return new LootsContainer(entry.container().name(), entry.container().blockTypeId(), pos, entry.origin(),
                opening, kept, memory, rareLootNotice);
    }

    @Override
    public LootsContainer lootEntity(LootSeams.EntityContainer entity, Permissions permissions) {
        var opening = ClientMenuOpening.ofEntity(entity.entityId(), false, permissions, layouts, close, interactions,
                toMainhand, sneaks, contexts);
        return new LootsContainer(entity.typeId(), entity.typeId(), entity.at(), entity.origin(),
                opening, kept, null, rareLootNotice);
    }
}
