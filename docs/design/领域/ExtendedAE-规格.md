# 领域规格：AE2 扩展（ExtendedAE）适配

> 状态：已批准（2026-11-27，全量推进批次 1～2）。
> 依据：实测与开发实例 `[AE2扩展] ExtendedAE-1.21-2.2.33-neoforge.jar` 与 `Glodium-1.21-2.2-neoforge.jar` 全量字节码核查（已逐类比对编译依赖 `maven.modrinth:extended-ae:1.21-2.2.35-neoforge` 的 131 个类签名，涉及客户端/服务端接口零破坏性变更）、[机器联动模组事实清单](机器联动模组事实清单.md)第 14 节、[联动模组需求总表](联动模组需求总表.md)第 3.10 节与第 4 节（A6）、[机器-规格](机器-规格.md)。
> 范围：以独立联动模组包（`compat.extendedae` + `neoforge.compat.extendedae` + `server.compat.extendedae`，`compileOnly` 零反射）完整接入 ExtendedAE 的全部核心加工与维护机、高频扩展网络设备、设施与装配矩阵多方块，并补齐扩展线缆部件菜单布局证明与专属中文知识页。
> 边界：本规格不新增任何顶层 MCP 工具或独立能力，不修改既有参数名表；所有交互全部挂接回既有能力（`machine_inspect` / `machine_review` / `machine_build` / `machine_connect` / `machine_configure` / `machine_run` / `obtain` / `deposit` / `use` / `lookup`）。遵循分层规则，`compat.extendedae` 与 `compat.ae2`、`compat.advancedae` 互不依赖；`server.compat.extendedae` 与其他服务端联动包互不依赖；未安装 ExtendedAE 或版本不在验证范围 `[1.21-2.2.33, 1.21-2.3)` 时零类加载风险。

## 1 范围

| 批次 | 模块 / 对象 | 主要挂接能力 | 是否依赖服务端只读 |
| --- | --- | --- | :---: |
| 基础 | 独立联动入口 `ExtendedAeCompat`、语义布局证明 `ExtendedAeSemanticsLayout`、界面储罐倒桶动作、服务端只读操作 `extendedae.device` | 全部扩展机器与容器界面 | 否（`extendedae.device` 可选增强） |
| 批次 1 | 水晶装配器（`extendedae:crystal_assembler`） | `machine_inspect`、`machine_run`（含水/岩浆桶界面倒桶与 `wait=false`）、`machine_configure`、`use` | 可选（不开窗读储罐与进度） |
| 批次 1 | 电路切片器（`extendedae:circuit_cutter`） | `machine_inspect`、`machine_run`（含 `wait=false`）、`machine_configure`、`use` | 可选（不开窗读进度与输出面） |
| 批次 1 | 扩展压印器（`extendedae:ex_inscriber`） | `machine_inspect`、`machine_run`（4 页自动分配与模板收回）、`machine_configure`、`use` | 可选（不开窗读设置与加速卡） |
| 批次 1 | 扩展充能器（`extendedae:ex_charger`） | `machine_inspect`、`machine_run`（4 槽并发充能与补电 `FullRun`）、`use` | 否（4 槽客户端直读） |
| 批次 1 | 水晶修复器（`extendedae:crystal_fixer`） | `machine_inspect`、`machine_run`（`FuelUse` 补燃料）、`use` | 否（槽 0 燃料客户端直读） |
| 批次 1 | 扩展样板供应器（`extendedae:ex_pattern_provider`） | `machine_inspect`、`machine_configure`、`machine_connect`、`use`、`obtain`、`deposit` | 可选（样板与设置） |
| 批次 1 | ME 扩展驱动器（`extendedae:ex_drive`） | `machine_inspect`（20 槽磁盘）、`machine_connect`、`use`、`obtain`、`deposit` | 可选 |
| 批次 1 | ME 扩展接口与超大接口（`extendedae:ex_interface`、`extendedae:oversize_interface`） | `machine_inspect`、`machine_connect`、`use` | 可选（36 槽备料明细） |
| 批次 1 | 扩展分子装配室（`extendedae:ex_molecular_assembler`） | `machine_inspect`、`machine_configure`（加速卡）、`machine_connect`、`use` | 可选（8 线程进度） |
| 批次 1 | ME 扩展 IO 端口（`extendedae:ex_io_port`） | `machine_inspect`、`machine_configure`、`machine_connect`、`use` | 可选 |
| 批次 2 | ME 原料缓存器（`extendedae:ingredient_buffer`） | `machine_inspect`、`use`、`obtain`、`deposit` | 可选（36 格流体/物品明细） |
| 批次 2 | ME 装罐机（`extendedae:caner`） | `machine_inspect`、`machine_configure`（`fill` / `empty`）、`machine_connect`、`use` | 可选 |
| 批次 2 | ME 无线连接器与集线器（`extendedae:wireless_connect`、`extendedae:wireless_hub`） | `machine_inspect`、`machine_configure`（升级卡与端口断开）、`machine_connect`、`use` | 是（频率、对端坐标、能耗） |
| 批次 2 | 装配矩阵多方块（`extendedae:assembler_matrix_*` 6 种方块） | `machine_inspect`、`machine_configure`（取消合成作业）、`machine_connect`、`use` | 是（边界、核心数、线程、样板） |
| 批次 2 | 扩展线缆部件菜单布局证明（扩展样板管理终端、扩展总线、标签/模组/精确/阈值总线、阈值发信器、主动成型/智能破坏面板） | `use`（安全打开配置界面不误点幽灵槽）、`machine_inspect` | 可选 |
| 批次 1 | 中文知识页（`maicraft://knowledge/machine/extendedae/index`） | `lookup` | 否 |

**不单独写联动的方块与物品**（按游戏数据或原生交互直接处理）：
- 普通原料、生长簇与装饰块：`entro_block`、`silicon_block`、`machine_frame`、`entro_cluster*`、`entro_budding_*`、`fishbig`、`mddyue`（按普通方块、采掘与合成表处理）。
- 手持一次性升级件与工具：`*_upgrade`（样板供应器/接口/驱动器/总线/终端/无线连接器升级）、`me_packing_tape`（封装胶带）、`entro_seed`（恩特罗种子）、`wireless_tool`（无线连接工具）——直接使用原生 `use` 右键目标方块或空气，知识页写清用法即可。
- 嬗变联动（`ex_emc_*`）：依赖 AppliedE / ProjectE，实测实例未安装，不纳入联动。

## 2 真人怎么玩

做出第一台水晶装配器后，真人把它五面中的任意一面（除顶面）接上 ME 电网供电。要批量做福鲁伊克斯水晶、恩特罗注入锭或恩特罗母岩时，先打开界面拿水桶点击界面里的储罐槽灌进水（每次倒进 1000 mB，空桶留背包），再把固体原料塞进 9 格输入槽，等进度走完把产物从输出槽取走；想要更快就往右侧 4 个升级槽插满加速卡，做全自动产线时打开自动输出并勾选推送面。同样，把扩展机器框架、压印模板和工程处理器塞进水晶装配器就能做出电路切片器——往电路切片器里丢整块金块、钻石块、硅块、石英块或恩特罗块，一次切出 4～9 张电路板，比单个压印快得多。日常压印与充能升级成扩展压印器（4 个独立分页同时压印，能分别设分离面、自动输出和 1/4/64 堆叠上限）和扩展充能器（像原版充能器一样空手/手持直接右键放取，一次并行充 4 颗石英或带电工具，也能贴木质曲柄手摇）。想让母岩持续产芽不退化，在母岩正前方放一台水晶修复器（正面贴母岩、背面接线缆供电），拿充能赛特斯石英（修赛特斯母岩）或末影珍珠粉（修恩特罗母岩）右键塞进机器燃料槽即可。ME 网络扩容时，把样板供应器、驱动器、接口、分子装配室和 IO 端口换成扩展版获得更大容量与多线程；需要跨厂房传频道时摆一对无线连接器或一台无线集线器，拿无线连接工具依次右键绑定；大后期把合成样板集中塞进 3×3×3 起步的装配矩阵多方块里极速并行合成。

## 3 新逻辑

### 3.1 独立联动入口、版本范围与编译依赖

- **编译依赖（`neoforge/build.gradle` + `gradle.properties`）**：
  - `compileOnly("maven.modrinth:extended-ae:${extendedae_version}") { transitive = false }`，`extendedae_version=1.21-2.2.35-neoforge`（许可证 `LGPL-3.0-or-later`）；
  - `compileOnly("maven.modrinth:glodium:${glodium_version}") { transitive = false }`，`glodium_version=1.21-2.2-neoforge`（许可证 `MIT`）。
- **验证版本范围**：NeoForge 读取的 `extendedae` 版本字符串带 `1.21-` 前缀（实测实例为 `1.21-2.2.33-neoforge`），`VerifiedVersions` 按 `[.+-]` 逐段比较数字，因此客户端与服务端清单均登记 `new VerifiedVersions("1.21-2.2.33", "1.21-2.3")`，确保实测 `1.21-2.2.33-neoforge` 与同小版本补丁均命中。
- **包布局与依赖隔离**：
  - `common`：`org.maiwithu.maicraft.compat.extendedae`（入口 `ExtendedAeCompat`、目录 `ExtendedAeMachineCatalog`、主类型 `ExtendedAeMachineType`、语义布局证明 `ExtendedAeSemanticsLayout`、各设备操作类与知识来源 `ExtendedAeKnowledge`）；
  - `common` 服务端：`org.maiwithu.maicraft.server.compat.extendedae`（入口 `ExtendedAeServerCompat`，注册只读操作 `extendedae.device` 版本 1）；
  - `neoforge`：`org.maiwithu.maicraft.neoforge.compat.extendedae`（客户端读写端 `ExtendedAeClientReads` / `ExtendedAeMenuActions`、服务端只读端 `ExtendedAeDeviceServerReads`）；
  - `ModFeatures` 新增 `"extendedae" -> "extendedae"`。

### 3.2 通用语义界面布局证明（`ExtendedAeSemanticsLayout`）

ExtendedAE 的所有容器都继承自 AE2 的 `AEBaseMenu` / `UpgradeableMenu`，当角色携带 `ae2:network_tool` 时同样会在前方动态插入 9 个 `TOOLBOX` 槽。

- 读写端按 `SlotSemantics.get(id)`（同时覆盖 AE2 原生语义和 ExtendedAE 注册的 `EX_1`～`EX_12`）查出当前打开菜单里指定语义的槽位索引，并检查 `!(slot instanceof AppEngSlot ae) || ae.isSlotEnabled()`（分页菜单中仅当前启用页的槽位才参与普通搬运）。
- 强制规则：
  - 必须包含恰好 36 个角色背包槽；
  - 严禁把 `TOOLBOX`、`CONFIG` 以及流体储罐槽（`crystal_assembler` 的 `STORAGE` 槽、`caner` 的 `EX_1` 流体槽）暴露给通用物品搬运 `containerSlots` / `machineSlots`；
  - 纯配置界面（无线连接器/集线器无额外容器槽时，以及扩展总线、标签/模组/精确/阈值总线、发信器、面板等）允许返回 `Supported(playerSlots, upgradeSlotsOrEmpty, upgradeSlotsOrEmpty)`，保证打开界面改配置或用 `use` 查看时不再报“不认识界面”。
- **菜单类型登记表**：

| 菜单注册 ID | 暴露给普通搬运的语义 | 按机器槽核对的语义 | 排除项 |
| --- | --- | --- | --- |
| `extendedae:crystal_assembler` | `MACHINE_INPUT`、`MACHINE_OUTPUT`、`UPGRADE` | 全部 | `STORAGE`（储罐专用）、`TOOLBOX` |
| `extendedae:circuit_cutter` | `MACHINE_INPUT`、`MACHINE_OUTPUT`、`UPGRADE` | 全部 | `TOOLBOX` |
| `extendedae:ex_inscriber` | 当前启用页 4 格 + `UPGRADE` | 全部 | 非当前页槽、`TOOLBOX` |
| `extendedae:ex_pattern_provider` | `ENCODED_PATTERN` | `ENCODED_PATTERN` | `TOOLBOX` |
| `extendedae:ex_drive` | `STORAGE_CELL`、`EX_1` | 全部 | `TOOLBOX` |
| `extendedae:ex_interface`、`extendedae:oversize_interface` | 当前启用页存储槽（`EX_2`/`EX_4`/`EX_6`/`EX_8`）+ `UPGRADE` | `UPGRADE` | 配置幽灵槽（`EX_1`/`EX_3`/`EX_5`/`EX_7`）、`TOOLBOX` |
| `extendedae:ex_molecular_assembler` | 当前启用页槽（`EX_1..EX_8`）+ 当前启用 `MACHINE_OUTPUT` + `UPGRADE` | 全部 | 非当前页槽、`TOOLBOX` |
| `extendedae:ex_ioport` | `MACHINE_INPUT`、`MACHINE_OUTPUT`、`UPGRADE` | 全部 | `TOOLBOX` |
| `extendedae:ingredient_buffer` | `STORAGE`（仅物品格） | 无（按普通存取容器） | `TOOLBOX` |
| `extendedae:caner` | `EX_2`（容器格） | `EX_2` | `EX_1`（通用流体/物品槽）、`TOOLBOX` |
| `extendedae:wireless_connector`、`extendedae:wireless_hub` | `UPGRADE` | `UPGRADE` | `TOOLBOX` |
| `extendedae:assembler_matrix` | 空（纯配置与样板总览） | 空 | `TOOLBOX` |
| `extendedae:ex_pattern_access_terminal`、`extendedae:ex_export_bus`、`extendedae:ex_import_bus`、`extendedae:tag_export_bus`、`extendedae:tag_storage_bus`、`extendedae:mod_export_bus`、`extendedae:mod_storage_bus`、`extendedae:precise_export_bus`、`extendedae:precise_storage_bus`、`extendedae:threshold_export_bus`、`extendedae:threshold_level_emitter`、`extendedae:active_formation_plane`、`extendedae:smart_annihilation_plane` | `UPGRADE`（有的则含） | `UPGRADE` | `CONFIG`、`TOOLBOX` |

### 3.3 水晶装配器（`extendedae:crystal_assembler`）

- **进出口与接网**：除相对顶面外的五面（`complementOf(TOP)`）可接入 ME 网络与受电；物品与流体可从相邻自动接口或界面进入。
- **状态读数（`machine_inspect`）**：
  - 客户端直读：`working`（方块状态 `WORKING` / `TileCrystalAssembler.isWorking()`）、`powered`、`active`；
  - 开窗缓存与服务端 `extendedae.device` 补全：`tank_fluid`（如 `minecraft:water` 或 `empty`）、`tank_amount_mb`、`tank_capacity_mb`（16000）、`processing_time`、`max_processing_time`（200）、`auto_export`（`yes`/`no`）、`output_sides`（允许输出方向列表）、`upgrade.0..3`、`inputs` 与 `output`。
- **投料与流体储罐倒桶（`machine_run`）**：
  - **配方流体换算（`ingredientFeed`）**：当配方包含流体输入（如 `minecraft:water` 100 mB / 200 mB，`minecraft:lava` 100 mB）时，先查本机最近观测或服务端读出的储罐存量：
    - 若储罐里已有**不同种流体**且存量大于 0，返回 `FeedPlan.conflict` 明确说明机内现有流体冲突（需先用空桶抽出或换机），不静默覆盖；
    - 若同种流体余量已满足本次全部批数所需毫桶数，写入说明“储罐里已有足够流体，不用再倒桶”，不额外备桶；
    - 否则按 `ceil((总需毫桶 - 现有同种毫桶) / 1000.0)` 计算所需桶数，把流体原料映射为对应标准桶物品（经游戏流体 `Fluid.getBucket()` 查得，如 `minecraft:water_bucket`、`minecraft:lava_bucket`，不硬编码流体名单）；没有对应桶物品的流体如实报带不进机器。
  - **界面倒桶动作（`CrystalAssemblerTankFeed`）**：当 `feed` 收到映射到流体储罐的桶条目时，走独立原生界面动作：
    1. 打开水晶装配器界面并核对 `STORAGE` 储罐槽索引；
    2. 在界面内检查当前储罐实际存量（避免缓存陈旧多倒或异种冲突）；
    3. 逐桶点击背包里的满桶拿起至光标 → 通过 `OpenedMenu.submitModAction` 发送 AE2 原生 `InventoryActionPacket(InventoryAction.EMPTY_ITEM, tankSlotIndex, 0)` → 等待服务端确认光标变为 `minecraft:bucket` 且储罐量增加 → 把空桶放回背包原格或空格；
    4. 关窗收尾，照实记账。
  - **固体投料、等待与收产物**：固体原料经 `MenuFeed` 投入 9 格 `MACHINE_INPUT`；支持 `wait=false`（倒桶与投料完成即返，不干等 200 刻加工）；等待完成时经 `MenuOutputLook` 从 `MACHINE_OUTPUT` 取出产物。
- **配置（`machine_configure`）**：
  - `auto_export`（`yes` / `no`）：开窗发 AE2 `ConfigButtonPacket(Settings.AUTO_EXPORT, false)` 切换并读回 `ContainerCrystalAssembler.getAutoExport()`；
  - `output_side.<direction>`（`<direction>` 为 `north`/`south`/`east`/`west`/`up`/`down`，值 `true` / `false`）：开窗发 `EAENetworkHandler.INSTANCE.sendToServer(new CEAEGenericPacket("set_side", dir, enabled))` 并读回 `getOutputSides()` 确认；
  - `upgrade.0..3`（`ae2:speed_card` / `empty`）：开窗向 `UPGRADE` 槽放取加速卡。

### 3.4 电路切片器（`extendedae:circuit_cutter`）

- **进出口与接网**：除相对正面与背面外的四面（`complementOf(FRONT, BACK)`）可连 ME 网与受电。
- **状态读数（`machine_inspect`）**：客户端直读 `working`、`input`（输入槽 0 同步物品）、`render_output`（渲染产物）；开窗缓存或服务端 `extendedae.device` 补全 `processing_time` / `max_processing_time`（200）、`auto_export`、`output_sides`、`upgrade.0..3`。
- **运行（`machine_run`）**：1 格 `MACHINE_INPUT` 经 `MenuFeed` 投整块原料（金块、钻石块、石英块、硅块、恩特罗块等），1 格 `MACHINE_OUTPUT` 经 `MenuOutputLook` 收成品；兼容 `wait=false`。
- **配置（`machine_configure`）**：与水晶装配器同一套 `auto_export`、`output_side.<direction>`（同走 `CEAEGenericPacket("set_side", ...)`）与 `upgrade.0..3`。

### 3.5 扩展压印器（`extendedae:ex_inscriber`）

- **进出口与接网**：除相对正面外的五面（`complementOf(FRONT)`）可连 ME 网、受电或安装木质曲柄。
- **状态读数（`machine_inspect`）**：
  - 客户端经 `TileExInscriber.readFromStream` 直接同步 4 页（`page 0..3`）全部内部库存（每页 4 格：顶模板、底模板、中间输入、输出）、4 页各自的 `isSmash` 状态与 `stackSize`（1 / 4 / 64）；`machine_inspect` 以结构化 `pages` 列表呈现每页状态与物品，不折叠省略；
  - 开窗或服务端补全 `separate_sides`、`auto_export`、`upgrade.0..3` 与各页进度。
- **运行（`machine_run`）**：
  - **4 页择优调度**：按实测现场客户端同步的 4 页库存选定本次目标页 `targetPage`（0..3）：
    1. 优先选已有匹配不消耗模板且无异种冲突的空闲页；
    2. 其次选全空页；
    3. 最后选非压印中且冲突最少的页，并在 `FeedPlan.clearing` 中生成该页的腾空动作。
  - **分页切换原生动作**：打开 `extendedae:ex_inscriber` 界面后，若 `ContainerExInscriber.getPage() != targetPage`，通过 `submitModAction` 依次发送 `CUpdatePage(targetPage)` 与 `CEAEGenericPacket("show")`，确认客户端菜单 `getPage() == targetPage` 且对应页 4 槽 `isSlotEnabled() == true` 后，再投放模板与主料；
  - **模板归还纪律**：与原版压印器保持一致——只记下本会话由角色亲自放入目标页的不消耗压印模板，收产物时一并收回背包，绝不擅自拿走机内原有模板。
- **配置（`machine_configure`）**：
  - `separate_sides`（`yes` / `no`，AE2 `ConfigButtonPacket(Settings.INSCRIBER_SEPARATE_SIDES)`）；
  - `auto_export`（`yes` / `no`，AE2 `ConfigButtonPacket(Settings.AUTO_EXPORT)`）；
  - `input_capacity`（`1` / `4` / `64`，发送 `CEAEGenericPacket("stack", size)`，通过 `getStackMode()` 核对 `64→0 / 1→1 / 4→2`）；
  - `upgrade.0..3`（加速卡）。

### 3.6 扩展充能器（`extendedae:ex_charger`）

- **无界面交互**：`BlockExCharger.openGui` 直接调用 `TileExCharger.activate(Player)`——主手持有可充能配方物品或带电物品右键放入 4 槽中的第一个空槽（每次 1 件）；空手或手持不可充能物品右键依次弹出非空槽物品。
- **进出口与接网**：除正面外的五面（`complementOf(FRONT)`）可连 ME 网、受电或安装木质曲柄。
- **状态读数（`machine_inspect`）**：客户端直接同步 `working` 与 4 个槽位（`slot_0..3`）的物品及带电物品电量（`item_energy` / `item_energy_max`）。
- **运行（`machine_run`）**：通过 `FullRun`（`ExChargerFullRun`）支持 4 槽并发充能：
  - 若请求物品是充能配方产物（如 `ae2:charged_certus_quartz_crystal`、`ae2:meteorite_compass`），最多一次连续放入 `min(剩余数量, 空槽数)` 件（最多 4 件并发），等对应槽位全部变为目标产物后逐个空手右键弹出并收进背包，循环直到凑齐 `count` 件；
  - 若请求物品是带电物品（实现 `IAEItemPowerStorage`）则放入充至满电再收回；
  - 兼容 `wait=false`（放满首批待充物品后即返回，由 LLM 自行用计时器或稍后收货）。

### 3.7 水晶修复器（`extendedae:crystal_fixer`）

- **无界面交互**：`BlockCrystalFixer.openGui` 直接调用 `TileCrystalFixer.refuel(Player)`——主手持有燃料右键装入槽 0（整叠填入）；空手右键取出全部燃料。
- **进出口与接网**：**仅相对背面一面**（`of(BACK)`）可连 ME 网与受电；相对正面必须紧贴待修复的母岩（赛特斯母岩用 `ae2:charged_certus_quartz_crystal`，恩特罗母岩用 `#c:dusts/ender_pearl`）。
- **状态读数（`machine_inspect`）**：客户端直接同步槽 0 燃料物品与数量，结合世界里正前方一格方块读出 `facing_block`、`front`、`power_side`（背面）以及是否正对着可修复母岩。
- **运行（`machine_run`）**：声明 `FuelUse.FUEL`——调用 `machine_run(target=..., item=燃料物品, count=数量)` 时备齐燃料并手持右键喂进槽 0。

### 3.8 高频扩展网络设备与批次 2 设施

- **扩展样板供应器（`extendedae:ex_pattern_provider`）**：36 槽样板容器，全六面连 ME 网；支持 `blocking_mode`、`lock_crafting`、`show_in_access_terminal`、`priority` 配置与 `use` / `obtain` / `deposit` 样板存取，服务端 `extendedae.device` 返回 36 槽样板摘要。
- **ME 扩展驱动器（`extendedae:ex_drive`）**：20 槽磁盘驱动器，客户端直读 20 槽存储元件状态与总使用槽数，`use` / `obtain` / `deposit` 可放取磁盘。
- **ME 扩展接口与超大接口（`extendedae:ex_interface`、`extendedae:oversize_interface`）**：36 槽分页（4 页，每页 9 格配置 + 9 格存储）；认领设备、暴露存储侧布局证明、服务端 `extendedae.device` 读出全部 36 格备料配置与当前存量。
- **扩展分子装配室（`extendedae:ex_molecular_assembler`）**：8 线程并行，客户端直读 `powered`，服务端读出各线程进度、升级卡与当前合成样板；`machine_configure` 支持放取加速卡。
- **ME 扩展 IO 端口（`extendedae:ex_io_port`）**：继承 `IOPortBlockEntity`，客户端/服务端读出输入输出磁盘与模式，`machine_configure` 支持 `fullness_mode`、`operation_mode` 与升级卡。
- **ME 原料缓存器（`extendedae:ingredient_buffer`）**：36 格通用物品/流体缓存方块；认领设备、允许物品格存取、服务端读出全部物品与流体内容。
- **ME 装罐机（`extendedae:caner`）**：仅顶面与底面两面（`TOP`/`BOTTOM`）连 ME 网与受电；`machine_inspect` 读模式（`fill` / `empty`）与槽内物品/流体，`machine_configure` 支持 `mode`（`fill` / `empty`，发 `CEAEGenericPacket("set", ordinal)`）。
- **ME 无线连接器与无线集线器（`extendedae:wireless_connect`、`extendedae:wireless_hub`）**：全六面连 ME 网；服务端 `extendedae.device` 读出 `connected`、`frequency`、`other_side`（集线器为 8 端口表 `ports[]`）、`power_use`、`color` 与升级卡；`machine_configure` 支持升级卡与集线器断开指定端口（`disconnect_port`，发 `CEAEGenericPacket("disconnect", port)`）。
- **装配矩阵多方块（`extendedae:assembler_matrix_frame/wall/glass/pattern/crafter/speed`）**：6 种构成方块全部认领；客户端读出 `formed`、`powered`、`active`；服务端 `extendedae.device` 读出集群边界（`bounds_min`、`bounds_max`）、`pattern_cores`、`crafter_cores`、`speed_cores`、`busy_crafters` 及已装样板总数；`machine_configure` 支持 `cancel_all`（发 `CEAEGenericPacket("cancel")` 停止矩阵全部合成任务）。

### 3.9 服务端只读操作（`extendedae.device`）

- 位于 `server.compat.extendedae`（`ExtendedAeServerCompat`），向 `ServerOperationRegistry` 注册 `extendedae.device`（版本 1）。
- 严格遵循只读与视野边界：入口一律经 `AskedCell.nearPlayer` 校验 128 格内已加载区块，不跨维、不替角色动手、不泄漏未加载区域。
- 按目标格实际方块实体类型返回对应只读字段（储罐毫桶、进度、自动输出与输出面、4 页压印进度、无线连接对端与能耗、装配矩阵多方块统计、36 槽扩展样板/接口明细等）；服务器未装 MaiCraft 或未装 ExtendedAE 时客户端自动回落到客户端同步字段 + 开窗缓存，不影响核心功能。

### 3.10 中文知识页（`maicraft://knowledge/machine/extendedae/index`）

由 `ExtendedAeKnowledge` 挂入 `KnowledgeSource`（并按 `entriesAbout` 挂到上述全部 ExtendedAE 方块/核心物品 ID 的用法页），重点写清：
1. **水晶装配器配方边界**：能做福鲁伊克斯水晶、恩特罗注入锭、恩特罗母岩、4 倍处理器以及所有 ExtendedAE 高级组件；**不能做恩特罗水晶和赛特斯石英水晶**（那两种走泡水 `obtain` 或 AdvancedAE 反应仓）。
2. **各加工机接电面差异**：水晶装配器非顶面五面、电路切片器非前后四面、扩展压印器与扩展充能器非正面五面、水晶修复器仅背面一面、装罐机仅顶底两面。
3. **无界面直点机器**：扩展充能器与水晶修复器右键直接放取，不弹界面；水晶修复器需正面紧贴母岩，修赛特斯母岩喂充能赛特斯水晶，修恩特罗母岩喂末影珍珠粉。
4. **母岩与配对**：拿恩特罗种子右键已放置的福鲁伊克斯块获得恩特罗母岩；无线连接器/集线器拿 `extendedae:wireless_tool` 依次右键两端配对；装配矩阵需用框架/墙壁/玻璃围成至少 3×3×3 的完整长方体外壳、内部填样板/合成/速度核心。

## 4 约束登记

| 编号 | 规则 | 类别 |
| --- | --- | :---: |
| EA-1 | 水晶装配器储罐内已有不同种流体且余量大于 0 时，拒绝静默倒新流体桶并报告冲突 | F（游戏事实：储罐单格只能容纳一种流体，异种流体无法倒入） |
| EA-2 | 扩展充能器仅接受有充能配方或实现 `IAEItemPowerStorage` 的物品，其他物品不盲点 | F（游戏事实：`TileExCharger.activate` 仅对这两类物品接收，其余触发弹出） |
| EA-3 | 水晶修复器正面不对着可修复母岩时仅在 `machine_inspect` 提示，不拦截加燃料 | C（原生交互原则：不替 LLM 预判拦截，只如实报告现场） |
| EA-4 | 语义布局证明排除 `TOOLBOX`、`CONFIG` 与流体储罐槽，非 36 格角色背包的菜单拒绝证明 | F（游戏事实：防止误点网络工具箱或把实体物品塞向幽灵/流体槽） |
| EA-5 | 服务端 `extendedae.device` 拒绝超过 128 格或未加载区块的查询 | F（服务端只读视野红线） |

## 5 验证计划

1. **离线单元测试（`:common:test`）**：
   - `ExtendedAeSemanticsLayoutTest`：覆盖全部菜单类型的语义映射、携带网络工具箱 9 格动态偏移、排除 `CONFIG` 与储罐槽、分页菜单仅暴露启用页槽位、非 36 格背包拒绝；
   - `ExtendedAeCrystalAssemblerTest`：覆盖水/岩浆桶需求换算（扣减储罐余量、刚好够数不备桶、异种流体冲突报错）、界面倒桶动作 `EMPTY_ITEM` 发包与空桶回收、固体+流体混合投料与 `wait=false`、自动输出与 `set_side` 配置；
   - `ExtendedAeProcessingMachinesTest`：覆盖电路切片器投取与设置、扩展压印器 4 页择优调度 / 翻页同步 / 模板收回 / `input_capacity`（1/4/64）、扩展充能器 4 槽并发充能与补电、水晶修复器状态读数与喂燃料；
   - `ExtendedAeNetworkDevicesAndKnowledgeTest`：覆盖 5 种高频扩展网络设备 + 原料缓存器 + 装罐机 + 无线连接器/集线器 + 装配矩阵的状态读数、配置、`extendedae.device` 128 格边界与序列化、知识页索引与条目；
   - `VerifiedVersions` 边界测试：验证 `[1.21-2.2.33, 1.21-2.3)` 接受 `1.21-2.2.33-neoforge` / `1.21-2.2.35-neoforge`，拒绝 `1.21-2.2.32-neoforge` 与 `1.21-2.3.0-neoforge`；
   - 架构检查（`org.maiwithu.maicraft.architecture.*`）：确保 `compat.extendedae` 与 `compat.ae2` 零互相依赖、`server.compat.extendedae` 零互相依赖、零反射、无违禁命名。
2. **双加载器编译**：`:fabric:compileJava` 与 `:neoforge:compileJava` 均通过（Fabric 零模组依赖，NeoForge `compileOnly` 编译通过）。
3. **实机验收项**：
   - 启动检查日志确认「联动 AE2 扩展（extendedae）：已登记」；
   - 在水晶装配器上用 `machine_run` 做出 8 颗福鲁伊克斯水晶（验证自动备水桶、界面倒桶、投料、取成品）与 4 块恩特罗注入锭；
   - 在电路切片器切一块金块得 9 张逻辑电路板；在扩展压印器并发压印处理器；在扩展充能器 4 槽充能赛特斯水晶；对水晶修复器喂充能水晶；
   - `machine_inspect` 核对扩展样板供应器、扩展驱动器、扩展接口、扩展分子装配室、无线连接器与装配矩阵读数。
