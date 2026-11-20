// SPDX-License-Identifier: GPL-3.0-only
/**
 * 应用能源2 线缆部件按面配置、元件工作台、ME IO 传输站与无线访问点增幅卡适配。
 *
 * <p>单向依赖 {@code compat.ae2} 包根的共享入口、设备委托接缝与槽位语义证明，
 * 不直接引用任何模组类；需要调用 AE2 公开接口的读写动作统一经 {@link Ae2PartControl}
 * 交由 NeoForge 读写端完成。
 */
package org.maiwithu.maicraft.compat.ae2.part;
