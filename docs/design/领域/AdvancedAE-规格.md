# 领域规格：高级 AE（AdvancedAE）适配

> 状态：已批准（2026-11-27，一期落地反应仓）。
> 依据：实测与开发实例 `[高级AE] AdvancedAE-1.6.11-1.21.1.jar`（含内嵌 `ae2addonlib-1.0.3-1.21.1.jar`）全量字节码核查、[机器联动模组事实清单](机器联动模组事实清单.md)第 15 节、[联动模组需求总表](联动模组需求总表.md)第 3.11 节与第 4 节（A7）、[机器-规格](机器-规格.md)。
> 范围：以独立联动模组包（`compat.advancedae` + `neoforge.compat.advancedae` + `server.compat.advancedae`，`compileOnly` 零反射）接入 AdvancedAE 的核心批量加工机器——**反应仓（`advanced_ae:reaction_chamber`）**，打通 64 颗批量合成福鲁伊克斯水晶、恩特罗水晶、赛特斯石英水晶、恩特罗注入锭与量子灌注液的完整自动化闭环，并附中文知识页。
> 边界：本规格不新增任何顶层 MCP 工具或独立能力，不修改既有参数名表；全部能力挂接回既有机器与知识能力（`machine_inspect` / `machine_configure` / `machine_run` / `machine_connect` / `use` / `lookup`）。`compat.advancedae` 与 `compat.ae2`、`compat.extendedae` 互不依赖；未安装 AdvancedAE 或版本不在验证范围 `[1.6.11, 1.7)` 时零类加载风险。AdvancedAE 的高级定向样板供应器、量子合成器、量子计算机多方块与量子盔甲/升级卡系统规模独立，留待后续需要时再按本规格扩期。

## 1 范围

| 模块 / 对象 | 主要挂接能力 | 是否依赖服务端只读 |
| --- | --- | :---: |
| 独立联动入口 `AdvancedAeCompat` 与语义布局证明 `AdvancedAeSemanticsLayout` | `use`、`machine_run`、`machine_configure` | 否 |
| 反应仓（`advanced_ae:reaction_chamber`）状态读数与六面连网 | `machine_inspect`、`machine_connect` | 可选（不开窗读双流体槽、进度、电量与输出面） |
| 反应仓批量加工（9 格物品输入 + 方块直点水/岩浆桶灌注 + 物品/流体产物收货 + `wait=false`） | `machine_run` | 否 |
| 反应仓配置（`auto_export`、4 格加速卡、清空输入/输出流体槽、相对六面输出配置） | `machine_configure` | 可选 |
| 服务端只读操作 `advanced_ae.device`（版本 1） | `machine_inspect` | 是（视野 128 格内只读） |
| 中文知识页（`maicraft://knowledge/machine/advanced_ae/index`） | `lookup` | 否 |

## 2 真人怎么玩

到了中后期需要成组生产赛特斯石英水晶、福鲁伊克斯水晶、恩特罗水晶或恩特罗注入锭时，往水里一把把扔不仅慢还容易冲散，真人会直接造一台 AdvancedAE 的反应仓并接上 ME 电网（六面任意一面都能接线缆，而且反应仓单次批量配方耗电高，电网里要配够能源元件避免跳电）。加流体时不用开界面，直接拿水桶或岩浆桶右键反应仓方块本身，一次灌进 1000 mB 到输入流体槽（上限 16,000 mB），空桶留在手上；如果生产的是量子灌注液这类流体产物，也能拿空桶直接右键反应仓方块把输出流体槽里的流体舀出来。放固体原料就打开界面把料放进 9 个输入格，塞满 4 张加速卡提速，等加工走完从输出格拿走 64 颗成品；如果储罐里残留了不需要的旧流体，就在界面里点清空按钮把输入或输出流体槽冲掉，或者配好自动输出与允许输出面让成品自动进相邻容器。

## 3 新逻辑

### 3.1 独立联动入口、版本范围与编译依赖

- **编译依赖（`neoforge/build.gradle` + `gradle.properties`）**：
  - `compileOnly("maven.modrinth:advancedae:${advancedae_version}") { transitive = false }`，`advancedae_version=1.6.11-1.21.1`（许可证 `LGPL-3.0-or-later`，与实测实例逐版一致）；
  - `compileOnly("io.github.pedroksl:ae2addonlib:${ae2addonlib_version}") { transitive = false }`，`ae2addonlib_version=1.0.3-1.21.1`（来自 Maven Central，许可证 `LGPL-3.0`，与实测实例内嵌 jarjar 版本一致）。
- **验证版本范围**：`new VerifiedVersions("1.6.11", "1.7")`（实测实例版本为 `1.6.11-1.21.1`，在范围内）。
- **包布局与依赖隔离**：
  - `common`：`org.maiwithu.maicraft.compat.advancedae`（`AdvancedAeCompat`、`AdvancedAeMachineCatalog`、`AdvancedAeMachineType`、`AdvancedAeSemanticsLayout`、`ReactionChamberOps`、`AdvancedAeKnowledge`）；
  - `common` 服务端：`org.maiwithu.maicraft.server.compat.advancedae`（`AdvancedAeServerCompat`，注册 `advanced_ae.device` 版本 1）；
  - `neoforge`：`org.maiwithu.maicraft.neoforge.compat.advancedae`（`AdvancedAeClientReads`、`AdvancedAeMenuActions`、`AdvancedAeDeviceServerReads`）；
  - `ModFeatures` 新增 `"advanced_ae" -> "advanced_ae"`。

### 3.2 反应仓界面布局证明（`AdvancedAeSemanticsLayout`）

- 菜单注册 ID：`advanced_ae:reaction_chamber`（`ReactionChamberMenu`，继承自 AE2 `UpgradeableMenu`）。
- 槽位规则：
  - 要求恰好 36 个角色背包槽；
  - 容器侧暴露 `MACHINE_INPUT`（9 格）、`MACHINE_OUTPUT`（1 格）与 `UPGRADE`（4 格，加速卡），全部按机器槽核对；
  - 显式排除动态 `TOOLBOX` 与 `CONFIG` 槽。
- 输出方向子菜单 `OutputDirectionMenu` 不含角色背包普通搬运格，仅由配置动作直连操作。

### 3.3 反应仓状态读数与进出口（`machine_inspect` / `machine_connect`）

- **进出口**：六面全部可连 ME 网络与受电（`ReactionChamberEntity.getGridConnectableSides` 返回 `EnumSet.allOf(Direction.class)`）；流体输入可通过方块右键倒桶或相邻流体管道/总线接入。
- **状态读数**：
  - 客户端直读：`working`（方块状态 `WORKING` / `ReactionChamberEntity.isWorking()`）、`powered`、`active`；
  - 服务端 `advanced_ae.device`（或开窗缓存）补全：
    - `input_fluid` / `input_fluid_mb` / `input_fluid_capacity_mb`（储罐槽 1，上限 16,000 mB）；
    - `output_fluid` / `output_fluid_mb` / `output_fluid_capacity_mb`（储罐槽 0，上限 16,000 mB）；
    - `processing_time` / `max_processing_time`（200）、`energy_stored` / `energy_capacity`（500,000 AE）、`auto_export`（`yes`/`no`）、`output_sides`（相对六面）、`upgrade.0..3`、`show_warning`（缺少电力或输出受阻提示）、输入/输出物品槽快照。

### 3.4 反应仓运行与流体桶投料（`machine_run`）

- **配方流体换算（`ingredientFeed`）**：
  - 查到配方包含流体输入（例如 500 mB 或 1000 mB 的水/岩浆）时，先查已知的输入流体槽（槽 1）余量：
    - 若输入流体槽里已有**不同种流体**且存量大于 0，在 `FeedPlan.conflict` 明确说明输入流体槽被异种流体占用，提示先通过 `machine_configure(flush_fluid_in=true)` 清空，不静默强倒；
    - 若同种流体余量已满足本次全部批数所需毫桶数，跳过备桶并记录说明；
    - 否则按 `ceil((总需毫桶 - 现有同种毫桶) / 1000.0)` 映射为对应标准桶物品（经游戏 `Fluid.getBucket()` 查得），由备料阶段备齐。
- **方块直点倒桶动作（`ReactionChamberBucketFeed`）**：
  - 核查 `ReactionChamberBlock.useItemOn` 确认：手持满桶右键反应仓方块本身直接调用 `useBucket` 注入 1000 mB 流体并把主手变成 `minecraft:bucket`，**不打开界面**；
  - 投料动作逐桶把满桶切到主手、瞄准反应仓方块右键并等待服务端确认主手由满桶变为空桶（`BlockUseConfirmation` 体系）；满罐或异种流体导致方块拒收时立刻中止并如实报告，不死循环连点。
- **固体投料、等待与收货**：
  - 固体原料经 `MenuFeed` 投入 9 格 `MACHINE_INPUT`；
  - 兼容 `wait=false`（倒桶与投料完成即返，不干等 200 刻加工）；
  - 物品产物（如 64 颗 `extendedae:entro_crystal`、`ae2:fluix_crystal`、`ae2:certus_quartz_crystal`、`extendedae:entro_ingot`）经 `MenuOutputLook` 从 `MACHINE_OUTPUT` 取出；
  - 若目标物品是反应仓流体产物对应的桶（例如 `advanced_ae:quantum_infusion_bucket`），在 `FullRun` / 收货阶段支持手持空桶右键反应仓方块（触发 `useBucket` 空桶分支从输出槽舀出 1000 mB 成满桶）。

### 3.5 反应仓配置（`machine_configure`）

- `auto_export`（`yes` / `no`）：开窗发送 AE2 `ConfigButtonPacket(Settings.AUTO_EXPORT, false)` 切换并读回 `ReactionChamberMenu.getAutoExport()`；
- `upgrade.0..3`（`ae2:speed_card` / `empty`）：开窗向 `UPGRADE` 槽放取加速卡；
- `flush_fluid_in`（`true`）：开窗调用 `ReactionChamberMenu.clearFluid()`（触发 `sendClientAction("flushFluid")`）清空输入流体槽；
- `flush_fluid_out`（`true`）：开窗调用 `ReactionChamberMenu.clearFluidOut()`（触发 `sendClientAction("flushFluidOut")`）清空输出流体槽；
- `output_side.<relative_side>`（`front`/`back`/`left`/`right`/`top`/`bottom`，值 `true` / `false`）：开窗调 `ReactionChamberMenu.configureOutput()` 进入 `OutputDirectionMenu` 子菜单，按目标状态调用 `updateSideStatus(RelativeSide)` 并确认回读后返回。

### 3.6 服务端只读操作（`advanced_ae.device`）与知识页

- `server.compat.advancedae` 注册 `advanced_ae.device`（版本 1），严格走 `AskedCell.nearPlayer` 128 格内已加载校验，只读返回反应仓的双流体槽、输入/输出物品槽、进度、电量、自动输出、允许输出面与升级卡。
- 知识页 `maicraft://knowledge/machine/advanced_ae/index` 写清：
  - 反应仓可一次批量产 64 颗恩特罗水晶、福鲁伊克斯水晶、赛特斯石英水晶、充能赛特斯石英与恩特罗注入锭，彻底替代往水里扔；
  - 加水/岩浆可直接手持桶右键方块，无需开界面；
  - 单次批量配方瞬时耗电较高（数万至数十万 AE），需接入带能源元件的 ME 网络。

## 4 约束登记

| 编号 | 规则 | 类别 |
| --- | --- | :---: |
| AAE-1 | 输入流体槽（槽 1）已有不同种流体时拒绝盲倒新桶，提示用 `flush_fluid_in` 清空 | F（游戏事实：`GenericStackInv` 单槽仅存一种流体） |
| AAE-2 | 布局证明排除 `TOOLBOX` 与 `CONFIG` 槽，非 36 格角色背包拒绝证明 | F（游戏事实） |
| AAE-3 | `advanced_ae.device` 拒绝超过 128 格或未加载区块的查询 | F（服务端只读视野红线） |

## 5 验证计划

1. **离线单元测试（`:common:test`）**：
   - `AdvancedAeSemanticsLayoutTest`：覆盖 `advanced_ae:reaction_chamber` 9 输入 + 1 输出 + 4 升级格布局、携带网络工具箱 9 格偏移、非 36 格背包拒绝；
   - `ReactionChamberOpsTest`：覆盖水/岩浆桶换算（余量扣减、够数不备桶、异种流体冲突）、方块直点倒桶与满罐中止、物品收货、流体桶空桶舀取、`wait=false`、`auto_export` / `flush_fluid_in` / `flush_fluid_out` / `output_side.*` / 升级卡配置；
   - `AdvancedAeServerReadsAndKnowledgeTest`：覆盖 `advanced_ae.device` 128 格边界、未加载/跨维拒绝、JSON 序列化及知识页内容；
   - 架构检查（`org.maiwithu.maicraft.architecture.*`）：确保 `compat.advancedae` 与 `compat.ae2` / `compat.extendedae` 零互相依赖，零反射。
2. **双加载器编译**：`:fabric:compileJava` 与 `:neoforge:compileJava` 均通过。
3. **实机验收项**：
   - 启动日志确认「联动 高级 AE（advanced_ae）：已登记」；
   - 接通带能源元件的 ME 电网后，在反应仓上执行 `machine_run` 批量制作 64 颗 `extendedae:entro_crystal` 与 64 颗 `ae2:fluix_crystal`，验证自动备水桶直点灌水、投料、取成品与 `machine_inspect` 读数。
