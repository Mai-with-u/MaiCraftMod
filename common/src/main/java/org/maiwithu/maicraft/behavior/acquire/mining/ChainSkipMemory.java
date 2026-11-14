// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 同一次采掘批次或挖掘通道内的连锁拒绝记忆：换一格也不会变的拒绝原因只判一次，后续直接走单挖，
 * 不在每一格前面走一遍“按下连锁键 → 等服务器回选区 → 松开连锁键”白白浪费时间。
 *
 * <p>按现场条件分档恢复：
 * <ul>
 *   <li>全局原因（没装连锁、F1 藏界面、工具耐久不够一整批、按键等待超时）整段动作都不再按连锁键；</li>
 *   <li>沿直线或斜线通道推进时，前方固定障碍（远端区块未加载、紧挨水或岩浆、碰到设施或不可挖格、超出允许范围）
 *       在当前区块同一朝向与斜平段内跳过按键，跨进下一区块或换方向后重新试一次；</li>
 *   <li>成片连通采掘（{@code heading == null}）同一小批都是相连同种方块，首格被非瞬时原因拒绝后本批剩余几格直接单挖；</li>
 *   <li>手上工具挖不下选区里的硬方块（例如表层泥土斜线伸进下层石头）在触发格换成另一种方块之前跳过按键，挖进石头层换镐后自动恢复。</li>
 * </ul>
 */
final class ChainSkipMemory {

    private String wholeRunReason;
    private String corridorReason;
    private boolean hasCorridorKey;
    private long corridorChunkKey;
    private Direction corridorHeading;
    private boolean corridorDown;
    private String unharvestableReason;
    private String unharvestableBlockType;

    /** 若当前格命中了换一格也不会变的已知拒绝条件，直接返回原因跳过按键；返回空表示值得试一次连锁。 */
    Optional<String> skipReason(BlockPos feet, Direction heading, boolean down, String triggerBlockType) {
        if (wholeRunReason != null) return Optional.of(wholeRunReason);
        if (corridorReason != null) {
            if (heading == null || feet == null) return Optional.of(corridorReason);
            if (hasCorridorKey && chunkKey(feet) == corridorChunkKey
                    && heading == corridorHeading && down == corridorDown) {
                return Optional.of(corridorReason);
            }
            corridorReason = null;
            hasCorridorKey = false;
        }
        if (unharvestableReason != null) {
            if (triggerBlockType == null || triggerBlockType.equals(unharvestableBlockType)) {
                return Optional.of(unharvestableReason);
            }
            unharvestableReason = null;
            unharvestableBlockType = null;
        }
        return Optional.empty();
    }

    /** 刚尝试了一次连锁却退回单挖：若是换一格也不会变的原因就记下来。 */
    void noteRefusal(String rawReason, BlockPos feet, Direction heading, boolean down, String triggerBlockType) {
        if (rawReason == null || rawReason.isBlank()) return;
        if (isWholeRunRefusal(rawReason)) {
            wholeRunReason = rawReason;
            return;
        }
        if (rawReason.contains("工具挖不下")) {
            if (heading == null) {
                wholeRunReason = rawReason;
            } else if (triggerBlockType != null) {
                unharvestableReason = rawReason;
                unharvestableBlockType = triggerBlockType;
            }
            return;
        }
        if (isCorridorRefusal(rawReason) || (heading == null && rawReason.contains("只连得到一格"))) {
            if (heading == null) {
                wholeRunReason = rawReason;
                return;
            }
            corridorReason = rawReason;
            if (feet != null) {
                hasCorridorKey = true;
                corridorChunkKey = chunkKey(feet);
                corridorHeading = heading;
                corridorDown = down;
            }
        }
    }

    private static boolean isWholeRunRefusal(String raw) {
        return raw.contains("界面被藏起来")
                || raw.contains("服务器没装")
                || raw.contains("没有连锁挖")
                || raw.contains("FTB 里没有")
                || raw.contains("耐久")
                || raw.contains("主播一直按着")
                || raw.contains("等了")
                || raw.contains("超时未挥出");
    }

    private static boolean isCorridorRefusal(String raw) {
        return raw.contains("没加载")
                || raw.contains("水或岩浆")
                || raw.contains("设施")
                || raw.contains("挖不动")
                || raw.contains("不在这次该挖的格里");
    }

    private static long chunkKey(BlockPos pos) {
        return (((long) (pos.getX() >> 4)) << 32) | ((pos.getZ() >> 4) & 0xFFFFFFFFL);
    }
}
