// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.compat;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.create.CreateCompat;
import org.maiwithu.maicraft.compat.mekanism.MekanismCompat;
import org.maiwithu.maicraft.compat.sable.SableCompat;
import org.maiwithu.maicraft.server.compat.ae2.Ae2ServerCompat;
import org.maiwithu.maicraft.server.compat.create.CreateServerCompat;
import org.maiwithu.maicraft.server.compat.mekanism.MekanismServerCompat;
import org.maiwithu.maicraft.server.compat.sable.SableServerCompat;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 联动模组的服务端只读操作：客户端问的名字和服务端登记的名字必须一样，不然握手时永远协商不到，
 * 角色只会说服务端答不了，实机前看不出来。
 */
class ServerReadNamesTest {

    @Test
    void 客户端问的名字和服务端登记的名字一样() {
        assertEquals(Ae2ServerCompat.OPERATION, Ae2Compat.NETWORK_OPERATION, "ME 网络读数");
        assertEquals(CreateServerCompat.TRAINS, CreateCompat.TRAINS_OPERATION, "列车时刻表");
        assertEquals(MekanismServerCompat.OPERATION, MekanismCompat.NETWORK_OPERATION, "通用机械管网存量");
        assertEquals(SableServerCompat.FORCES_OPERATION, SableCompat.FORCES_OPERATION, "物理结构受力");
    }
}
