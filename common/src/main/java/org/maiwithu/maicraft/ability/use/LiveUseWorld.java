// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.interaction.ItemUseAim;
import org.maiwithu.maicraft.behavior.inventory.spi.NetworkStorage;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.StackKey;

/**
 * 用东西的世界读数：每次都按这一刻的客户端现场回答，角色不在世界里或那一格没加载时如实给空。
 */
final class LiveUseWorld implements UseSeams.ReadsWorld {

    private final Supplier<PlayerContext> context;
    private final Supplier<Optional<NetworkStorage>> networkStorage;

    LiveUseWorld(Supplier<PlayerContext> context) {
        this(context, Optional::empty);
    }

    LiveUseWorld(Supplier<PlayerContext> context, Supplier<Optional<NetworkStorage>> networkStorage) {
        this.context = Objects.requireNonNull(context, "context");
        this.networkStorage = Objects.requireNonNull(networkStorage, "networkStorage");
    }

    @Override public String dimension() {
        ClientLevel level = level();
        return level == null ? null : level.dimension().location().toString();
    }

    @Override public BlockPos feet() {
        LocalPlayer player = player();
        return player == null ? null : player.blockPosition();
    }

    @Override public Vec3 position() {
        LocalPlayer player = player();
        return player == null ? null : player.position();
    }

    // 只看那一格所在的区块在不在客户端：高度不影响"那一片加载了没有"。
    @Override public boolean loaded(BlockPos cell) {
        ClientLevel level = level();
        // 客户端世界的 hasChunkAt 永远答“加载着”，没加载的格读出来是空气、地表落在世界最底层；按区块真加载没加载判。
        return level != null && level.isLoaded(cell);
    }

    @Override public Optional<String> blockId(BlockPos cell) {
        return state(cell).map(state -> BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    }

    @Override public boolean air(BlockPos cell) {
        return state(cell).map(BlockState::isAir).orElse(true);
    }

    // 短草、蕨、薄雪、水这类人站在里面、放方块能直接顶掉的格：找脚下地面时穿过它看下面垫着的实心格。
    @Override public boolean replaceable(BlockPos cell) {
        return state(cell).map(state -> state.isAir() || state.canBeReplaced()).orElse(true);
    }

    // 方块 ID 按注册名比；标签按方块自己挂没挂这个标签比，模组加进标签的方块也认。
    @Override public boolean blockIs(BlockPos cell, String blockOrTag) {
        Optional<BlockState> state = state(cell);
        if (state.isEmpty()) return false;
        String spec = blockOrTag.toLowerCase(Locale.ROOT);
        if (spec.startsWith("#")) {
            ResourceLocation tagId = ResourceLocation.tryParse(spec.substring(1));
            return tagId != null && state.get().is(TagKey.create(Registries.BLOCK, tagId));
        }
        return BuiltInRegistries.BLOCK.getKey(state.get().getBlock()).toString().equals(spec);
    }

    @Override public boolean hasBlockEntity(BlockPos cell) {
        ClientLevel level = level();
        return level != null && level.isLoaded(cell) && level.getBlockEntity(cell) != null;
    }

    // 门、活板门、栅栏门读方块状态里的"开着"；木桶等别的带"开着"的方块不算门，不报。
    @Override public Optional<Boolean> doorOpen(BlockPos cell) {
        return state(cell).filter(state -> state.getBlock() instanceof DoorBlock || state.getBlock() instanceof TrapDoorBlock
                || state.getBlock() instanceof FenceGateBlock).map(state -> state.getValue(BlockStateProperties.OPEN));
    }

    // 原版营火：读方块状态的点燃标记与方块实体四个烤位的占用数，供放食后给出准确回报。
    @Override public Optional<CampfireStatus> campfire(BlockPos cell) {
        ClientLevel level = level();
        if (level == null || !level.isLoaded(cell)) return Optional.empty();
        BlockState state = level.getBlockState(cell);
        if (!(state.getBlock() instanceof CampfireBlock)) return Optional.empty();
        boolean lit = state.hasProperty(CampfireBlock.LIT) && state.getValue(CampfireBlock.LIT);
        int occupied = 0;
        int capacity = 4;
        if (level.getBlockEntity(cell) instanceof CampfireBlockEntity campfire) {
            capacity = campfire.getItems().size();
            for (ItemStack stack : campfire.getItems()) {
                if (!stack.isEmpty()) occupied++;
            }
        }
        return Optional.of(new CampfireStatus(lit, occupied, capacity));
    }

    // 原版熔炉、烟熏炉、高炉及其继承 AbstractFurnaceBlock 的变体：非潜行右键只会打开炉界面，不会直接消耗主手物品投料。
    @Override public boolean isVanillaFurnace(BlockPos cell) {
        return state(cell).map(state -> state.getBlock() instanceof AbstractFurnaceBlock
                || UseDecider.isVanillaFurnaceId(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()))
                .orElse(false);
    }

    // 机械动力合成器右键投料、空手取料与扳手改向都严格要求命中正面（HORIZONTAL_FACING），点侧面或背面会被直接忽略。
    @Override public Optional<Direction> preferredInteractFace(BlockPos cell) {
        return state(cell)
                .filter(state -> UseDecider.MECHANICAL_CRAFTER_BLOCK.equals(
                        BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()))
                .filter(state -> state.hasProperty(BlockStateProperties.HORIZONTAL_FACING))
                .map(state -> state.getValue(BlockStateProperties.HORIZONTAL_FACING));
    }

    // 工作盆顶面四周边沿有 2/16 宽：只要正上方那一格底面不是整格实心封死的（空气、溜槽、漏斗都留着边沿），就能点到顶面。
    @Override public boolean topRimOpen(BlockPos cell) {
        ClientLevel level = level();
        BlockPos above = cell.above();
        if (level == null || !level.isLoaded(above)) return true;
        BlockState aboveState = level.getBlockState(above);
        return aboveState.isAir() || !Block.isFaceFull(aboveState.getShape(level, above), Direction.DOWN);
    }

    @Override public boolean bottomOpen(BlockPos cell) {
        ClientLevel level = level();
        BlockPos below = cell.below();
        if (level == null || !level.isLoaded(below)) return true;
        BlockState belowState = level.getBlockState(below);
        return belowState.isAir() || !Block.isFaceFull(belowState.getShape(level, below), Direction.UP);
    }

    // 对工作盆直接右键有效的物品：除名字认得的桶瓶、海绵、扳手外，还认扳手与桶标签，以及用完留空容器的装液物品。
    @Override public boolean interactsWithBasin(String itemOrTag) {
        return isBasinInteractItem(itemOrTag);
    }

    /** 复用同一份工作盆直接交互物品判定，供世界读端与交互组装共用。 */
    static boolean isBasinInteractItem(String itemOrTag) {
        if (UseDecider.interactsWithBasinById(itemOrTag)) return true;
        if (itemOrTag == null || itemOrTag.startsWith("#")) return false;
        ResourceLocation id = ResourceLocation.tryParse(StackKey.parse(itemOrTag.toLowerCase(Locale.ROOT)).itemId());
        if (id == null) return false;
        Optional<Item> found = BuiltInRegistries.ITEM.getOptional(id);
        if (found.isEmpty()) return false;
        Item item = found.get();
        ItemStack probe = new ItemStack(item);
        return item.hasCraftingRemainingItem()
                || probe.is(TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("c", "tools/wrench")))
                || probe.is(TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("c", "buckets")));
    }

    // 那一列最上面一块挡得住身体或有流体的方块：按客户端同步下来的高度图读，不逐格往下扫。
    @Override public Optional<BlockPos> ground(int x, int z) {
        ClientLevel level = level();
        BlockPos column = new BlockPos(x, 0, z);
        if (level == null || !level.isLoaded(column)) return Optional.empty();
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        return top < level.getMinBuildHeight() ? Optional.empty() : Optional.of(new BlockPos(x, top, z));
    }

    @Override public Fluid fluid(BlockPos cell) {
        ClientLevel level = level();
        if (level == null || !level.isLoaded(cell)) return Fluid.NONE;
        FluidState fluid = level.getFluidState(cell);
        if (fluid.isEmpty()) return Fluid.NONE;
        return fluid.isSource() ? Fluid.SOURCE : Fluid.FLOWING;
    }

    // 沿连通的同种流体做三维广度优先搜索向源格回溯：只走已加载的同种流体格，绝不跨墙跨空气跳到另一片水体。
    @Override public Optional<BlockPos> nearestSource(BlockPos near, int radius) {
        ClientLevel level = level();
        if (level == null || !level.isLoaded(near)) return Optional.empty();
        FluidState kind = level.getFluidState(near);
        if (kind.isEmpty()) return Optional.empty();
        return ConnectedFluidSource.find(near, radius, cell -> {
            if (!level.isLoaded(cell)) return Fluid.NONE;
            FluidState fluid = level.getFluidState(cell);
            if (fluid.isEmpty() || !fluid.getType().isSame(kind.getType())) return Fluid.NONE;
            return fluid.isSource() ? Fluid.SOURCE : Fluid.FLOWING;
        });
    }

    // 告示牌的上蜡状态随方块实体同步到客户端：上过蜡的游戏不让改字。
    @Override public Sign sign(BlockPos cell) {
        ClientLevel level = level();
        if (level == null || !level.isLoaded(cell)) return Sign.NOT_A_SIGN;
        if (!(level.getBlockEntity(cell) instanceof SignBlockEntity sign)) return Sign.NOT_A_SIGN;
        return sign.isWaxed() ? Sign.WAXED : Sign.WRITABLE;
    }

    // 准星选不选得中按实体自己的说法：掉落物、经验球、射出去的箭都选不中。
    @Override public Optional<SeenEntity> entity(int entityId) {
        return entityById(entityId).map(entity -> new SeenEntity(
                EntityType.getKey(entity.getType()).toString(), entity.blockPosition(), entity.isPickable()));
    }

    @Override public boolean riding(int entityId) {
        LocalPlayer player = player();
        Entity vehicle = player == null ? null : player.getVehicle();
        return vehicle != null && vehicle.getId() == entityId;
    }

    @Override public Optional<Held> heldItem() {
        LocalPlayer player = player();
        if (player == null) return Optional.empty();
        ItemStack held = player.getMainHandItem();
        return held.isEmpty() ? Optional.empty()
                : Optional.of(new Held(BuiltInRegistries.ITEM.getKey(held.getItem()).toString(), held.getCount()));
    }

    // 只有手持 BlockItem 时才可能回退成向邻格放方块：直接取该 BlockItem 对应的方块注册 ID，覆盖物品与方块不同名的情形。
    @Override public Optional<String> heldBlockId() {
        LocalPlayer player = player();
        if (player == null) return Optional.empty();
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty() || !(held.getItem() instanceof BlockItem blockItem)) return Optional.empty();
        return Optional.of(BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString());
    }

    // 统计角色主背包与副手当前每样物品的总件数，用于交互前后比对拆解或转化出的新增物品。
    @Override public Map<String, Integer> backpackCounts() {
        LocalPlayer player = player();
        if (player == null) return Map.of();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty()) {
                counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
            }
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (!stack.isEmpty()) {
                counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
            }
        }
        return Map.copyOf(counts);
    }

    // 实体会走动：靠近时取它这一刻的包围盒，不用落实目标时记下的位置。
    // 写告示牌默认写正面：从哪一面点就编辑哪一面，站位只挑正面那一侧，站到背面会把字写到背面去。
    // 正面朝哪边按原版告示牌自己的水平朝向角回答，立式、挂墙、悬挂、挂墙悬挂四种告示牌原版都会答；
    // 那一格不是告示牌了给空，任务按目标没了收场。
    // 对船、矿车、马这类载具右键就是骑上去：站位候选声明允许湿脚，水边的船才登得上去；
    // 村民、动物这些别的实体照旧只要干位，湿脚只放给成为载具的乘客这一种用法。
    @Override public Optional<ApproachTarget> approachTarget(ResolvedTarget target, boolean writesSign) {
        return approachTarget(target, writesSign, false);
    }

    @Override public Optional<ApproachTarget> approachTarget(ResolvedTarget target, boolean writesSign, boolean sneak) {
        if (target.isEntity()) {
            return entityById(target.entityId()).map(entity ->
                    rideableVehicle(entity) ? ApproachTarget.ofVehicle(entity.getBoundingBox())
                            : ApproachTarget.ofEntity(entity.getBoundingBox()));
        }
        if (!writesSign) {
            // 空手且不潜行点方块时，若该格装着 ME 终端面板，够不够得着与看不看得见都按面板框判，免得走到只见线缆不见面板的站位。
            if (!sneak && heldItem().isEmpty()) {
                Optional<AABB> panel = networkStorage.get()
                        .flatMap(storage -> storage.terminalPanelAt(target.cell(), target.face()))
                        .filter(box -> new AABB(target.cell()).intersects(box));
                if (panel.isPresent()) {
                    return Optional.of(ApproachTarget.ofBlockPart(target.cell(), panel.get()));
                }
            }
            // 指定了点哪一面：站位按方块外形上朝这一面的表面看不看得见挑。
            ApproachTarget base = target.face() != null
                    ? ApproachTarget.ofBlockFace(target.cell(), target.face())
                    : ApproachTarget.ofBlock(target.cell());
            // 桶舀倒、水上放船、装水瓶与刷子在服务端按真实射线或持续按住准星结算，到达站位后仍核对视线与原距离。
            return Optional.of(requiresSightForHeld() ? base.requiringSightOnArrival() : base);
        }
        ClientLevel level = level();
        if (level == null || !level.isLoaded(target.cell())) return Optional.empty();
        BlockState state = level.getBlockState(target.cell());
        if (!(state.getBlock() instanceof SignBlock sign)) return Optional.empty();
        return Optional.of(ApproachTarget.onFrontSideOf(target.cell(), sign.getYRotationDegrees(state)));
    }

    private boolean requiresSightForHeld() {
        ItemUseAim.Gesture gesture = ItemUseAim.gestureOf(heldItem().map(Held::itemId).orElse(null));
        return gesture == ItemUseAim.Gesture.SCOOP
                || gesture == ItemUseAim.Gesture.POUR
                || gesture == ItemUseAim.Gesture.OWN_USE
                || gesture == ItemUseAim.Gesture.BRUSH;
    }

    private Optional<BlockState> state(BlockPos cell) {
        ClientLevel level = level();
        if (level == null || !level.isLoaded(cell)) return Optional.empty();
        return Optional.of(level.getBlockState(cell));
    }

    private Optional<Entity> entityById(int entityId) {
        ClientLevel level = level();
        Entity entity = level == null ? null : level.getEntity(entityId);
        return entity == null || entity.isRemoved() ? Optional.empty() : Optional.of(entity);
    }

    // 原版空手右键就骑上去的载具：船、矿车、马这一族（骆驼也是马的分支）。与"别人的东西"里
    // 「空手骑上船、矿车、马不改动世界」认的是同一批；村民是交易不是骑乘，不在这批里。
    private static boolean rideableVehicle(Entity entity) {
        return entity instanceof Boat || entity instanceof AbstractMinecart || entity instanceof AbstractHorse;
    }

    private ClientLevel level() {
        PlayerContext current = context.get();
        return current == null ? null : current.level();
    }

    private LocalPlayer player() {
        PlayerContext current = context.get();
        return current == null ? null : current.localPlayer();
    }
}
