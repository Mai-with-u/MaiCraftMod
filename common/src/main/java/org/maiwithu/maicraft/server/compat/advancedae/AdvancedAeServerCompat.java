// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.compat.advancedae;

import java.util.Objects;

import org.maiwithu.maicraft.server.compat.ServerCompatModule;
import org.maiwithu.maicraft.server.compat.ServerCompatRegistry;

/**
 * 高级 AE（AdvancedAE）的服务端联动入口：注册只读操作 {@code advanced_ae.device}（版本 1），
 * 仅在角色同维已加载 128 格范围内回答反应仓的权威状态，不修改世界，不依赖其他联动模组包。
 */
public final class AdvancedAeServerCompat extends ServerCompatModule {

    /** 模组注册 ID。 */
    public static final String MOD_ID = "advanced_ae";
    /** 单格设备权威只读操作名。 */
    public static final String DEVICE_OPERATION = AdvancedAeServerReads.OPERATION;

    private final AdvancedAeServerReads.Reader deviceReads;

    public AdvancedAeServerCompat(AdvancedAeServerReads.Reader deviceReads) {
        super(MOD_ID, "高级 AE");
        this.deviceReads = Objects.requireNonNull(deviceReads, "deviceReads");
    }

    @Override public void contribute(ServerCompatRegistry registry) {
        AdvancedAeServerReads.register(this, registry, deviceReads);
    }
}
