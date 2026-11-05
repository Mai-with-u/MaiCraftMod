// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.suicide;

import java.util.List;

/**
 * 死法：七种，参数 {@code via} 的取值同名。档位是不指定死法时挑的先后——
 * 服务器给了寻死命令就先发命令（一下就死，不用走路、不脱甲），
 * 再是死得快、几乎不失手的（岩浆、摔得死的落差），原地就能点的火其次，慢又可能被打断的再次（仙人掌、敌对生物），
 * 倒出来收不回去的岩浆桶最后。
 */
enum WayToDie {
    COMMAND("command", "寻死命令", 0),
    LAVA("lava", "现成岩浆", 1),
    FALL("fall", "摔得死的落差", 1),
    FIRE("fire", "随身点火", 2),
    CACTUS("cactus", "仙人掌", 3),
    HOSTILE_MOB("hostile_mob", "敌对生物", 3),
    LAVA_BUCKET("lava_bucket", "随身倒岩浆", 4);

    /** 点火用的东西：打火石有耐久能反复点，排在火焰弹前面。 */
    static final List<String> IGNITERS = List.of("minecraft:flint_and_steel", "minecraft:fire_charge");
    static final String LAVA_BUCKET_ITEM = "minecraft:lava_bucket";

    final String param;
    final String label;
    /** 不指定死法时第几档挑它；数小的先。 */
    final int tier;

    WayToDie(String param, String label, int tier) {
        this.param = param;
        this.label = label;
        this.tier = tier;
    }

    /** 用身上带的东西在身边造出来的（点火、倒岩浆），不是去找现成的；要放方块，许可不让动方块时不用。 */
    boolean carried() {
        return this == FIRE || this == LAVA_BUCKET;
    }

    /** 去找、去造一处能死的地方的死法：除了发命令都是，找死法的扫描只管这几种。 */
    boolean needsSpot() {
        return this != COMMAND;
    }

    /** 扫地形找得到的（岩浆、落差、仙人掌）。 */
    boolean terrain() {
        return this == LAVA || this == FALL || this == CACTUS;
    }

    /** 抗火时伤不到人的死法。 */
    boolean burns() {
        return this == LAVA || this == FIRE || this == LAVA_BUCKET;
    }

    /** 参数取值对应的死法；没有这个取值为 null。 */
    static WayToDie fromParam(String value) {
        for (WayToDie way : values()) {
            if (way.param.equals(value)) return way;
        }
        return null;
    }

    /** 全部取值，给参数规格列可选值。 */
    static String[] params() {
        WayToDie[] all = values();
        String[] params = new String[all.length];
        for (int i = 0; i < all.length; i++) params[i] = all[i].param;
        return params;
    }
}
