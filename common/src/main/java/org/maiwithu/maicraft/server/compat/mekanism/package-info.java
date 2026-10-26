// SPDX-License-Identifier: GPL-3.0-only
/**
 * 通用机械（Mekanism）在服务端的联动：只读操作 mekanism.network 回答某一格管道所在那张管网的汇总
 * （几段管道、连着几处机器、存着多少电或流体、化学品）。读写接缝 MekanismNetworkReads 只用 Minecraft 与 Java 的类型，
 * 直接调 Mekanism 网络对象的读写端在 NeoForge 模块里。
 */
package org.maiwithu.maicraft.server.compat.mekanism;
