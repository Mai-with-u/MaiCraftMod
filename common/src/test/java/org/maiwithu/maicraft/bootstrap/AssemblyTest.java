// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.ability.chat.ChatAbility;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.GoalRunTable;
import org.maiwithu.maicraft.kernel.goal.InMemoryGoalRunStore;
import org.maiwithu.maicraft.kernel.goal.PlayerControlHandover;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.interrupt.ControlLoop;
import org.maiwithu.maicraft.kernel.param.ParseResult;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 总装的替身测试：能力清单能在没有任何真实游戏对象的情况下创建并登记；
 * 下达一个目标后控制循环每刻能推进，目标按能力自己的节奏走完。
 * 真实的端到端连通（LLM 一句话 → 角色动手）是实机验收，这里只保证装得上、推得动。
 */
class AssemblyTest {

    @BeforeAll
    static void bootMinecraft() {
        // 清单总装要建带方块状态的替身：注册表引导过才能碰，单独跑这个类也一样。
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }


    @TempDir
    Path tempDir;

    @Test
    void catalogRegistersEveryAssembledAbility() {
        AbilityRegistry registry = AbilityCatalog.create(OfflineCatalog.deps(tempDir));

        Set<String> registered = new LinkedHashSet<>();
        for (var module : registry.all()) {
            registered.add(module.spec().name());
        }
        // 存东西与记地点能力接上了：找容器、界面读数、整堆搬运与挖盖子都有实现方；记地点只改记忆。
        // 画图与施工也接上了：图纸存在按实例的设计库里，施工把蓝图落到锚点交给施工任务。
        // 任务书也接上了：离线清单当作 FTB 任务装着，操作来自登记表（空登记表时它如实说用不了）。
        // 机器的六个能力也接上了：机器类型与网络读取器由登记表按装了的模组建，离线一份没有也照常登记，
        // 施工与接网络只认这两样接口，放方块不需要认识机器。
        // 钓鱼与附魔也接上了：钓位几何、咬钩收竿、装料与报价都组合玩家行为层已有的模型。
        // 组装载具也接上了：物理组装器的办法由登记表按装了的 Simulated 建，离线一份没有也照常登记（如实说用不了）。
        // 驾驶载具同样：无线红石打字机驾驶由登记表按装了的 Simulated 建，离线没有也照常登记。
        // 寻死也接上了：读角色与地形、走到、直接按键、换手、瞄准交互与穿卸都用共用的那一套。
        // 计时也接上了：只往启动侧那份计时表里挂，离线清单给一份没人听事件的。
        assertEquals(Set.of("use", "eat", "equip", "drop", "suicide", "obtain", "gather", "deposit", "sleep", "design", "build",
                "machine_inspect", "machine_review", "machine_configure", "machine_run",
                "machine_build", "machine_connect", "light",
                "fight", "follow", "timer", "travel", "find", "explore", "chat", "remember", "sequence", "quest", "vehicle_assemble",
                "vehicle_drive", "fish", "enchant"), registered,
                "清单里的能力要一个不少地登记上");
    }

    @Test
    void assignedGoalAdvancesThroughControlLoopEachTick() {
        var registry = AbilityCatalog.create(OfflineCatalog.deps(tempDir));
        // 手上没有生存需求（替身清单为空），控制循环只推进主任务。
        ControlLoop controlLoop = new ControlLoop(List.of());
        // 下达经目标运行表：LLM 用 execute 派的活走同一条路，目标成为控制循环的主任务。
        // 列出挂着的计时：一刻就做完的真实能力。生产里 execute 把不占身体的能力当场做完，放进 sequence 时才由控制循环推进；
        // 这里借它把目标运行表、任务工厂与控制循环这条推进路径完整走一遍。
        // 目标写能力的完整 ID：目标推进器按 ID 查清单。
        // 控制权交接给空实现：总装测试不接输入层，只验证目标推得动。
        GoalRunTable goals = new GoalRunTable(registry, new InMemoryGoalRunStore(),
                OfflineCatalog.deps(tempDir).world().memory(), controlLoop, new PlayerControlHandover() {
                    @Override public boolean automationOwnsControls() {
                        return true;
                    }

                    @Override public void requestControl() {
                    }
                });
        var timer = registry.find("maicraft:timer").orElseThrow();
        ParseResult parsed = timer.spec().paramSpecs()
                .parse(JsonParser.parseString("{\"operation\":\"list\"}").getAsJsonObject());
        assertTrue(parsed.ok(), "列出计时的参数应能解析：" + parsed.errors());
        GoalRunTable.Launch launch = goals.launch(Goal.of("maicraft:timer", null, parsed.params()), null);
        assertTrue(!launch.repeated(), "第一次下达要新开一个目标运行");

        // 下达后的第一刻能力做决定、开出任务，随后一刻任务走完：两刻内给结果。
        TaskResult finished = null;
        for (int tick = 0; tick < 3 && finished == null; tick++) {
            ControlLoop.Decision decision = controlLoop.tick(new OfflineTick());
            ControlLoop.Decision.Advanced advanced =
                    assertInstanceOf(ControlLoop.Decision.Advanced.class, decision);
            finished = advanced.finished();
        }
        assertNotNull(finished, "列出计时在几刻内就该走完");
        assertEquals(TaskResult.Status.DONE, finished.status(),
                "列出计时的结算要如实写清：" + finished.summary());
    }

    @Test
    void chatTypewriterConfigReachesTheAssembledChatTask() {
        // 实例配置的逐字打字开关要在组装层接进说话能力：这里钉住「配置 → 造出的发话任务初始相位」这一跳，
        // 防止配置收进依赖后被丢弃、开关恒被当成开着的。
        // 关掉逐字：任务一开始就在提交阶段，整句第一刻交给聊天输入，不再逐字打草稿。
        Task closedTask = assembledChatTask(OfflineCatalog.deps(tempDir, false));
        assertEquals("正在说话", closedTask.currentProgress().orElseThrow().phase(),
                "逐字打字关掉时，发话任务要一开始就在提交阶段");

        // 开着逐字（默认）：任务一开始在逐字打字阶段，逐字行为保持不变。
        Task openTask = assembledChatTask(OfflineCatalog.deps(tempDir, true));
        assertEquals("逐字打字", openTask.currentProgress().orElseThrow().phase(),
                "逐字打字开着时，发话任务要一开始就在逐字打字阶段");
    }

    /** 从组装层取一份说话能力，照真实路径开出一条发话任务：能力登记任务工厂，决定阶段产出输入，工厂造任务。 */
    private static Task assembledChatTask(AbilityCatalog.Deps deps) {
        ChatAbility chat = AbilityCatalog.chatModule(deps);
        TaskFactories factories = new TaskFactories();
        chat.registerTasks(factories);
        ParseResult parsed = chat.spec().paramSpecs()
                .parse(JsonParser.parseString("{\"message\":\"大家好\"}").getAsJsonObject());
        assertTrue(parsed.ok(), "一句话的参数应能解析：" + parsed.errors());
        Goal goal = new Goal("maicraft:chat", null, null, parsed.params(), null, List.of(), null);
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class, chat.decide(new StepContext() {
            @Override public Goal goal() {
                return goal;
            }

            @Override public int stepIndex() {
                return 0;
            }

            @Override public TickContext tick() {
                throw new IllegalStateException("决定阶段不应碰到每刻上下文");
            }
        }));
        return factories.create(run.input());
    }

    /** 离线的一刻：只有游戏刻号，没有角色；计时只看刻号，不需要角色。 */
    private record OfflineTick() implements TickContext {
        @Override public long gameTick() {
            return 0;
        }

        @Override public PlayerContext player() {
            return null;
        }
    }
}
