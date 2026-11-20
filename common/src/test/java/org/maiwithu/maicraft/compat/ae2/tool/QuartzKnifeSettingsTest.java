// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * AE2 石英切割刀设置离线测试：
 * 验证两种切割刀识别、engrave_name 设置键暴露、金属锭标签与读端判定、非法键拒收。
 */
class QuartzKnifeSettingsTest {

    @BeforeAll
    static void 引导注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 识别赛特斯与下界石英切割刀并暴露刻名键() {
        QuartzKnifeSettings settings = new QuartzKnifeSettings(
                ToolTestFakes.compat(),
                Ae2ToolReads.NONE,
                ToolTestFakes.services(() -> null, Map.of(), List.of()),
                stack -> stack.is(Items.IRON_SWORD) ? QuartzKnifeSettings.CERTUS_KNIFE
                        : stack.is(Items.DIAMOND_SWORD) ? QuartzKnifeSettings.NETHER_KNIFE
                        : PortableCellCatalog.idOf(stack));

        ItemStack certus = new ItemStack(Items.IRON_SWORD);
        ItemStack nether = new ItemStack(Items.DIAMOND_SWORD);
        assertTrue(settings.covers(certus));
        assertTrue(settings.covers(nether));
        assertFalse(settings.covers(new ItemStack(Items.STICK)));

        List<MachineSetting> list = settings.settings(certus);
        assertEquals(1, list.size());
        assertEquals(QuartzKnifeSettings.KEY_ENGRAVE_NAME, list.getFirst().key());
        assertTrue(list.getFirst().description().contains("ae2:name_press"));

        // 无角色上下文的三参 holds 恒返回 false，必须走带角色背包的重载核对产出。
        assertFalse(settings.holds(certus, QuartzKnifeSettings.KEY_ENGRAVE_NAME, "iron_gear"));
        assertFalse(settings.holds(certus, null, QuartzKnifeSettings.KEY_ENGRAVE_NAME, "iron_gear"));
    }

    @Test
    void 金属锭判定同时兼容物品标签与模组读端且未知设置键被拒() {
        Ae2ToolReads reads = new Ae2ToolReads() {
            @Override public boolean isMetalIngot(ItemStack stack) {
                return stack.is(Items.GOLD_INGOT);
            }
        };
        QuartzKnifeSettings settings = new QuartzKnifeSettings(
                ToolTestFakes.compat(),
                reads,
                ToolTestFakes.services(() -> null,
                        Map.of("minecraft:iron_ingot", Set.of(QuartzKnifeSettings.METAL_INGOTS_TAG)),
                        List.of()));

        assertTrue(settings.isMetalIngot(new ItemStack(Items.IRON_INGOT)), "标签命中的铁锭应认作金属锭");
        assertTrue(settings.isMetalIngot(new ItemStack(Items.GOLD_INGOT)), "模组读端命中的金锭应认作金属锭");
        assertFalse(settings.isMetalIngot(new ItemStack(Items.COBBLESTONE)));

        assertTrue(settings.change(0, Map.of("unknown", "abc"), Permissions.DEFAULT).isEmpty());
        Optional<Action> action = settings.change(0, Map.of(QuartzKnifeSettings.KEY_ENGRAVE_NAME, "my_press"),
                Permissions.DEFAULT);
        assertTrue(action.isPresent());
        assertInstanceOf(Action.class, action.get());
        assertTrue(action.get().describe().contains("my_press"));
    }

    @Test
    void 放锭阶段空槽不跳刻名且取模板仅发一次快速移动防连发() {
        // 光标非空先往输入槽放 1 锭。
        assertEquals(QuartzKnifeSettings.PlaceDecision.PLACE_ONE,
                QuartzKnifeSettings.decidePlaceStep(false, false, 0));
        // 光标为空但输入槽仍空（上一步拿起未生效）：不得跳 SEND_NAME，必须回退重试并在超限后失败。
        assertEquals(QuartzKnifeSettings.PlaceDecision.RETRY_PICK,
                QuartzKnifeSettings.decidePlaceStep(true, false, 0));
        assertEquals(QuartzKnifeSettings.PlaceDecision.FAIL_PICK,
                QuartzKnifeSettings.decidePlaceStep(true, false, QuartzKnifeSettings.MAX_PICK_ATTEMPTS));
        // 仅当光标为空且输入槽确认已有金属锭时才放行 SEND_NAME。
        assertEquals(QuartzKnifeSettings.PlaceDecision.SEND_NAME,
                QuartzKnifeSettings.decidePlaceStep(true, true, 0));

        // 输出槽首次出现模板且尚未发取货：发一次 quickMove。
        assertEquals(QuartzKnifeSettings.TakeDecision.SEND_QUICK_MOVE,
                QuartzKnifeSettings.decideTakeStep(false, false, true, 1));
        // 已发过一次 quickMove（takeSent=true）后，即使输入槽有余锭导致输出槽依然非空，也绝不连发第二次。
        assertEquals(QuartzKnifeSettings.TakeDecision.WAIT_SYNC,
                QuartzKnifeSettings.decideTakeStep(false, true, true, 2));
        // 背包同步到命名压印模板后立即转入清槽收尾。
        assertEquals(QuartzKnifeSettings.TakeDecision.DONE_RECOVER,
                QuartzKnifeSettings.decideTakeStep(true, true, true, 3));
        // 已发取货但超时仍未同步到背包时明确超时退出。
        assertEquals(QuartzKnifeSettings.TakeDecision.TIMEOUT_UNSYNCED,
                QuartzKnifeSettings.decideTakeStep(false, true, true, 31));
    }
}
