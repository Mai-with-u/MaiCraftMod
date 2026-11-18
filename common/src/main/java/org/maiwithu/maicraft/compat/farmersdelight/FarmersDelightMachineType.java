// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.farmersdelight;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.MachineFeeding;
import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.behavior.inventory.containers.MenuFeed;
import org.maiwithu.maicraft.behavior.menu.ClientMenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuOutputLook;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.CompatMenuFeed;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 农夫乐事的厨锅：一口放在热源上煮菜的锅。放好料、下方有热源就自己煮，没有开关也不吃燃料；
 * 汤类的餐做出来还要锅里存着碗才分装到成品格。投料与取餐都点开它的界面（手持碗右键也能直接
 * 盛一份，那是玩家的取法，这里统一走界面）；上方能自动进料、下方能抽出成品，是漏斗类的接法。
 */
final class FarmersDelightMachineType implements MachineType, MachineFeeding {

    private final FarmersDelightMachineCatalog.Kind kind;
    private final FarmersDelightCompat compat;
    private final FarmersDelightReads reads;
    private final PlayerServices services;
    private final Set<Block> blocks;

    FarmersDelightMachineType(FarmersDelightMachineCatalog.Kind kind, FarmersDelightCompat compat,
            FarmersDelightReads reads, PlayerServices services) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.compat = Objects.requireNonNull(compat, "compat");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.services = Objects.requireNonNull(services, "services");
        this.blocks = compat.blocksOf(kind.blocks());
    }

    @Override public String id() { return kind.id(); }

    @Override public String name() { return kind.name(); }

    @Override public String modId() { return FarmersDelightCompat.MOD_ID; }

    @Override public MachineRole role() { return kind.role(); }

    @Override public boolean covers(BlockState state) {
        return blocks.contains(state.getBlock());
    }

    // 进出口：上方自动进食材、下方抽成品与送碗（漏斗类的接法）；角色自己投料取餐点开界面。
    @Override public List<ExchangePoint> exchangePoints(BlockState state, BlockPos at) {
        return List.of(
                new ExchangePoint(at, Direction.UP, ExchangePoint.Medium.ITEM, ExchangePoint.Flow.INPUT,
                        "上方能自动把食材送进锅的食材格（漏斗、传送带）；角色自己投料点开界面放"),
                new ExchangePoint(at, Direction.DOWN, ExchangePoint.Medium.ITEM, ExchangePoint.Flow.OUTPUT,
                        "下方能抽出做好的餐与收回空碗（漏斗）；角色自己取餐点开界面，或拿配对的碗右键盛一份"));
    }

    // 运行状态只说供热：锅有没有在煮要看进度条（点开界面），这里只给"能不能煮"。
    @Override public MachineState state(BlockPos at) {
        ClientLevel level = level();
        if (level == null) return MachineState.unknown("角色现在不在世界里，读不到厨锅");
        Optional<Boolean> heated = compat.potHeated(level, at);
        if (heated.isEmpty()) return MachineState.unknown("这一格读不到厨锅（没加载，或已经不是厨锅）");
        if (heated.get()) {
            return new MachineState(MachineState.Activity.RUNNING, null, null, List.of(),
                    Map.of("heated", "是"), "下方有热源，配好料就会开始煮；有没有煮要看界面里的进度");
        }
        return new MachineState(MachineState.Activity.IDLE, null, null, List.of(),
                Map.of("heated", "否"), "下方没有热源（篝火、炉子、烟熏炉这类），不会开始煮");
    }

    // 厨锅的本事只有投料与取餐；安装段、部件、设置项它都没有，不答那些问题。
    @Override public Optional<MachineFeeding> feeding() { return Optional.of(this); }

    // 投料：点开厨锅的界面把食材搬进去（整堆快速移动，锅自己把它放进收它的格）。

    @Override public Optional<Action> feed(BlockPos at, String itemId, int count, Permissions permissions) {
        return Optional.of(new CompatMenuFeed(new MenuFeed(services, kind.name(), at, kind.id(), itemId, count,
                permissions, null)));
    }

    // 出口：做好的餐在餐品显示格与成品输出格里，随方块实体同步，站外面读得到。
    @Override public List<MachineState.Shown> output(BlockPos at) {
        ClientLevel level = level();
        if (level == null) return List.of();
        return compat.potMeal(level, at).orElse(List.of());
    }

    // 收产物：点开厨锅的界面把做好的餐搬进背包，看完关上。
    @Override public Optional<Action> take(BlockPos at, String itemId, int count, Permissions permissions) {
        return Optional.of(new MenuOutputLook(itemId, true,
                new ClientMenuOpening(at, permissions, services.menuLayouts(), services.approach(),
                        services.interactions(), services.context()),
                services.context()));
    }

    private ClientLevel level() {
        PlayerContext current = services.context().get();
        return current == null ? null : current.level();
    }
}
