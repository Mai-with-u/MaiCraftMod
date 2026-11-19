// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;

import org.maiwithu.maicraft.behavior.inventory.containers.RareLootNotice;
import org.maiwithu.maicraft.behavior.inventory.spi.NetworkStorage;
import org.maiwithu.maicraft.behavior.menu.ClientMenuChannel;
import org.maiwithu.maicraft.behavior.menu.ClientMenuContent;
import org.maiwithu.maicraft.behavior.menu.ClientOpenedMenu;
import org.maiwithu.maicraft.behavior.menu.MenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.menu.MenuSession;
import org.maiwithu.maicraft.behavior.menu.OpenScreen;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 看看点开的界面的生产实现：认领这一次点开的那份界面，等内容同步完列出里面有什么，再关上。
 *
 * <p>每次点开都新建读端，绑上的就是这一份界面（读端只绑一次，复用上一次的会一直读旧的那份）。
 * 认界面用联动登记表合并后的布局：联动证明过的模组界面（女仆界面这类）也列得出内容。
 * 界面认不出（村民交易这类）或内容一直没同步完时列不出内容，照样关上：打开者负责关闭。
 * 关闭走界面会话，一份会话从头用到尾，只请求游戏关一次。
 */
final class LiveMenuLooks implements UseSeams.LooksInMenus {

    /** 等界面内容同步完的期限（刻）；等不到就不列，不把没同步的界面当成空的。 */
    static final int SYNC_WAIT_TICKS = 40;

    private final Supplier<PlayerContext> context;
    private final MenuLayouts layouts;
    private final Supplier<Optional<NetworkStorage>> networkStorage;

    /** @param layouts 认得出哪些界面：原版加上联动模组证明过的 */
    LiveMenuLooks(Supplier<PlayerContext> context, MenuLayouts layouts) {
        this(context, layouts, Optional::empty);
    }

    LiveMenuLooks(Supplier<PlayerContext> context, MenuLayouts layouts,
            Supplier<Optional<NetworkStorage>> networkStorage) {
        this.context = Objects.requireNonNull(context, "context");
        this.layouts = Objects.requireNonNull(layouts, "layouts");
        this.networkStorage = Objects.requireNonNull(networkStorage, "networkStorage");
    }

    @Override
    public Optional<UseSeams.MenuLook> opened(int menuBefore) {
        return opened(menuBefore, null, null);
    }

    @Override
    public Optional<UseSeams.MenuLook> opened(int menuBefore, BlockPos clickedCell, Direction clickedFace) {
        return opened(menuBefore, clickedCell, clickedFace, null);
    }

    @Override
    public Optional<UseSeams.MenuLook> opened(int menuBefore, BlockPos clickedCell, Direction clickedFace,
            String insertItem) {
        PlayerContext current = context.get();
        LocalPlayer player = current == null ? null : current.localPlayer();
        if (player == null || player.containerMenu == player.inventoryMenu
                || player.containerMenu.containerId == menuBefore) {
            return Optional.empty();
        }
        // 优先让网络存储认领 ME 终端界面（线缆面板终端或随身无线终端）：读真实网络存货，不当成空箱子写世界记忆。
        Optional<NetworkStorage.TerminalLook> terminalLook = networkStorage.get()
                .flatMap(storage -> storage.openTerminalLook(clickedCell, clickedFace));
        if (terminalLook.isPresent()) {
            return Optional.of(new TerminalLookView(terminalLook.get()));
        }
        ClientMenuChannel channel = ClientMenuChannel.claimCurrent(context);
        if (channel == null) return Optional.empty();
        ClientMenuContent menuContent = new ClientMenuContent(context, layouts);
        OpenedMenu openedMenu = insertItem == null ? null : ClientOpenedMenu.claimCurrent(context, menuContent);
        int selectedHotbar = player.getInventory().selected;
        return Optional.of(new Look(channel, menuContent, openedMenu, insertItem, selectedHotbar));
    }

    /** 把联动交来的终端界面查看器适配为 {@code use} 的界面查看接缝。 */
    private static final class TerminalLookView implements UseSeams.MenuLook {
        private final NetworkStorage.TerminalLook delegate;

        TerminalLookView(NetworkStorage.TerminalLook delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override public Optional<Map<String, Integer>> contents() { return Optional.empty(); }
        @Override public boolean isNetworkTerminal() { return true; }
        @Override public Optional<String> networkTerminalSummary() { return delegate.summary(); }
        @Override public ActionStatus tick(TickContext tick) { return delegate.tick(tick); }
        @Override public Interruptibility interruptibility() { return delegate.interruptibility(); }
        @Override public void pause() { delegate.pause(); }
        @Override public boolean stopSafely() { return delegate.stopSafely(); }
        @Override public boolean stoppedSafely() { return delegate.stoppedSafely(); }
        @Override public void close() { delegate.close(); }
        @Override public String describe() { return delegate.describe(); }
    }

    /** 看一次界面：等同步 → （若需要且是已支持模组界面，把手中指定物品放进空槽）→ 列内容 → 关上。 */
    static final class Look implements UseSeams.MenuLook {

        private enum Stage { WAIT_SYNC, INSERT_CLICK, INSERT_WAIT, CLOSE, DONE }

        /** 放进模组界面空槽后等槽位变化落定的宽限（刻）。 */
        private static final int INSERT_SETTLE_TICKS = 10;

        private final MenuChannel channel;
        private final MenuContent content;
        private final OpenedMenu openedMenu;
        private final String insertItem;
        private final int selectedHotbar;
        private Stage stage = Stage.WAIT_SYNC;
        private int waited;
        private int insertSlot = -1;
        private int countBeforeInsert;
        private int insertWaits;
        private Insertion insertion = Insertion.NONE;
        private Map<String, Integer> contents;
        private List<RareLootNotice.SeenPrecious> preciousItems = List.of();
        private boolean unrecognized;
        private MenuSession session;

        Look(MenuChannel channel, MenuContent content, OpenedMenu openedMenu, String insertItem, int selectedHotbar) {
            this.channel = channel;
            this.content = content;
            this.openedMenu = openedMenu;
            this.insertItem = insertItem;
            this.selectedHotbar = selectedHotbar;
        }

        @Override public Optional<Map<String, Integer>> contents() {
            return Optional.ofNullable(contents);
        }

        @Override public Insertion insertion() {
            return insertion;
        }

        @Override public List<RareLootNotice.SeenPrecious> preciousItems() {
            return preciousItems;
        }

        @Override public boolean unrecognizedLayout() {
            return unrecognized;
        }

        @Override public ActionStatus tick(TickContext tick) {
            return switch (stage) {
                case WAIT_SYNC -> waitSync();
                case INSERT_CLICK -> insertClick();
                case INSERT_WAIT -> insertWait();
                case CLOSE -> closeMenu(tick);
                case DONE -> ActionStatus.done();
            };
        }

        // 同步完了先判断是否需要顺手把手中物品放进已支持模组界面的空槽（如驱动器插入存储元件），再列内容并关上。
        private ActionStatus waitSync() {
            if (content.unrecognizedLayout()) {
                unrecognized = true;
                stage = Stage.CLOSE;
                return ActionStatus.progressed();
            }
            Optional<MenuContent.Reading> reading = content.current();
            if (reading.isPresent()) {
                MenuContent.Reading synced = reading.get();
                if (shouldTryInsert(synced)) {
                    return prepareInsert(synced);
                }
                captureReading(synced);
                stage = Stage.CLOSE;
                return ActionStatus.progressed();
            }
            if (++waited > SYNC_WAIT_TICKS || !channel.stillOpen()) {
                stage = Stage.CLOSE;
            }
            return ActionStatus.running();
        }

        private boolean shouldTryInsert(MenuContent.Reading reading) {
            return insertItem != null && openedMenu != null
                    && MenuLayout.kindOf(reading.slots().menuTypeId()) == OpenScreen.Kind.MOD;
        }

        // 挑出手中物品所在槽位准备快速移动进容器空槽；无空槽或物品不在背包里则如实标记拒收并仍列出界面明细。
        private ActionStatus prepareInsert(MenuContent.Reading reading) {
            boolean hasEmpty = reading.containerSnapshots().stream().anyMatch(SlotSnapshot::isEmpty);
            int slotId = findPlayerSlot(reading, insertItem, selectedHotbar);
            if (!hasEmpty || slotId < 0) {
                insertion = Insertion.REJECTED;
                captureReading(reading);
                stage = Stage.CLOSE;
                return ActionStatus.progressed();
            }
            insertSlot = slotId;
            countBeforeInsert = countInPlayer(reading, insertItem);
            insertWaits = 0;
            stage = Stage.INSERT_CLICK;
            return ActionStatus.progressed();
        }

        // 发出一次快速移动：本刻发不出（界面刚刷新或无交互机会）则下一刻再试，超出宽限再按拒收收尾。
        private ActionStatus insertClick() {
            if (!channel.stillOpen()) {
                stage = Stage.DONE;
                return ActionStatus.done();
            }
            if (openedMenu.busy()) return ActionStatus.running();
            if (openedMenu.quickMove(insertSlot)) {
                stage = Stage.INSERT_WAIT;
                insertWaits = 0;
                return ActionStatus.progressed();
            }
            if (++insertWaits > SYNC_WAIT_TICKS) {
                insertion = Insertion.REJECTED;
                content.current().ifPresent(this::captureReading);
                stage = Stage.CLOSE;
            }
            return ActionStatus.running();
        }

        // 等快速移动结清后按背包件数复核：件数减少即确认放进去了，刷新容器内容后关闭；未减少按槽满或拒收汇报。
        private ActionStatus insertWait() {
            if (!channel.stillOpen()) {
                stage = Stage.DONE;
                return ActionStatus.done();
            }
            if (openedMenu.busy()) return ActionStatus.running();
            Optional<MenuContent.Reading> latest = content.current();
            if (latest.isPresent() && countInPlayer(latest.get(), insertItem) < countBeforeInsert) {
                insertion = Insertion.INSERTED;
                captureReading(latest.get());
                stage = Stage.CLOSE;
                return ActionStatus.progressed();
            }
            if (++insertWaits >= INSERT_SETTLE_TICKS) {
                insertion = Insertion.REJECTED;
                latest.ifPresent(this::captureReading);
                stage = Stage.CLOSE;
                return ActionStatus.progressed();
            }
            return ActionStatus.running();
        }

        private void captureReading(MenuContent.Reading reading) {
            contents = reading.containerContents();
            preciousItems = RareLootNotice.collectInitial(reading.containerSnapshots());
        }

        // 优先挑主手选中快捷栏格，其次挑背包里第一个匹配该物品 ID 的格。
        private static int findPlayerSlot(MenuContent.Reading reading, String itemId, int selectedHotbar) {
            int fallback = -1;
            for (int i = 0; i < reading.playerSnapshots().size(); i++) {
                SlotSnapshot snap = reading.playerSnapshots().get(i);
                if (snap.isEmpty() || !idOf(snap).equals(itemId)) continue;
                int slotId = reading.playerSlotIds().get(i);
                if (reading.playerInventoryIndices().get(i) == selectedHotbar) return slotId;
                if (fallback < 0) fallback = slotId;
            }
            return fallback;
        }

        private static int countInPlayer(MenuContent.Reading reading, String itemId) {
            int total = 0;
            for (SlotSnapshot snap : reading.playerSnapshots()) {
                if (!snap.isEmpty() && idOf(snap).equals(itemId)) total += snap.count();
            }
            return total;
        }

        private static String idOf(SlotSnapshot snapshot) {
            return BuiltInRegistries.ITEM.getKey(snapshot.stack().getItem()).toString();
        }

        // 关上：一份会话从头用到尾；界面先一步没了（玩家关掉、被顶掉）也算关上了。
        private ActionStatus closeMenu(TickContext tick) {
            if (session == null) {
                MenuSession.Claim claim = MenuSession.claim(channel);
                if (claim instanceof MenuSession.Claim.Refused) {
                    // 认领不了：界面已经不在，或光标上挂着不是自己拿的东西；后者照样请游戏关一次，
                    // 光标上的东西由原版的关闭流程还回背包。
                    if (channel.stillOpen()) channel.requestClose();
                    stage = Stage.DONE;
                    return ActionStatus.done();
                }
                session = ((MenuSession.Claim.Owned) claim).session();
            }
            return switch (session.closeNow(channel, tick.player().clientTick())) {
                case MenuSession.Closing.Closed closed -> {
                    stage = Stage.DONE;
                    yield ActionStatus.done();
                }
                case MenuSession.Closing.Failed failed -> ActionStatus.failed(failed.problem());
                default -> ActionStatus.running();
            };
        }

        // 任务被取消时界面还开着：请游戏关一次，不把点开的界面留给玩家收拾。
        @Override public void close() {
            if (stage != Stage.DONE && channel.stillOpen()) {
                channel.requestClose();
            }
        }

        @Override public String describe() {
            return stage == Stage.WAIT_SYNC ? "等点开的界面同步完" : "关上点开的界面";
        }

        // 按物品合并的界面内容；数量大的全列，不悄悄截断。
    }
}
