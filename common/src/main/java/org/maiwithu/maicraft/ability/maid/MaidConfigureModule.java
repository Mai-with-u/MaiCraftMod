// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.maid;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.maiwithu.maicraft.ability.maid.spi.MaidSettings;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.ability.RequiredMod;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.TargetKind;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

/**
 * 改女仆设置的能力：点名一只自己的女仆（车万女仆模组里的一种可驯服生物，不是角色），打开她的界面改皮肤、工作、
 * 班次、跟随或驻守、拾取、手上与身上的装备。设置写法的形状在这里一次核完；她认哪些键、值对不对、怎么改由联动办法负责。
 * 车万女仆没装时这个能力不上架；装了联动却没登记上时照样上架，点名时如实说联动没登记。
 */
public final class MaidConfigureModule implements AbilityModule {

    /** 本能力需要的模组：车万女仆没装就不登记。 */
    static final String REQUIRED_MOD = "touhou_little_maid";

    private final FindsMaid maids;

    public MaidConfigureModule(FindsMaid maids) {
        this.maids = Objects.requireNonNull(maids, "maids");
    }

    @Override public AbilitySpec spec() {
        return new AbilitySpec(
                "maicraft:maid_configure",
                "改自己的女仆的设置：皮肤、工作、班次、跟随或驻守、拾取、手上与身上的装备（车万女仆）",
                AbilityDoc.forAbility("maid_configure"),
                ParamSpecs.of(
                        ParamSpec.of("settings", ParamType.JSON_OBJECT).required()
                                .doc("要改成什么：键是她认的设置项（skin、work、work_shift、stay、pickup、mainhand、offhand、"
                                        + "head、chest、legs、feet），值写字符串，按给的顺序改，"
                                        + "例如 {\"skin\": \"geckolib:winefox\", \"stay\": \"in_area\"}").build()),
                Set.of(TargetKind.SEEN),
                ExecutionMode.CONTROLS_PLAYER,
                Set.of(RequiredMod.of(REQUIRED_MOD)),
                List.of(),
                Listing.LISTED);
    }

    // 形状在这里一次核完：target 必须是一只生物，settings 至少一项、每项的值能当成一个字符串；键认不认得到现场问她。
    @Override public StepDecision decide(StepContext step) {
        if (!(step.goal().target() instanceof Target.Seen seen) || !seen.id().startsWith("e")) {
            return invalid("target 要给女仆的观察编号 e#（例如 e3）");
        }
        JsonObject raw = step.goal().params().json("settings").getAsJsonObject();
        List<MaidSettings.Setting> settings = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : raw.entrySet()) {
            if (!entry.getValue().isJsonPrimitive()) {
                return invalid("设置「" + entry.getKey() + "」的值要写字符串，现在给的是别的形状");
            }
            settings.add(new MaidSettings.Setting(entry.getKey(), entry.getValue().getAsString()));
        }
        if (settings.isEmpty()) return invalid("settings 要至少一项，例如 {\"stay\": \"in_area\"}");
        return new StepDecision.Run(new MaidConfigureInput(seen.id(), settings, step.goal().permissions()));
    }

    private static StepDecision invalid(String why) {
        return new StepDecision.Finish(TaskResult.failed("设置写法不对",
                Problem.of(Problem.Kind.INVALID_PARAMETER, why, null)));
    }

    @Override public void registerTasks(TaskFactories factories) {
        // 每次运行新建改设置任务；找女仆的办法经闭包交给任务。
        factories.register(MaidConfigureInput.class, input -> new MaidConfigureTask(input, maids));
    }
}
