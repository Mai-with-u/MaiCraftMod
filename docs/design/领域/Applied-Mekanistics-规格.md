# 领域规格：应用机械（Applied-Mekanistics）适配

> 状态：已批准（2026-11-21）。
> 依据：Applied-Mekanistics 源码 `Applied-Mekanistics/`（tag `1.6.3`，与实测实例 `Applied-Mekanistics-1.6.3.jar` 一致）、[机器联动模组事实清单](机器联动模组事实清单.md)第 1.16 节、[联动模组需求总表](联动模组需求总表.md)第 3.2 节与第 4 节（A5）、[AE2全面适配-规格](AE2全面适配-规格.md)。
> 范围：在 AE2 全面适配基础之上，以零反射、零跨模组包耦合的方式补齐 Applied-Mekanistics（`appmek` 1.6.3）带来的化学品 ME 网络自动化能力——输入总线化学品键类型开关、过滤/备料/分区槽化学品包装、样板编码 `PROCESSING` 化学品项、化学品 P2P 调谐与配对、5 种便携化学品元件只读查看与补电、5 种 ME 化学品存储元件与监视器兼容、服务端 `ae2.device` 槽位键类型标注与 `ae2.network` 化学品存量摘要、QIO 外部存储说明与专属知识页。
> 边界：本规格不新增任何顶层 MCP 工具或独立能力，不修改既有参数名表；所有能力全部挂接回既有机器与知识能力（`machine_inspect` / `machine_configure` / `machine_run` / `use` / `observe` / `lookup`）。**零反射**（全部经 `compileOnly` 调用公开 API）；**不把化学品储罐灌装塞进 `obtain` 物品取货**；便携化学品元件**不纳入 `CarriedBackpack`**；`compat.ae2` 与 `compat.appmek` 互不依赖，通过 `compat` 包根 `ExternalKeyWrappers` 零耦合协作，未装 `appmek` 时零类加载风险。

## 1 范围

| 模块 / 对象 | 主要挂接能力 | 是否依赖服务端只读 |
| --- | --- | :---: |
| 跨模组零耦合键包装接缝（`compat.ExternalKeyWrappers`） | 支撑 AE2 总线、接口、元件工作台、样板编码与监视器扩展外部键类型 | 否 |
| 输入总线化学品键类型开关（`key_type.chemical`） | `machine_inspect`、`machine_configure` | 可选（不开窗时经 `ae2.device`） |
| 过滤 / 备料 / 分区槽化学品 ID 包装（输入/输出/存储总线、ME 接口、成型面板、标准发信器、元件工作台） | `machine_configure`、`machine_inspect` | 可选 |
| 样板编码终端 `PROCESSING` 模式化学品项（`kind="chemical"`） | `machine_configure` | 否 |
| 化学品 P2P 通道（`appmek:chemical_p2p_tunnel`，`attunement=chemical`）与主手内存卡配对 | `machine_inspect`、`machine_configure`、`use` | 是（`is_output` 与出入端角色） |
| 5 种便携化学品元件（`appmek:portable_chemical_cell_1k..256k`）与菜单 `appmek:portable_chemical_cell` | `machine_inspect(item=...)`（`ItemSettings`）、`MenuLayoutProof`、`machine_run`（充能器补电）、`observe(self)`（电量条） | 否 |
| 5 种 ME 化学品存储元件（`appmek:chemical_storage_cell_1k..256k`）与存储/转换监视器化学品展示 | `machine_inspect`、`machine_configure`、`machine_run`（驱动器/ME 箱子/元件工作台/ME IO 传输站） | 可选 |
| 服务端只读扩展（`ae2.device` `config_slot_types`/`storage_slot_types`、`ae2.network` `chemical_types`/`stored_chemicals[]`） | `machine_inspect` | 是 |
| QIO 外部存储说明与知识来源 `AppMekKnowledge`（`maicraft://knowledge/machine/appmek/index`） | `lookup` | 否 |

**既有能力衔接**：
- `compat.ae2` 继续保留全部 AE2 本体能力与设备目录，不直接 import `compat.appmek` 或 `me.ramidzkh.mekae2.*`。
- 新增独立联动模组包 `compat.appmek`（入口 `AppMekCompat`，验证版本范围 `[1.6.3, 1.7)`）与 NeoForge 读写端 `neoforge.compat.appmek`，通过 `ClientCompatCatalog` 启动时局部创建并注入双方的 `compat.ExternalKeyWrappers` 向 AE2 的幽灵槽包装、键类型切换与样板编码项包装注册 `appmek:chemical` 扩展点（P2P 调谐映射走 AE2 原生 `P2PTunnelAttunement`）。
- 未安装 `appmek` 或版本不在已验证范围时，`AppMekCompat` 不登记，`ExternalKeyWrappers` 保持为空（提供只读 `NONE`），AE2 全部现有行为与类加载零受影响。

## 2 真人怎么玩

同时装了 AE2、Mekanism 和 Applied-Mekanistics 后，真人会把 Mekanism 的氧气、氢气、乙烯、硫酸、矿浆、灌注物这些非放射性化学品直接存进 ME 网络：往 ME 驱动器或 ME 箱子里插上 1k～256k 的 ME 化学品存储元件，用输入总线把电解分离器、化学溶解室等机器产出的化学品抽进网（在输入总线界面勾选化学品导入种类），用输出总线或 ME 接口把所需化学品送到下一道 Mekanism 机器，或者在样板编码终端里把含化学品输入/产出的处理配方编进 `PROCESSING` 样板，放到贴着 Mekanism 机器或加压管道的样板供应器里参与全自动合成。需要跨距离分流化学品管线时，把 P2P 通道拿任意一档 Mekanism 化学品储罐右键调谐成化学品 P2P，再拿主手内存卡配对输入端与输出端。出门临时带几种非放射性化学品时，随身带一块便携化学品元件，低电了放进充能器补电，打开一律普通右键绝不对着空盘潜行右键拆碎；放射性化学品（如核废料、钚、钋）游戏本身禁止装进化学品元件、也禁止塞进物质聚合器或用销毁卡清除，必须留在 Mekanism 放射性废料桶与防辐射管线里处理。若想把 Mekanism 的 QIO 仓库直接挂进 ME 网络当外部物品仓库，就把存储总线贴在 QIO 仪表盘的**背面**并确认频率与安全权限正常。

## 3 新逻辑

### 3.1 零耦合跨模组接缝：`compat.ExternalKeyWrappers`

架构分层规则要求 `compat.ae2` 与 `compat.appmek` 两个联动模组子包互不依赖（`LayerRulesTest.compatModsAreIndependent`）。为此在 `compat` 包根提供零模组类型、零可变 static 字段的外部键包装登记表 `ExternalKeyWrappers`（附只读空常量 `ExternalKeyWrappers.NONE`）：

- **职责与接口**：
  - 按外部键类型 ID（本规格为 `"appmek:chemical"`）管理由附属联动模组提供的纯翻译包装函数（由 `neoforge.compat.appmek` 以 `compileOnly` 实现，零反射）：
    1. `register(keyTypeId, wrapper)` / `unregister(keyTypeId)`：附属联动模组加载时登记包装器，停用或回滚时自动撤回；
    2. `supports(keyTypeId)`：查询指定外部键类型（如 `"appmek:chemical"`）此刻是否已登记可用；
    3. `wrap(keyTypeId, registryId, amount)`：将指定外部键类型与注册 ID（例如 `"appmek:chemical"`、`"mekanism:oxygen"`）包装为 `GenericStack.wrapInItemStack(MekanismKey.of(new ChemicalStack(holder, 1)), amount)` 返回的 `ItemStack`（找不到合法化学品或键类型未登记时返回 `Optional.empty()`）。
  - 化学品 P2P 调谐物品映射直接走 AE2 原生 `P2PTunnelAttunement`，不经 `ExternalKeyWrappers` 重复维护。
- **生命周期与隔离**：
  - 由 `ClientCompatCatalog` 启动时作为局部变量创建并分别注入 `Ae2Compat` 与 `AppMekCompat`（无静态全局可变状态）；
  - 未装 `appmek` 时包装表为空，不加载任何 `me.ramidzkh.mekae2.*` 或 `mekanism.api.*` 类，彻底杜绝 `NoClassDefFoundError`。

### 3.2 输入总线 `key_type.chemical` 与过滤/备料/分区槽化学品包装

- **输入总线导入种类开关**：
  - `ae2:import_bus` 在 `ExternalKeyWrappers.supports("appmek:chemical")` 为真（或菜单已同步该键类型）时，动态增加局部设置键 `key_type.chemical`（值 `true` / `false`，`machine_configure` 按线缆格加面名前缀 `<side>.key_type.chemical`）；
  - 修改时经 `AEKeyTypes.getAll()` 按 `"appmek:chemical"` 解析键类型并发送 `SelectKeyTypePacket`，从菜单 `@GuiSync(20)` 核对回读。
- **过滤 / 备料 / 分区槽化学品 ID 包装**：
  - 覆盖全部支持 `GenericStack` 幽灵槽的 AE2 配置点：输入总线 `filter.0..62`、输出总线 `filter.0..62`、存储总线 `filter.0..62`、ME 接口（方块与部件）`stock.0..8`、成型面板 `filter.0..62`、标准发信器 `filter.0`、元件工作台 `filter.0..62`。
  - 取值写法：支持 `chemical:<chemical_id>`（以及 ME 接口 `stock.<n>` 的 `chemical:<chemical_id> [amount_mB]`）；当未写前缀且该 ID 不在原版物品/流体注册表但能被 `ExternalKeyWrappers.wrap("appmek:chemical", id, amount)` 识别为合法化学品时，自动包装为化学品 `GenericStack` 物品栈并经 `SET_FILTER` 发送；
  - 读回展示：当槽内保存的是 `appmek:chemical` 键时，配置值统一规范呈现为 `chemical:<chemical_id>`（备料槽附带毫桶数），与写入格式一一对应。
  - 若传入不存在的化学品 ID 或未装 `appmek` 时请求 `chemical:` 前缀，如实以 `INVALID_PARAMETER` / `UNSUPPORTED` 失败并说明原因，不静默丢弃。

### 3.3 样板编码终端 `PROCESSING` 模式支持 `kind="chemical"`

- 在 `<side>.encode_pattern` 的 JSON 请求中，`inputs[]` 与 `outputs[]` 除既有 `item` 与 `fluid` 外，当 `mode == "processing"` 时支持 `kind="chemical"`（及简写 `{"chemical": "<id>", "count"|"amount": <mB>}`，数量为毫桶正整数）：
  1. 编码前先校验 `ExternalKeyWrappers.supports("appmek:chemical")`（未装 `appmek` 时以 `UNSUPPORTED` 结束）；
  2. 经 `ExternalKeyWrappers.wrap("appmek:chemical", id, count)` 生成 `GenericStack` 包装物品栈，按 `PROCESSING_INPUTS` / `PROCESSING_OUTPUTS` 槽位发 `SET_FILTER` 写入；
  3. **仅允许在 `PROCESSING` 模式使用 `kind="chemical"`**：在 `crafting` / `smithing` / `stonecutting` 模式下提交化学品项直接以 `INVALID_PARAMETER` 拒绝（游戏内非处理模式不支持 `MekanismKey`）；
  4. 严格沿用既有防洗样板纪律：编码前先取走输出槽已有编码样板、校验输入与 0 号主产物均非空后再发 `encode()`。

### 3.4 化学品 P2P 通道调谐与配对

- **调谐（`attunement=chemical`）**：
  - 线缆格已安装任意 P2P 通道时，`machine_configure` 的 `<side>.attunement` 在装有 `appmek` 时新增合法值 `chemical`；
  - 调谐物品取自 `P2PTunnelAttunement` 注册的标签（5 档 Mekanism 化学品储罐：`mekanism:basic_chemical_tank` / `advanced_chemical_tank` / `elite_chemical_tank` / `ultimate_chemical_tank` / `creative_chemical_tank`），手持右键原地替换为 `appmek:chemical_p2p_tunnel`（不消耗储罐，保留既有频率与出入端）；
  - 角色身上没有任一调谐储罐时如实以 `NEED_ITEM` 提示。
- **配对（`<side>.p2p_role=input|output`）**：
  - 复用 AE2 既有主手 `ae2:memory_card` 配对流程：输入端主手潜行右键复制并分配频率，输出端主手普通右键粘贴绑定并自动转为同型输出端；副手拿内存卡会被游戏拒绝，强制要求主手持有。
  - `machine_inspect` 在客户端读出频率、经服务端 `ae2.device` 补全 `is_output` 与 `p2p_type="appmek:chemical_p2p_tunnel"`。

### 3.5 5 种便携化学品元件（只读查看、布局、补电与边界）

覆盖 `appmek:portable_chemical_cell_1k` / `4k` / `16k` / `64k` / `256k`：

- **只读查看（`ItemSettings`：`AppMekPortableCellSettings`）**：
  - `machine_inspect(item="appmek:portable_chemical_cell_...")` 客户端直接从同步物品组件与 `StorageCells.getCellInventory(stack, null)` 读取以下 5 项只读设置（不需打开界面）：
    - `energy` / `energy_max`（当前与上限电量，继承 `IAEItemPowerStorage`，同时自动进入 `observe(self).gear_bars`）；
    - `bytes`（已用 / 总字节数，8,000 mB/byte 换算）；
    - `types`（已用 / 上限种类数，固定上限 18 种）；
    - `chemicals`（盘内非空化学品的 `<chemical_id> <amount>mB` 汇总）；
    - 提示放射性化学品受游戏黑名单拒绝，低电时可经充能器 `machine_run` 补电。
- **界面布局证明（`MenuLayoutProof`：`AppMekPortableCellLayout`）**：
  - 为 `appmek:portable_chemical_cell` 登记布局证明：严格校验包含恰好 36 格角色背包槽，容器侧与机器侧均返回空集合（天然免疫网络工具箱动态 9 槽与升级/视图槽），支持 `use` 普通右键安全打开查看。
- **防空盘拆解纪律**：
  - 任何打开便携化学品元件的动作一律使用**普通右键**，绝不潜行右键空盘，避免触发 `PortableCellItem` 的 `disassembleDrive` 把元件拆成外壳与组件。
- **充能器补电**：
  - 5 种便携化学品元件继承 `PortableCellItem`（实现 `IAEItemPowerStorage`），直接支持放进充能器执行 `machine_run` 补电（有外电自动充，无外电配合木质曲柄摇动并遵守满电即停纪律）。
- **明确边界**：
  - **不纳入 `CarriedBackpack`**：便携化学品元件只能装非放射性化学品，不能在腾背包时放入杂物物品，也不在 `observe(self).carried_backpacks` 中充当物品背包；
  - **不纳入 `obtain` 随身取货**：不把化学品储罐灌装塞进 `obtain` 物品获取流程。

### 3.6 5 种 ME 化学品存储元件兼容与监视器化学品展示

- **5 种 ME 化学品存储元件（`appmek:chemical_storage_cell_1k..256k`）**：
  - 每盘上限 5 种非放射性化学品（8,000 mB/byte），可装反相卡、均分卡、销毁卡；
  - 在 ME 驱动器十槽读数、ME 箱子（相对顶面开终端、其余 5 面换盘）、元件工作台（支持化学品分区与升级卡）、ME IO 传输站（`operation_mode=empty|fill` 批量倒盘）中均可原样识别与操作；
  - 若向存储元件、销毁卡溢出或物质聚合器投入放射性化学品，游戏原生通过 `ChemicalAttributeValidator.DEFAULT`、`CondenserMEStorageMixin` 与 `MEInventoryHandlerMixin` 拒绝；本适配如实回报游戏拒绝事实，不作假也不预判拦截非放射性化学品。
- **存储监视器 / 转换监视器（`ae2:storage_monitor` / `ae2:conversion_monitor`）**：
  - 当监视器展示内容为 `MekanismKey` 时，`machine_inspect` 客户端直接读出 `displayed = "chemical:<chemical_id>"`、`amount`（毫桶）与 `locked` 状态，与物品/流体展示保持一致。

### 3.7 服务端只读扩展（`ae2.device` 与 `ae2.network`）

- 严格遵守 128 格 `AskedCell.nearPlayer` 边界、不反射、不透视、不替玩家动手；所有新增字段完全向后兼容既有 JSON 结构。
- **`ae2.device` 槽位键类型标注**：
  - 既有 `config_slots` 与 `storage_slots` 维持 `Map<String, String>` 原格式（如 `"0": "mekanism:hydrogen x16000"`），不破坏既有调用方；
  - 当过滤/备料/分区槽（`config_slots`）或接口实体槽（`storage_slots`）中出现非物品 `GenericStack` 时，并行补充 `config_slot_types` / `storage_slot_types`（槽位下标到 `"item"` / `"fluid"` / `"chemical"` 的映射，其余未知外部键类型写其 `AEKeyType.getId()`），便于客户端准确区分同名流体与化学品（监视器展示由客户端直同步，不走 `ae2.device` 槽位表）。
- **`ae2.network` 化学品存量摘要**：
  - 在遍历 `IGrid.getStorageService().getCachedInventory()` 时，按 `AEKey.getType().getId()` 识别 `"appmek:chemical"`，保留原有 `stored_types` / `stored_amount` 口径不变；当网络中存有化学品时补充 `chemical_types`（化学品总种数）与按存量降序截顶（最多 32 项）的 `stored_chemicals[]`（每项含 `id` 化学品注册 ID 与 `amount` 毫桶数）；网络中无化学品或未装 `appmek` 时省略，不影响旧调用方。

### 3.8 QIO 外部存储说明与知识来源 `AppMekKnowledge`

- 新增独立知识来源 `compat.appmek.AppMekKnowledge`（由 `AppMekCompat` 登记，仅在装有 `appmek` 且版本在范围内时注册）：
  - URI：`maicraft://knowledge/machine/appmek/index`；
  - `entriesAbout(registryId)` 匹配全部 12 个 `appmek:*` 物品 ID（外壳、5 档 ME 化学品存储元件、5 档便携化学品元件、`appmek:chemical_p2p_tunnel`）以及 `mekanism:qio_dashboard`；
  - 正文简明覆盖：
    1. ME 化学品存储元件（5 档、5 种上限、8,000 mB/byte）与便携化学品元件（5 档、18 种上限、充能器补电、绝不潜行右键空盘、不进腾背包）；
    2. 化学品自动化闭环：输入总线开 `key_type.chemical`、总线/接口/发信器/元件工作台使用 `chemical:<id>`、样板编码终端 `PROCESSING` 模式使用 `kind="chemical"`、ME 接口与样板供应器已自动暴露 `IChemicalHandler` 可直连加压管道；
    3. 化学品 P2P 通道：持任意 Mekanism 化学品储罐右键调谐，主手内存卡配对输入/输出端；
    4. 放射性化学品禁忌：存储元件黑名单、物质聚合器与销毁卡均由模组拦截，必须使用 Mekanism 放射性废料桶处理；
    5. **QIO 外部存储（`mekanism:qio_dashboard`）**：存储总线必须贴在仪表盘**背面**（`FACING.getOpposite()`），仪表盘须绑定有效 QIO 频率且放置存储总线的玩家拥有访问权限（`PUBLIC` 或受信任/归属者），且仅支持**物品**读写、不支持化学品。

## 4 能力与接口挂接总览

- **不新增顶层 MCP 工具、不新增独立能力、不新增 `ParamNames`**。
- **新增知识 URI**：`maicraft://knowledge/machine/appmek/index`（仅装了 `appmek` 时出现）。
- **新增配置键 / 取值（仅装了 `appmek` 时生效）**：
  - 输入总线：`key_type.chemical`（`true` / `false`，线缆格带 `<side>.` 前缀）；
  - 过滤 / 备料 / 分区槽：支持 `chemical:<chemical_id>`（备料槽 `chemical:<chemical_id> [amount]`）；
  - 样板编码终端 `<side>.encode_pattern`：`PROCESSING` 模式 `inputs[]` / `outputs[]` 支持 `kind="chemical"`；
  - P2P 通道 `<side>.attunement`：新增取值 `chemical`。
- **新增布局证明菜单 ID**：`appmek:portable_chemical_cell`。
- **服务端只读扩展**：`ae2.device` 在含非物品槽位时并行补充 `config_slot_types` / `storage_slot_types`；`ae2.network` 补充 `chemical_types` 与 `stored_chemicals[]`（降序最多 32 项）。

## 5 约束登记（失败与拒绝归类）

本表仅包含 **F（游戏事实）** 与 **C（玩家常识）** 两类；**不存在任何 P 补丁、G 预判拦截或 S 过严规则**。

| 场景与收场方式 | 类 | 错误码（`Problem.Kind`） | 依据 |
| --- | :---: | --- | --- |
| 放射性化学品被化学品存储元件、便携化学品元件、销毁卡或物质聚合器拒绝时如实回报 | F | `REFUSED_BY_GAME` | `ChemicalStorageCell`、`ChemicalPortableCellItem`、`CondenserMEStorageMixin`、`MEInventoryHandlerMixin` 均按 `ChemicalAttributeValidator.DEFAULT` 拦截放射性化学品 |
| 打开便携化学品元件一律使用普通右键，绝不对空盘潜行右键 | F | —（操作规程） | `ChemicalPortableCellItem` 继承 `PortableCellItem`，潜行右键空盘会触发 `disassembleDrive` 拆回外壳与组件 |
| 便携化学品元件电量不足时如实停下并报当前/上限电量与充能器补电建议 | F | `NOT_POSSIBLE_HERE` | 电量同步在 `AEComponents.STORED_ENERGY`，没电时游戏拒绝打开或操作 |
| 在非 `PROCESSING` 模式下向样板编码终端提交 `kind="chemical"` 项时拒绝 | F | `INVALID_PARAMETER` | AE2 仅 `PROCESSING` 样板支持非物品 `GenericStack`，其他模式会清空或导致无效配方洗样板 |
| 未安装 `appmek` 时请求 `key_type.chemical`、`chemical:` 过滤、`kind="chemical"` 样板或 `attunement=chemical` | F | `UNSUPPORTED` | 游戏未注册 `appmek:chemical` 键类型与化学品 P2P 通道 |
| 化学品 P2P 通道配对强制主手持有内存卡 | F | `NEED_ITEM` | AE2 源码对 `hand == OFF_HAND` 直接返回 false |
| 化学品 P2P 调谐缺少 Mekanism 化学品储罐时停止并提示 | F | `NEED_ITEM` | `ItemTagsProvider` 仅将 5 档 Mekanism 化学品储罐加入调谐标签 |
| QIO 仪表盘非背面、未设有效频率或无安全权限时存储总线读不到内容，如实回报并在知识页写明 3 项前提 | F / C | —（事实说明） | `QioStorageAdapter.getFrequency()` 严格校验背面、有效频率与 `ISecurityUtils` 权限，且仅支持 `AEItemKey` |

**明确不拦的事**：非放射性化学品元件空间不足、网络未接通、ME 接口未连加压管道、目标 Mekanism 机器未配置化学品输入面时，不以「预测会失败」提前拦截配置；照常按原生操作完成设置并将现场读数如实回报。

## 6 行为变化清单

| # | 场景 | 之前 | 之后 |
| --- | --- | --- | --- |
| 1 | 装有 `appmek` 时配置输入总线与过滤/备料/分区槽 | 仅支持 `item` 与 `fluid`，无法勾选化学品或填入化学品 ID | 输入总线支持 `key_type.chemical`；全部 `GenericStack` 槽支持 `chemical:<id>` 读写 |
| 2 | 样板编码终端编含 Mekanism 化学品的处理样板 | `inputs`/`outputs` 不认 `kind="chemical"` | `PROCESSING` 模式支持 `kind="chemical"` 编码并遵守防洗样板纪律 |
| 3 | 化学品 P2P 通道调谐与配对 | `attunement` 仅认 AE2 本体 6 种 | 支持 `attunement=chemical` 持储罐调谐与主手内存卡配对，服务端可读出入端 |
| 4 | 5 种便携化学品元件 | 无法 `machine_inspect(item=...)` 看盘内化学品，`appmek:portable_chemical_cell` 无布局证明 | `ItemSettings` 只读电量/容量/盘内化学品汇总，登记布局证明并支持充能器补电，且严守不进 `CarriedBackpack` |
| 5 | `ae2.device` 与 `ae2.network` 服务端读数 | 槽位不区分键类型、网络不汇总化学品库存 | `ae2.device` 保留原槽位映射并在含非物品时并行补充 `config_slot_types` / `storage_slot_types`，`ae2.network` 补充 `chemical_types` 与降序最多 32 项的 `stored_chemicals[]` |
| 6 | 查阅 Applied-Mekanistics 与 QIO 接入规则 | 无专属中文知识页 | `lookup` 可读 `maicraft://knowledge/machine/appmek/index` |

## 7 游戏事实归属与代码落点

| 包 / 位置 | 职责 |
| --- | --- |
| `compat.ExternalKeyWrappers`（`compat` 包根） | 跨模组零耦合的外部键包装登记表（`register` / `unregister` / `supports` / `wrap`，附只读 `NONE`，由 `ClientCompatCatalog` 启动时局部创建并注入 `Ae2Compat` 与 `AppMekCompat`），使 `compat.ae2` 与 `compat.appmek` 互不依赖 |
| `compat.appmek` | `AppMekCompat`（联动入口，验证范围 `[1.6.3, 1.7)`，停用/回滚自动 `unregister`）、`AppMekReads`（只读接缝）、`AppMekPortableCellSettings`（5 种便携化学品元件只读 `ItemSettings`：`energy` / `energy_max` / `bytes` / `types` / `chemicals`）、`AppMekPortableCellLayout`（`appmek:portable_chemical_cell` 布局证明，严检 36 格角色槽、容器侧空集合）、`AppMekKnowledge`（`maicraft://knowledge/machine/appmek/index`） |
| `neoforge.compat.appmek` | `compileOnly` 直接调用 `me.ramidzkh.mekae2.*`、`mekanism.api.*`、`appeng.api.*` 公开 API 的读写端（`AppliedMekanisticsReads`，实现 `appmek:chemical` 包装与 `AppMekReads`），零反射，且仅在 `ClientCompatCatalog` 的 lambda 内延迟构造 |
| `server.compat.ae2` / `neoforge.compat.ae2` | 向后兼容扩展 `ae2.device`（并行补充 `config_slot_types` / `storage_slot_types`）与 `ae2.network`（补充 `chemical_types` 与降序最多 32 项 `stored_chemicals[]`），纯按 `AEKey.getType().getId()` 识别 `"appmek:chemical"`，不直接 import `appmek` 类，未装 `appmek` 时零类加载风险 |

## 8 不做的事

- **零反射**：不得使用反射访问 `appmek`、`mekanism` 或 `ae2` 的私有字段或方法；全部走 `compileOnly` 公开 API。
- **不把化学品储罐灌装塞进 `obtain` 物品取货**：化学品不是普通物品堆叠，储罐灌装/抽取不接入 `ItemSource`，避免扭曲 `obtain` 的物品获取语义。
- **便携化学品元件不纳入 `CarriedBackpack`**：不能装物品杂物，不参与背包腾空，也不混入 `observe(self).carried_backpacks`。
- **`compat.ae2` 与 `compat.appmek` 零互相依赖**：双方仅通过 `compat` 包根 `ExternalKeyWrappers` 协作，确保未安装 `appmek` 时完全不触发相关类加载，严格通过 `LayerRulesTest.compatModsAreIndependent`。
- **不替玩家配置 Mekanism 放射性废料处理或绕过放射性黑名单**：游戏拒绝即如实返回。

## 9 验收

### 9.1 离线单元测试清单（全用替身，不用 `Unsafe` 与反射）

1. **`ExternalKeyWrappersTest`（零耦合与空回退）**：
   - 使用只读 `ExternalKeyWrappers.NONE` 或未注册 `"appmek:chemical"`（模拟未装 `appmek`）时，`supports("appmek:chemical")` 为 `false`、`wrap(...)` 返回空，`key_type.chemical` 不暴露，请求 `chemical:` 过滤与 `kind="chemical"` 样板均以 `UNSUPPORTED` 明确拒绝且零异常；
   - `register("appmek:chemical", ...)` 注册包装器替身后，`supports` 返回 `true`、`wrap` 正确包装 `GenericStack` 物品栈，`unregister("appmek:chemical")`（停用/回滚）后立即恢复未支持状态。
2. **`AppMekBusAndFilterTest`（输入总线与过滤/备料/分区槽）**：
   - 输入总线列出 `key_type.chemical` 并向 `SelectKeyTypePacket` 路由 `appmek:chemical`；
   - 输入/输出/存储总线、ME 接口（含备料毫桶数）、成型面板、标准发信器、元件工作台支持写入 `chemical:mekanism:oxygen` 并规范读回 `chemical:mekanism:oxygen`。
3. **`AppMekPatternEncodingTest`（样板编码 `PROCESSING` 化学品项）**：
   - `PROCESSING` 模式下支持 `inputs[]`/`outputs[]` 中混排 `item`、`fluid`、`chemical`（含 `kind="chemical"` 与简写 `{"chemical":...}`）并正常编码；
   - 在 `crafting`/`smithing`/`stonecutting` 模式提交 `kind="chemical"` 直接以 `INVALID_PARAMETER` 拦截，不发送 `encode()`，不洗掉输出槽已有样板。
4. **`AppMekP2pAndMonitorTest`（化学品 P2P 与监视器）**：
   - `attunement=chemical` 校验并匹配 AE2 原生 `P2PTunnelAttunement` 登记的 Mekanism 化学品储罐调谐物品，缺储罐报 `NEED_ITEM`；
   - 主手内存卡配对生效、副手报错；监视器展示 `MekanismKey` 时客户端 `machine_inspect` 规范输出 `chemical:<id>` 与毫桶数，且不与同名流体误判匹配。
5. **`AppMekPortableCellTest`（5 种便携化学品元件与布局证明）**：
   - `AppMekPortableCellSettings` 覆盖 1k/4k/16k/64k/256k 全部 5 档，正确返回 `energy`、`energy_max`、`bytes`（8,000 mB/byte）、`types`（18 种上限）与 `chemicals` 汇总；
   - `AppMekPortableCellLayout` 对 `appmek:portable_chemical_cell` 严检 36 格角色槽、容器侧与机器侧返回空集合（免疫网络工具箱动态 9 槽）且与既有 AE2 布局零碰撞；
   - 验证 `AppMekCompat` 不登记任何 `CarriedBackpack` 与 `ItemSource`。
6. **`AppMekServerReadsAndKnowledgeTest`（服务端扩展与知识页）**：
   - `ae2.device` 保留 `config_slots` / `storage_slots` 原格式，在含非物品槽位时并行写出 `config_slot_types` / `storage_slot_types`（`item` / `fluid` / `chemical`）；
   - `ae2.network` 含化学品时写出 `chemical_types` 与降序最多 32 项 `stored_chemicals[]`，无化学品时不破坏旧字段；
   - `AppMekKnowledge` 的 URI 为 `maicraft://knowledge/machine/appmek/index`，`entriesAbout` 覆盖全部 12 个 `appmek:*` 物品与 `mekanism:qio_dashboard`，正文包含 QIO 背面三条件与放射性禁忌。
7. **架构与编译验证**：全套 `architecture` 架构测试（含 `LayerRulesTest.compatModsAreIndependent`、`SourceRulesTest`、`MemberNamesTest`、`CodingRulesTest`）与 `:fabric:compileJava`、`:neoforge:compileJava` 全部通过。

### 9.2 实机验收清单

1. **未装 `appmek` 回归**：在仅装 AE2、未装 `appmek` 的客户端中启动并打开输入总线、样板编码终端与 P2P 通道，确认零 `NoClassDefFoundError`，无多余 `key_type.chemical` 项。
2. **输入总线与过滤/备料槽**：装上 `appmek` 1.6.3 后，对贴着电解分离器的输入总线执行 `machine_configure` 开启 `key_type.chemical` 并写入 `chemical:mekanism:hydrogen`，确认化学品成功导入 ME 化学品存储元件；在 ME 接口配 `stock.0 = "chemical:mekanism:oxygen 4000"` 验证备料生效且加压管道可读出。
3. **处理样板编码**：在样板编码终端使用 `mode="processing"` 编出一张含 `kind="chemical"` 输入/产出的处理样板，放进样板供应器并核对自动合成闭环。
4. **化学品 P2P 通道**：手持基础化学品储罐经 `machine_configure` 将 ME P2P 调谐为 `attunement=chemical`，再以主手内存卡配对输入端与输出端，`machine_inspect` 确认频率与出入端准确无误。
5. **便携化学品元件查看与补电**：背包携带低电 4k 便携化学品元件（内含氧气），验证 `observe(self).gear_bars` 显示电量条、`observe(self).carried_backpacks` 不出现该便携化学品元件、`machine_inspect(item=...)` 读出 `energy`/`energy_max`/`bytes`/`types`/`chemicals`；放进充能器 `machine_run` 充满电；打开界面走普通右键不误拆空盘。
6. **放射性化学品保护**：尝试把核废料导入化学品元件或投入物质聚合器，验证游戏原生拒绝且 Mod 如实回报拒绝事实。
7. **服务端读数与监视器**：在装有化学品存储元件的 ME 网络上执行 `machine_inspect`，确认 `ae2.network` 返回 `chemical_types` 与 `stored_chemicals[]`、`ae2.device` 对含非物品槽位并行返回 `config_slot_types` / `storage_slot_types`，存储监视器展示化学品名称与毫桶数。
8. **QIO 外部存储与知识页**：在 `mekanism:qio_dashboard` 背面挂存储总线并设好频率与权限，确认 ME 终端可读出 QIO 物品；用 `lookup(topic=knowledge, id="maicraft://knowledge/machine/appmek/index")` 完整读出中文资料页。

## 10 需登记 09 的新名字

| 中文 | 代码 / 标识 | 意思 |
| --- | --- | --- |
| 应用机械联动 | `compat.appmek`（入口 `AppMekCompat`、读写端 `neoforge.compat.appmek`） | Applied-Mekanistics（`appmek`）的独立联动模组包，提供化学品 ME 自动化、便携化学品元件与知识页，与 `compat.ae2` 互不依赖 |
| 外部键包装表 | `compat.ExternalKeyWrappers`（`register` / `unregister` / `supports` / `wrap`、只读 `NONE`） | 位于 `compat` 包根的跨模组零耦合接缝，由 `ClientCompatCatalog` 启动时局部创建并注入 `Ae2Compat` 与 `AppMekCompat`，供附属模组登记外部键（如 `appmek:chemical`）的幽灵槽包装 |
| 应用机械知识页 | `compat.appmek.AppMekKnowledge`（URI `maicraft://knowledge/machine/appmek/index`） | Applied-Mekanistics 化学品存储元件、便携元件、处理样板、化学品 P2P、放射性禁忌与 QIO 背面挂存储总线的中文知识来源 |
| 化学品配置项与存量摘要 | `key_type.chemical`、`kind="chemical"`、`ae2.device` 的 `config_slot_types` / `storage_slot_types`、`ae2.network` 的 `chemical_types` / `stored_chemicals[]` | 输入总线化学品导入开关、处理样板化学品项、服务端非物品槽位键类型并行映射与 ME 网络化学品存量摘要 |
