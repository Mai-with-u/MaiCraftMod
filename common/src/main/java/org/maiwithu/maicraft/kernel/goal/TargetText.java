// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import java.util.Locale;
import java.util.Optional;

/**
 * 目标文本解析：把参数里与 {@code target} 同写法的字符串（如 {@code source}）解析成结构化的 {@link Target}。
 *
 * <p>当能力除了主目标 {@code target} 之外还需要第二个位置参数（例如接网络的来源端、从指定容器或区域往外整理搬运的来源处）时，
 * 统一按同一套规则认字面写法：{@code "here"} 为角色脚下，{@code e#/f#/b#/s#} 为观察编号，{@code [x,y,z]} 为整格坐标，
 * 其余非空且不带残缺方括号的文本按地标名处理；空白或写坏的方括号坐标返回空，由能力按参数错误拒绝。
 */
public final class TargetText {

    private TargetText() {}

    /**
     * 把一段文本解析为目标对象；空白字符串或格式错误的方括号坐标（如 {@code [1,2]}、{@code [a,b,c]}）返回空。
     */
    public static Optional<Target> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        // 原地关键字忽略大小写直接认成脚下位置，避免被当成名为 here 的地标去查记忆。
        if ("here".equalsIgnoreCase(trimmed)) {
            return Optional.of(new Target.Here());
        }
        // 观察编号统一归一化为小写，覆盖实体、地形特征、方块设施与物理结构四类合法前缀。
        if (trimmed.matches("(?i)[efbs]\\d+")) {
            return Optional.of(new Target.Seen(trimmed.toLowerCase(Locale.ROOT)));
        }
        // 只要带了方括号就按坐标语法严判：必须成对包住三个整数，残缺括号直接报错而不误认成地标名。
        if (trimmed.startsWith("[") || trimmed.endsWith("]")) {
            if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
                return Optional.empty();
            }
            String[] parts = trimmed.substring(1, trimmed.length() - 1).split(",", -1);
            if (parts.length != 3) {
                return Optional.empty();
            }
            try {
                int x = Integer.parseInt(parts[0].trim());
                int y = Integer.parseInt(parts[1].trim());
                int z = Integer.parseInt(parts[2].trim());
                return Optional.of(new Target.Position(x, y, z, null));
            } catch (NumberFormatException notCoordinates) {
                return Optional.empty();
            }
        }
        return Optional.of(new Target.Landmark(trimmed));
    }
}
