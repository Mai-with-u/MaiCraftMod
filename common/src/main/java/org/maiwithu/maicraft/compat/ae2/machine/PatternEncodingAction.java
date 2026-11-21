// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.machine;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 样板编码终端（ae2:pattern_encoding_terminal）动作：
 * 支持单独切换编码模式/替换开关/切石配方，以及执行一次完整编码（4 种模式均支持，处理模式支持流体与大于 64 数量）。
 *
 * <p>严格遵守防洗样板纪律：编码前先清空输出槽里已有编码样板 → 写入幽灵格并等待同步 →
 * 调 encode() 前核验当前模式配方有效，无效直接报 INVALID_PARAMETER 绝不调 encode() →
 * 缺空白样板报 NEED_ITEM → 编码完成后取出新编码样板回背包再关窗。
 */
final class PatternEncodingAction implements Action {

    private static final String BLANK_PATTERN_ID = "ae2:blank_pattern";

    private enum Stage {
        OPEN, CLEAR_OLD_OUTPUT, WAIT_OLD_CLEARED, SET_MODE_AND_CLEAR, WAIT_MODE_SYNC,
        WRITE_SLOTS, WAIT_RECIPE_SYNC, ENSURE_BLANK, COMMIT_ENCODE, WAIT_ENCODED,
        TAKE_ENCODED, WAIT_TAKEN, CLOSE, DONE
    }

    private final BlockPos host;
    private final Direction side;
    private final MenuOpening opening;
    private final Ae2Compat compat;
    private final Ae2MachineReads reads;
    private final Ae2MachineCache cache;
    /** 完整编码请求及原始 JSON 字符串；仅改单一项开关/模式时为 null。 */
    private final PatternEncodingRequest request;
    private final String rawRequestValue;
    /** 单一项修改键名与值；完整编码时为 null。 */
    private final String singleKey;
    private final String singleValue;

    private Stage stage = Stage.OPEN;
    private OpenedMenu menu;
    private Action closing;
    private int waited;
    private Problem problem;
    private String encodedOutputNote = "";

    /** 完整样板编码构造器。 */
    static PatternEncodingAction encode(BlockPos host, Direction side, PatternEncodingRequest request,
            String rawRequestValue, Ae2Compat compat, Ae2MachineReads reads, Ae2MachineCache cache,
            PlayerServices services, Permissions permissions) {
        return new PatternEncodingAction(host, side,
                Ae2MenuActionSupport.openBlockOrPart(host, side, reads, services, permissions),
                compat, reads, cache, Objects.requireNonNull(request, "request"),
                Objects.requireNonNull(rawRequestValue, "rawRequestValue"), null, null);
    }

    /** 单项参数调整构造器（encode_mode / substitute / substitute_fluids / stonecutting_recipe）。 */
    static PatternEncodingAction singleSetting(BlockPos host, Direction side, String singleKey, String singleValue,
            Ae2Compat compat, Ae2MachineReads reads, Ae2MachineCache cache,
            PlayerServices services, Permissions permissions) {
        return new PatternEncodingAction(host, side,
                Ae2MenuActionSupport.openBlockOrPart(host, side, reads, services, permissions),
                compat, reads, cache, null, null, Objects.requireNonNull(singleKey, "singleKey"),
                Objects.requireNonNull(singleValue, "singleValue"));
    }

    /** 离线测试用构造器：可注入替身 MenuOpening。 */
    PatternEncodingAction(BlockPos host, Direction side, MenuOpening opening, Ae2Compat compat,
            Ae2MachineReads reads, Ae2MachineCache cache, PatternEncodingRequest request,
            String rawRequestValue, String singleKey, String singleValue) {
        this.host = host.immutable();
        this.side = side;
        this.opening = Objects.requireNonNull(opening, "opening");
        this.compat = Objects.requireNonNull(compat, "compat");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.request = request;
        this.rawRequestValue = rawRequestValue;
        this.singleKey = singleKey;
        this.singleValue = singleValue;
    }

    @Override public ActionStatus tick(TickContext tick) {
        return switch (stage) {
            case OPEN -> open(tick);
            case CLEAR_OLD_OUTPUT -> clearOldOutput();
            case WAIT_OLD_CLEARED -> waitOldCleared();
            case SET_MODE_AND_CLEAR -> setModeAndClear();
            case WAIT_MODE_SYNC -> waitModeSync();
            case WRITE_SLOTS -> writeSlots();
            case WAIT_RECIPE_SYNC -> waitRecipeSync();
            case ENSURE_BLANK -> ensureBlankPattern();
            case COMMIT_ENCODE -> commitEncode();
            case WAIT_ENCODED -> waitEncoded();
            case TAKE_ENCODED -> takeEncoded();
            case WAIT_TAKEN -> waitTaken();
            case CLOSE -> closeMenu(tick);
            case DONE -> problem == null ? ActionStatus.done() : ActionStatus.failed(problem);
        };
    }

    private ActionStatus open(TickContext tick) {
        ActionStatus status = opening.tick(tick);
        if (status instanceof ActionStatus.Failed failed) return finish(failed.problem());
        if (!(status instanceof ActionStatus.Done)) return status;
        menu = opening.opened().orElse(null);
        if (menu == null) return finish(Problem.of(Problem.Kind.TARGET_GONE, "样板编码终端界面没打开"));
        waited = 0;
        stage = request == null ? Stage.SET_MODE_AND_CLEAR : Stage.CLEAR_OLD_OUTPUT;
        return ActionStatus.progressed();
    }

    // 编码前第一步：若输出槽里已有旧编码样板，先移回背包，防止洗掉旧样板或覆盖失败。
    private ActionStatus clearOldOutput() {
        Optional<Ae2MachineReads.PatternEncodingMenuView> view = reads.openPatternEncodingMenu();
        if (view.isEmpty()) {
            if (++waited <= Ae2MenuActionSupport.SYNC_WAIT_TICKS) return ActionStatus.running();
            return beginClose(Problem.of(Problem.Kind.STUCK, "样板编码终端界面同步超时"));
        }
        if (!view.get().hasEncodedPattern()) {
            stage = Stage.SET_MODE_AND_CLEAR;
            return ActionStatus.progressed();
        }
        List<Integer> outSlots = compat.menuSlotsOf("ENCODED_PATTERN");
        if (outSlots.isEmpty()) {
            return beginClose(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "找不到样板编码终端的输出样板槽"));
        }
        if (!menu.quickMove(outSlots.getFirst())) {
            if (++waited <= Ae2MenuActionSupport.SYNC_WAIT_TICKS) return ActionStatus.running();
            return beginClose(Problem.of(Problem.Kind.STUCK, "无法从样板编码终端输出槽移出旧编码样板"));
        }
        waited = 0;
        stage = Stage.WAIT_OLD_CLEARED;
        return ActionStatus.progressed();
    }

    private ActionStatus waitOldCleared() {
        Optional<Ae2MachineReads.PatternEncodingMenuView> view = reads.openPatternEncodingMenu();
        if (view.isPresent() && !view.get().hasEncodedPattern()) {
            stage = Stage.SET_MODE_AND_CLEAR;
            return ActionStatus.progressed();
        }
        if (++waited <= Ae2MenuActionSupport.SYNC_WAIT_TICKS) return ActionStatus.running();
        return beginClose(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                "输出槽里已有编码样板且无法取回背包（可能背包已满），拒绝覆盖或洗掉旧样板"));
    }

    // 处理单项配置或为完整编码切换目标模式并清空旧幽灵格。
    private ActionStatus setModeAndClear() {
        Optional<Ae2MachineReads.PatternEncodingMenuView> view = reads.openPatternEncodingMenu();
        if (view.isEmpty()) {
            if (++waited <= Ae2MenuActionSupport.SYNC_WAIT_TICKS) return ActionStatus.running();
            return beginClose(Problem.of(Problem.Kind.STUCK, "样板编码终端界面同步超时"));
        }
        if (request == null) {
            return applySingleSetting(view.get());
        }
        if (!reads.sendEncodeMode(request.mode())) {
            return beginClose(Problem.of(Problem.Kind.REFUSED_BY_GAME, "无法切换编码模式至 " + request.mode()));
        }
        reads.sendEncodeClear();
        if (request.substitute() != null) reads.sendEncodeSubstitute(request.substitute());
        if (request.substituteFluids() != null) reads.sendEncodeSubstituteFluids(request.substituteFluids());
        waited = 0;
        stage = Stage.WAIT_MODE_SYNC;
        return ActionStatus.progressed();
    }

    private ActionStatus applySingleSetting(Ae2MachineReads.PatternEncodingMenuView current) {
        boolean sent = switch (singleKey) {
            case "encode_mode" -> reads.sendEncodeMode(PatternEncodingRequest.normalizeMode(singleValue));
            case "substitute" -> reads.sendEncodeSubstitute(Boolean.parseBoolean(singleValue));
            case "substitute_fluids" -> reads.sendEncodeSubstituteFluids(Boolean.parseBoolean(singleValue));
            case "stonecutting_recipe" -> reads.sendEncodeStonecuttingRecipe(singleValue);
            default -> false;
        };
        if (!sent) {
            return beginClose(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                    "无法发送样板编码终端设置 " + singleKey + "=" + singleValue));
        }
        String mode = "encode_mode".equals(singleKey)
                ? PatternEncodingRequest.normalizeMode(singleValue) : current.mode();
        boolean sub = "substitute".equals(singleKey) ? Boolean.parseBoolean(singleValue) : current.substitute();
        boolean subFluids = "substitute_fluids".equals(singleKey)
                ? Boolean.parseBoolean(singleValue) : current.substituteFluids();
        String stone = "stonecutting_recipe".equals(singleKey) ? singleValue : current.stonecuttingRecipeId();
        Ae2MachineCache.EncodingCache prev = cache.encoding(host, side).orElse(null);
        cache.putEncoding(host, side, new Ae2MachineCache.EncodingCache(mode, sub, subFluids, stone,
                prev == null ? "" : prev.lastRequest(), prev == null ? "" : prev.lastOutput()));
        return beginClose(null);
    }

    // 不仅要核对模式已切到目标模式，还要等待 clear() 确实把旧网格清空，防止同模式下旧配方残留绕过后续校验。
    private ActionStatus waitModeSync() {
        Optional<Ae2MachineReads.PatternEncodingMenuView> view = reads.openPatternEncodingMenu();
        if (view.isPresent()
                && request.mode().equals(PatternEncodingRequest.normalizeMode(view.get().mode()))
                && isGridCleared(view.get())) {
            stage = Stage.WRITE_SLOTS;
            waited = 0;
            return ActionStatus.progressed();
        }
        if (++waited <= Ae2MenuActionSupport.SYNC_WAIT_TICKS) return ActionStatus.running();
        return beginClose(Problem.of(Problem.Kind.STUCK,
                "等待样板编码终端切换模式 " + request.mode() + " 并清空旧网格超时"));
    }

    /** 判定编码终端旧网格是否已被 clear() 清空：四个模式的残留结果/输入均为空才算干净。 */
    static boolean isGridCleared(Ae2MachineReads.PatternEncodingMenuView view) {
        if (view == null) return false;
        boolean craftEmpty = view.craftingResultId() == null || view.craftingResultId().isEmpty();
        boolean procEmpty = !view.processingInputNonEmpty() && !view.processingPrimaryOutputNonEmpty();
        boolean stoneEmpty = view.stonecuttingRecipeId() == null || view.stonecuttingRecipeId().isEmpty();
        return craftEmpty && procEmpty && stoneEmpty && !view.smithingValid();
    }

    // 逐格写入幽灵槽：处理模式支持流体与 >64 数量（经 GenericStack.wrapInItemStack 包装）。
    private ActionStatus writeSlots() {
        boolean ok = switch (request.mode()) {
            case "crafting" -> writeSlotList("CRAFTING_GRID", request.inputs(), false, true);
            case "processing" -> writeProcessingSlots();
            case "stonecutting" -> writeStonecuttingSlots();
            case "smithing" -> writeSmithingSlots();
            default -> false;
        };
        if (!ok) {
            return beginClose(Problem.of(Problem.Kind.INVALID_PARAMETER,
                    "无法向样板编码终端写入目标模式的幽灵槽（请检查物品、流体或化学品 ID）"));
        }
        waited = 0;
        stage = Stage.WAIT_RECIPE_SYNC;
        return ActionStatus.progressed();
    }

    private boolean writeProcessingSlots() {
        return writeSlotList("PROCESSING_INPUTS", request.inputs(), true, false)
                && writeSlotList("PROCESSING_OUTPUTS", request.outputs(), true, false);
    }

    private boolean writeStonecuttingSlots() {
        if (!writeSlotList("STONECUTTING_INPUT", List.of(request.inputs().getFirst()), false, false)) return false;
        return reads.sendEncodeStonecuttingRecipe(request.stonecuttingRecipe());
    }

    private boolean writeSmithingSlots() {
        return writeSingleSemanticSlot("SMITHING_TABLE_TEMPLATE", request.inputs().get(0))
                && writeSingleSemanticSlot("SMITHING_TABLE_BASE", request.inputs().get(1))
                && writeSingleSemanticSlot("SMITHING_TABLE_ADDITION", request.inputs().get(2));
    }

    private boolean writeSingleSemanticSlot(String semantic, PatternEncodingRequest.SlotEntry entry) {
        List<Integer> slots = compat.menuSlotsOf(semantic);
        if (slots.isEmpty()) return false;
        ItemStack stack = buildGhostStack(entry, false);
        if (stack.isEmpty()) return false;
        return compat.sendSetFilter(slots.getFirst(), stack);
    }

    private boolean writeSlotList(String semantic, List<PatternEncodingRequest.SlotEntry> entries,
            boolean allowGenericWrap, boolean clearUnusedSlots) {
        List<Integer> slots = compat.menuSlotsOf(semantic);
        if (slots.isEmpty()) return false;
        int limit = clearUnusedSlots ? slots.size() : Math.min(slots.size(), entries.size());
        for (int i = 0; i < limit; i++) {
            PatternEncodingRequest.SlotEntry entry = i < entries.size() ? entries.get(i) : null;
            if (entry == null || entry.isEmpty()) {
                if (clearUnusedSlots && !compat.sendSetFilter(slots.get(i), ItemStack.EMPTY)) return false;
                continue;
            }
            ItemStack ghost = buildGhostStack(entry, allowGenericWrap);
            if (ghost.isEmpty()) return false;
            if (!compat.sendSetFilter(slots.get(i), ghost)) return false;
        }
        return true;
    }

    private ItemStack buildGhostStack(PatternEncodingRequest.SlotEntry entry, boolean allowGenericWrap) {
        if (entry.isEmpty()) return ItemStack.EMPTY;
        // 流体、化学品或处理模式下超过 64 的数量必须通过 GenericStack.wrapInItemStack 包装后发 SET_FILTER。
        if (entry.requiresGenericWrap() || (allowGenericWrap && entry.amount() > 64)) {
            if (!allowGenericWrap) return ItemStack.EMPTY;
            return reads.wrapGenericStack(entry.kind(), entry.id(), entry.amount());
        }
        ResourceLocation key = ResourceLocation.tryParse(entry.id());
        if (key == null) return ItemStack.EMPTY;
        Item item = BuiltInRegistries.ITEM.getOptional(key).orElse(Items.AIR);
        if (item == Items.AIR) return ItemStack.EMPTY;
        return new ItemStack(item, (int) Math.max(1, Math.min(64, entry.amount())));
    }

    // 关键防洗样板门槛：先等至少一刻让服务端新槽位同步回来，再确认当前模式配方有效，无效绝不发 encode()！
    private ActionStatus waitRecipeSync() {
        if (++waited == 1) return ActionStatus.running();
        Optional<Ae2MachineReads.PatternEncodingMenuView> view = reads.openPatternEncodingMenu();
        if (view.isPresent()) {
            Optional<String> invalid = PatternEncodingRequest.validateMenuBeforeEncode(request, view.get());
            if (invalid.isEmpty()) {
                stage = Stage.ENSURE_BLANK;
                waited = 0;
                return ActionStatus.progressed();
            }
            if (waited > Ae2MenuActionSupport.SYNC_WAIT_TICKS) {
                return beginClose(Problem.of(Problem.Kind.INVALID_PARAMETER, invalid.get()));
            }
            return ActionStatus.running();
        }
        if (waited <= Ae2MenuActionSupport.SYNC_WAIT_TICKS) return ActionStatus.running();
        return beginClose(Problem.of(Problem.Kind.STUCK, "等待样板编码配方核验同步超时"));
    }

    // 确保空白样板槽里有 ae2:blank_pattern，没有则从角色背包移入，背包也没有报 NEED_ITEM。
    private ActionStatus ensureBlankPattern() {
        Optional<Ae2MachineReads.PatternEncodingMenuView> view = reads.openPatternEncodingMenu();
        if (view.isEmpty()) {
            if (++waited <= Ae2MenuActionSupport.SYNC_WAIT_TICKS) return ActionStatus.running();
            return beginClose(Problem.of(Problem.Kind.STUCK, "读取空白样板槽超时"));
        }
        if (view.get().blankPatternCount() > 0) {
            stage = Stage.COMMIT_ENCODE;
            return ActionStatus.progressed();
        }
        Optional<MenuContent.Reading> reading = menu.reading();
        if (reading.isEmpty()) {
            if (++waited <= Ae2MenuActionSupport.SYNC_WAIT_TICKS) return ActionStatus.running();
            return beginClose(Problem.of(Problem.Kind.STUCK, "读取背包空白样板超时"));
        }
        int playerBlankSlot = findBlankPatternSlot(reading.get());
        if (playerBlankSlot < 0) {
            return beginClose(Problem.of(Problem.Kind.NEED_ITEM,
                    "缺少空白样板（ae2:blank_pattern），请先备好空白样板再编码"));
        }
        if (!menu.quickMove(playerBlankSlot)) {
            if (++waited <= Ae2MenuActionSupport.SYNC_WAIT_TICKS) return ActionStatus.running();
            return beginClose(Problem.of(Problem.Kind.STUCK, "无法将背包里的空白样板移入样板编码终端"));
        }
        waited = 0;
        stage = Stage.COMMIT_ENCODE;
        return ActionStatus.progressed();
    }

    private ActionStatus commitEncode() {
        Optional<Ae2MachineReads.PatternEncodingMenuView> view = reads.openPatternEncodingMenu();
        if (view.isEmpty()) return ActionStatus.running();
        if (view.get().blankPatternCount() <= 0) {
            if (++waited <= Ae2MenuActionSupport.SYNC_WAIT_TICKS) return ActionStatus.running();
            return beginClose(Problem.of(Problem.Kind.NEED_ITEM, "空白样板未能放入样板编码终端"));
        }
        // 发送 encode() 前最后一次防洗样板确认。
        Optional<String> invalid = PatternEncodingRequest.validateMenuBeforeEncode(request, view.get());
        if (invalid.isPresent()) {
            return beginClose(Problem.of(Problem.Kind.INVALID_PARAMETER, invalid.get()));
        }
        if (!reads.sendEncodeCommit()) {
            return beginClose(Problem.of(Problem.Kind.REFUSED_BY_GAME, "发送样板编码指令失败"));
        }
        waited = 0;
        stage = Stage.WAIT_ENCODED;
        return ActionStatus.progressed();
    }

    private ActionStatus waitEncoded() {
        Optional<Ae2MachineReads.PatternEncodingMenuView> view = reads.openPatternEncodingMenu();
        if (view.isPresent() && view.get().hasEncodedPattern()) {
            encodedOutputNote = view.get().encodedPatternOutputId() == null ? "" : view.get().encodedPatternOutputId();
            stage = Stage.TAKE_ENCODED;
            waited = 0;
            return ActionStatus.progressed();
        }
        if (++waited <= Ae2MenuActionSupport.SYNC_WAIT_TICKS) return ActionStatus.running();
        return beginClose(Problem.of(Problem.Kind.STUCK, "已发编码指令，但输出槽一直没出现已编码样板"));
    }

    private ActionStatus takeEncoded() {
        List<Integer> outSlots = compat.menuSlotsOf("ENCODED_PATTERN");
        if (outSlots.isEmpty()) {
            return beginClose(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "找不到输出样板槽以收回编码样板"));
        }
        if (!menu.quickMove(outSlots.getFirst())) {
            if (++waited <= Ae2MenuActionSupport.SYNC_WAIT_TICKS) return ActionStatus.running();
            return beginClose(Problem.of(Problem.Kind.STUCK, "无法从输出槽收回刚编好的样板"));
        }
        waited = 0;
        stage = Stage.WAIT_TAKEN;
        return ActionStatus.progressed();
    }

    private ActionStatus waitTaken() {
        Optional<Ae2MachineReads.PatternEncodingMenuView> view = reads.openPatternEncodingMenu();
        if (view.isPresent() && !view.get().hasEncodedPattern()) {
            Ae2MachineReads.PatternEncodingMenuView snap = view.get();
            cache.putEncoding(host, side, new Ae2MachineCache.EncodingCache(
                    snap.mode(), snap.substitute(), snap.substituteFluids(),
                    snap.stonecuttingRecipeId(), rawRequestValue, encodedOutputNote));
            return beginClose(null);
        }
        if (++waited <= Ae2MenuActionSupport.SYNC_WAIT_TICKS) return ActionStatus.running();
        return beginClose(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "已编出样板但无法取回背包（可能背包已满）"));
    }

    private static int findBlankPatternSlot(MenuContent.Reading reading) {
        for (int i = 0; i < reading.playerSnapshots().size(); i++) {
            SlotSnapshot snap = reading.playerSnapshots().get(i);
            if (!snap.isEmpty() && BLANK_PATTERN_ID.equals(snap.key())) {
                return reading.playerSlotIds().get(i);
            }
        }
        return -1;
    }

    private ActionStatus closeMenu(TickContext tick) {
        if (closing == null) closing = menu.closing();
        ActionStatus status = closing.tick(tick);
        if (status instanceof ActionStatus.Running) return status;
        closing = null;
        return finish(problem);
    }

    private ActionStatus beginClose(Problem cause) {
        problem = cause;
        stage = Stage.CLOSE;
        return ActionStatus.progressed();
    }

    private ActionStatus finish(Problem cause) {
        problem = cause;
        stage = Stage.DONE;
        return cause == null ? ActionStatus.done() : ActionStatus.failed(cause);
    }

    @Override public void pause() {
        if (closing != null) closing.pause();
        else if (menu == null) opening.pause();
    }

    @Override public void close() {
        if (closing != null) closing.close();
        if (menu != null && stage != Stage.DONE) menu.abandon();
        else if (menu == null) opening.close();
    }

    @Override public Interruptibility interruptibility() { return Interruptibility.WORKING; }

    @Override public String describe() {
        return request == null
                ? "调整样板编码终端设置 " + singleKey + "=" + singleValue
                : "在样板编码终端按 " + request.mode() + " 模式编码样板";
    }
}
