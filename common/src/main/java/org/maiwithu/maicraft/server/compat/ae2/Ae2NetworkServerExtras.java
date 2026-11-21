// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.compat.ae2;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * AE2 服务端只读操作 ae2.network 的扩展详情接缝与 JSON 写出工具：
 * 在既有网络基础摘要（控制器、频道、能量、存储总量、合成 CPU 数量）之上，向后兼容地补充
 * 电网输入/消耗与按设备分组的耗电统计（{@code power_injection}、{@code power_usage}、
 * {@code channel_power}、{@code machines[]}）、各台合成 CPU 的详细状态与在途/待产物料明细
 * （{@code cpus[]}），以及全网样板容器分组与产出清单（{@code pattern_containers[]}）。
 * 仅做服务端只读汇总，不列仓库存货明细，也不替角色操作网络。
 */
public interface Ae2NetworkServerExtras {

    /** 没接扩展读写端时的空实现：不向 ae2.network 回答里追加扩展字段。 */
    Ae2NetworkServerExtras NONE = (level, at) -> Optional.empty();
    /** ae2.network 中 stored_chemicals[] 最多列出的化学品条目数（按存量降序），另附 chemical_types 总种类数。 */
    int MAX_LISTED_CHEMICALS = 32;

    /** 读取这一格所在 ME 网络的扩展明细；这一格不在任何 ME 网上时返回空。 */
    Optional<NetworkDetail> detail(ServerLevel level, BlockPos at);

    /**
     * 一张 ME 网络的扩展明细。
     *
     * @param powerInjection    平均能量输入（AE/t）
     * @param powerUsage        平均能量消耗（AE/t）
     * @param channelPower      频道本身消耗的能量（AE/t）
     * @param machines          按设备展示物品与缺频道状态分组后的耗电与发电能力清单
     * @param cpus              每台合成 CPU 的容量、协处理器、调度模式与作业明细
     * @param patternContainers 按样板容器分组汇总的总槽数、已用槽数与样板主产出清单
     * @param chemicalTypes     网内存储的化学品种类总数（仅统计 {@code appmek:chemical}）
     * @param storedChemicals   按数量降序排列的前若干种化学品存量清单（单位 mB）
     */
    record NetworkDetail(double powerInjection, double powerUsage, double channelPower,
                         List<MachineEntry> machines, List<CpuEntry> cpus,
                         List<PatternGroupEntry> patternContainers,
                         int chemicalTypes, List<StockAmount> storedChemicals) {
        public NetworkDetail {
            machines = List.copyOf(Objects.requireNonNull(machines, "machines"));
            cpus = List.copyOf(Objects.requireNonNull(cpus, "cpus"));
            patternContainers = List.copyOf(Objects.requireNonNull(patternContainers, "patternContainers"));
            chemicalTypes = Math.max(0, chemicalTypes);
            storedChemicals = List.copyOf(Objects.requireNonNull(storedChemicals, "storedChemicals"));
        }

        /** 兼容未统计化学品库存的旧调用方构造。 */
        public NetworkDetail(double powerInjection, double powerUsage, double channelPower,
                             List<MachineEntry> machines, List<CpuEntry> cpus,
                             List<PatternGroupEntry> patternContainers) {
            this(powerInjection, powerUsage, channelPower, machines, cpus, patternContainers, 0, List.of());
        }
    }

    /**
     * 网络里一类设备的分组耗电与发电统计。
     *
     * @param itemId                  展示物品注册 ID
     * @param displayName             设备展示名
     * @param count                   这一组设备台数
     * @param idlePowerUsage          空载总耗电（AE/t）
     * @param powerGenerationCapacity 总发电能力（AE/t）
     * @param missingChannel          是否因缺频道离线
     */
    record MachineEntry(String itemId, String displayName, int count, double idlePowerUsage,
                        double powerGenerationCapacity, boolean missingChannel) {
        public MachineEntry {
            itemId = Objects.requireNonNull(itemId, "itemId");
            displayName = Objects.requireNonNull(displayName, "displayName");
        }
    }

    /**
     * 一台合成 CPU 的权威状态与在途作业明细。
     *
     * @param serial       网内顺序编号（从 0 起）
     * @param name         自定义名称；未命名为空串
     * @param storage      可用合成存储字节数
     * @param coProcessors 协处理器并行线程数
     * @param mode         自动调度模式：any / player_only / machine_only
     * @param busy         是否正在执行合成作业
     * @param suspended    当前作业是否已暂停调度
     * @param cantStore    作业结束后是否因网络存储满溢而退不回物品
     * @param currentJob   当前最终产物标识与数量摘要；空闲为空串
     * @param progress     已完成产出数量
     * @param totalItems   总计划产出数量
     * @param active       正在加工等待回收的物料清单
     * @param pending      尚未推送到机器的待产物料清单
     */
    record CpuEntry(int serial, String name, long storage, int coProcessors, String mode,
                    boolean busy, boolean suspended, boolean cantStore, String currentJob,
                    long progress, long totalItems, List<StockAmount> active, List<StockAmount> pending) {
        public CpuEntry {
            name = name == null ? "" : name;
            mode = Objects.requireNonNull(mode, "mode");
            currentJob = currentJob == null ? "" : currentJob;
            active = List.copyOf(Objects.requireNonNull(active, "active"));
            pending = List.copyOf(Objects.requireNonNull(pending, "pending"));
        }
    }

    /** 一项合成在途或待产物料的标识与数量。 */
    record StockAmount(String keyId, long amount) {
        public StockAmount {
            keyId = Objects.requireNonNull(keyId, "keyId");
        }
    }

    /**
     * 样板管理终端视角的单组样板容器汇总。
     *
     * @param name       分组显示名（相邻机器名或自定义名）
     * @param iconItemId 分组图标物品 ID；没有为空串
     * @param totalSlots 该组样板总槽位数
     * @param usedSlots  已放样板的槽位数
     * @param outputs    各张样板的主产出标识列表
     */
    record PatternGroupEntry(String name, String iconItemId, int totalSlots, int usedSlots, List<String> outputs) {
        public PatternGroupEntry {
            name = Objects.requireNonNull(name, "name");
            iconItemId = iconItemId == null ? "" : iconItemId;
            outputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
        }
    }

    /**
     * 把网络扩展明细并入既有 ae2.network 回答对象：不覆盖已有基础字段，保持完全向后兼容。
     */
    static void appendTo(JsonObject target, NetworkDetail detail) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(detail, "detail");
        target.addProperty("power_injection", detail.powerInjection());
        target.addProperty("power_usage", detail.powerUsage());
        target.addProperty("channel_power", detail.channelPower());
        target.add("machines", machinesJson(detail.machines()));
        target.add("cpus", cpusJson(detail.cpus()));
        target.add("pattern_containers", patternContainersJson(detail.patternContainers()));
        if (detail.chemicalTypes() > 0 || !detail.storedChemicals().isEmpty()) {
            target.addProperty("chemical_types", detail.chemicalTypes());
            target.add("stored_chemicals", stocksJson(detail.storedChemicals()));
        }
    }

    /** 独立写出扩展明细 JSON（便于测试与外部汇总校验）。 */
    static JsonObject toJson(NetworkDetail detail) {
        JsonObject out = new JsonObject();
        appendTo(out, detail);
        return out;
    }

    private static JsonArray machinesJson(List<MachineEntry> machines) {
        JsonArray array = new JsonArray();
        for (MachineEntry entry : machines) {
            JsonObject obj = new JsonObject();
            obj.addProperty("item", entry.itemId());
            obj.addProperty("name", entry.displayName());
            obj.addProperty("count", entry.count());
            obj.addProperty("idle_power", entry.idlePowerUsage());
            obj.addProperty("generation_capacity", entry.powerGenerationCapacity());
            obj.addProperty("missing_channel", entry.missingChannel());
            array.add(obj);
        }
        return array;
    }

    private static JsonArray cpusJson(List<CpuEntry> cpus) {
        JsonArray array = new JsonArray();
        for (CpuEntry cpu : cpus) {
            JsonObject obj = new JsonObject();
            obj.addProperty("serial", cpu.serial());
            obj.addProperty("name", cpu.name());
            obj.addProperty("storage", cpu.storage());
            obj.addProperty("co_processors", cpu.coProcessors());
            obj.addProperty("mode", cpu.mode());
            obj.addProperty("busy", cpu.busy());
            obj.addProperty("suspended", cpu.suspended());
            obj.addProperty("cant_store", cpu.cantStore());
            obj.addProperty("current_job", cpu.currentJob());
            obj.addProperty("progress", cpu.progress());
            obj.addProperty("total_items", cpu.totalItems());
            obj.add("active", stocksJson(cpu.active()));
            obj.add("pending", stocksJson(cpu.pending()));
            array.add(obj);
        }
        return array;
    }

    private static JsonArray stocksJson(List<StockAmount> items) {
        JsonArray array = new JsonArray();
        for (StockAmount item : items) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", item.keyId());
            obj.addProperty("amount", item.amount());
            array.add(obj);
        }
        return array;
    }

    private static JsonArray patternContainersJson(List<PatternGroupEntry> groups) {
        JsonArray array = new JsonArray();
        for (PatternGroupEntry group : groups) {
            JsonObject obj = new JsonObject();
            obj.addProperty("name", group.name());
            obj.addProperty("icon", group.iconItemId());
            obj.addProperty("total_slots", group.totalSlots());
            obj.addProperty("used_slots", group.usedSlots());
            JsonArray outputs = new JsonArray();
            for (String output : group.outputs()) {
                outputs.add(output);
            }
            obj.add("outputs", outputs);
            array.add(obj);
        }
        return array;
    }
}
