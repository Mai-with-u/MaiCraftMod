// SPDX-License-Identifier: GPL-3.0-only
/**
 * 应用能源2（AE2）的联动：角色从附近的 ME 终端取网络里已有的东西，也往网络里存东西、
 * 在终端前看网络存货与下单合成，还没绑定访问点的无线终端先绑再取。
 * 联动入口 Ae2Compat 把各途径的来源（ME 终端来源、压印器工作格单取、转化配方、机器类型、网络读取器）、
 * 界面布局证明与任务交给登记表。读写接缝（找终端、终端界面、网格设备、样板、转化配方）与
 * 共享模型（设备委托接缝 Ae2DeviceOps 与汇总路由、槽位语义 Ae2MenuSlots、界面动作骨架 Ae2MenuWork、
 * 设备目录 Ae2MachineCatalog）都在包根，各子包只指向包根。
 *
 * <p>读写接缝只用 Minecraft 与 Java 的类型描述要读什么、点什么；直接调用 AE2 类的读写端在 NeoForge 模块里。
 * 压印器一族在子包 inscriber；机器类型、网络读数、线缆施工、样板供应器与设备界面归 machine/；
 * 手持工具、部件、量子与空间在既有 tool/part/advanced。
 */
package org.maiwithu.maicraft.compat.ae2;
