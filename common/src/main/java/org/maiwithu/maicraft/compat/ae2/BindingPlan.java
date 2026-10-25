// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;

import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;

/**
 * 绑定无线终端的下一步怎么点：读访问点界面此刻的样子（光标、角色侧、绑定格、产出格），
 * 给出下一步点哪一格，或者给出点不了的原因。纯计算，不读世界也不动手。
 *
 * <p>顺序就是玩家手上那套：空手拿起终端 → 放进绑定格 → 等绑定副本出现在产出格 →
 * 拿起副本 → 放进背包。物品按 ID 认不按组件认：无线终端带电，组件一路上会变；
 * 光标与产出格上的东西是不是"绑好了"由调用方用联动入口的绑定读数判好，这里只管点哪格。
 */
public final class BindingPlan {

    /** 绑定走到了哪一步：点哪一格，还是先等一等、或者点不下去。 */
    public sealed interface Step {
    }

    /** 从角色侧这一格拿起无线终端（整份）。 */
    public record PickUp(int slotId) implements Step {
    }

    /** 光标上的终端放进访问点界面的绑定格。 */
    public record PutIn(int slotId) implements Step {
    }

    /** 终端已在绑定格里，副本还没出现在产出格：等游戏结算，不重点。 */
    public record WaitCopy() implements Step {
    }

    /** 从产出格拿起绑定好的副本。 */
    public record TakeCopy(int slotId) implements Step {
    }

    /** 副本放进角色侧这一格。 */
    public record PutDown(int slotId) implements Step {
    }

    /** 绑定做完了：副本已在背包里，可以去关界面。 */
    public record Finished() implements Step {
    }

    /** 点不下去，写明为什么：光标被占、身上找不到终端、认不出格子、背包放不下。 */
    public record Blocked(String why) implements Step {
    }

    /**
     * 访问点界面此刻的样子。
     *
     * @param cursor            光标上的东西（空就是空手）
     * @param playerSlotIds     角色侧的槽位号，与快照一一对应
     * @param playerSnapshots   角色侧此刻的样子
     * @param playerLinked      角色侧每格上的东西是不是绑定好的副本，与快照一一对应；绑定落上没有由调用方按绑定读数判
     * @param bindingInputSlot  绑定格的槽位号；认不出为空
     * @param bindingInput      绑定格此刻的样子；读不到为空
     * @param bindingOutputSlot 产出格的槽位号；认不出为空
     * @param bindingOutput     产出格此刻的样子；读不到为空
     * @param terminalItemId    要绑定的无线终端的物品 ID
     * @param cursorIsLinkedCopy 光标上的东西是不是绑定好的副本（联动入口的绑定读数判的）
     * @param outputIsLinkedCopy 产出格上的东西是不是绑定好的副本
     */
    public record Input(SlotSnapshot cursor, List<Integer> playerSlotIds, List<SlotSnapshot> playerSnapshots,
                        List<Boolean> playerLinked,
                        Optional<Integer> bindingInputSlot, Optional<SlotSnapshot> bindingInput,
                        Optional<Integer> bindingOutputSlot, Optional<SlotSnapshot> bindingOutput,
                        String terminalItemId, boolean cursorIsLinkedCopy, boolean outputIsLinkedCopy) {
        public Input {
            playerSlotIds = List.copyOf(playerSlotIds);
            playerSnapshots = List.copyOf(playerSnapshots);
            playerLinked = List.copyOf(playerLinked);
        }
    }

    private BindingPlan() {}

    /** 按此刻的样子给出下一步；一步一步来，一次只点一下。 */
    public static Step next(Input input) {
        // 光标上挂着东西：刚拿起的原料接着放进绑定格，绑好的副本接着放回背包，别的东西不往里放。
        if (!input.cursor().isEmpty()) {
            if (input.cursorIsLinkedCopy()) return putDown(input);
            if (isItem(input.cursor(), input.terminalItemId())) return putIn(input);
            return new Blocked("光标上挂着别的东西，不往访问点的绑定格里放");
        }
        // 产出格有绑好的副本：拿回来。有东西但还不是副本（绑定没落上）按还在等算。
        Optional<SlotSnapshot> output = input.bindingOutput();
        if (output.isPresent() && !output.get().isEmpty()) {
            if (!input.outputIsLinkedCopy()) return new WaitCopy();
            if (input.bindingOutputSlot().isEmpty()) return new Blocked("认不出访问点界面的产出格");
            return new TakeCopy(input.bindingOutputSlot().get());
        }
        // 终端已在绑定格里：等服务端把绑定副本放进产出格。
        Optional<SlotSnapshot> boundInput = input.bindingInput();
        if (boundInput.isPresent() && !boundInput.get().isEmpty()) return new WaitCopy();
        // 身上已经有绑定好的副本：绑定落上了，去做完（去关界面、按身上的物品核对），不再碰它。
        // 不看这一条，放回背包的副本会被当成还没绑的原料再拿起来放进绑定格，一遍遍重绑到超时。
        if (input.playerLinked().stream().anyMatch(linked -> linked)) return new Finished();
        // 身上找终端：空手拿起它。
        Optional<Integer> held = slotWithItem(input.playerSlotIds(), input.playerSnapshots(),
                input.terminalItemId());
        if (held.isPresent()) return new PickUp(held.get());
        return new Blocked("光标空着、绑定格是空的，身上主背包里也没有" + input.terminalItemId());
    }

    // 拿起副本之后往哪放：第一个空着的角色格；一格都空不出来承认放不下，不硬塞。
    private static Step putDown(Input input) {
        for (int i = 0; i < input.playerSlotIds().size() && i < input.playerSnapshots().size(); i++) {
            if (input.playerSnapshots().get(i).isEmpty()) return new PutDown(input.playerSlotIds().get(i));
        }
        return new Blocked("背包放不下绑定好的无线终端");
    }

    private static Step putIn(Input input) {
        if (input.bindingInputSlot().isEmpty()) return new Blocked("认不出访问点界面的绑定格");
        return new PutIn(input.bindingInputSlot().get());
    }

    // 快照上的东西是不是这个物品：无线终端带电，组件一路上会变，所以只按 ID 认。
    private static boolean isItem(SlotSnapshot snapshot, String itemId) {
        return itemId.equals(itemId(snapshot));
    }

    private static Optional<Integer> slotWithItem(List<Integer> slotIds, List<SlotSnapshot> snapshots,
            String itemId) {
        for (int i = 0; i < slotIds.size() && i < snapshots.size(); i++) {
            SlotSnapshot snapshot = snapshots.get(i);
            if (!snapshot.isEmpty() && isItem(snapshot, itemId)) return Optional.of(slotIds.get(i));
        }
        return Optional.empty();
    }

    /** 快照上东西的物品 ID；空快照给空串。 */
    public static String itemId(SlotSnapshot snapshot) {
        return snapshot.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(snapshot.stack().getItem()).toString();
    }
}
