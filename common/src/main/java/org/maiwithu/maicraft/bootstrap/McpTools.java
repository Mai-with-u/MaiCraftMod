// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.inventory.ReservedItems;
import org.maiwithu.maicraft.behavior.perception.looking.LooksAfterReading;
import org.maiwithu.maicraft.behavior.reminder.Reminder;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.event.ChatEventLog;
import org.maiwithu.maicraft.kernel.event.TaskEventLog;
import org.maiwithu.maicraft.kernel.goal.GoalRunTable;
import org.maiwithu.maicraft.mcp.tool.EventsTool;
import org.maiwithu.maicraft.mcp.tool.ExecuteTool;
import org.maiwithu.maicraft.mcp.tool.GoalTool;
import org.maiwithu.maicraft.mcp.tool.LookupTool;
import org.maiwithu.maicraft.mcp.tool.ObserveTool;
import org.maiwithu.maicraft.game.picture.CameraPictures;
import org.maiwithu.maicraft.mcp.tool.RecentToolCalls;
import org.maiwithu.maicraft.mcp.tool.ToolDispatcher;
import org.maiwithu.maicraft.mcp.view.SelfView;

/**
 * MCP 的五个工具怎么接：能力注册表、目标运行表与任务事件流。LLM 用 execute 下达的目标经目标运行表
 * 成为控制循环的主任务；目标处境每次变化都发成任务事件，宿主用 events 等。
 * 能力按清单在进世界时登记进这份注册表。事件流用客户端入口的那一份：目标处境与生存需求共一条流。
 */
final class McpTools {
    private McpTools() {}

    /** 五个工具装进一个调用分发；观察读当前世界的现场，不在世界里时各视图如实说不在。 */
    static ToolDispatcher dispatcher(AbilityRegistry abilities, GoalRunTable goals, ClientTickWork clientWork,
                                     TaskEventLog taskEvents, ChatEventLog chatEvents, Supplier<WorldScope> worldScope,
                                     RecentToolCalls recentCalls, LookupTool lookup,
                                     Supplier<List<Reminder>> reminders, ReservedItems reserved,
                                     CameraPictures cameraPictures) {
        LooksAfterReading looks = looksInWorld(worldScope);
        // 拍画面：取画面的观察口全程一份；转头交给当前世界的闲时看，建筑档案读当前世界的那一份。
        ObserveTool.Pictures pictures = new ObserveTool.Pictures(cameraPictures,
                inWorld(worldScope, WorldScope::idleLooking), inWorld(worldScope, WorldScope::buildingArchives));
        return new ToolDispatcher(List.of(
                new ObserveTool(inWorld(worldScope, WorldScope::scene),
                        inWorld(worldScope, WorldScope::memory), goals, clientWork,
                        reminders, inWorld(worldScope, WorldScope::menus),
                        // 预留与身上各有几件：读的是客户端全程那一份，进世界时已换成这个世界存着的。
                        () -> reserved.list().stream().map(entry -> new SelfView.Reserved(entry.item(),
                                entry.atLeast(), reserved.carriedFor(entry))).toList(), looks,
                        inWorld(worldScope, WorldScope::identity), inWorld(worldScope, WorldScope::afterDeath), pictures,
                        inWorld(worldScope, WorldScope::protection),
                        () -> {
                            WorldScope scope = worldScope.get();
                            return scope == null ? List.of() : scope.carriedBackpacks();
                        },
                        () -> {
                            WorldScope scope = worldScope.get();
                            return scope == null ? List.of() : scope.modFeatures();
                        },
                        inWorld(worldScope, WorldScope::lastChainMining)),
                // 查资料接随包的机制常识、物品资料页、联动模组登记的资料来源与配方；联网的资料来源还没登记。
                lookup,
                new ExecuteTool(abilities, goals, clientWork, looks),
                new GoalTool(goals, clientWork),
                // 读事件每一页都带角色眼下在哪个世界：读的是这一趟进世界认出的那个，不在世界里为 null。
                new EventsTool(taskEvents, chatEvents, goals, clientWork,
                        inWorld(worldScope, WorldScope::world))), recentCalls);
    }

    /**
     * 从当前世界的现场读一样东西；不在世界里时为 null。MCP 的请求线程上读：现场先取到手再判空，
     * 不分两次读——退出世界那一刻客户端线程正好把现场摘掉，第二次读就是空的（读事件被叫醒时恰好就在这一刻）。
     */
    private static <T> Supplier<T> inWorld(Supplier<WorldScope> worldScope, Function<WorldScope, T> read) {
        return () -> {
            WorldScope scope = worldScope.get();
            return scope == null ? null : read.apply(scope);
        };
    }

    /** 读完告诉当前世界的闲时看读了什么；不在世界里时没有角色可演，什么都不做。 */
    static LooksAfterReading looksInWorld(Supplier<WorldScope> worldScope) {
        return what -> {
            WorldScope scope = worldScope.get();
            if (scope != null) scope.idleLooking().justRead(what);
        };
    }
}
