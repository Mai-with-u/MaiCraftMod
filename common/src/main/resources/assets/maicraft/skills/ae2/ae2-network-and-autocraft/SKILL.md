---
name: ae2-network-and-autocraft
description: 应用能源2网络规划与全自动合成：ME 频道与控制器、合成终端 3×3 手搓、样板编码 4 模式与防洗样板、样板管理与供应器、分子组装室、合成 CPU 监控与取消、10 类线缆部件按面配置与 P2P 主手内存卡配对、元件工作台、ME IO 传输站、无线访问点、量子环与奇点爆炸转化、空间存储、进阶工具及装有 Applied-Mekanistics 时的化学品自动化；搭 ME 网络或配自动合成前读
metadata: {"requires-mods": "ae2"}
---

# 应用能源2（AE2）网络规划与全自动合成

搭建或扩展 ME 网络时，先读总览 `lookup(topic=knowledge, id=maicraft://knowledge/machine/ae2/index)`，再按当前环节查阅专题页：自动合成读 `maicraft://knowledge/machine/ae2/autocrafting`，线缆部件与 P2P 读 `maicraft://knowledge/machine/ae2/parts-and-p2p`，磁盘与运维读 `maicraft://knowledge/machine/ae2/storage-and-ops`，量子环与空间存储读 `maicraft://knowledge/machine/ae2/quantum-and-spatial`，手持工具读 `maicraft://knowledge/machine/ae2/tools-and-power`；如果实例同时装了 Applied-Mekanistics，化学品接入读 `maicraft://knowledge/machine/appmek/index`。

## ME 频道、控制器与布线

- **频道规矩**：
  - 无控制器的小网全网最多 **8 个频道**，超限全网停摆；
  - 有 ME 控制器后，每个控制器表面可引出 **32 个频道**（普通线缆每根 8 频道、致密线缆每根 32 频道）；控制器多块拼装不能成冲突形状，否则 `controller_state=conflicted` 导致整网离线。
  - 不同颜色线缆互不相连，不带颜色的福鲁伊克斯线缆和任意颜色都相连。
- **施工与连网**：
  - `machine_build` 写蓝图时，线缆不是方块，写进安装段 `{kind: "ae2:fluix_glass_cable", offsets: [...]}`；终端、总线、面板、P2P 等写进 `parts`（`{offset, side, item}`）；控制器、驱动器、供应器等实心设备写进 `cells`。
  - 拉一条新线接回已有网络用 `machine_connect(target=设备, network=me)`。
  - `machine_inspect` 在客户端能看到部件通电与频道亮灭；完整的全网电量、频道用量、CPU 在途明细、样板容器清单与设备内部配置需要服务器支持 `ae2.network` 与 `ae2.device` 只读查询。

## 完整自动合成闭环

1. **合成终端 3×3 手搓**：身上带着已绑定无线合成终端或身旁有在线合成终端时，`obtain` 可直接借 3×3 合成格调用网内材料手搓；填料固定不自动跳到自动合成确认页，完工后自动把余料退回网络或背包。
2. **样板编码终端（`ae2:pattern_encoding_terminal`）**：
   - 作为线缆部件按面配置：`<side>.encode_mode` 支持 `crafting`、`processing`、`smithing`、`stonecutting` 四种模式，另有 `<side>.substitute`、`<side>.substitute_fluids`、`<side>.stonecutting_recipe` 与 `<side>.encode_pattern`（一次传入完整编码 JSON）。
   - `processing` 处理样板支持流体与超过 64 件的输入输出（0 号主产物必填）；切离 `processing` 会清空流体并把数量压回 1。
   - **防洗样板纪律**：编码前先把输出槽里已有的编码样板取走，并且确认当前模式的配方有效后再发编码动作；配方无效时触发编码会把输出槽里原有样板洗回空白样板。
3. **样板管理终端与样板供应器**：
   - 样板供应器（`ae2:pattern_provider` / 部件版 `ae2:cable_pattern_provider`）支持 `pattern.<产出物品>=in|out`、`blocking_mode`（`yes`/`no`）、`lock_crafting_mode`（`none` / `until_pulse` / `while_high` / `while_low` / `until_result`）、`show_in_access_terminal`、`priority`，方块版还有 `push_direction`。
   - **单面推送注意点**：用石英扳手切出单面 `push_direction` 后，**被设为推送面的那一面不再连 ME 网**，接线缆要走其他面。
   - 样板管理终端（`ae2:pattern_access_terminal`）支持 `<side>.show_providers` 与 `<side>.put_pattern` 快速送入；全网样板槽位总览经 `machine_inspect` 查看。
4. **分子组装室（`ae2:molecular_assembler`）**：
   - 可插 5 张加速卡（`upgrade.0..4`）；**单机样板槽（`pattern`）一旦放入样板，相邻样板供应器就不再向它推送自动合成作业**，用作集群组装室时务必保持单机样板槽为空。
5. **合成 CPU 监控与取消**：
   - 正常取货走 `obtain(..., via=ae2)`，网内缺货且有样板时会自动算计划并下单；缺料或 CPU 不够时如实返回计划与原因。
   - 作业卡住或需让位时，对合成 CPU 方块用 `machine_inspect` 查看存储、并行、进度、在途/待产明细与 `cant_store`（网络存储满溢告警），用 `machine_configure` 配 `scheduling_mode`（`any`/`player_only`/`machine_only`）或 `job_action`（`cancel` 取消 / `toggle_pause` 暂停恢复）。

## 10 类线缆部件按面配置与 P2P 配对

- **统一键名规矩**：`machine_inspect` 查看 `ae2:cable_bus` 时，每个面部件的局部设置直接挂在对应部件的 `settings` 里（不带面名前缀）；调用 `machine_configure` 修改线缆格上的部件时，键名**必须加小写六向面名前缀 `<side>.<key>`**（例如 `north.redstone_mode`、`east.stock.0`、`up.priority`）。
- **高频部件速查**：
  - **输入总线（`ae2:import_bus`）**：`redstone_mode`、`fuzzy_mode`、`key_type.item`、`key_type.fluid`、`filter.0..62`、`upgrade.0..4`（超过 18 格过滤需插容量卡）。
  - **输出总线（`ae2:export_bus`）**：`redstone_mode`、`fuzzy_mode`、`craft_only`、`scheduling_mode`、`filter.0..62`、`upgrade.0..4`。
  - **存储总线（`ae2:storage_bus`）**：`access`（`read_write`/`read`/`write`）、`storage_filter`、`filter_on_extract`、`fuzzy_mode`、`priority`、`filter.0..62`、`action`（`partition`/`clear`）。
  - **ME 接口（`ae2:interface` / `ae2:cable_interface`）**：`fuzzy_mode`、`priority`、`stock.0..8`（`<物品或流体 ID> [数量]` 或 `empty`）、`upgrade.0`（插合成卡自动备料）。
  - **成型面板 / 破坏面板**：成型面板配 `place_mode`（`block`/`drop`）、`fuzzy_mode`、`priority`、`filter.0..62`；破坏面板无界面，放置时继承物品附魔（世界最高建筑高度朝上放置可被动产陨石粉）。
  - **标准发信器 / 能量发信器**：**不占频道**，配 `redstone_mode`、`reporting_value`（标准版另有 `filter.0`、`fuzzy_mode`、`craft_via_redstone`、`upgrade.0`）。
  - **存储监视器 / 转换监视器**：客户端直读展示物、数量、能否合成与锁定态，配 `locked`、`displayed`。
- **P2P 通道调谐与配对**：
  - AE2 本体支持 6 种 `attunement`：`me`、`redstone`、`item`、`fluid`、`fe`、`light`，手持调谐物品右键原地转换且不消耗物品。
  - **主手内存卡硬规矩**：配对 `<side>.p2p_role`（`input` / `output`）**必须主手持有 `ae2:memory_card`**，副手会被游戏直接拒绝；输入端潜行右键复制并分配频率，输出端普通右键绑定频率并自动转为同型输出端。

## 磁盘分区、倒盘与网络运维

- **元件工作台（`ae2:cell_workbench`）**：配 `cell=in:<元件 ID>|out`、`copy_mode`（`clear_on_remove`/`keep_on_remove`）、`fuzzy_mode`、`action=partition|clear`、`filter.0..62`、`upgrade.0..7`（升级槽数随当前元件动态变化）。
- **ME IO 传输站（游戏方块 `ae2:io_port`）**：
  - 配 `operation_mode`（`empty` 盘→网 / `fill` 网→盘）、`fullness_mode`（`empty`/`half`/`full`）、`redstone_mode`、`upgrade.0..2`；用 `machine_run` 把存储元件、物质炮或上色器放进输入格批量倒盘或装填弹药/颜料，完成后自动从输出格收回。
  - **红石卡坑点**：装了红石卡后，即使把 `redstone_mode` 设为 `ignore` 仍会按低电平逻辑工作；不需要红石控制时不要插红石卡。
- **无线访问点（`ae2:wireless_access_point`）**：用 `machine_configure` 配 `boosters=0..64`（有效范围 `16 + boosters^1.5` 格，64 张达 528 格；耗电随张数急剧上升）。客户端不开窗不传张数，优先看服务端 `ae2.device` 或打开界面确认。
- **内存卡与网络工具**：`machine_inspect(item=ae2:memory_card)` 直读已复制配置，`machine_configure(item=ae2:memory_card, settings={"clear": "true"})` 清空；全网各设备耗电明细经 `machine_inspect` 读服务端 `ae2.network` 获取。

## 量子环、奇点爆炸转化与空间存储

- **量子环（`ae2:quantum_ring`）与量子链接仓（`ae2:quantum_link`）**：
  - 3×3×1 平面结构：外圈 8 个量子环 + 中心 1 个量子链接仓，载 32 频道。
  - **四角不能接线缆**：仅 4 条边中点可以连外部线缆；两端链接仓放入同频 `ae2:quantum_entangled_singularity`（`singularity=in|out`）且**两端区块同时保持加载**才跨维连通。
- **奇点爆炸转化**：直接走 `obtain(item=ae2:quantum_entangled_singularity, via=ae2_explosion_transform)`，每次消耗 1 个奇点与 1 个末影珍珠/末影珍珠粉，优先用不破坏方块的迷你 TNT（`ae2:tiny_tnt`）在安全空地引爆产出 **2 个同频缠绕奇点**（严格受保护区与当前许可约束）。
- **空间存储三件套**：
  - **空间塔（`ae2:spatial_pylon`）**：沿轴连续 ≥2 格成一线（每线 1 频道），合围长方体内腔；塔块越齐效率越高、单次切换耗电越低。
  - **空间传输站（`ae2:spatial_io_port`）**：槽 0 放空间元件（2/16/128 档）、槽 1 必须为空，满足区域有效、尺寸匹配且网络电量充足时，给一次**红石低→高上升沿脉冲**即互换区域并把元件移到槽 1（`machine_run` 支持自动放盘并在旁有开关时触发脉冲）。
  - **空间锚（`ae2:spatial_anchor`）**：按加载区块数计耗电（`80 + n*(n+1)/2` AE/t），配 `overlay_mode=yes|no`。

## 进阶手持工具

- **物质炮（`ae2:matter_cannon`）**：自动接入 `fight` 远程战斗（直线瞬发、无冷却、单发 1600 AE），自动排除零伤害彩弹；**装弹必须放进 ME 箱子或 ME IO 传输站（`fill` 模式）**，不能直接在背包塞入。
- **上色器（`ae2:color_applicator`）**：同样经 ME 箱子或 ME IO 传输站装填染料/彩弹/雪球，用 `machine_configure(item=ae2:color_applicator, settings={"color": "<颜色|transparent>"})` 直切颜色，副手拿着铺线缆会自动染色。
- **熵变仪（`ae2:entropy_manipulator`）**：普通右键加热/烧炼/点 TNT（点 TNT 不耗电），潜行右键冷却（单次 1600 AE），可对着流体使用。
- **充能法杖（`ae2:charged_staff`）**：满电近战附加 6 点魔法伤害（每次 300 AE）。全部带电工具没电时都进充能器 `machine_run` 补电。

## 装有 Applied-Mekanistics（`appmek`）时的化学品自动化

当实例同时装了 Mekanism 与 Applied-Mekanistics 时，先读 `lookup(topic=knowledge, id=maicraft://knowledge/machine/appmek/index)`：

- **输入总线与过滤/备料/分区槽**：输入总线多出 `key_type.chemical=true|false`；总线、ME 接口（含备料毫桶数）、成型面板、发信器与元件工作台的过滤/备料/分区槽支持 `chemical:<化学品 ID>`；ME 接口与样板供应器可直接贴 Mekanism 加压管道收发化学品。
- **处理样板支持化学品**：样板编码终端仅在 `processing` 模式支持 `inputs[]`/`outputs[]` 里写 `kind="chemical"`（数量按毫桶），非处理模式不接受化学品。
- **化学品 P2P 通道**：`attunement=chemical` 需持任意 Mekanism 化学品储罐右键调谐，再以主手内存卡配对输入端与输出端。
- **化学品存储元件与便携化学品元件**：5 档 ME 化学品元件（每盘 5 种上限）与 5 档便携化学品元件（每盘 18 种上限）均按 8,000 mB/byte 计容；便携化学品元件支持 `machine_inspect(item=...)` 只读电量与盘内化学品及充能器补电，同样**绝不对空盘潜行右键**，且**不进腾背包、不把储罐灌装塞进 `obtain` 物品取货**。
- **放射性化学品禁忌**：核废料、钚、钋等放射性化学品被游戏原生禁止存入化学品元件、禁止投入物质聚合器、也禁止销毁卡溢出清除，必须走 Mekanism 放射性废料桶处理。
- **QIO 外部存储**：把存储总线贴在 `mekanism:qio_dashboard` 的**背面**，仪表盘绑好有效频率且放置存储总线的玩家拥有访问权限即可把 QIO 挂载为 ME 外部存储（仅物品，不含化学品）。

## 什么时候停下

频道规划已满且无控制器材料、合成 CPU 报缺料或 `cant_store` 满溢需扩容存储、空间切换耗电超过现有能源元件上限，或需要在受保护区外引爆奇点却未获许可时，把当前 `machine_inspect` 读数与原因写进结果，停下来交由调用方决定。
