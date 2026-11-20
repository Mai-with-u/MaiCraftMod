// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.spi.MenuLayoutProof;

/**
 * 压印器界面的布局证明：优先经 {@link Ae2MenuSlots} 按 AE2 槽位语义识别四个工作格（顶模板、底模板、
 * 中间输入、输出）与四个升级槽，自动跳过玩家携带网络工具箱时动态插入的 9 个 TOOLBOX 槽；
 * 在无语义读写端的离线替身场景下回退兼容原 8 槽位置布局。
 *
 * <p>容器侧规范化重排为「前四格工作格、后四格升级槽」，使容器侧 0..3 与压印器方块实体内部库存 0..3
 * 严格对齐；八格全部按机器格核对，避免当普通存取箱乱搬。
 */
public final class Ae2InscriberLayout implements MenuLayoutProof {

    /** 压印器点开的界面。这里是菜单类型的注册 ID，不是方块 ID。类别：游戏事实（AE2 菜单类型注册，InscriberMenu）。 */
    static final String INSCRIBER_MENU = "ae2:inscriber";

    /** 原版角色背包槽位总数：27 格主背包加 9 格快捷栏。F 游戏事实。 */
    private static final int PLAYER_INVENTORY_SLOTS = 36;
    /** 界面容器侧总格数：四个工作格加四个升级格。 */
    private static final int INSCRIBER_SLOTS = 8;
    private static final int WORK_SLOTS = 4;
    private static final int UPGRADE_SLOTS = 4;
    /** 压印器四个工作格在 AE2 里的固定语义顺序：顶模板、底模板、中间输入、输出。 */
    private static final List<String> WORK_SEMANTICS = List.of(
            "INSCRIBER_PLATE_TOP", "INSCRIBER_PLATE_BOTTOM", "MACHINE_INPUT", "MACHINE_OUTPUT");

    private final Function<String, List<Integer>> slotsReader;

    /** 无语义读写端时构造（用于离线测试回退兼容原 8 槽位置判定）。 */
    public Ae2InscriberLayout() {
        this(semantic -> List.of());
    }

    /** 生产接线用：每次读语义槽位经联动入口包一层，接口对不上时自动停用。 */
    public Ae2InscriberLayout(Ae2Compat compat) {
        this(Objects.requireNonNull(compat, "compat")::menuSlotsOf);
    }

    /** 直接持有语义读端时构造（用于测试携带网络工具箱等语义场景）。 */
    public Ae2InscriberLayout(Ae2MenuSlots menuSlots) {
        this(Objects.requireNonNull(menuSlots, "menuSlots")::slotsOf);
    }

    private Ae2InscriberLayout(Function<String, List<Integer>> slotsReader) {
        this.slotsReader = Objects.requireNonNull(slotsReader, "slotsReader");
    }

    @Override public Set<String> menuTypes() {
        return Set.of(INSCRIBER_MENU);
    }

    @Override public MenuLayout.Layout classify(MenuSlots slots, List<Integer> playerSlots, List<Integer> otherSlots) {
        // 压印器界面必须带完整 36 格角色背包；直接调用 prove 时同样拦下非 36 格异常菜单。
        if (playerSlots.size() != PLAYER_INVENTORY_SLOTS) {
            return new MenuLayout.Unsupported("压印器界面的角色背包槽位是 " + playerSlots.size() + " 格而不是 "
                    + PLAYER_INVENTORY_SLOTS + " 格，证明不了布局");
        }
        MenuLayout.Layout bySemantic = classifyBySemantics(playerSlots, otherSlots);
        if (bySemantic != null) return bySemantic;
        return classifyByPosition(playerSlots, otherSlots);
    }

    // 优先走 AE2 槽位语义识别：携带网络工具箱多出 9 个 TOOLBOX 槽时仍能精准挑出 4 工作格 + 4 升级格；
    // 四种工作语义各自必须恰好命中 1 格，防止总数为 4 但槽位错位。
    private MenuLayout.Layout classifyBySemantics(List<Integer> playerSlots, List<Integer> otherSlots) {
        Set<Integer> allowedOther = Set.copyOf(otherSlots);
        Set<Integer> toolbox = Set.copyOf(slotsReader.apply("TOOLBOX"));
        boolean anySemanticSeen = !toolbox.isEmpty();
        List<List<Integer>> matchedByWorkSemantic = new ArrayList<>(WORK_SEMANTICS.size());
        for (String semantic : WORK_SEMANTICS) {
            List<Integer> found = slotsReader.apply(semantic);
            if (!found.isEmpty()) anySemanticSeen = true;
            List<Integer> valid = new ArrayList<>();
            for (int index : found) {
                if (allowedOther.contains(index) && !toolbox.contains(index)) valid.add(index);
            }
            matchedByWorkSemantic.add(valid);
        }
        List<Integer> rawUpgrades = slotsReader.apply("UPGRADE");
        if (!rawUpgrades.isEmpty()) anySemanticSeen = true;
        if (!anySemanticSeen) return null;

        List<Integer> workSlots = new ArrayList<>(WORK_SLOTS);
        for (int i = 0; i < WORK_SEMANTICS.size(); i++) {
            List<Integer> matched = matchedByWorkSemantic.get(i);
            if (matched.size() != 1) {
                return new MenuLayout.Unsupported("按语义认出的压印器工作格 " + WORK_SEMANTICS.get(i) + " 有 "
                        + matched.size() + " 格而不是 1 格，证明不了布局");
            }
            workSlots.add(matched.getFirst());
        }
        List<Integer> upgradeSlots = new ArrayList<>(UPGRADE_SLOTS);
        for (int index : rawUpgrades) {
            if (allowedOther.contains(index) && !toolbox.contains(index)) upgradeSlots.add(index);
        }
        if (upgradeSlots.size() != UPGRADE_SLOTS) {
            return new MenuLayout.Unsupported("按语义认出的压印器升级格有 " + upgradeSlots.size()
                    + " 格而不是 " + UPGRADE_SLOTS + " 格，证明不了布局");
        }
        List<Integer> ordered = new ArrayList<>(INSCRIBER_SLOTS);
        ordered.addAll(workSlots);
        ordered.addAll(upgradeSlots);
        Set<Integer> unique = new LinkedHashSet<>(ordered);
        if (unique.size() != INSCRIBER_SLOTS) {
            return new MenuLayout.Unsupported("压印器工作格与升级格存在重复编号，证明不了布局");
        }
        return new MenuLayout.Supported(List.copyOf(playerSlots), List.copyOf(ordered), Set.copyOf(unique));
    }

    // 离线无语义读端回退：按实机默认 4 升级格在前、36 玩家格在中、4 工作格在后的固定位置判定。
    private static MenuLayout.Layout classifyByPosition(List<Integer> playerSlots, List<Integer> otherSlots) {
        if (otherSlots.size() != INSCRIBER_SLOTS) {
            return new MenuLayout.Unsupported("压印器界面的容器侧是 " + otherSlots.size() + " 格而不是 "
                    + INSCRIBER_SLOTS + " 格，证明不了布局");
        }
        int firstPlayer = playerSlots.stream().mapToInt(Integer::intValue).min().orElseThrow();
        int lastPlayer = playerSlots.stream().mapToInt(Integer::intValue).max().orElseThrow();
        List<Integer> upgradeSlots = new ArrayList<>(UPGRADE_SLOTS);
        List<Integer> workSlots = new ArrayList<>(WORK_SLOTS);
        for (int slot : otherSlots) {
            if (slot < firstPlayer) {
                upgradeSlots.add(slot);
            } else if (slot > lastPlayer) {
                workSlots.add(slot);
            }
        }
        if (upgradeSlots.size() != UPGRADE_SLOTS || workSlots.size() != WORK_SLOTS) {
            return new MenuLayout.Unsupported("压印器界面的升级格（" + upgradeSlots.size() + "）与工作格（"
                    + workSlots.size() + "）位置对不上，证明不了布局");
        }
        List<Integer> orderedContainer = new ArrayList<>(INSCRIBER_SLOTS);
        orderedContainer.addAll(workSlots);
        orderedContainer.addAll(upgradeSlots);
        return new MenuLayout.Supported(List.copyOf(playerSlots), List.copyOf(orderedContainer), Set.copyOf(otherSlots));
    }
}
