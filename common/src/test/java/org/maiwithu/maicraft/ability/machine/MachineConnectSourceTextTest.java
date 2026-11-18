// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.ability.machine.spi.NetworkReader;
import org.maiwithu.maicraft.behavior.recipe.GameRecipeTable;
import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 接网络模块复用共享 {@code TargetText} 解析 {@code source} 参数的回归测试。
 */
class MachineConnectSourceTextTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void parsesHereSeenPositionLandmarkAndRejectsMalformedCoordinates() {
        MachineConnectModule module = createModule();
        Target target = new Target.Position(6, 64, 6, null);

        MachineConnectInput fromHere = runInput(module, target, "{\"network\":\"kinetic\",\"source\":\"here\"}");
        assertEquals(Optional.of(new Target.Here()), fromHere.source());

        MachineConnectInput fromSeen = runInput(module, target, "{\"network\":\"kinetic\",\"source\":\"B7\"}");
        assertEquals(Optional.of(new Target.Seen("b7")), fromSeen.source());

        MachineConnectInput fromCoords = runInput(module, target, "{\"network\":\"kinetic\",\"source\":\"[2, 64, -4]\"}");
        assertEquals(Optional.of(new Target.Position(2, 64, -4, null)), fromCoords.source());

        MachineConnectInput fromLandmark = runInput(module, target, "{\"network\":\"kinetic\",\"source\":\"水车房\"}");
        assertEquals(Optional.of(new Target.Landmark("水车房")), fromLandmark.source());

        StepDecision malformed = decide(module, target, "{\"network\":\"kinetic\",\"source\":\"[1,2\"}");
        StepDecision.Finish rejected = assertInstanceOf(StepDecision.Finish.class, malformed);
        assertEquals(Problem.Kind.INVALID_PARAMETER, rejected.result().problem().kind());
    }

    private MachineConnectModule createModule() {
        DocumentStore store = new DocumentStore(tempDir.resolve("machine-connect.sqlite"));
        NetworkReader kinetic = new MachineTestDoubles.FakeNetworkReader("kinetic", "应力网");
        MachineServices services = new MachineServices(() -> null, new MachineTestDoubles.FakeWorld(),
                List.of(), List.of(kinetic), new MachineTestDoubles.FakeArea(),
                new RecipeLookup(List.of(), new GameRecipeTable(Optional::empty)),
                new MachineTestDoubles.FakeNeeds(), new MachineTestDoubles.FakeClose(),
                new MachineTestDoubles.FakeClicks(), new MachineTestDoubles.FakeSeen(),
                new MachineTestDoubles.FakePlaces());
        return new MachineConnectModule(
                services, new MachineArchives(store, "w1"), new WorldMemory(store, "w1"), permissions -> null);
    }

    private static MachineConnectInput runInput(MachineConnectModule module, Target target, String json) {
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class, decide(module, target, json));
        return assertInstanceOf(MachineConnectInput.class, run.input());
    }

    private static StepDecision decide(MachineConnectModule module, Target target, String json) {
        ParamValues params = module.spec().paramSpecs()
                .parse(JsonParser.parseString(json).getAsJsonObject())
                .params();
        Goal goal = Goal.of("maicraft:machine_connect", target, params);
        TickContext tick = new TickContext() {
            @Override public long gameTick() { return 1L; }
            @Override public PlayerContext player() { return null; }
        };
        return module.decide(new StepContext() {
            @Override public Goal goal() { return goal; }
            @Override public int stepIndex() { return 0; }
            @Override public TickContext tick() { return tick; }
        });
    }
}
