// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.interaction.spi.BreakAccelerator;
import org.maiwithu.maicraft.behavior.interaction.spi.ChainKeyHold;
import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.CompatRegistry;
import org.maiwithu.maicraft.compat.SupportedMod;
import org.maiwithu.maicraft.compat.VerifiedVersions;
import org.maiwithu.maicraft.game.loader.LoaderEnvironment;
import org.maiwithu.maicraft.game.world.FurnaceFuels;

/** 当前世界就绪模组能力清单（mod_features）的映射、连锁服务端监听检查与字母排序测试。 */
class ModFeaturesTest {

    private static SupportedMod<CompatModule> mod(String modId) {
        return new SupportedMod<>(modId, modId, new VerifiedVersions("1.0.0", "2.0.0"),
                () -> new CompatModule(modId, modId) {
                    @Override public void contribute(CompatRegistry registry) {}
                });
    }

    private static BreakAccelerator accelerator(boolean serverListens) {
        return new BreakAccelerator() {
            @Override public boolean available() { return serverListens; }
            @Override public Optional<ChainKeyHold> hold(ChainShape shape, Predicate<BlockPos> mayBreak) {
                return Optional.empty();
            }
        };
    }

    @Test
    void 映射协议标准代号并按字母排_服务端未监听连锁时不报chain_mining_无联动保留空列表() {
        assertTrue(ModFeatures.activeInWorld(CompatRegistry.empty(), List.of(), false).isEmpty(),
                "无联动模组时返回空列表");

        Map<String, String> loaded = Map.of(
                "sophisticatedbackpacks", "1.2.0",
                "ftbquests", "1.2.0",
                "create", "1.2.0",
                "ftbultimine", "1.2.0",
                "emi", "1.2.0");
        LoaderEnvironment loader = new LoaderEnvironment() {
            @Override public String loaderName() { return "neoforge"; }
            @Override public boolean isModLoaded(String modId) { return loaded.containsKey(modId); }
            @Override public Optional<String> modVersion(String modId) { return Optional.ofNullable(loaded.get(modId)); }
            @Override public Path gameDirectory() { return Path.of("."); }
            @Override public Path configDirectory() { return Path.of("."); }
            @Override public boolean isDevelopment() { return true; }
            @Override public FurnaceFuels furnaceFuels() { return itemId -> 0; }
        };
        CompatRegistry compat = CompatRegistry.load(List.of(
                mod("sophisticatedbackpacks"), mod("ftbquests"), mod("create"), mod("ftbultimine"), mod("emi")),
                loader);

        // 客户端登记了 FTB 连锁破坏，但当前连的原版服务器不接连锁包：chain_mining 不该出现。
        assertEquals(List.of("create", "ftb_quests", "sophisticated_backpacks"),
                ModFeatures.activeInWorld(compat, List.of(accelerator(false)), false));

        // 服务端接收连锁包且世界有 Lootr 容器标签：chain_mining 与 lootr 一并按字母排入列。
        assertEquals(List.of("chain_mining", "create", "ftb_quests", "lootr", "sophisticated_backpacks"),
                ModFeatures.activeInWorld(compat, List.of(accelerator(true)), true));
    }
}
