// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.compat.extendedae;

import java.util.Objects;

import org.maiwithu.maicraft.server.compat.ServerCompatModule;
import org.maiwithu.maicraft.server.compat.ServerCompatRegistry;

/**
 * AE2 扩展（ExtendedAE）服务端联动入口：登记只读操作 {@code extendedae.device}（版本 1），
 * 为客户端补全不开窗不可见的储罐、进度、无线对端与组装矩阵集群统计。
 */
public final class ExtendedAeServerCompat extends ServerCompatModule {

    public static final String MOD_ID = "extendedae";
    public static final String OPERATION = ExtendedAeDeviceQuery.OPERATION;

    private final ExtendedAeDeviceQuery.Reader deviceReader;

    public ExtendedAeServerCompat(ExtendedAeDeviceQuery.Reader deviceReader) {
        super(MOD_ID, "AE2 扩展");
        this.deviceReader = Objects.requireNonNull(deviceReader, "deviceReader");
    }

    @Override public void contribute(ServerCompatRegistry registry) {
        ExtendedAeDeviceQuery.register(this, registry, deviceReader);
    }
}
