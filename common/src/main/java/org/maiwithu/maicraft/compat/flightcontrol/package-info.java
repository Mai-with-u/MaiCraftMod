// SPDX-License-Identifier: GPL-3.0-only
/**
 * Create: Flight Control 的联动：装在物理结构上的高度稳定器、智能姿态稳定器、线速度耦合器与飞控红石代理。
 * 联动入口 FlightControlCompat 把读写飞行稳定器的办法交给登记表，看载具时列进控制回路，驾驶飞艇时设目标高度、
 * 经代理给方向。
 *
 * <p>读写接缝 StabilizerParts 只用 Minecraft 与 Java 的类型；直接调用 Flight Control 类的读写端在 NeoForge 模块里。
 */
package org.maiwithu.maicraft.compat.flightcontrol;
