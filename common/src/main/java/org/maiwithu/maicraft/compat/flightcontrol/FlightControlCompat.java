// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.flightcontrol;

import java.util.Objects;

import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.CompatRegistry;

/**
 * Create: Flight Control 的联动入口：装在物理结构上的高度稳定器、智能姿态稳定器、线速度耦合器与飞控红石代理。
 * 交给登记表一样东西——读写这些稳定器的办法：看载具时把它们列进控制回路，驾驶飞艇时设目标高度、经代理给方向。
 * 模组没装时这一行不登记，看载具与驾驶照旧。每一下读写都经联动入口包一层。
 */
public final class FlightControlCompat extends CompatModule {

    public static final String MOD_ID = "create_flight_control";

    private final StabilizerParts parts;

    public FlightControlCompat(StabilizerParts parts) {
        super(MOD_ID, "Create: Flight Control 飞行稳定器");
        this.parts = Objects.requireNonNull(parts, "parts");
    }

    @Override public void contribute(CompatRegistry registry) {
        // 读写飞行稳定器：读要角色所在的客户端世界，进世界时带着玩家行为建。
        registry.flightStabilizers(this, services -> new StabilizerReading(this, services));
    }

    StabilizerParts parts() {
        return parts;
    }
}
