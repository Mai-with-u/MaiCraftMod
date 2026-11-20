// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.maiwithu.maicraft.kernel.knowledge.KnowledgeDocument;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeSource;

/**
 * 应用能源2（AE2）进阶中文专题知识来源（独立于基础机器总览，零碰撞注册）：
 * 按 6 个高频专题组织日常单机与曲柄、自动合成与样板编码、线缆部件与 P2P 配对、
 * 磁盘分区与网络运维、量子环与空间存储、便携存储元件与手持工具的全部设备用法、
 * 设置键与核心避坑提示。
 */
public final class Ae2ExtendedKnowledge implements KnowledgeSource {

    public static final String URI_DAILY = "maicraft://knowledge/machine/ae2/daily-machines";
    public static final String URI_AUTOCRAFT = "maicraft://knowledge/machine/ae2/autocrafting";
    public static final String URI_PARTS = "maicraft://knowledge/machine/ae2/parts-and-p2p";
    public static final String URI_OPS = "maicraft://knowledge/machine/ae2/storage-and-ops";
    public static final String URI_QUANTUM = "maicraft://knowledge/machine/ae2/quantum-and-spatial";
    public static final String URI_TOOLS = "maicraft://knowledge/machine/ae2/tools-and-power";

    private record Topic(KnowledgeDocument.Entry entry, Set<String> relatedIds, String content) {}

    private final Map<String, Topic> topics;
    private final List<KnowledgeDocument.Entry> entryList;

    public Ae2ExtendedKnowledge() {
        List<Topic> list = List.of(
                dailyMachinesTopic(),
                autocraftingTopic(),
                partsAndP2pTopic(),
                storageAndOpsTopic(),
                quantumAndSpatialTopic(),
                toolsAndPowerTopic());
        Map<String, Topic> map = new LinkedHashMap<>();
        List<KnowledgeDocument.Entry> entries = new ArrayList<>(list.size());
        for (Topic topic : list) {
            map.put(topic.entry().uri(), topic);
            entries.add(topic.entry());
        }
        this.topics = Map.copyOf(map);
        this.entryList = List.copyOf(entries);
    }

    @Override public List<KnowledgeDocument.Entry> entries() {
        return entryList;
    }

    @Override public List<KnowledgeDocument.Entry> entriesAbout(String registryId) {
        if (registryId == null || registryId.isBlank()) return List.of();
        String id = registryId.toLowerCase(Locale.ROOT);
        List<KnowledgeDocument.Entry> matched = new ArrayList<>();
        for (Topic topic : topics.values()) {
            if (topic.relatedIds().contains(id) || matchesPrefix(topic, id)) {
                matched.add(topic.entry());
            }
        }
        return List.copyOf(matched);
    }

    @Override public KnowledgeDocument read(String uri) {
        Topic topic = topics.get(uri);
        if (topic == null) return null;
        KnowledgeDocument.Entry e = topic.entry();
        return new KnowledgeDocument(e.uri(), e.name(), e.title(), "AE2 专题资料", topic.content());
    }

    @Override public String status() {
        return "应用能源2进阶专题资料：可用，" + entryList.size() + " 篇专题";
    }

    private static boolean matchesPrefix(Topic topic, String id) {
        if (topic.entry().uri().equals(URI_TOOLS) && PortableCellCatalog.isPortableCellId(id)) {
            return true;
        }
        if (topic.entry().uri().equals(URI_PARTS) && id.startsWith("ae2:") && id.endsWith("_p2p_tunnel")) {
            return true;
        }
        return false;
    }

    private static Topic dailyMachinesTopic() {
        KnowledgeDocument.Entry entry = new KnowledgeDocument.Entry(
                URI_DAILY,
                "machine.ae2.daily",
                "AE2 日常单机、充能器与木质曲柄",
                "充能器、木质曲柄防毁、振动室发电、物质聚合器、ME 箱子双面、陨石箱/储罐与水晶催生器",
                "ae2 充能器 曲柄 振动室 物质聚合器 ME箱子 陨石箱 陨石储罐 水晶催生器 charger crank condenser");
        Set<String> ids = Set.of(
                "ae2:charger", "ae2:crank", "ae2:vibration_chamber", "ae2:condenser",
                "ae2:chest", "ae2:sky_stone_chest", "ae2:smooth_sky_stone_chest",
                "ae2:sky_stone_tank", "ae2:growth_accelerator", "ae2:charged_certus_quartz_crystal");
        String body = """
                # AE2 日常单机、充能器与木质曲柄

                ## 充能器（`ae2:charger`）与木质曲柄（`ae2:crank`）
                - 连网、受电与安装木质曲柄的面是**除正面（FRONT）外的其余 5 面**。
                - 放入物品与弹出物品：`machine_run` 支持转化配方（如赛特斯石英变充能赛特斯石英）和给任意带电工具补电（便携元件、物质炮、熵变仪、上色器、充能法杖）。
                - **正面掉落**：空手右键弹出成品时，物品会掉在充能器正面相邻格的地上，需要走近拾取。
                - **防毁曲柄**：木质曲柄每次转动 18 刻注入 160 AE；主机满电后继续空摇超过 10 次会无掉落销毁曲柄，满电务必立即停摇。

                ## 振动室（`ae2:vibration_chamber`）
                - 接受任意可燃烧燃料发电直注电网（无内部电池缓冲），支持 `upgrade.0..2` 放能量卡/加速卡。
                - **空桶坑点**：岩浆桶烧完留下的空桶会卡住燃料槽，建议优先烧煤炭、木炭或木板。

                ## 物质聚合器（`ae2:condenser`）
                - 配置键 `mode`（`trash` / `matter_balls` / `singularity`）与 `storage_component`（`in:<id>` / `out`）。
                - 不插存储组件无法蓄能：物质球需 256 能量（至少 1k 组件，容量 8192），奇点需 **256,000** 能量（至少 64k 组件，容量 524,288）。

                ## ME 箱子、陨石箱、陨石储罐与水晶催生器
                - **ME 箱子（`ae2:chest`）**：点击**相对顶面（`getTop()`，非固定朝上）**打开盘内终端界面，点击其余 5 面打开元件插槽界面换盘。
                - **陨石箱子（`ae2:sky_stone_chest` / `ae2:smooth_sky_stone_chest`）**：36 格普通存储，**不能用石英扳手潜行拆解**，拆解请用镐。
                - **陨石储罐（`ae2:sky_stone_tank`）**：16 桶（16,000 mB）容量，客户端实时同步流体与水量，拿桶右键双向存取。
                - **水晶催生器（`ae2:growth_accelerator`）**：仅 `FRONT` / `BACK` 两面可连线缆、受电或接曲柄，但全部 6 个相邻面均对 `#ae2:growth_acceleratable` 催生。
                """;
        return new Topic(entry, ids, body);
    }

    private static Topic autocraftingTopic() {
        KnowledgeDocument.Entry entry = new KnowledgeDocument.Entry(
                URI_AUTOCRAFT,
                "machine.ae2.autocrafting",
                "AE2 自动合成闭环与样板编码",
                "样板编码终端 4 种模式、样板管理终端、样板供应器、分子组装室、压印器设置、合成 CPU 与合成终端 3x3 手搓",
                "ae2 自动合成 样板 编码 样板供应器 分子组装室 压印器 合成CPU 手搓 pattern crafting cpu");
        Set<String> ids = Set.of(
                "ae2:pattern_encoding_terminal", "ae2:pattern_access_terminal",
                "ae2:pattern_provider", "ae2:cable_pattern_provider",
                "ae2:molecular_assembler", "ae2:inscriber",
                "ae2:crafting_unit", "ae2:crafting_accelerator", "ae2:crafting_monitor",
                "ae2:1k_crafting_storage", "ae2:4k_crafting_storage", "ae2:16k_crafting_storage",
                "ae2:64k_crafting_storage", "ae2:256k_crafting_storage",
                "ae2:crafting_terminal", "ae2:wireless_crafting_terminal", "ae2:blank_pattern");
        String body = """
                # AE2 自动合成闭环与样板编码

                ## 样板编码终端（`ae2:pattern_encoding_terminal`）
                - 挂在 `ae2:cable_bus` 对应面的 `<side>.encode_mode`（`crafting` / `processing` / `smithing` / `stonecutting`）、`<side>.substitute`、`<side>.substitute_fluids`、`<side>.stonecutting_recipe` 与 `<side>.encode_pattern`。
                - 处理样板支持流体与超过 64 件的输入输出（0 号主产物必填）；切离 `processing` 会清空流体并把数量压回 1。
                - **防洗样板纪律**：编码前先取出输出槽里已有的编码样板，且确认当前模式配方有效再编码；配方无效时发 `encode` 会把输出槽里的旧样板洗成空白样板。

                ## 样板供应器（`ae2:pattern_provider` / `ae2:cable_pattern_provider`）与管理终端
                - 配置键：`pattern.<item_id>=in|out`、`blocking_mode`（`yes`/`no`）、`lock_crafting_mode`（`none`/`until_pulse`/`while_high`/`while_low`/`until_result`）、`show_in_access_terminal`、`priority`，方块版还有 `push_direction`。
                - 用石英扳手普通右键方块版样板供应器可切单面推送，注意**被设为推送面的那一面不再连 ME 网**。
                - 样板管理终端支持 `<side>.show_providers` 与 `<side>.put_pattern` 快速送入；全网样板槽位总览经服务端 `ae2.network` 读取。

                ## 分子组装室、压印器与合成 CPU
                - **分子组装室（`ae2:molecular_assembler`）**：可装 5 张加速卡（`upgrade.0..4`）；**单机样板槽（`pattern`）放入样板后将不再接收相邻样板供应器推送的自动合成任务**。
                - **压印器（`ae2:inscriber`）**：配置键 `separate_sides`（分离面：顶/底/四周分槽）、`auto_export`、`input_capacity`（`1`/`4`/`64`）与 `upgrade.0..3`。
                - **合成 CPU**：`machine_inspect` 可看每台 CPU 的存储、并行、作业进度、在途/待产明细与 `cant_store` 满溢告警；`machine_configure` 支持 `scheduling_mode`（`any`/`player_only`/`machine_only`）和 `job_action`（`cancel`/`toggle_pause`）。
                - **合成终端手搓**：按语义槽定位 3×3 合成格，填料固定不自动切确认页，取产物走终端合成动作包并自动退回余料。
                """;
        return new Topic(entry, ids, body);
    }

    private static Topic partsAndP2pTopic() {
        KnowledgeDocument.Entry entry = new KnowledgeDocument.Entry(
                URI_PARTS,
                "machine.ae2.parts",
                "AE2 线缆部件按面配置与 P2P 配对",
                "输入/输出/存储总线、ME 接口备料、成型/破坏面板、标准与能量发信器、监视器与 6 种 P2P 通道",
                "ae2 线缆 部件 总线 接口 面板 发信器 监视器 P2P 调谐 配对 import export storage interface p2p");
        Set<String> ids = Set.of(
                "ae2:cable_bus", "ae2:import_bus", "ae2:export_bus", "ae2:storage_bus",
                "ae2:interface", "ae2:cable_interface", "ae2:formation_plane", "ae2:annihilation_plane",
                "ae2:level_emitter", "ae2:energy_level_emitter", "ae2:storage_monitor", "ae2:conversion_monitor",
                "ae2:me_p2p_tunnel", "ae2:redstone_p2p_tunnel", "ae2:item_p2p_tunnel",
                "ae2:fluid_p2p_tunnel", "ae2:fe_p2p_tunnel", "ae2:light_p2p_tunnel");
        String body = """
                # AE2 线缆部件按面配置与 P2P 配对

                ## 按面配置规则
                - `machine_inspect` 查看 `ae2:cable_bus` 时，每个面部件的设置会直接列在对应 `role=part` 条目的 `settings` 中（不带面名前缀）。
                - 用 `machine_configure` 修改线缆格上的部件时，键必须带小写六向面名前缀 `<side>.<key>`（如 `north.redstone_mode`、`east.stock.0`、`up.priority`）。
                - 携带 `ae2:network_tool` 时所有带升级槽的 AE2 界面都会动态多出 9 个工具箱槽，本适配全部按槽位语义定位，不受格号偏移影响。

                ## 常用部件设置键速查
                - **输入总线（`ae2:import_bus`）**：`redstone_mode`、`fuzzy_mode`、`key_type.item`、`key_type.fluid`、`filter.0..62`、`upgrade.0..4`（超 18 格过滤需装容量卡）。
                - **输出总线（`ae2:export_bus`）**：`redstone_mode`、`fuzzy_mode`、`craft_only`、`scheduling_mode`、`filter.0..62`、`upgrade.0..4`。
                - **存储总线（`ae2:storage_bus`）**：`access`（`read_write`/`read`/`write`）、`storage_filter`、`filter_on_extract`、`fuzzy_mode`、`priority`、`filter.0..62`、`action`（`partition`/`clear`）。
                - **ME 接口（`ae2:interface` / `ae2:cable_interface`）**：`fuzzy_mode`、`priority`、`stock.0..8`（`<item_id> [count]` 或 `empty`）、`upgrade.0`（装合成卡可自动下单备料）。
                - **成型面板（`ae2:formation_plane`）**：`place_mode`（`block`/`drop`）、`fuzzy_mode`、`priority`、`filter.0..62`；**破坏面板（`ae2:annihilation_plane`）**无界面，放置时继承附魔，世界最高建筑高度朝上放置可被动产陨石粉。
                - **发信器（`ae2:level_emitter` / `ae2:energy_level_emitter`）**：**不占频道**，配 `redstone_mode` 与 `reporting_value`。
                - **存储/转换监视器**：客户端直同步展示物、数量、能否合成与锁定态，配 `locked`、`displayed`。

                ## 6 种 P2P 通道调谐与配对
                - 支持类型（`attunement`）：`me`、`redstone`、`item`、`fluid`、`fe`、`light`，持调谐物品右键替换不消耗物品。
                - **配对必须主手拿内存卡（`ae2:memory_card`）**：副手会被游戏直接拒绝；输入端潜行右键复制并分配频率，输出端普通右键绑定频率并自动转成同型输出。
                """;
        return new Topic(entry, ids, body);
    }

    private static Topic storageAndOpsTopic() {
        KnowledgeDocument.Entry entry = new KnowledgeDocument.Entry(
                URI_OPS,
                "machine.ae2.ops",
                "AE2 磁盘分区、倒盘与网络运维",
                "元件工作台分区、ME IO 传输站倒盘与红石卡注意点、无线访问点增幅卡公式、内存卡与网络工具",
                "ae2 元件工作台 分区 倒盘 IO 传输站 无线访问点 增幅卡 内存卡 网络工具 cell_workbench io_port memory_card network_tool");
        Set<String> ids = Set.of(
                "ae2:cell_workbench", "ae2:io_port", "ae2:wireless_access_point",
                "ae2:wireless_booster", "ae2:memory_card", "ae2:network_tool");
        String body = """
                # AE2 磁盘分区、倒盘与网络运维

                ## 元件工作台（`ae2:cell_workbench`）
                - 配置键：`cell=in:<id>|out`、`copy_mode`（`clear_on_remove`/`keep_on_remove`）、`fuzzy_mode`（`ignore_all`/`percent_99`/`percent_75`/`percent_50`/`percent_25`）、`action`（`partition`/`clear`）、`filter.0..62`、`upgrade.0..7`。
                - 注意模糊模式写入的是存储元件自身的物品组件，且升级槽数量随当前放入的元件动态变化。

                ## ME IO 传输站（游戏方块 `ae2:io_port`）
                - 配置键：`operation_mode`（`empty` 盘→网 / `fill` 网→盘）、`fullness_mode`（`empty`/`half`/`full`）、`redstone_mode`、`upgrade.0..2`。
                - **红石卡坑点**：在 AE2 19.2.17 中装入红石卡后，即使把 `redstone_mode` 设为 `ignore` 仍会按低电平逻辑触发，不需要红石控制时请勿插红石卡。
                - `machine_run` 支持把存储元件、物质炮或上色器放入输入格批量倒盘或装填弹药/颜料，完成后自动从输出格收回。

                ## 无线访问点（`ae2:wireless_access_point`）
                - 增幅卡（`ae2:wireless_booster`，上限 64 张）范围公式：`16 + boosters^1.5` 格（0 张 16 格、16 张 80 格、64 张 528 格）；耗电公式：`8 + boosters^(1 + boosters/64)` AE/t。
                - 客户端不开界面时不传增幅卡数量，需依赖服务端 `ae2.device` 或打开界面刷新，配置键 `boosters=<0..64>`。

                ## 内存卡与网络工具
                - **内存卡（`ae2:memory_card`）**：`machine_inspect(item="ae2:memory_card")` 客户端直读 12 项导出组件；`machine_configure(item="ae2:memory_card", settings={"clear": "true"})` 主手潜行对空右键清空。
                - **网络工具（`ae2:network_tool`）**：对空右键打开 9 格升级卡工具箱（`obtain` 可直接从随身工具箱取升级卡）；潜行右键可拆解 AE2 设备（但**不能旋转方块**）；全网耗电明细经服务端 `ae2.network` 查看。
                """;
        return new Topic(entry, ids, body);
    }

    private static Topic quantumAndSpatialTopic() {
        KnowledgeDocument.Entry entry = new KnowledgeDocument.Entry(
                URI_QUANTUM,
                "machine.ae2.quantum_spatial",
                "AE2 量子环、奇点爆炸转化与空间存储",
                "3x3 量子环连线规则、奇点迷你 TNT 安全爆炸转化、空间塔、空间传输站与空间锚",
                "ae2 量子环 量子链接仓 奇点 爆炸 转化 空间塔 空间传输站 空间锚 quantum spatial singularity");
        Set<String> ids = Set.of(
                "ae2:quantum_ring", "ae2:quantum_link", "ae2:singularity",
                "ae2:quantum_entangled_singularity", "ae2:tiny_tnt",
                "ae2:spatial_pylon", "ae2:spatial_io_port", "ae2:spatial_anchor",
                "ae2:spatial_storage_cell_2", "ae2:spatial_storage_cell_16", "ae2:spatial_storage_cell_128");
        String body = """
                # AE2 量子环、奇点爆炸转化与空间存储

                ## 量子环（`ae2:quantum_ring`）与量子链接仓（`ae2:quantum_link`）
                - 结构为 3×3×1 平面环：外圈 8 个量子环 + 中心 1 个量子链接仓，载 32 频道，空载耗电约 22 AE/t。
                - **接线约束**：仅 4 条边的中点可以连外部线缆，**4 个角块不能接线缆**。
                - 两端链接仓放入同一频率的 `ae2:quantum_entangled_singularity`（配置键 `singularity=in|out`）且**两端区块同时加载**即可跨维度连通。

                ## 奇点爆炸转化
                - 获取 `ae2:quantum_entangled_singularity` 可直接走 `obtain(..., via="ae2_explosion_transform")`：每次消耗 1 个 `ae2:singularity` 与 1 个末影珍珠/末影珍珠粉，在同一爆炸盒（±1 格）内安全引爆产出 **2 个同频缠绕奇点**。
                - 优先使用不炸毁方块的迷你 TNT（`ae2:tiny_tnt`），严格受保护区与当前许可约束。

                ## 空间存储
                - **空间塔（`ae2:spatial_pylon`）**：沿 X/Y/Z 轴连续至少 2 格成一线（每线 1 频道），所有塔块围出长方体外包盒；需求塔块数 `max(6, (dx*dz + dx*dy + dy*dz) * 3 / 8)`，效率越低切换能耗越高。
                - **空间传输站（游戏方块 `ae2:spatial_io_port`）**：槽 0 放空间元件（2/16/128 档）、槽 1 必须为空，满足区域有效、尺寸不超元件上限（已绑定 `SPATIAL_PLOT_INFO` 的元件尺寸必须完全一致）且网络电量足够时，给一次**红石低→高上升沿脉冲**即完成互换并将元件移到槽 1。
                - **空间锚（`ae2:spatial_anchor`）**：能耗 `80 + n*(n+1)/2` AE/t（`n` 为加载区块数），断电 200 刻后卸载；配置键 `overlay_mode=yes|no`。
                """;
        return new Topic(entry, ids, body);
    }

    private static Topic toolsAndPowerTopic() {
        KnowledgeDocument.Entry entry = new KnowledgeDocument.Entry(
                URI_TOOLS,
                "machine.ae2.tools",
                "AE2 便携存储元件、手持工具装填与补电",
                "10 种便携存储元件、石英扳手、石英切割刀、陨石罗盘、熵变仪、物质炮、上色器与充能法杖",
                "ae2 便携存储元件 石英扳手 切割刀 陨石罗盘 熵变仪 物质炮 上色器 充能法杖 portable wrench knife compass matter_cannon color_applicator");
        Set<String> ids = Set.of(
                "ae2:certus_quartz_wrench", "ae2:nether_quartz_wrench",
                "ae2:certus_quartz_cutting_knife", "ae2:nether_quartz_cutting_knife",
                "ae2:name_press", "ae2:meteorite_compass",
                "ae2:entropy_manipulator", "ae2:matter_cannon",
                "ae2:color_applicator", "ae2:charged_staff");
        String body = """
                # AE2 便携存储元件、手持工具装填与补电

                ## 10 种便携存储元件
                - 涵盖物品 1k/4k/16k/64k/256k 与流体 1k/4k/16k/64k/256k；物品版可与精妙背包共存参与腾背包，流体与物品版均支持 `obtain(..., via="carried")` 随身取货（流体装桶需带空桶）。
                - **绝不潜行右键空便携元件**：潜行右键空元件会触发拆解将其拆回外壳与组件，打开一律用普通右键。

                ## 石英扳手、石英切割刀与陨石罗盘
                - **石英扳手**：潜行右键整块拆解 AE2 设备与线缆部件（排除陨石箱子），普通右键旋转方块朝向或切换样板供应器推送面（网络工具仅可拆解，不能旋转）。
                - **石英切割刀**：`machine_configure(item="ae2:certus_quartz_cutting_knife", settings={"engrave_name": "名称"})`，需身上有 `#ae2:metal_ingots` 金属锭，关窗前自动收回输入槽残锭防止掉地。
                - **陨石罗盘（`ae2:meteorite_compass`）**：仅携带在身上时才在 `observe(scene).mod_things` 与 `machine_inspect(item=...)` 报出 3 种肉眼可见指针状态：`pointing`（＞6 格，附八向水平方位）、`nearby_spinning`（≤6 格慢转）与 `searching_spinning`（无目标快转），绝不透出精确距离与 Y 坐标。

                ## 熵变仪、物质炮、上色器与充能法杖
                - **熵变仪（`ae2:entropy_manipulator`）**：普通右键加热/烧炼/点 TNT（点 TNT 不耗电），潜行右键冷却，单次 1600 AE，可对着流体直接使用。
                - **物质炮（`ae2:matter_cannon`）**：自动接入远程战斗（瞬发直线射线、无冷却、单发 1600 AE），**排除零伤害彩弹**；**装弹必须放进 ME 箱子或 ME IO 传输站（FILL 模式）**，不能直接在背包塞入。
                - **上色器（`ae2:color_applicator`）**：同样经 ME 箱子或 ME IO 传输站装填染料/彩弹/雪球（透明清洗色），`machine_configure(item="ae2:color_applicator", settings={"color": "<颜色|transparent>"})` 直切颜色，副手拿着放线缆会自动染色。
                - **充能法杖（`ae2:charged_staff`）**：满电近战每次耗 300 AE 附加 6 点魔法伤害。
                - **通用补电**：所有带电工具电量条自动显示在 `observe(self).gear_bars`，电量不足时把工具放进充能器（`ae2:charger`）执行 `machine_run` 即可充满。
                """;
        return new Topic(entry, ids, body);
    }
}
