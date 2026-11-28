// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.part;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.behavior.menu.ClientMenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.ae2.Ae2MenuWork;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 打开 AE2 部件或方块菜单并改写一项设置的通用执行动作。
 *
 * <p>统一承载全部开窗配置路径：配置按钮循环切换、输入总线键类型切换、元件工作台模糊模式与复制模式、
 * 分区与清空动作、幽灵过滤槽（含容量卡解锁提示）、ME 接口备料与换页填数量（{@code ae2:set_stock_amount}）、
 * 优先级子菜单（{@code ae2:priority}）、发信器阈值，以及升级卡槽与元件槽的放取。
 * 每次改完在关闭界面前刷新本地缓存，确保配置任务下一刻读回立即命中。
 */
final class Ae2MenuConfigAction extends Ae2MenuWork {

    /** 枚举配置按钮防无限打转上限（AE2 所有枚举设置最多不超过 8 项）。 */
    private static final int MAX_CYCLE_STEPS = 12;

    /** 具体改法类别。 */
    enum Kind {
        CONFIG_BUTTON,
        KEY_TYPE,
        WORKBENCH_FUZZY,
        WORKBENCH_COPY_MODE,
        NO_ARG_ACTION,
        FILTER_SLOT,
        STOCK_SLOT,
        PRIORITY,
        REPORTING_VALUE,
        ITEM_SLOT
    }

    /** 一次改配置的输入规格。 */
    record Spec(BlockPos pos, Direction side, String label, String expectedMenuId, Kind kind, String localKey,
                String wantedValue, String ae2SettingName, int slotIndex, boolean isCellSlot,
                Ae2PartKeys.StockValue stockValue) {
    }

    private enum Step { SYNC, APPLY, SUB_OPEN, SUB_APPLY, SUB_RETURN, SLOT_PICK, SLOT_PLACE, SLOT_RETURN }

    private final Ae2Compat compat;
    private final Ae2PartControl control;
    private final Ae2PartCache cache;
    private final Spec spec;
    private final Set<String> seenCycleValues = new LinkedHashSet<>();

    private Step step = Step.SYNC;
    private int cycleSteps;
    private boolean sent;
    /** 不等同步回显、只等固定几刻的短等待（分区/清空等两三刻结算）单独计数。 */
    private int settleWaited;
    private int targetMenuSlot = -1;
    private int sourcePlayerSlot = -1;
    private String wantedSlotItem;
    private boolean applied;

    Ae2MenuConfigAction(Ae2Compat compat, Ae2PartControl control, Ae2PartCache cache, PlayerServices services,
                        Spec spec, Permissions permissions) {
        this(compat, control, cache, services, spec, defaultOpening(control, services, spec, permissions));
    }

    /** 离线测试用构造：允许注入菜单打开替身。 */
    Ae2MenuConfigAction(Ae2Compat compat, Ae2PartControl control, Ae2PartCache cache, PlayerServices services,
                        Spec spec, MenuOpening opening) {
        super(opening, spec.label() + "的界面没打开");
        this.compat = Objects.requireNonNull(compat, "compat");
        this.control = Objects.requireNonNull(control, "control");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.spec = Objects.requireNonNull(spec, "spec");
    }

    // 线缆部件按部件世界坐标盒点开，方块设备直接点整格。
    private static MenuOpening defaultOpening(Ae2PartControl control, PlayerServices services, Spec spec,
                                              Permissions permissions) {
        AABB box = null;
        if (spec.side() != null && services.context().get() != null && services.context().get().level() != null) {
            box = control.partBox(services.context().get().level(), spec.pos(), spec.side()).orElse(null);
        }
        return new ClientMenuOpening(spec.pos(), box, permissions, services.menuLayouts(), services.approach(),
                services.interactions(), services.toMainhand(), services.context());
    }

    // 优先级与备料数量需要服务端换页至子菜单，提前向已打开界面声明合法换页路径。
    @Override protected void menuOpened(OpenedMenu menu) {
        if (spec.kind() == Kind.PRIORITY) {
            menu.followPageSwaps((from, to) -> control.isMenuType(to, Ae2PartKeys.MENU_PRIORITY)
                    || control.isMenuType(to, spec.expectedMenuId()));
        } else if (spec.kind() == Kind.STOCK_SLOT) {
            menu.followPageSwaps((from, to) -> control.isMenuType(to, Ae2PartKeys.MENU_SET_STOCK_AMOUNT)
                    || control.isMenuType(to, Ae2PartKeys.MENU_INTERFACE));
        }
    }

    @Override protected ActionStatus work(TickContext tick) {
        return switch (step) {
            case SYNC -> waitSync();
            case APPLY -> apply();
            case SUB_OPEN -> waitSubOpen();
            case SUB_APPLY -> applySub();
            case SUB_RETURN -> waitSubReturn();
            case SLOT_PICK -> slotPick();
            case SLOT_PLACE -> slotPlace();
            case SLOT_RETURN -> slotReturn();
        };
    }

    private ActionStatus waitSync() {
        Optional<Ae2PartControl.OpenMenuSnapshot> snapshot = control.readOpenMenu();
        if (snapshot.isPresent() && menu().reading().isPresent()) {
            step = Step.APPLY;
            resetWait();
            return ActionStatus.progressed();
        }
        if (syncWaitElapsed()) {
            return stop(Problem.of(Problem.Kind.STUCK, spec.label() + "的界面内容一直没同步过来"));
        }
        return ActionStatus.running();
    }

    private ActionStatus apply() {
        Optional<Ae2PartControl.OpenMenuSnapshot> opt = control.readOpenMenu();
        if (opt.isEmpty()) return stop(Problem.of(Problem.Kind.TARGET_GONE, spec.label() + "的界面被关掉了"));
        Ae2PartControl.OpenMenuSnapshot snap = opt.get();
        return switch (spec.kind()) {
            case CONFIG_BUTTON -> applyConfigButton(snap);
            case KEY_TYPE -> applyKeyType(snap);
            case WORKBENCH_FUZZY -> applyWorkbenchFuzzy(snap);
            case WORKBENCH_COPY_MODE -> applyWorkbenchCopyMode(snap);
            case NO_ARG_ACTION -> applyNoArgAction(snap);
            case FILTER_SLOT -> applyFilterSlot(snap);
            case STOCK_SLOT -> applyStockSlot(snap);
            case PRIORITY -> startPriority(snap);
            case REPORTING_VALUE -> applyReportingValue(snap);
            case ITEM_SLOT -> startItemSlot(snap);
        };
    }

    // 配置按钮按循环步进：每次发一次包等同步变化，转满一圈仍未命中（如未装模糊卡）如实收场。
    private ActionStatus applyConfigButton(Ae2PartControl.OpenMenuSnapshot snap) {
        String current = snap.settings().getOrDefault(spec.localKey(), "");
        if (current.equals(spec.wantedValue())) return succeed(snap);
        if (sent) {
            if (!seenCycleValues.contains(current)) {
                sent = false;
                resetWait();
            } else if (syncWaitElapsed()) {
                return stop(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                        spec.label() + "的「" + spec.localKey() + "」切换没有生效（当前仍为 " + current
                                + "，请检查是否缺少对应的模糊卡/红石卡/合成卡）"));
            } else {
                return ActionStatus.running();
            }
        }
        if (!seenCycleValues.add(current) || ++cycleSteps > MAX_CYCLE_STEPS) {
            return stop(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    spec.label() + "的「" + spec.localKey() + "」在可选值之间打转，未能切到 " + spec.wantedValue()));
        }
        if (!compat.sendConfigButton(spec.ae2SettingName(), false)) {
            return stop(Problem.of(Problem.Kind.UNSUPPORTED, "发不出 AE2 配置切换包：" + spec.ae2SettingName()));
        }
        sent = true;
        resetWait();
        return ActionStatus.progressed();
    }

    // 输入总线导入键类型走 SelectKeyTypePacket，不走 ConfigButtonPacket。
    private ActionStatus applyKeyType(Ae2PartControl.OpenMenuSnapshot snap) {
        String type = spec.localKey().substring("key_type.".length());
        boolean want = Boolean.parseBoolean(spec.wantedValue());
        Boolean current = snap.keyTypes().get(type);
        if (current != null && current == want) return succeed(snap);
        if (!sent) {
            if (!control.selectKeyType(type, want)) {
                return stop(Problem.of(Problem.Kind.UNSUPPORTED, spec.label() + "不支持切换导入类型 " + type));
            }
            sent = true;
            resetWait();
            return ActionStatus.progressed();
        }
        if (syncWaitElapsed()) {
            return stop(Problem.of(Problem.Kind.STUCK, spec.label() + "切换导入类型 " + type + " 后一直没回显"));
        }
        return ActionStatus.running();
    }

    // 元件工作台的模糊模式直接写进元件物品组件，绝不走 ConfigButtonPacket。
    private ActionStatus applyWorkbenchFuzzy(Ae2PartControl.OpenMenuSnapshot snap) {
        if (snap.cellSlots().isEmpty() || snap.cellSlots().getFirst().isEmpty()) {
            return stop(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "元件工作台里还没有放入存储元件，无法设置模糊模式"));
        }
        String current = snap.settings().getOrDefault("fuzzy_mode", "");
        if (current.equals(spec.wantedValue())) return succeed(snap);
        if (!sent) {
            if (!control.setCellWorkbenchFuzzy(spec.wantedValue())) {
                return stop(Problem.of(Problem.Kind.INVALID_PARAMETER, "不认识的模糊模式：" + spec.wantedValue()));
            }
            sent = true;
            resetWait();
            return ActionStatus.progressed();
        }
        if (syncWaitElapsed()) {
            return stop(Problem.of(Problem.Kind.STUCK, "元件工作台设置模糊模式后一直没回显"));
        }
        return ActionStatus.running();
    }

    // 元件工作台 copy_mode 走 nextCopyMode 界面动作。
    private ActionStatus applyWorkbenchCopyMode(Ae2PartControl.OpenMenuSnapshot snap) {
        String current = snap.settings().getOrDefault("copy_mode", "");
        if (current.equals(spec.wantedValue())) return succeed(snap);
        if (sent) {
            if (!seenCycleValues.contains(current)) {
                sent = false;
                resetWait();
            } else if (syncWaitElapsed()) {
                return stop(Problem.of(Problem.Kind.STUCK, "元件工作台切换复制模式后一直没回显"));
            } else {
                return ActionStatus.running();
            }
        }
        if (!seenCycleValues.add(current) || ++cycleSteps > 4) {
            return stop(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "元件工作台切不到复制模式 " + spec.wantedValue()));
        }
        if (!compat.sendMenuAction("nextCopyMode")) {
            return stop(Problem.of(Problem.Kind.UNSUPPORTED, "发不出 nextCopyMode 动作"));
        }
        sent = true;
        resetWait();
        return ActionStatus.progressed();
    }

    // 分区（partition）或清空（clear）：发一次无参界面动作，等两刻让过滤槽同步后再把最新过滤刷入缓存。
    private ActionStatus applyNoArgAction(Ae2PartControl.OpenMenuSnapshot snap) {
        if (!sent) {
            if (!compat.sendMenuAction(spec.wantedValue())) {
                return stop(Problem.of(Problem.Kind.UNSUPPORTED, spec.label() + "发不出动作 " + spec.wantedValue()));
            }
            sent = true;
            settleWaited = 0;
            return ActionStatus.progressed();
        }
        if (++settleWaited < 3) return ActionStatus.running();
        return succeed(snap);
    }

    // 幽灵过滤槽：越过 18 格基础槽且未启用时明确提示需容量卡，否则发 SET_FILTER 并等槽位回显。
    private ActionStatus applyFilterSlot(Ae2PartControl.OpenMenuSnapshot snap) {
        Optional<Ae2PartControl.MenuSlotEntry> found = Ae2PartSnapshots.slotAt(snap.configSlots(), spec.slotIndex());
        if (found.isEmpty()) {
            return stop(Problem.of(Problem.Kind.INVALID_PARAMETER,
                    spec.label() + "没有过滤槽 filter." + spec.slotIndex()));
        }
        Ae2PartControl.MenuSlotEntry slot = found.get();
        if (!slot.enabled()) {
            String tip = spec.slotIndex() >= Ae2PartKeys.BASE_FILTER_SLOTS
                    ? "过滤槽 filter." + spec.slotIndex() + " 尚未解锁，请先插入容量卡（" + Ae2PartKeys.CAPACITY_CARD + "）"
                    : "过滤槽 filter." + spec.slotIndex() + " 当前未启用（请先放入存储元件或切换保留模式）";
            return stop(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, tip));
        }
        boolean wantEmpty = spec.wantedValue().equalsIgnoreCase(Ae2PartKeys.EMPTY);
        if ((wantEmpty && slot.isEmpty()) || (!wantEmpty && Ae2PartSnapshots.matchesFilterTarget(slot, spec.wantedValue()))) {
            return succeed(snap);
        }
        if (!sent) {
            Optional<ItemStack> filter = control.filterStackOf(spec.wantedValue());
            if (filter.isEmpty()) {
                return stop(Problem.of(Problem.Kind.INVALID_PARAMETER, "不认识的过滤物品、流体或化学品：" + spec.wantedValue()));
            }
            if (!compat.sendSetFilter(slot.menuIndex(), filter.get())) {
                return stop(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "无法写入过滤槽 filter." + spec.slotIndex()));
            }
            sent = true;
            resetWait();
            return ActionStatus.progressed();
        }
        if (syncWaitElapsed()) {
            return stop(Problem.of(Problem.Kind.STUCK, "写入过滤槽 filter." + spec.slotIndex() + " 后一直没回显"));
        }
        return ActionStatus.running();
    }

    // ME 接口备料槽：先 SET_FILTER 设目标物，若指定了具体数量则发 setAmount 换页至 ae2:set_stock_amount。
    private ActionStatus applyStockSlot(Ae2PartControl.OpenMenuSnapshot snap) {
        Optional<Ae2PartControl.MenuSlotEntry> found = Ae2PartSnapshots.slotAt(snap.configSlots(), spec.slotIndex());
        if (found.isEmpty()) {
            return stop(Problem.of(Problem.Kind.INVALID_PARAMETER, "ME 接口没有备料槽 stock." + spec.slotIndex()));
        }
        Ae2PartControl.MenuSlotEntry slot = found.get();
        Ae2PartKeys.StockValue wanted = spec.stockValue();
        if (Ae2PartSnapshots.matchesStock(slot, wanted)) return succeed(snap);
        if (wanted.empty()) {
            if (!sent) {
                if (!compat.sendSetFilter(slot.menuIndex(), ItemStack.EMPTY)) {
                    return stop(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "无法清空备料槽 stock." + spec.slotIndex()));
                }
                sent = true;
                resetWait();
                return ActionStatus.progressed();
            }
            if (syncWaitElapsed()) return stop(Problem.of(Problem.Kind.STUCK, "清空备料槽后一直没回显"));
            return ActionStatus.running();
        }
        // 目标物还没写进过滤槽：先用 SET_FILTER 写进去，写好后再看要不要换页改数量。
        if (!Ae2PartSnapshots.matchesFilterTarget(slot, wanted.targetId())) {
            if (!sent) {
                Optional<ItemStack> filter = control.filterStackOf(wanted.targetId());
                if (filter.isEmpty()) {
                    return stop(Problem.of(Problem.Kind.INVALID_PARAMETER, "不认识的备料物品、流体或化学品：" + wanted.targetId()));
                }
                if (!compat.sendSetFilter(slot.menuIndex(), filter.get())) {
                    return stop(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "无法设备料槽 stock." + spec.slotIndex()));
                }
                sent = true;
                resetWait();
                return ActionStatus.progressed();
            }
            if (syncWaitElapsed()) return stop(Problem.of(Problem.Kind.STUCK, "设备料物品后一直没回显"));
            return ActionStatus.running();
        }
        // 物品已匹配，但还需要设置数量：发 setAmount 打开 ae2:set_stock_amount 子界面。
        if (!compat.sendMenuIntAction("setAmount", spec.slotIndex())) {
            return stop(Problem.of(Problem.Kind.UNSUPPORTED, "发不出打开备料数量子界面的动作 setAmount"));
        }
        enterStep(Step.SUB_OPEN);
        return ActionStatus.progressed();
    }

    // 优先级：发 SwitchGuisPacket 打开 ae2:priority 子菜单。
    private ActionStatus startPriority(Ae2PartControl.OpenMenuSnapshot snap) {
        if (!compat.openSubMenu(Ae2PartKeys.MENU_PRIORITY)) {
            return stop(Problem.of(Problem.Kind.UNSUPPORTED, spec.label() + "打不开优先级子界面"));
        }
        enterStep(Step.SUB_OPEN);
        return ActionStatus.progressed();
    }

    // 等换页至子菜单（ae2:priority 或 ae2:set_stock_amount）。
    private ActionStatus waitSubOpen() {
        String subId = spec.kind() == Kind.PRIORITY ? Ae2PartKeys.MENU_PRIORITY : Ae2PartKeys.MENU_SET_STOCK_AMOUNT;
        Optional<Ae2PartControl.OpenMenuSnapshot> snap = control.readOpenMenu();
        if (snap.isPresent() && snap.get().menuTypeId().equals(subId)) {
            enterStep(Step.SUB_APPLY);
            return ActionStatus.progressed();
        }
        if (syncWaitElapsed()) {
            return stop(Problem.of(Problem.Kind.STUCK, "等待切换到子界面 " + subId + " 超时"));
        }
        return ActionStatus.running();
    }

    // 在子菜单里提交目标优先级或备料数量：优先级需手动返回父菜单，备料数量由 AE2 自动返回。
    private ActionStatus applySub() {
        Optional<Ae2PartControl.OpenMenuSnapshot> opt = control.readOpenMenu();
        if (opt.isEmpty()) return stop(Problem.of(Problem.Kind.TARGET_GONE, "子界面意外关掉了"));
        Ae2PartControl.OpenMenuSnapshot snap = opt.get();
        if (spec.kind() == Kind.PRIORITY) {
            int wanted = Integer.parseInt(spec.wantedValue());
            if (!sent) {
                if (snap.priority().isEmpty() || snap.priority().getAsInt() != wanted) {
                    if (!compat.sendMenuIntAction("setPriority", wanted)) {
                        return stop(Problem.of(Problem.Kind.UNSUPPORTED, "发不出 setPriority 动作"));
                    }
                }
                sent = true;
                settleWaited = 0;
                return ActionStatus.progressed();
            }
            if (++settleWaited < 2) return ActionStatus.running();
            compat.returnToParentMenu();
            enterStep(Step.SUB_RETURN);
            return ActionStatus.progressed();
        }
        // SetStockAmountMenu：发 setStockAmount(amount)，服务端会自动返回 InterfaceMenu。
        int amount = spec.stockValue().amount();
        if (!compat.sendMenuIntAction("setStockAmount", amount)) {
            return stop(Problem.of(Problem.Kind.UNSUPPORTED, "发不出 setStockAmount 动作"));
        }
        enterStep(Step.SUB_RETURN);
        return ActionStatus.progressed();
    }

    // 等子菜单退回主菜单，再读回最新状态并刷新缓存。
    private ActionStatus waitSubReturn() {
        Optional<Ae2PartControl.OpenMenuSnapshot> opt = control.readOpenMenu();
        if (opt.isPresent() && opt.get().menuTypeId().equals(spec.expectedMenuId())) {
            Ae2PartControl.OpenMenuSnapshot snap = opt.get();
            if (spec.kind() == Kind.STOCK_SLOT) {
                Optional<Ae2PartControl.MenuSlotEntry> slot = Ae2PartSnapshots.slotAt(snap.configSlots(), spec.slotIndex());
                if (slot.isPresent() && Ae2PartSnapshots.matchesStock(slot.get(), spec.stockValue())) {
                    return succeed(snap);
                }
                if (!syncWaitElapsed()) return ActionStatus.running();
                // 数量可能被服务端上限截断，把实际值刷入缓存后按实情收场。
                Ae2PartSnapshots.flushToCache(cache, spec.pos(), spec.side(), snap, null, null);
                long actual = slot.map(Ae2PartControl.MenuSlotEntry::count).orElse(0L);
                return stop(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                        "备料数量写入后实际为 " + actual + "，与要求的 " + spec.stockValue().amount() + " 不一致（可能超过单格上限）"));
            }
            return succeed(snap);
        }
        if (syncWaitElapsed()) {
            return stop(Problem.of(Problem.Kind.STUCK, "子界面没有退回 " + spec.label() + " 的主界面"));
        }
        return ActionStatus.running();
    }

    // 发信器阈值（标准发信器 / 能量发信器）：发 setReportingValue(Long)，写缓存后关窗。
    private ActionStatus applyReportingValue(Ae2PartControl.OpenMenuSnapshot snap) {
        long wanted = Long.parseLong(spec.wantedValue());
        if (snap.reportingValue().isPresent() && snap.reportingValue().getAsLong() == wanted) {
            return succeed(snap);
        }
        if (!sent) {
            if (!compat.sendMenuLongAction("setReportingValue", wanted)) {
                return stop(Problem.of(Problem.Kind.UNSUPPORTED, spec.label() + "发不出 setReportingValue 动作"));
            }
            sent = true;
            settleWaited = 0;
            return ActionStatus.progressed();
        }
        if (++settleWaited < 2) return ActionStatus.running();
        return succeed(snap);
    }

    // 升级卡槽与元件工作台元件槽的放入/取出：先核对槽位启用态，再按背包现存物做一次精确点击搬运。
    private ActionStatus startItemSlot(Ae2PartControl.OpenMenuSnapshot snap) {
        List<Ae2PartControl.MenuSlotEntry> group = spec.isCellSlot() ? snap.cellSlots() : snap.upgradeSlots();
        Optional<Ae2PartControl.MenuSlotEntry> found = Ae2PartSnapshots.slotAt(group, spec.slotIndex());
        if (found.isEmpty()) {
            return stop(Problem.of(Problem.Kind.INVALID_PARAMETER, spec.label() + "没有槽位 " + spec.localKey()));
        }
        Ae2PartControl.MenuSlotEntry slot = found.get();
        if (!slot.enabled()) {
            return stop(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    spec.label() + "的 " + spec.localKey() + " 当前未激活（超出当前元件支持的升级槽数量）"));
        }
        targetMenuSlot = slot.menuIndex();
        boolean wantEmpty = spec.isCellSlot()
                ? spec.wantedValue().equalsIgnoreCase("out")
                : spec.wantedValue().equalsIgnoreCase(Ae2PartKeys.EMPTY);
        wantedSlotItem = wantEmpty ? null
                : (spec.isCellSlot() ? Ae2PartKeys.parseCellValue(spec.wantedValue()).orElseThrow().itemId()
                        : spec.wantedValue());
        if (wantEmpty && slot.isEmpty()) return succeed(snap);
        if (!wantEmpty && wantedSlotItem.equals(slot.itemId())) return succeed(snap);
        // 要清空或先腾出异物：Shift 快速移回角色背包。
        if (!slot.isEmpty()) {
            if (menu().busy()) return ActionStatus.running();
            if (!sent) {
                if (!menu().quickMove(targetMenuSlot)) return ActionStatus.running();
                sent = true;
                resetWait();
                return ActionStatus.progressed();
            }
            if (syncWaitElapsed()) {
                return stop(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                        "从" + spec.label() + "的 " + spec.localKey() + " 取出原有物品失败（背包可能已满）"));
            }
            return ActionStatus.running();
        }
        // 目标槽已空，找背包里有没有要放进去的升级卡或元件。
        sourcePlayerSlot = findPlayerSlot(wantedSlotItem);
        if (sourcePlayerSlot < 0) {
            return stop(Problem.of(Problem.Kind.NEED_ITEM, "背包里没有 " + wantedSlotItem + "，无法放入 " + spec.localKey()));
        }
        enterStep(Step.SLOT_PICK);
        return ActionStatus.progressed();
    }

    private ActionStatus slotPick() {
        if (menu().busy()) return ActionStatus.running();
        if (!menu().cursorEmpty() && wantedSlotItem.equals(menu().cursor().key())) {
            enterStep(Step.SLOT_PLACE);
            return ActionStatus.progressed();
        }
        if (!sent) {
            if (!menu().click(sourcePlayerSlot, 0)) return ActionStatus.running();
            menu().noteCursorTakenFrom(sourcePlayerSlot);
            sent = true;
            resetWait();
            return ActionStatus.progressed();
        }
        if (syncWaitElapsed()) {
            return stop(Problem.of(Problem.Kind.STUCK, "从背包拿起 " + wantedSlotItem + " 一直没生效"));
        }
        return ActionStatus.running();
    }

    private ActionStatus slotPlace() {
        if (menu().busy()) return ActionStatus.running();
        Optional<Ae2PartControl.OpenMenuSnapshot> opt = control.readOpenMenu();
        if (opt.isEmpty()) return stop(Problem.of(Problem.Kind.TARGET_GONE, spec.label() + "的界面被关掉了"));
        List<Ae2PartControl.MenuSlotEntry> group = spec.isCellSlot() ? opt.get().cellSlots() : opt.get().upgradeSlots();
        Optional<Ae2PartControl.MenuSlotEntry> slot = Ae2PartSnapshots.slotAt(group, spec.slotIndex());
        if (slot.isPresent() && wantedSlotItem.equals(slot.get().itemId())) {
            if (menu().cursorEmpty()) return succeed(opt.get());
            enterStep(Step.SLOT_RETURN);
            return ActionStatus.progressed();
        }
        if (!sent) {
            // 右键放单件，避免把一整组升级卡全压在光标上或导致拒收。
            if (!menu().click(targetMenuSlot, 1)) return ActionStatus.running();
            sent = true;
            resetWait();
            return ActionStatus.progressed();
        }
        if (syncWaitElapsed()) {
            return stop(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                    spec.label() + "的 " + spec.localKey() + " 不接受 " + wantedSlotItem));
        }
        return ActionStatus.running();
    }

    private ActionStatus slotReturn() {
        if (menu().busy()) return ActionStatus.running();
        if (menu().cursorEmpty()) {
            return control.readOpenMenu().map(this::succeed).orElseGet(() -> stop(null));
        }
        if (!sent) {
            if (!menu().click(sourcePlayerSlot, 0)) return ActionStatus.running();
            sent = true;
            resetWait();
            return ActionStatus.progressed();
        }
        if (syncWaitElapsed()) {
            return stop(Problem.of(Problem.Kind.STUCK, "把手里余下的 " + wantedSlotItem + " 放回背包超时"));
        }
        return ActionStatus.running();
    }

    private int findPlayerSlot(String itemId) {
        Optional<MenuContent.Reading> reading = menu().reading();
        if (reading.isEmpty()) return -1;
        List<SlotSnapshot> snaps = reading.get().playerSnapshots();
        List<Integer> ids = reading.get().playerSlotIds();
        for (int i = 0; i < snaps.size(); i++) {
            if (!snaps.get(i).isEmpty() && itemId.equals(snaps.get(i).key())) {
                return ids.get(i);
            }
        }
        return -1;
    }

    // 改完后、关闭界面前刷新本地缓存，确保配置任务紧接着的同步读回立刻命中。
    private ActionStatus succeed(Ae2PartControl.OpenMenuSnapshot snapshot) {
        applied = true;
        Ae2PartSnapshots.flushToCache(cache, spec.pos(), spec.side(), snapshot, spec.localKey(), spec.wantedValue());
        return stop(null);
    }

    // 没改成就要收场：也趁界面还开着读一次最新配置刷进缓存，让下一次读回拿到实际值而不是旧值。
    private ActionStatus stop(Problem reason) {
        if (reason != null && !applied) {
            control.readOpenMenu().ifPresent(snap -> Ae2PartSnapshots.flushToCache(cache, spec.pos(), spec.side(),
                    snap, null, null));
        }
        return closeWith(reason);
    }

    // 换步骤：发包与等待计时归零，等两三刻结算的短等待也从头计。
    private void enterStep(Step next) {
        step = next;
        sent = false;
        resetWait();
        settleWaited = 0;
    }

    // 光标上拿着升级卡或元件时停不得：拿起、放进、放回三段被关掉会落在流程外。
    @Override public Interruptibility interruptibility() {
        return step == Step.SLOT_PICK || step == Step.SLOT_PLACE || step == Step.SLOT_RETURN
                ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
    }

    @Override protected String doing() {
        return "修改" + spec.label() + "的「" + spec.localKey() + "」为 " + spec.wantedValue().toLowerCase(Locale.ROOT);
    }
}
