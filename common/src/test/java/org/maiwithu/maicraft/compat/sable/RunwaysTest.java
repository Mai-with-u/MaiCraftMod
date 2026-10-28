// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

/**
 * 跑道：起飞量机头前方畅通多远；落地要的长度按实测滑跑距离；一条地面要平、不是水、都加载着；
 * 下滑线下面要空；候选先近后远、朝向和飞来的方向顺的先试；迫降挑最长的一片平地。
 */
class RunwaysTest {

    /** 一片地：默认处处 y=64 的平地，可以垫高、改成水、挖成没加载。 */
    private static final class Field implements LandingSites.Ground {
        final Map<Long, Integer> tops = new HashMap<>();
        final Set<Long> water = new HashSet<>();
        final Set<Long> unloaded = new HashSet<>();

        static long key(int x, int z) {
            return ((long) x << 32) ^ (z & 0xffffffffL);
        }

        void raise(int x0, int z0, int x1, int z1, int top) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) tops.put(key(x, z), top);
            }
        }

        @Override public Integer top(int x, int z) {
            if (unloaded.contains(key(x, z))) return null;
            return tops.getOrDefault(key(x, z), 64);
        }

        @Override public boolean liquid(int x, int z) {
            return water.contains(key(x, z));
        }
    }

    @Test
    void 落地要多长按实测滑跑距离() {
        assertEquals(48, Runways.needed(10, 8), 1e-9, "至少 48 格");
        assertEquals(2 * 40 + 8, Runways.needed(40, 8), 1e-9);
        assertEquals(Runways.UNMEASURED_LENGTH, Runways.needed(Double.NaN, 8), 1e-9, "空中接手没实测按 96 格");
    }

    @Test
    void 机头前方畅通到墙为止_没加载和水都算到头() {
        Field field = new Field();
        field.raise(30, -10, 31, 10, 67);
        // 朝东（270）从 x=0 量：墙在 x=30。
        double clear = Runways.clearAhead(new Vec3(0.5, 64, 0.5), 270, 6, 200, 64, false, field);
        assertTrue(clear >= 28 && clear <= 30, "到墙为止：" + clear);
        Field pond = new Field();
        for (int z = -5; z <= 5; z++) pond.water.add(Field.key(12, z));
        assertTrue(Runways.clearAhead(new Vec3(0.5, 64, 0.5), 270, 6, 200, 64, false, pond) <= 12, "水算到头");
        assertEquals(200, Runways.clearAhead(new Vec3(0.5, 64, 0.5), 270, 6, 200, 64, true, pond), 1e-9,
                "浮在水上起飞的水面也算跑道");
        Field dark = new Field();
        dark.unloaded.add(Field.key(20, 0));
        assertTrue(Runways.clearAhead(new Vec3(0.5, 64, 0.5), 270, 6, 200, 64, false, dark) <= 20, "没加载的不当畅通");
    }

    @Test
    void 一条地面要平_下滑线下面要空() {
        Field field = new Field();
        Runway runway = new Runway(new Vec3(0, 64, 0), 270, 60, 12, false);
        assertEquals(64, Runways.surface(runway.threshold(), 270, 60, 12, field), 1e-9);
        assertTrue(Runways.approachClear(runway, field));
        // 跑道头往外 60 格有一棵 8 格高的树：下滑线在那里只高 6 格，挡着。
        field.raise(-61, -1, -59, 1, 72);
        assertFalse(Runways.approachClear(runway, field));
        Field bumpy = new Field();
        bumpy.raise(20, -2, 22, 2, 66);
        assertTrue(Double.isNaN(Runways.surface(runway.threshold(), 270, 60, 12, bumpy)), "高低差 2 格不平");
        assertTrue(Runways.blocked(runway, bumpy), "选好的跑道上长出了东西");
    }

    @Test
    void 候选先近后远_朝向和飞来的方向顺的先试() {
        Field field = new Field();
        Runways.Search search = new Runways.Search(new Vec3(0, 64, 0), 32, 8, 90, 48, 12, false, field);
        Optional<Runway> first = search.advance(1);
        assertTrue(first.isPresent());
        assertEquals(90, first.get().heading(), 1e-9, "飞来的方向是西（90），先试朝西的");
        assertEquals(0, first.get().at(first.get().length() / 2, 0).x, 1e-6, "最近的候选就在目的地");
        assertTrue(search.advance(1).isPresent(), "接着往下试就是下一条");
    }

    @Test
    void 附近都不平就试完_迫降挑最长的一片平地() {
        Field field = new Field();
        // 整片地横竖每隔 24 格一道 3 格高的埂：要 48 格的跑道哪个方向都摆不下，埂之间的平地有二十来格。
        for (int line = -240; line <= 240; line += 24) {
            field.raise(line, -240, line, 240, 67);
            field.raise(-240, line, 240, line, 67);
        }
        Runways.Search search = new Runways.Search(new Vec3(12, 64, 12), 32, 16, 270, 48, 6, true, field);
        Optional<Runway> found = Optional.empty();
        while (!search.exhausted() && found.isEmpty()) found = search.advance(16);
        assertTrue(found.isEmpty(), "埂太密，没有 48 格的跑道");
        assertTrue(search.exhausted());
        Runway forced = search.longest().orElseThrow();
        assertTrue(forced.forced());
        assertTrue(forced.length() >= Runways.MIN_FORCED && forced.length() < 48, "埂之间最长的那一片：" + forced.length());
    }
}
