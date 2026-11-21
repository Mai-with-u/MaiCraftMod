// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import net.minecraft.world.item.ItemStack;

/**
 * 跨联动模组共享的外部资源键包装登记表：
 * 让提供额外介质键类型的附属模组（如应用机械的 {@code appmek:chemical}）登记把资源 ID 与数量包装为
 * {@link ItemStack} 的实现，供需要向幽灵过滤槽或处理样板槽写入该介质的联动模组按需查询，
 * 彼此无需互相依赖，也不引入任何静态可变全局状态。
 */
public final class ExternalKeyWrappers {

    /** 空登记表常量：不接受登记，供无需外部扩展键的旧构造与离线测试复用。 */
    public static final ExternalKeyWrappers NONE = new ExternalKeyWrappers(true);

    /** 把某个外部介质注册 ID 与正数量包装成可发给界面的 {@link ItemStack}；无法识别时返回空。 */
    @FunctionalInterface
    public interface KeyWrapper {

        /** 将资源 ID 与正数量包装为物品堆；资源不存在、数量非正或附属停用时返回空。 */
        Optional<ItemStack> wrap(String resourceId, long amount);
    }

    private record Entry(BooleanSupplier available, KeyWrapper wrapper) {}

    private final boolean readOnly;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /** 新建一份可登记的外部键包装表，在启动时创建并传给提供方与使用方。 */
    public ExternalKeyWrappers() {
        this(false);
    }

    private ExternalKeyWrappers(boolean readOnly) {
        this.readOnly = readOnly;
    }

    /** 登记一种外部键类型的包装实现（默认始终可用）。 */
    public void register(String keyTypeId, KeyWrapper wrapper) {
        register(keyTypeId, () -> true, wrapper);
    }

    /**
     * 登记一种外部键类型的包装实现并绑定可用性检查：
     * 对应联动模组停用后，{@link #supports} 立即回答否、{@link #wrap} 立即返回空，确保隔离安全。
     */
    public void register(String keyTypeId, BooleanSupplier available, KeyWrapper wrapper) {
        if (readOnly) {
            throw new UnsupportedOperationException("ExternalKeyWrappers.NONE 为只读空表，不能登记外部键包装器");
        }
        String normalized = normalizeKeyType(keyTypeId);
        Objects.requireNonNull(available, "available");
        Objects.requireNonNull(wrapper, "wrapper");
        entries.put(normalized, new Entry(available, wrapper));
    }

    /** 撤销指定外部键类型的包装登记（例如联动初始化回滚或运行时停用）。 */
    public void unregister(String keyTypeId) {
        if (readOnly || keyTypeId == null || keyTypeId.isBlank()) return;
        entries.remove(normalizeKeyType(keyTypeId));
    }

    /** 判断指定外部键类型此刻是否已登记且仍处于可用状态。 */
    public boolean supports(String keyTypeId) {
        if (keyTypeId == null || keyTypeId.isBlank()) return false;
        Entry entry = entries.get(normalizeKeyType(keyTypeId));
        return entry != null && entry.available().getAsBoolean();
    }

    /** 按外部键类型将指定资源 ID 与正数量包装成 {@link ItemStack}；未登记、已停用或包装失败时返回空。 */
    public Optional<ItemStack> wrap(String keyTypeId, String resourceId, long amount) {
        if (keyTypeId == null || keyTypeId.isBlank() || resourceId == null || resourceId.isBlank() || amount <= 0L) {
            return Optional.empty();
        }
        Entry entry = entries.get(normalizeKeyType(keyTypeId));
        if (entry == null || !entry.available().getAsBoolean()) {
            return Optional.empty();
        }
        Optional<ItemStack> wrapped = entry.wrapper().wrap(resourceId.trim(), amount);
        if (wrapped == null || wrapped.isEmpty() || wrapped.get().isEmpty()) {
            return Optional.empty();
        }
        return wrapped;
    }

    private static String normalizeKeyType(String keyTypeId) {
        Objects.requireNonNull(keyTypeId, "keyTypeId");
        String normalized = keyTypeId.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("外部键类型 ID 不能为空");
        }
        return normalized;
    }
}
