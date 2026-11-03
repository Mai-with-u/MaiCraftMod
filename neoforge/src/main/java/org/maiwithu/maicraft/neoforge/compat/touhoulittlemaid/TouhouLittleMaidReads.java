// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.touhoulittlemaid;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTaskEnableEvent;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.AIChatScreen;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.other.CheckSchedulePosGui;
import com.github.tartaricacid.touhoulittlemaid.client.resource.CustomPackLoader;
import com.github.tartaricacid.touhoulittlemaid.config.subconfig.AIConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.ChatBubbleDataCollection;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.ChatBubbleRegister;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.IChatBubbleData;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.implement.TextChatBubbleData;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.implement.WaitingChatBubbleData;
import com.github.tartaricacid.touhoulittlemaid.entity.item.EntityTombstone;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.github.tartaricacid.touhoulittlemaid.util.ParseI18n;
import com.mojang.datafixers.util.Pair;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.schedule.Activity;
import net.neoforged.neoforge.common.NeoForge;

import org.maiwithu.maicraft.compat.touhoulittlemaid.MaidReads;
import org.maiwithu.maicraft.compat.touhoulittlemaid.MaidState;

/**
 * 车万女仆的读端：读女仆同步给周围玩家的实体数据（皮肤、工作、班次、跟随或驻守、好感、气泡）、本机模型表、
 * 眼前的对话框。文字气泡的正文没有读法，用车万女仆登记的那份序列化器写进临时缓冲区再按同样的格式读回来，
 * 不用反射。只翻译，不判断。
 */
public final class TouhouLittleMaidReads implements MaidReads {

    /** 和没有预设人设的女仆说第一句时，她只发这句的翻译键。 */
    private static final String PERSONA_KEY = "ai.touhou_little_maid.chat.llm.auto_gen_setting";

    @Override public boolean isMaid(Entity entity) {
        return entity instanceof EntityMaid;
    }

    @Override public MaidState state(Entity entity) {
        EntityMaid maid = (EntityMaid) entity;
        String skinId = maid.getModelId();
        String skinName = CustomPackLoader.MAID_MODELS.getInfo(skinId)
                .map(info -> ParseI18n.parse(info.getName()).getString()).orElse(null);
        return new MaidState(maid.getId(), maid.getName().getString(), skinId, skinName,
                maid.getTask().getUid().toString(), maid.getTask().getName().getString(),
                shift(maid.getSchedule()), shiftNow(maid.getScheduleDetail()), maid.isHomeModeEnable(),
                maid.getRestrictCenter(), maid.getRestrictRadius(), maid.isPickup(), maid.isRideable(),
                maid.getFavorability(), maid.getExperience(), maid.isBegging(), maid.getIsInvulnerable(),
                maid.getMaidBackpackType().getId().toString(), bubbles(maid));
    }

    private static MaidState.Shift shift(MaidSchedule schedule) {
        return switch (schedule) {
            case DAY -> MaidState.Shift.DAY;
            case NIGHT -> MaidState.Shift.NIGHT;
            case ALL -> MaidState.Shift.ALL;
        };
    }

    // 车万女仆按班次与世界时刻算好的此刻活动：WORK 工作、IDLE 休息、REST 睡觉。
    private static MaidState.ShiftNow shiftNow(Activity activity) {
        if (activity == Activity.WORK) return MaidState.ShiftNow.WORK;
        if (activity == Activity.IDLE) return MaidState.ShiftNow.REST;
        return MaidState.ShiftNow.SLEEP;
    }

    // 她头上的气泡按编号（到期时刻）先后：文字气泡读回正文，在想的气泡读副文本（她在调哪个工具）。
    private static List<MaidState.Bubble> bubbles(EntityMaid maid) {
        ChatBubbleDataCollection collection = maid.getChatBubbleManager().getChatBubbleDataCollection();
        List<MaidState.Bubble> bubbles = new ArrayList<>();
        for (long key : collection.keySet()) {
            IChatBubbleData data = collection.get(key);
            if (data instanceof TextChatBubbleData) {
                bubbles.add(new MaidState.Bubble(key, MaidState.Bubble.Kind.TEXT, text(data)));
            } else if (data instanceof WaitingChatBubbleData waiting) {
                Component note = waiting.getSecondaryText();
                bubbles.add(new MaidState.Bubble(key, MaidState.Bubble.Kind.THINKING,
                        note == null ? null : note.getString()));
            } else {
                bubbles.add(new MaidState.Bubble(key, MaidState.Bubble.Kind.OTHER, null));
            }
        }
        return bubbles;
    }

    // 文字气泡的正文：序列化器先写正文再写背景图，写进去再把头一段读回来。
    private static String text(IChatBubbleData data) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ChatBubbleRegister.CODEC_MAP.get(TextChatBubbleData.ID).writeToBuff(buffer, data);
            return buffer.readJsonWithCodec(ComponentSerialization.CODEC).getString();
        } finally {
            buffer.release();
        }
    }

    @Override public Optional<String> tombstoneName(Entity entity) {
        if (!(entity instanceof EntityTombstone tombstone)) return Optional.empty();
        return Optional.of(tombstone.getMaidName().getString());
    }

    @Override public Map<String, String> skins() {
        Map<String, String> skins = new TreeMap<>();
        for (String id : CustomPackLoader.MAID_MODELS.getModelIdSet()) {
            String name = CustomPackLoader.MAID_MODELS.getInfo(id)
                    .map(info -> ParseI18n.parse(info.getName()).getString()).orElse(id);
            skins.put(id, name);
        }
        return skins;
    }

    @Override public boolean aiChatEnabled() {
        return AIConfig.LLM_ENABLED.get();
    }

    @Override public boolean dialogOpenFor(int entityId) {
        return Minecraft.getInstance().screen instanceof AIChatScreen screen && screen.getMaid().getId() == entityId;
    }

    // 输入框是对话框里唯一的文字框：从它的子控件里找出来读字。
    @Override public String dialogText() {
        if (!(Minecraft.getInstance().screen instanceof AIChatScreen screen)) return null;
        for (GuiEventListener child : screen.children()) {
            if (child instanceof EditBox box) return box.getValue();
        }
        return null;
    }

    @Override public String personaGeneratedText() {
        return Component.translatable(PERSONA_KEY).getString();
    }

    // 她界面工作列表里的那几项：按钮灰不灰和界面一样判（事件拦了、或工作自己说不能用），条件文字照界面拼。
    @Override public List<Work> works(Entity entity) {
        EntityMaid maid = (EntityMaid) entity;
        List<Work> works = new ArrayList<>();
        for (IMaidTask task : TaskManager.getNotHiddenTaskList(maid)) {
            List<Pair<String, Predicate<EntityMaid>>> enableConditions = new ArrayList<>();
            boolean enabled = true;
            if (task != TaskManager.getIdleTask()) {
                if (NeoForge.EVENT_BUS.post(new MaidTaskEnableEvent(task, maid, enableConditions)).isCanceled()) {
                    enabled = false;
                } else if (!task.isEnable(maid)) {
                    enableConditions.addAll(task.getEnableConditionDesc(maid));
                    enabled = false;
                }
            }
            List<String> conditions = new ArrayList<>();
            String prefix = "task." + task.getUid().getNamespace() + "." + task.getUid().getPath();
            if (!enabled) conditions.addAll(lines(prefix + ".enable_condition.", enableConditions, maid));
            conditions.addAll(lines(prefix + ".condition.", task.getConditionDescription(maid), maid));
            works.add(new Work(task.getUid().toString(), task.getName().getString(), enabled, conditions));
        }
        return works;
    }

    private static List<String> lines(String keyPrefix, List<Pair<String, Predicate<EntityMaid>>> conditions,
            EntityMaid maid) {
        List<String> lines = new ArrayList<>();
        for (Pair<String, Predicate<EntityMaid>> line : conditions) {
            String text = Component.translatable(keyPrefix + line.getFirst()).getString();
            lines.add(text + "：" + (line.getSecond().test(maid) ? "满足" : "不满足"));
        }
        return lines;
    }

    @Override public boolean schedulePosScreenOpen() {
        return Minecraft.getInstance().screen instanceof CheckSchedulePosGui;
    }
}
