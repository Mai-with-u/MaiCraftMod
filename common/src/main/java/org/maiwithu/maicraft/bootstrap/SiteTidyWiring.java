// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import org.maiwithu.maicraft.ability.deposit.DepositModule;
import org.maiwithu.maicraft.ability.fillpit.FillPitModule;
import org.maiwithu.maicraft.ability.loot.LootModule;
import org.maiwithu.maicraft.behavior.interaction.LiveHoldsSneak;

/**
 * 营地与容器整理相关能力的组装：存东西、搜刮战利品容器与用现成废料填平地面坑。
 */
final class SiteTidyWiring {

    private SiteTidyWiring() {}

    static DepositModule depositModule(AbilityCatalog.Deps deps, AbilityCatalog.Shared shared) {
        return DepositModule.live(deps.character().context(), shared.storing(), shared.collects(),
                deps.items().itemTags(), deps.world().memory(), deps.character().backpack(),
                deps.world().blockScans(), deps.guards().protection(), deps.world()::scene,
                deps.world().facilities(), () -> shared.networks().stream().findFirst(), deps.kept(),
                deps.compat().menuLayouts(), shared.bringClose(), deps.hands().interactions(), shared.toMainhand(),
                new LiveHoldsSneak(deps.hands().inputs(), deps.character().context()));
    }

    static LootModule lootModule(AbilityCatalog.Deps deps, AbilityCatalog.Shared shared) {
        return LootModule.live(deps.character().context(), deps.world()::scene, deps.world().memory(),
                deps.world().blockScans(), deps.guards().protection(), deps.world().facilities(), shared.collects(),
                deps.compat().menuLayouts(), shared.bringClose(), deps.hands().interactions(), shared.toMainhand(),
                new LiveHoldsSneak(deps.hands().inputs(), deps.character().context()), deps.kept(),
                deps.guards().rareLootNotice());
    }

    static FillPitModule fillPitModule(AbilityCatalog.Deps deps, AbilityCatalog.Shared shared) {
        return FillPitModule.live(deps.world()::scene, deps.world().memory(), deps.guards().protection(),
                deps.world().campPits(), deps.travel().walks(), deps.hands().interactions(),
                shared.toMainhand(), deps.kept());
    }
}
