// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.mekanism;

import java.util.List;

import org.maiwithu.maicraft.kernel.knowledge.KnowledgeDocument;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeSource;

/**
 * 通用机械机器这一半的资料：认得哪些机器与管道，侧面配置的设置项怎么写，配置器怎么改，发电机怎么送燃料，管道怎么接。
 */
final class MekanismMachineKnowledge implements KnowledgeSource {

    static final String INDEX = "maicraft://knowledge/machine/mekanism/index";

    @Override public List<KnowledgeDocument.Entry> entries() {
        return List.of(new KnowledgeDocument.Entry(INDEX, "machine.mekanism.index", "通用机械的机器与侧面配置",
                "认得哪些 Mekanism 机器与管道、侧面配置与分拣器规则怎么写、配置器怎么改、怎么投料、发电机怎么送燃料、管网怎么接",
                "mekanism 通用机械 侧面配置 配置器 物流分拣器 规则 投料 线缆 管道 导管 能量立方 冶金灌注机 发电机 燃料"));
    }

    @Override public List<KnowledgeDocument.Entry> entriesAbout(String registryId) {
        boolean known = MekanismMachineCatalog.kinds().stream().anyMatch(kind -> kind.blocks().contains(registryId));
        return known || registryId.equals(ConfiguratorClicks.CONFIGURATOR) ? entries() : List.of();
    }

    @Override public KnowledgeDocument read(String uri) {
        if (!uri.equals(INDEX)) return null;
        StringBuilder text = new StringBuilder("# 通用机械的机器与侧面配置\n\n");
        text.append("## 侧面配置\n\n")
                .append("- 每台机器每一面、每种介质有一个模式：none 不通、input 进、output 出、input_output 进出都行，")
                .append("还有 input_1、output_2、energy、extra 等，能选哪几种看 `machine_inspect` 或 `machine_configure` 报的可选值。\n")
                .append("- `machine_configure` 的键写 `side.方向.介质`（方向 north/south/east/west/up/down，介质 item/fluid/energy），")
                .append("值写模式名，例如 `{\"side.north.item\": \"output\"}`。化学品与热只看不改。\n")
                .append("- 改的时候角色手持配置器、调到那种介质的模式、潜行右键那一面，每点一下往后换一种，读回对上为止；")
                .append("身上没有配置器会先去拿。\n\n");
        text.append("## 物流分拣器\n\n")
                .append("- `sorter.item` 写一样物品的 ID：分拣器只放行这一样（一条精确的单物品规则）；没有规则就新建，")
                .append("只有一条同物品规则就改成精确的，已经有别的规则时报冲突、不删。\n")
                .append("- `sorter.auto_eject` 写 on / off：没有规则对得上的东西往不往外送。\n")
                .append("- 规则只在开着界面时同步：改的时候角色会空着手点开它的界面再改，读数是上次打开时看见的。\n\n");
        text.append("## 投料\n\n")
                .append("- `machine_run` 对加工机器会走过去点开界面，把料搬进去；格子在界面里，站在外面看不见出口，")
                .append("投完就收手，做没做好用 `use` 打开它看。\n")
                .append("- 能开界面看、存取的：加工机器、能量立方、各级工厂与发电机；升级、配方显示这类虚拟格不点。\n")
                .append("- 灌注物（碳、红石、钻石……）在 1.21 里是化学品，背包带不进去：冶金灌注机这类从附加格吃物品再换成它。")
                .append("`machine_run` 会挑一样能换的物品（煤、红石、钻石粉……，身上带够的先用）先投进附加格再投主料；")
                .append("钻石灌注物从钻石粉换，不是钻石。机器里已有的灌注物开着界面读得到，读得到就少投。\n\n");
        text.append("## 发电机\n\n")
                .append("- 热能、生物、燃气发电机有燃料格：`machine_run` 的 `item` 写燃料、`count` 写投几件，不查配方；")
                .append("角色点开界面把燃料放进燃料格，投完就收手。收哪些燃料见下面各台的说明。\n")
                .append("- 太阳能、风力发电机不吃料：`machine_run` 给了 `item` 会照实说不用投。\n")
                .append("- 发电机不能配侧面：只从模组定死的面出电（多数是正面，太阳能是底面，高级太阳能与风力是正面和底面），")
                .append("`machine_inspect` 的 `energy_out` 写着是哪几面，线缆要接在这几面上。\n\n");
        text.append("## 管网\n\n")
                .append("- 通用线缆送电、机械管道送流体、物流传输管道送物品、加压管道送化学品；管道贴着管道、贴着机器能收发的那一面就连上。\n")
                .append("- `machine_connect` 的 `network` 写 energy、fluid 或 item，会用基础那一级的线缆、管道、导管接；")
                .append("机器要有对应介质能进的那一面（侧面配置不是 none）。\n")
                .append("- 接哪一面按侧面配置挑：来源挑往外送的面（output、能量立方的正面、发电机出电的面），要接的机器挑往里收的面；")
                .append("这样的面一个都没有才接介质对得上的别的面。热的侧面不当能量面接。\n")
                .append("- 线路不贴着同种管道和别的 Mekanism 机器走（会被一起连上），贴着别种管道照走（线缆和物流管道不相连）。")
                .append("铺完等几秒让连接同步过来再核对两端在不在同一张网。\n")
                .append("- 读不到存量：网里存着多少电、多少流体客户端看不到，只数得出几段管道、连着几台机器；要看存量打开能量立方、储罐的界面。\n\n");
        text.append("## 认得的机器与管道\n\n");
        for (MekanismMachineCatalog.Kind kind : MekanismMachineCatalog.kinds()) {
            text.append("- ").append(kind.name()).append("（`").append(kind.id()).append("`）");
            // 发电机带上收什么燃料或靠什么发电：送燃料前不用另查。
            if (!kind.fuel().note().isEmpty()) text.append("：").append(kind.fuel().note());
            text.append("\n");
        }
        text.append("- 别的能配侧面的 Mekanism 机器：认作\"其他 Mekanism 机器\"，侧面配置照样能看能改\n");
        return new KnowledgeDocument(INDEX, "machine.mekanism.index", "通用机械的机器与侧面配置", "Mekanism 机器资料",
                text.toString());
    }

    @Override public String status() {
        return "通用机械机器资料：可用，" + MekanismMachineCatalog.kinds().size() + " 种";
    }
}
