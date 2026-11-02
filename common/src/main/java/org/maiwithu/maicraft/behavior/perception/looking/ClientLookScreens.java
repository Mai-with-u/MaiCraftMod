// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.looking;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.menu.ClientMenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.recipe.OpensRecipePage;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.menu.GlanceScreens;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 闲时看开、关界面的生产实现：背包经菜单入口打开，手边的容器站在原地点开（不走过去），
 * 配方页交给装了的查看器翻，关的时候交给游戏接口层按原版关。
 *
 * <p>记着"眼前的界面是闲时看开的"：从发出打开那一刻算起，到眼前没有界面为止；
 * 这期间出现的界面（背包下一刻才画出来、箱子等服务器回话）都算自己的，不当成别人开的。
 */
final class ClientLookScreens implements LookScreens {

    private final Supplier<PlayerContext> contexts;
    private final MenuLayouts layouts;
    private final Interactions interactions;
    private final WorldMemory memory;
    private final Supplier<List<OpensRecipePage>> pages;
    private boolean ours;

    ClientLookScreens(Supplier<PlayerContext> contexts, MenuLayouts layouts, Interactions interactions,
            WorldMemory memory, Supplier<List<OpensRecipePage>> pages) {
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.layouts = Objects.requireNonNull(layouts, "layouts");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.memory = memory;
        this.pages = Objects.requireNonNull(pages, "pages");
    }

    @Override public boolean othersOpen() {
        return GlanceScreens.anyOpen() && !oursOpen();
    }

    @Override public boolean oursOpen() {
        if (!GlanceScreens.anyOpen()) ours = false;
        return ours;
    }

    @Override public boolean showInventory() {
        PlayerContext player = contexts.get();
        if (player == null || player.menuActions() == null) return false;
        ours = true;
        return player.menuActions().ensureVisible(player);
    }

    // 站在原地点开：靠近这一步原地就算到了，点不着也不挪地方再试；开着了把里面有什么记进世界记忆。
    @Override public Action peek(LookPlans.Screen.Container container) {
        ours = true;
        WorldPosition at = container.at();
        ClientMenuOpening opening = new ClientMenuOpening(new BlockPos(at.x(), at.y(), at.z()), Permissions.DEFAULT,
                layouts, new StayPut(), interactions, contexts);
        return new Action() {
            @Override public ActionStatus tick(TickContext context) {
                ActionStatus status = opening.tick(context);
                if (status instanceof ActionStatus.Done) {
                    opening.opened().flatMap(OpenedMenu::reading).ifPresent(reading -> remember(container, reading));
                }
                return status;
            }

            @Override public void close() {
                opening.close();
            }

            @Override public String describe() {
                return "打开手边的容器看一眼";
            }
        };
    }

    @Override public boolean opensRecipePages() {
        return !pages.get().isEmpty();
    }

    @Override public boolean openRecipePage(String itemId, boolean uses) {
        ours = true;
        for (OpensRecipePage page : pages.get()) {
            if (page.open(itemId, uses)) return true;
        }
        return false;
    }

    @Override public void closeOurs() {
        if (oursOpen()) GlanceScreens.close(contexts.get());
    }

    // 开箱看到的照拿东西时的做法记进世界记忆：来源"打开过"，时间是现在，空箱子记空列表。
    private void remember(LookPlans.Screen.Container container, MenuContent.Reading reading) {
        if (memory == null) return;
        Set<String> items = new LinkedHashSet<>();
        for (SlotSnapshot snapshot : reading.containerSnapshots()) {
            if (snapshot.isEmpty()) continue;
            items.add(BuiltInRegistries.ITEM.getKey(snapshot.stack().getItem()).toString());
        }
        memory.rememberContainerOpened(container.at(), container.blockType(), List.copyOf(items), Instant.now());
    }

    /** 不走路的靠近：第一次就算到了；点开时说够不着、要换站位，就不再挪，按走不到收场。 */
    private static final class StayPut implements BringsPlayerClose {
        private boolean used;

        @Override public Action toward(ApproachTarget target, Permissions permissions) {
            boolean first = !used;
            used = true;
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    return first ? ActionStatus.done()
                            : ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE, "站在原地够不着，看一眼不走过去", null));
                }

                @Override public String describe() {
                    return "站在原地";
                }
            };
        }
    }
}
