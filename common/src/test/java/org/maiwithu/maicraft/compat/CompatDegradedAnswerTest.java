// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.inventory.spi.CarriedBackpack;
import org.maiwithu.maicraft.behavior.travel.spi.OfferSink;
import org.maiwithu.maicraft.behavior.travel.spi.TravelLeg;
import org.maiwithu.maicraft.behavior.travel.spi.TravelMode;
import org.maiwithu.maicraft.behavior.travel.spi.TravelOffer;
import org.maiwithu.maicraft.behavior.travel.spi.TravelRequest;
import org.maiwithu.maicraft.behavior.travel.spi.TravelVia;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeDocument;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeSource;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 同一份降级回答罩住几条接缝：模组停用后，物品来源问价答不支持、随身背包答放不下、
 * 知识来源目录为空且状态写明原因、出行方式不报价也不认领、开跑的一段第一刻按不支持收场；
 * 这些回答都走 compat 包共用的 DegradedAnswer，模组装错版本不变成内部错误。
 */
class CompatDegradedAnswerTest {

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

    /** 停用后包装层不该再碰模组来源：替身一律抛错，被碰到就会让测试红。 */
    private static final class Untouched {
        void touch() {
            throw new AssertionError("停用后不该再碰模组的来源");
        }
    }

    @Test
    void 停用后物品来源问价答不支持_动手给空() {
        CompatModule module = module();
        Untouched untouched = new Untouched();
        ItemSource source = new ItemSource() {
            @Override public String describe() { return "测试来源"; }
            @Override public AcquireVia via() { throw new AssertionError(); }
            @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
                untouched.touch();
                return null;
            }
            @Override public Optional<org.maiwithu.maicraft.kernel.task.Action> begin(
                    ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
                untouched.touch();
                return Optional.empty();
            }
        };
        ItemSource wrapped = new CompatItemSource(module, source);

        disable(module);
        SourceQuote quote = wrapped.quote(null, null);
        SourceQuote.Unsupported unsupported = assertInstanceOf(SourceQuote.Unsupported.class, quote);
        assertEquals("测试来源", unsupported.source());
        assertEquals("测试模组的联动已停用：触发停用时模组的类或方法对不上：java.lang.NoSuchMethodError: test.missingMethod",
                unsupported.reason());
        assertTrue(wrapped.begin(null, null, null).isEmpty(), "停用后开始执行交回空，引擎换路");
        assertTrue(wrapped.look(null, null).isEmpty());
    }

    @Test
    void 停用后随身背包答放不下() {
        CompatModule module = module();
        Untouched untouched = new Untouched();
        CarriedBackpack backpack = new CarriedBackpack() {
            @Override public int freeSlots() {
                untouched.touch();
                return 5;
            }
            @Override public Optional<org.maiwithu.maicraft.kernel.task.Action> store(
                    org.maiwithu.maicraft.game.player.BackpackStack stack,
                    org.maiwithu.maicraft.kernel.task.TaskRecords records) {
                untouched.touch();
                return Optional.empty();
            }
        };
        CarriedBackpack wrapped = new CompatCarriedBackpack(module, backpack);

        disable(module);
        assertEquals(0, wrapped.freeSlots(), "停用后空槽答 0");
        assertTrue(wrapped.store(null, null).isEmpty(), "停用后存放交回空，腾地方跳过这一步");
        assertTrue(wrapped.carried().isEmpty());
    }

    @Test
    void 停用后知识来源目录为空_状态写明原因() {
        CompatModule module = module();
        Untouched untouched = new Untouched();
        KnowledgeSource source = new KnowledgeSource() {
            @Override public List<KnowledgeDocument.Entry> entries() {
                untouched.touch();
                return List.of();
            }
            @Override public KnowledgeDocument read(String uri) {
                untouched.touch();
                return null;
            }
            @Override public String status() {
                untouched.touch();
                return "测试思索：可用";
            }
        };
        KnowledgeSource wrapped = new CompatKnowledgeSource(module, source);

        disable(module);
        assertTrue(wrapped.entries().isEmpty(), "停用后目录为空");
        assertNull(wrapped.read("test://whatever"));
        assertTrue(wrapped.searchCandidates("查询词").isEmpty());
        assertEquals("测试模组：联动已停用（触发停用时模组的类或方法对不上：java.lang.NoSuchMethodError: test.missingMethod）",
                wrapped.status(), "状态写明停用原因，空结果不冒充没有这回事");
    }

    @Test
    void 停用后出行方式不报价_不认领_开跑的第一刻按不支持收场() {
        CompatModule module = module();
        Untouched untouched = new Untouched();
        TravelMode mode = new TravelMode() {
            // 取值与参数表在包装类构造时读一次，停用与否都要给。
            @Override public TravelVia via() { return new TravelVia("testmode", "测试的出行方式"); }
            @Override public void offer(TravelRequest request, OfferSink sink) {
                untouched.touch();
            }
            @Override public boolean claims(TravelRequest request) {
                untouched.touch();
                return true;
            }
            @Override public TravelLeg start(TravelRequest request, TravelOffer offer) {
                untouched.touch();
                return null;
            }
        };
        TravelMode wrapped = new CompatTravelMode(module, mode);

        disable(module);
        AtomicReference<String> decline = new AtomicReference<>();
        wrapped.offer(null, new OfferSink() {
            @Override public void offer(TravelOffer offer) {
                untouched.touch();
            }
            @Override public void decline(Problem.Kind kind, String reason) {
                assertEquals(Problem.Kind.UNSUPPORTED, kind);
                decline.set(reason);
            }
        });
        assertEquals("测试模组的联动已停用：触发停用时模组的类或方法对不上：java.lang.NoSuchMethodError: test.missingMethod",
                decline.get(), "不报价，原因写明是哪个模组");
        assertFalse(wrapped.claims(null), "停用后不认领，不拦着别的方式");
        TravelLeg leg = wrapped.start(null, null);
        Problem problem = assertInstanceOf(ActionStatus.Failed.class, leg.tick((TickContext) null)).problem();
        assertEquals(Problem.Kind.UNSUPPORTED, problem.kind());
        assertTrue(leg.describe().contains("测试模组的联动已停用"), leg.describe());
    }
}
