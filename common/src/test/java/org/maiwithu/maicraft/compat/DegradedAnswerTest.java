// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * 降级回答这一份共用逻辑本身：模组还开着就照常读并只读一次，停用了不碰模组直接给"用不了"的答案，
 * 读到接口对不上也交同一个答案；带原因的回答按停用与否给对应的那句原因。
 */
class DegradedAnswerTest {

    private static CompatModule module() {
        return new CompatModule("testmod", "测试模组") {
            @Override public void contribute(CompatRegistry registry) {
            }
        };
    }

    private static void disable(CompatModule module) {
        assertThrows(ModApiMismatch.class, () -> module.call("触发停用", () -> {
            throw new NoSuchMethodError("test.missingMethod");
        }));
    }

    @Test
    void 模组还开着就照常读且只读一次() {
        CompatModule module = module();
        AtomicInteger reads = new AtomicInteger();
        assertEquals("读到的东西", DegradedAnswer.ask(module, () -> {
            reads.incrementAndGet();
            return "读到的东西";
        }, "用不了"));
        assertEquals(1, reads.get());
    }

    @Test
    void 模组停用后不碰模组直接给默认答案() {
        CompatModule module = module();
        disable(module);
        AtomicInteger reads = new AtomicInteger();
        assertEquals(7, DegradedAnswer.ask(module, () -> {
            reads.incrementAndGet();
            return 1;
        }, 7));
        assertEquals(0, reads.get(), "停用后不能再碰模组");
        assertTrue(DegradedAnswer.ask(module, () -> Optional.of("有"), Optional.<String>empty()).isEmpty());
        assertTrue(DegradedAnswer.ask(module, () -> List.of("不该给"), List.<String>of()).isEmpty());
        assertNull(DegradedAnswer.ask(module, () -> "有", (String) null));
    }

    @Test
    void 读到接口对不上时给默认答案不再往外抛() {
        CompatModule module = module();
        assertEquals(false, DegradedAnswer.ask(module, () -> {
            throw new ModApiMismatch("读的时候对不上");
        }, false));
    }

    @Test
    void 带原因的回答停用时给停用原因_对不上时给对不上的说明() {
        CompatModule module = module();
        assertEquals("答不出：读压机时模组的类或方法对不上", DegradedAnswer.ask(module, () -> {
            throw new ModApiMismatch("读压机时模组的类或方法对不上");
        }, why -> "答不出：" + why));

        disable(module);
        String answer = DegradedAnswer.ask(module, () -> "读到了", why -> "答不出：" + why);
        assertEquals("答不出：测试模组的联动已停用：触发停用时模组的类或方法对不上：java.lang.NoSuchMethodError: test.missingMethod",
                answer);
        assertEquals("测试模组的联动已停用：触发停用时模组的类或方法对不上：java.lang.NoSuchMethodError: test.missingMethod",
                DegradedAnswer.reason(module));
    }

    @Test
    void 不回答什么的动作停用了就不做_对不上时收住不往外抛() {
        CompatModule module = module();
        AtomicInteger runs = new AtomicInteger();
        DegradedAnswer.run(module, runs::incrementAndGet);
        assertEquals(1, runs.get());

        DegradedAnswer.run(module, () -> {
            throw new ModApiMismatch("做的时候对不上");
        });

        disable(module);
        DegradedAnswer.run(module, runs::incrementAndGet);
        assertEquals(1, runs.get(), "停用后不能再做");
        assertFalse(module.active());
    }
}
