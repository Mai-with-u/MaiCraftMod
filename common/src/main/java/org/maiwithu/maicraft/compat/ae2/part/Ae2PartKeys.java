// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.part;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.Direction;

import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;

/**
 * AE2 线缆部件、方块 ME 接口、元件工作台、ME IO 传输站与无线访问点的设置键字典、
 * 选项表、前缀解析与值校验纯函数。集中维护一处口径，避免读设置与改设置各自拼键。
 */
final class Ae2PartKeys {

    /** 客户端未开窗且无服务端读数时的占位值：让配置任务校验放行，改完后立刻以缓存新值核对。 */
    static final String UNKNOWN = "unknown";
    static final String EMPTY = "empty";
    static final String ACTION_IDLE = "none";

    /** AE2 菜单注册 ID（全部与 AE2 19.2.17 源码 MenuTypeBuilder.build 名称一致）。 */
    static final String MENU_IMPORT_BUS = "ae2:import_bus";
    static final String MENU_EXPORT_BUS = "ae2:export_bus";
    static final String MENU_STORAGE_BUS = "ae2:storagebus";
    static final String MENU_INTERFACE = "ae2:interface";
    static final String MENU_SET_STOCK_AMOUNT = "ae2:set_stock_amount";
    static final String MENU_FORMATION_PLANE = "ae2:formationplane";
    static final String MENU_STORAGE_LEVEL_EMITTER = "ae2:storage_level_emitter";
    static final String MENU_ENERGY_LEVEL_EMITTER = "ae2:energy_level_emitter";
    static final String MENU_PRIORITY = "ae2:priority";
    static final String MENU_CELL_WORKBENCH = "ae2:cellworkbench";
    static final String MENU_CELL_TRANSFER = "ae2:ioport";
    static final String MENU_WIRELESS_ACCESS_POINT = "ae2:wireless_access_point";

    /** 常用物品 ID 与外部扩展键常量。 */
    static final String MEMORY_CARD = "ae2:memory_card";
    static final String CAPACITY_CARD = "ae2:capacity_card";
    static final String WIRELESS_BOOSTER = "ae2:wireless_booster";
    /** 应用机械化学品键类型 ID（字符串字面量，零模组类依赖）。 */
    static final String CHEMICAL_KEY_TYPE = "appmek:chemical";
    static final String CHEMICAL_PREFIX = "chemical:";
    static final String CHEMICAL_P2P_ITEM = "appmek:chemical_p2p_tunnel";

    /** 各配置项的标准选项列表（全小写）。 */
    static final List<String> REDSTONE_MODES = List.of("ignore", "low_signal", "high_signal", "signal_pulse");
    static final List<String> EMITTER_REDSTONE_MODES = List.of("high_signal", "low_signal");
    static final List<String> TRANSFER_REDSTONE_MODES = List.of("ignore", "high_signal", "low_signal");
    static final List<String> FUZZY_MODES = List.of("ignore_all", "percent_99", "percent_75", "percent_50", "percent_25");
    static final List<String> YES_NO = List.of("yes", "no");
    static final List<String> BOOL_CHOICES = List.of("true", "false");
    static final List<String> SCHEDULING_MODES = List.of("default", "roundrobin", "random");
    static final List<String> ACCESS_MODES = List.of("read_write", "read", "write");
    static final List<String> STORAGE_FILTER_MODES = List.of("extractable_only", "none");
    static final List<String> PLACE_MODES = List.of("block", "drop");
    static final List<String> PARTITION_ACTIONS = List.of("partition", "clear");
    static final List<String> COPY_MODES = List.of("clear_on_remove", "keep_on_remove");
    static final List<String> OPERATION_MODES = List.of("empty", "fill");
    static final List<String> FULLNESS_MODES = List.of("empty", "half", "full");
    static final List<String> P2P_ATTUNEMENTS = List.of("me", "redstone", "item", "fluid", "fe", "light");
    static final List<String> P2P_ATTUNEMENTS_WITH_CHEMICAL = List.of(
            "me", "redstone", "item", "fluid", "fe", "light", "chemical");
    static final List<String> P2P_ROLES = List.of("input", "output");

    /** 10 类线缆部件中容量卡未插时固定启用的基础过滤槽数（2 行 × 9 列）。F 游戏事实。 */
    static final int BASE_FILTER_SLOTS = 18;
    static final int MAX_FILTER_SLOTS = 63;
    static final int BUS_UPGRADE_SLOTS = 5;
    static final int INTERFACE_STOCK_SLOTS = 9;
    static final int WORKBENCH_UPGRADE_SLOTS = 8;
    static final int TRANSFER_UPGRADE_SLOTS = 3;
    static final int MAX_WIRELESS_BOOSTERS = 64;

    private static final Map<String, String> P2P_ITEM_TO_ATTUNEMENT = Map.of(
            "ae2:me_p2p_tunnel", "me",
            "ae2:redstone_p2p_tunnel", "redstone",
            "ae2:item_p2p_tunnel", "item",
            "ae2:fluid_p2p_tunnel", "fluid",
            "ae2:fe_p2p_tunnel", "fe",
            "ae2:light_p2p_tunnel", "light",
            CHEMICAL_P2P_ITEM, "chemical");

    private static final Map<String, String> ATTUNEMENT_TO_P2P_ITEM = Map.of(
            "me", "ae2:me_p2p_tunnel",
            "redstone", "ae2:redstone_p2p_tunnel",
            "item", "ae2:item_p2p_tunnel",
            "fluid", "ae2:fluid_p2p_tunnel",
            "fe", "ae2:fe_p2p_tunnel",
            "light", "ae2:light_p2p_tunnel",
            "chemical", CHEMICAL_P2P_ITEM);

    /** 线缆部件种类（按注册物品 ID 分类）。 */
    enum PartKind {
        IMPORT_BUS,
        EXPORT_BUS,
        STORAGE_BUS,
        INTERFACE,
        FORMATION_PLANE,
        ANNIHILATION_PLANE,
        LEVEL_EMITTER,
        ENERGY_LEVEL_EMITTER,
        STORAGE_MONITOR,
        CONVERSION_MONITOR,
        P2P_TUNNEL
    }

    /** 带面名前缀的设置键解析结果（例如 north.redstone_mode -> NORTH + redstone_mode）。 */
    record SideKey(Direction side, String localKey) {
    }

    /** ME 接口备料项值解析结果（empty 或 <item_or_fluid_id> [count]）。 */
    record StockValue(boolean empty, String targetId, Integer amount) {
    }

    /** 元件槽动作值解析结果（in:<item_id> 或 out）。 */
    record CellValue(boolean putIn, String itemId) {
    }

    private Ae2PartKeys() {
    }

    /** 按线缆部件物品 ID 识别它属于哪一类可管理部件；不在 10 类范围内的返回空。 */
    static Optional<PartKind> partKindOf(String itemId) {
        if (itemId == null) return Optional.empty();
        if (P2P_ITEM_TO_ATTUNEMENT.containsKey(itemId)) return Optional.of(PartKind.P2P_TUNNEL);
        return switch (itemId) {
            case "ae2:import_bus" -> Optional.of(PartKind.IMPORT_BUS);
            case "ae2:export_bus" -> Optional.of(PartKind.EXPORT_BUS);
            case "ae2:storage_bus" -> Optional.of(PartKind.STORAGE_BUS);
            case "ae2:cable_interface", "ae2:interface" -> Optional.of(PartKind.INTERFACE);
            case "ae2:formation_plane" -> Optional.of(PartKind.FORMATION_PLANE);
            case "ae2:annihilation_plane" -> Optional.of(PartKind.ANNIHILATION_PLANE);
            case "ae2:level_emitter" -> Optional.of(PartKind.LEVEL_EMITTER);
            case "ae2:energy_level_emitter" -> Optional.of(PartKind.ENERGY_LEVEL_EMITTER);
            case "ae2:storage_monitor" -> Optional.of(PartKind.STORAGE_MONITOR);
            case "ae2:conversion_monitor" -> Optional.of(PartKind.CONVERSION_MONITOR);
            default -> Optional.empty();
        };
    }

    /** P2P 通道部件物品 ID 转调谐类型名（me / redstone / item / fluid / fe / light）。 */
    static Optional<String> attunementOfPart(String partItemId) {
        return Optional.ofNullable(P2P_ITEM_TO_ATTUNEMENT.get(partItemId));
    }

    /** 调谐类型名转对应的 P2P 通道部件物品 ID。 */
    static Optional<String> p2pItemOf(String attunement) {
        if (attunement == null) return Optional.empty();
        return Optional.ofNullable(ATTUNEMENT_TO_P2P_ITEM.get(attunement.toLowerCase(Locale.ROOT)));
    }

    /** 六向方位的小写名（用于 <side>.<key> 前缀）。 */
    static String sideName(Direction side) {
        return side.getName().toLowerCase(Locale.ROOT);
    }

    /** 给局部设置列表批量加上 <side>. 前缀。 */
    static List<MachineSetting> prefix(Direction side, List<MachineSetting> local) {
        String pre = sideName(side) + ".";
        List<MachineSetting> prefixed = new ArrayList<>(local.size());
        for (MachineSetting setting : local) {
            prefixed.add(new MachineSetting(pre + setting.key(), setting.value(), setting.choices(),
                    setting.description()));
        }
        return List.copyOf(prefixed);
    }

    /** 解析 <side>.<localKey> 形式的线缆部件设置键；前缀不是合法六向方位或局部键为空时返回空。 */
    static Optional<SideKey> parseSideKey(String fullKey) {
        if (fullKey == null) return Optional.empty();
        int dot = fullKey.indexOf('.');
        if (dot <= 0 || dot >= fullKey.length() - 1) return Optional.empty();
        String sideText = fullKey.substring(0, dot).toLowerCase(Locale.ROOT);
        Direction side = Direction.byName(sideText);
        if (side == null) return Optional.empty();
        return Optional.of(new SideKey(side, fullKey.substring(dot + 1)));
    }

    /** 解析带数字下标的键（如 filter.0..62、stock.0..8、upgrade.0..7），越界返回 -1。 */
    static int indexedSlot(String key, String prefix, int maxExclusive) {
        if (key == null || !key.startsWith(prefix)) return -1;
        String suffix = key.substring(prefix.length());
        if (suffix.isEmpty()) return -1;
        try {
            int idx = Integer.parseInt(suffix);
            return idx >= 0 && idx < maxExclusive ? idx : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    /** 当前世界可用的 P2P 调谐可选值：仅在化学品键已登记或通道已是化学品 P2P 时附带 chemical。 */
    static List<String> p2pAttunements(boolean chemicalSupported) {
        return chemicalSupported ? P2P_ATTUNEMENTS_WITH_CHEMICAL : P2P_ATTUNEMENTS;
    }

    /** 解析 ME 接口备料项值：empty 或 "<物品/流体/化学品 ID>" 或 "<ID> <正整数数量>"（支持 chemical:<id> 前缀）。 */
    static Optional<StockValue> parseStockValue(String raw) {
        if (raw == null) return Optional.empty();
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return Optional.empty();
        if (trimmed.equalsIgnoreCase(EMPTY)) return Optional.of(new StockValue(true, null, null));
        String[] parts = trimmed.split("\\s+");
        if (!isValidTargetToken(parts[0])) return Optional.empty();
        if (parts.length == 1) {
            return Optional.of(new StockValue(false, parts[0], null));
        }
        if (parts.length == 2) {
            try {
                int amount = Integer.parseInt(parts[1]);
                if (amount <= 0) return Optional.empty();
                return Optional.of(new StockValue(false, parts[0], amount));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static boolean isValidTargetToken(String token) {
        if (token == null || token.isBlank()) return false;
        if (token.regionMatches(true, 0, CHEMICAL_PREFIX, 0, CHEMICAL_PREFIX.length())) {
            String bare = token.substring(CHEMICAL_PREFIX.length());
            return !bare.isBlank() && bare.contains(":");
        }
        return token.contains(":");
    }

    /** 解析元件工作台 cell 键的值：in:<item_id> 或 out。 */
    static Optional<CellValue> parseCellValue(String raw) {
        if (raw == null) return Optional.empty();
        String trimmed = raw.trim();
        if (trimmed.equalsIgnoreCase("out")) return Optional.of(new CellValue(false, null));
        if (trimmed.startsWith("in:") && trimmed.length() > 3) {
            return Optional.of(new CellValue(true, trimmed.substring(3).trim()));
        }
        return Optional.empty();
    }

    /** 把局部配置键映射成 AE2 Settings 注册名（用于 ConfigButtonPacket）；不走配置按钮的键返回空。 */
    static Optional<String> ae2SettingName(PartKind partKind, String localKey) {
        return switch (localKey) {
            case "redstone_mode" -> Optional.of(
                    partKind == PartKind.LEVEL_EMITTER || partKind == PartKind.ENERGY_LEVEL_EMITTER
                            ? "redstone_emitter" : "redstone_controlled");
            case "fuzzy_mode" -> Optional.of("fuzzy_mode");
            case "craft_only" -> Optional.of("craft_only");
            case "scheduling_mode" -> Optional.of("scheduling_mode");
            case "access" -> Optional.of("access");
            case "storage_filter" -> Optional.of("storage_filter");
            case "filter_on_extract" -> Optional.of("filter_on_extract");
            case "place_mode" -> Optional.of("place_block");
            case "craft_via_redstone" -> Optional.of("craft_via_redstone");
            case "operation_mode" -> Optional.of("operation_mode");
            case "fullness_mode" -> Optional.of("fullness_mode");
            default -> Optional.empty();
        };
    }

    /** 把整型 P2P 频率格式化为 4 位大写十六进制（0 记为 unlinked）。 */
    static String formatFrequency(int frequency) {
        int unsigned = frequency & 0xFFFF;
        return unsigned == 0 ? "unlinked" : String.format(Locale.ROOT, "%04X", unsigned);
    }

    /** 列出某类线缆部件（或方块 ME 接口）的全部局部设置项，现值优先读缓存/客户端，未读过填 unknown。 */
    static List<MachineSetting> partSettings(PartKind kind, String partItemId, Map<String, String> values,
                                             Ae2PartControl.ClientPartReading clientReading) {
        return partSettings(kind, partItemId, values, clientReading, false);
    }

    /** 同 {@link #partSettings}，并按应用机械是否可用决定是否暴露化学品键类型与化学品 P2P 调谐选项。 */
    static List<MachineSetting> partSettings(PartKind kind, String partItemId, Map<String, String> values,
                                             Ae2PartControl.ClientPartReading clientReading,
                                             boolean chemicalSupported) {
        List<MachineSetting> list = new ArrayList<>();
        switch (kind) {
            case IMPORT_BUS -> addImportBusSettings(list, values, chemicalSupported);
            case EXPORT_BUS -> addExportBusSettings(list, values);
            case STORAGE_BUS -> addStorageBusSettings(list, values);
            case INTERFACE -> addInterfaceSettings(list, values);
            case FORMATION_PLANE -> addFormationPlaneSettings(list, values);
            case ANNIHILATION_PLANE -> { /* 破坏面板只读，无配置键 */ }
            case LEVEL_EMITTER -> addLevelEmitterSettings(list, values);
            case ENERGY_LEVEL_EMITTER -> addEnergyEmitterSettings(list, values);
            case STORAGE_MONITOR, CONVERSION_MONITOR -> addMonitorSettings(list, values, clientReading);
            case P2P_TUNNEL -> addP2pSettings(list, partItemId, values, chemicalSupported);
        }
        return List.copyOf(list);
    }

    private static void addImportBusSettings(List<MachineSetting> list, Map<String, String> values,
                                             boolean chemicalSupported) {
        list.add(setting("redstone_mode", values, REDSTONE_MODES, "红石控制模式（需装红石卡才受红石控制）"));
        list.add(setting("fuzzy_mode", values, FUZZY_MODES, "模糊匹配模式（需装模糊卡才生效）"));
        list.add(setting("key_type.item", values, BOOL_CHOICES, "是否启用导入物品"));
        list.add(setting("key_type.fluid", values, BOOL_CHOICES, "是否启用导入流体"));
        // 仅在化学品键已登记或菜单同步已回报过 key_type.chemical 时暴露，避免未装应用机械时冒出无效设置键。
        if (chemicalSupported || values.containsKey("key_type.chemical")) {
            list.add(setting("key_type.chemical", values, BOOL_CHOICES, "是否启用导入化学品"));
        }
        addIndexed(list, "filter.", MAX_FILTER_SLOTS, values,
                "过滤槽（物品、流体或化学品 ID，支持 chemical:<id> 前缀，或 empty；18 格以上需容量卡解锁）");
        addIndexed(list, "upgrade.", BUS_UPGRADE_SLOTS, values, "升级卡槽（升级卡 ID 或 empty）");
    }

    private static void addExportBusSettings(List<MachineSetting> list, Map<String, String> values) {
        list.add(setting("redstone_mode", values, REDSTONE_MODES, "红石控制模式（需装红石卡才受红石控制）"));
        list.add(setting("fuzzy_mode", values, FUZZY_MODES, "模糊匹配模式（需装模糊卡才生效）"));
        list.add(setting("craft_only", values, YES_NO, "是否仅靠自动合成输出（需装合成卡）"));
        list.add(setting("scheduling_mode", values, SCHEDULING_MODES, "多过滤槽轮询输出策略"));
        addIndexed(list, "filter.", MAX_FILTER_SLOTS, values,
                "过滤槽（物品、流体或化学品 ID，支持 chemical:<id> 前缀，或 empty；18 格以上需容量卡解锁）");
        addIndexed(list, "upgrade.", BUS_UPGRADE_SLOTS, values, "升级卡槽（升级卡 ID 或 empty）");
    }

    private static void addStorageBusSettings(List<MachineSetting> list, Map<String, String> values) {
        list.add(setting("access", values, ACCESS_MODES, "外部容器读写权限"));
        list.add(setting("storage_filter", values, STORAGE_FILTER_MODES, "对外展示可提取项还是全部项"));
        list.add(setting("filter_on_extract", values, YES_NO, "提取时是否也按过滤列表限制"));
        list.add(setting("fuzzy_mode", values, FUZZY_MODES, "模糊匹配模式（需装模糊卡）"));
        list.add(setting("priority", values, List.of(), "网络存取优先级（整数）"));
        list.add(actionSetting(values, "按相邻容器现存物分区（partition）或清空全部过滤（clear）"));
        addIndexed(list, "filter.", MAX_FILTER_SLOTS, values,
                "过滤槽（物品、流体或化学品 ID，支持 chemical:<id> 前缀，或 empty；18 格以上需容量卡解锁）");
        addIndexed(list, "upgrade.", BUS_UPGRADE_SLOTS, values, "升级卡槽（升级卡 ID 或 empty）");
    }

    private static void addInterfaceSettings(List<MachineSetting> list, Map<String, String> values) {
        list.add(setting("fuzzy_mode", values, FUZZY_MODES, "备料模糊匹配模式（需装模糊卡）"));
        list.add(setting("priority", values, List.of(), "ME 接口优先级（整数）"));
        addIndexed(list, "stock.", INTERFACE_STOCK_SLOTS, values,
                "备料槽（<物品、流体或化学品 ID> [数量] 或 empty，支持 chemical:<id>；支持合成卡自动下单备料）");
        addIndexed(list, "upgrade.", 1, values, "升级卡槽（模糊卡或合成卡，或 empty）");
    }

    private static void addFormationPlaneSettings(List<MachineSetting> list, Map<String, String> values) {
        list.add(setting("place_mode", values, PLACE_MODES, "输出方式：放置方块（block）或扔出掉落物（drop）"));
        list.add(setting("fuzzy_mode", values, FUZZY_MODES, "模糊匹配模式（需装模糊卡）"));
        list.add(setting("priority", values, List.of(), "成型面板优先级（整数）"));
        addIndexed(list, "filter.", MAX_FILTER_SLOTS, values,
                "过滤槽（物品、流体或化学品 ID，支持 chemical:<id> 前缀，或 empty；18 格以上需容量卡解锁）");
        addIndexed(list, "upgrade.", BUS_UPGRADE_SLOTS, values, "升级卡槽（升级卡 ID 或 empty）");
    }

    private static void addLevelEmitterSettings(List<MachineSetting> list, Map<String, String> values) {
        list.add(setting("redstone_mode", values, EMITTER_REDSTONE_MODES, "达标时输出高电平或低电平"));
        list.add(setting("fuzzy_mode", values, FUZZY_MODES, "模糊匹配模式（需装模糊卡）"));
        list.add(setting("craft_via_redstone", values, YES_NO, "是否按红石信号触发自动合成（需装合成卡）"));
        list.add(setting("reporting_value", values, List.of(), "发信数量阈值（非负整数）"));
        list.add(setting("filter.0", values, List.of(), "监测目标物品、流体或化学品 ID（支持 chemical:<id> 或 empty）"));
        list.add(setting("upgrade.0", values, List.of(), "升级卡槽（模糊卡或合成卡，或 empty）"));
    }

    private static void addEnergyEmitterSettings(List<MachineSetting> list, Map<String, String> values) {
        list.add(setting("redstone_mode", values, EMITTER_REDSTONE_MODES, "电量达标时输出高电平或低电平"));
        list.add(setting("reporting_value", values, List.of(), "发信电量阈值（AE，非负整数）"));
    }

    private static void addMonitorSettings(List<MachineSetting> list, Map<String, String> values,
                                           Ae2PartControl.ClientPartReading reading) {
        String locked = reading != null && reading.hasMonitor()
                ? Boolean.toString(reading.locked()) : values.getOrDefault("locked", UNKNOWN);
        String displayed;
        if (reading != null && reading.hasMonitor()) {
            String live = reading.displayed();
            if (live == null) {
                displayed = EMPTY;
            } else {
                // 当客户端直读返回 chemical:<id> 而本次缓存刚写入对应裸 <id> 时，按裸 <id> 回显，
                // 保证无论调用方写 chemical:<id> 还是裸 <id>，MachineConfigureTask 读回都能一次命中不打转。
                String cached = values.get("displayed");
                displayed = live.startsWith(CHEMICAL_PREFIX)
                        && cached != null
                        && live.equals(CHEMICAL_PREFIX + cached)
                        ? cached : live;
            }
        } else {
            displayed = values.getOrDefault("displayed", UNKNOWN);
        }
        list.add(new MachineSetting("locked", locked, BOOL_CHOICES, "监视器锁定状态（空手潜行右键切换）"));
        list.add(new MachineSetting("displayed", displayed, List.of(),
                "展示物品、流体或化学品 ID（支持 chemical:<id>；解锁后持样本右键，容器二次右键切内含物）"));
    }

    private static void addP2pSettings(List<MachineSetting> list, String partItemId, Map<String, String> values,
                                       boolean chemicalSupported) {
        String attunement = attunementOfPart(partItemId).orElse(values.getOrDefault("attunement", UNKNOWN));
        boolean allowChemical = chemicalSupported || CHEMICAL_P2P_ITEM.equals(partItemId);
        list.add(new MachineSetting("attunement", attunement, p2pAttunements(allowChemical),
                "P2P 调谐类型（手持对应调谐物品右键替换，不消耗物品）"));
        list.add(setting("p2p_role", values, P2P_ROLES,
                "P2P 角色：input（主手内存卡潜行右键复制并分配频率）或 output（主手内存卡普通右键绑定）"));
    }

    /** 列出元件工作台的全部设置项。 */
    static List<MachineSetting> cellWorkbenchSettings(Map<String, String> values) {
        List<MachineSetting> list = new ArrayList<>();
        list.add(setting("cell", values, List.of(), "元件槽（in:<元件物品 ID> 放入，out 取出）"));
        list.add(setting("copy_mode", values, COPY_MODES, "取走元件时清空或保留分区配置"));
        list.add(setting("fuzzy_mode", values, FUZZY_MODES, "写入当前元件组件的模糊匹配模式"));
        list.add(actionSetting(values, "按元件现存物自动分区（partition）或清空分区（clear）"));
        addIndexed(list, "filter.", MAX_FILTER_SLOTS, values, "分区过滤槽（物品、流体或化学品 ID，支持 chemical:<id> 或 empty）");
        addIndexed(list, "upgrade.", WORKBENCH_UPGRADE_SLOTS, values, "元件升级卡槽（仅当前元件激活槽位内可放取）");
        return List.copyOf(list);
    }

    /** 列出 ME IO 传输站的全部设置项。 */
    static List<MachineSetting> cellTransferSettings(Map<String, String> values) {
        List<MachineSetting> list = new ArrayList<>();
        list.add(setting("operation_mode", values, OPERATION_MODES, "倒盘方向：empty（元件→网络）或 fill（网络→元件）"));
        list.add(setting("fullness_mode", values, FULLNESS_MODES, "元件移至输出槽的触发条件：empty / half / full"));
        list.add(setting("redstone_mode", values, TRANSFER_REDSTONE_MODES,
                "红石控制模式（装有红石卡时 ignore 在 AE2 中仍按低电平逻辑生效）"));
        addIndexed(list, "upgrade.", TRANSFER_UPGRADE_SLOTS, values, "升级卡槽（加速卡或红石卡，或 empty）");
        return List.copyOf(list);
    }

    private static MachineSetting setting(String key, Map<String, String> values, List<String> choices, String desc) {
        return new MachineSetting(key, values.getOrDefault(key, UNKNOWN), choices, desc);
    }

    private static MachineSetting actionSetting(Map<String, String> values, String desc) {
        return new MachineSetting("action", values.getOrDefault("action", ACTION_IDLE), PARTITION_ACTIONS, desc);
    }

    private static void addIndexed(List<MachineSetting> list, String prefix, int count, Map<String, String> values,
                                   String desc) {
        for (int i = 0; i < count; i++) {
            String key = prefix + i;
            list.add(new MachineSetting(key, values.getOrDefault(key, UNKNOWN), List.of(), desc));
        }
    }
}
