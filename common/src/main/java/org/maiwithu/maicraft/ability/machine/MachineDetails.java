// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 机器查看的结果细节：这一片有哪几张网、哪些设备、设备之间怎么连着，
 * 哪些格看着像机器但没有机器类型认领、有几格没加载。
 *
 * <p>线缆、传动件、管道这些只负责连接的格不单列，合进它们所在的网，只给格数、种类与按读数分组的运行状态；
 * 其余机器方块是设备，同一块里同种相邻的格合成一个，线缆上装着的部件各算一个。设备编号 d1、d2 只在这一次结果里有效：
 * 再指某一个设备用它的格（观察编号或坐标），建过档的用档案名；有格落在所看档案里的设备与网会带上档案名。
 *
 * @param networks     网：网络读取器认出来的每张网一条，没有读取器的连接件（管道这类）按贴着连成段，各一条
 * @param devices      设备，编号从 1 起
 * @param connections  设备之间的连接：部件朝着哪一格、加工处在哪一格、两个设备贴着
 * @param unrecognized 有方块实体、看着像机器、但没有机器类型认领的格，按方块 ID 归并
 * @param unloadedCells 范围内没加载的格数：这些格不知道是什么，不按空气算
 * @param scanComplete  范围扫完了没有；没扫完时下面列的只是已经看到的部分
 * @param archive       看的是哪份档案；没指档案为 null
 * @param check         和档案的整机比对：每种结论几格、没对上的每一格、多出来的格、安装段与部件；没指档案为 null。
 *                      结构一致不等于能转，转不转看各设备的 state
 * @param note          给 LLM 的一句说明，没有为空字符串
 * @param site          读现场（operation=read_site）抄出来的机器蓝图；只看时为 null
 */
record MachineDetails(List<NetworkView> networks, List<DeviceView> devices, List<Connection> connections,
                      List<UnrecognizedBlock> unrecognized, int unloadedCells, boolean scanComplete, String archive,
                      ArchiveCheck.Result check, String note, SiteRead site) implements ResultDetails {

    MachineDetails {
        networks = List.copyOf(networks);
        devices = List.copyOf(devices);
        connections = List.copyOf(connections);
        unrecognized = List.copyOf(unrecognized);
        note = note == null ? "" : note;
    }

    /**
     * 一张网，或一段没有网络读取器、按贴着连起来的连接件。
     *
     * @param id          网络编号（读取器给的，例如 me@x,y,z）；没有读取器的段是 adjacent@最低那一格
     * @param kind        网络种类（me、kinetic……）；没有读取器的段是 adjacent
     * @param description 这种网是什么，一句话
     * @param readable    整张网的读数读到了没有；没有读取器的段为 null
     * @param readings    整张网的读数（应力、频道、能量……），只在这里给一次；没有为 null
     * @param note        读不到的原因或要紧的一句；没有为 null
     * @param connectors  这张网在范围里的连接件；范围里一格连接件都没有（设备直接贴着）为 null
     * @param archive     有连接件落在所看档案里时写档案名；没有为 null
     */
    record NetworkView(String id, String kind, String description, Boolean readable, Map<String, Object> readings,
                       String note, Connectors connectors, String archive) {

        NetworkView {
            readings = readings == null || readings.isEmpty() ? null : Map.copyOf(readings);
            note = note == null || note.isEmpty() ? null : note;
        }
    }

    /**
     * 一张网在范围里的连接件：线缆、传动件、管道这些只负责连接的格，不逐格列，只给总数、范围、按种类计数，
     * 运行状态按读数分组给每组几格、在哪一片。
     *
     * @param cells  共几格
     * @param at     只有一格时在哪
     * @param box    不止一格时的包围盒
     * @param types  按机器类型计数（ae2:cable_bus、create:shaft……）
     * @param states 按读数分组：读数一样的格合成一组
     */
    record Connectors(int cells, BlockPos at, Box box, Map<String, Integer> types, List<ConnectorState> states) {

        Connectors {
            types = Map.copyOf(types);
            states = List.copyOf(states);
        }
    }

    /** 连接件里读数一样的一组：几格、在哪（一格给位置，多格给包围盒）、读数。 */
    record ConnectorState(int cells, BlockPos at, Box box, StateView state) {
    }

    /**
     * 一个设备：线缆与传动件以外的机器方块，同一块里同种相邻的格合成一个；或者线缆格某一面装着的一个部件。
     *
     * @param id      这一眼里的编号（d1、d2……）
     * @param type    机器类型编号；部件写部件的物品 ID
     * @param role    在机器里干什么（机器角色的小写名）；部件写 part
     * @param at      只有一格时在哪；部件为 null（看 host 与 side）
     * @param cells   不止一格时共几格
     * @param box     不止一格时的包围盒
     * @param host    部件装在哪一格；不是部件为 null
     * @param side    部件装在那一格的哪一面；不是部件为 null
     * @param on      挂在哪些网上（网络编号，含没有读取器的连接件段）；不在网上为 null
     * @param archive 有格落在所看档案里时写档案名；没有为 null
     * @param state   各格读数一样时的读数
     * @param states  各格读数不一样时按读数分组，每组列出是哪几格；读数一样时为 null
     * @param ports   网表示不了的进出口（侧面配置、台面怎么放东西、还没接上网的设备该从哪一面接）；没有为 null
     */
    record DeviceView(String id, String type, String role, BlockPos at, Integer cells, Box box, BlockPos host,
                      Direction side, List<String> on, String archive, StateView state, List<StateGroup> states,
                      List<ExchangePoint> ports) {

        DeviceView {
            on = on == null || on.isEmpty() ? null : List.copyOf(on);
            states = states == null || states.isEmpty() ? null : List.copyOf(states);
            ports = ports == null || ports.isEmpty() ? null : List.copyOf(ports);
        }
    }

    /** 一个设备里读数一样的几格。 */
    record StateGroup(List<BlockPos> cells, StateView state) {

        StateGroup {
            cells = List.copyOf(cells);
        }
    }

    /**
     * 两样东西之间的一条连接。
     *
     * @param from    从哪个设备（编号）
     * @param to      连到哪个设备（编号）；那一格不是设备时为 null，看 toCell 与 toBlock
     * @param toCell  连到的那一格不是设备时在哪
     * @param toBlock 连到的那一格不是设备时是什么方块
     * @param kind    怎么连着
     */
    record Connection(String from, String to, BlockPos toCell, String toBlock, ConnectionKind kind) {
    }

    /** 两样东西怎么连着。 */
    enum ConnectionKind {
        /** 部件朝着那一格：总线对着的箱子、保险库，存储总线读的那个容器。 */
        FACING,
        /** 那一格是这个设备的加工处：压机、搅拌器、注液器正下方第二格，机械手前方第二格，鼓风机前方第一格。 */
        PROCESSING_SPOT,
        /** 两个设备贴着（六向贴面）：漏斗贴着保险库、置物台贴着传送带。 */
        TOUCHING
    }

    /** 一组格的包围盒：最小角与最大角。 */
    record Box(BlockPos min, BlockPos max) {

        static Box of(Collection<BlockPos> cells) {
            Set<BlockPos> all = Set.copyOf(cells);
            return new Box(MachineInspectTask.corner(all, true), MachineInspectTask.corner(all, false));
        }
    }

    /**
     * 一格读数的写法：和机器类型给的运行状态一样，只是空着的项不写（没有转速、台面上没东西、没有说明）。
     *
     * @param activity 在干活、停着、过载、离线、不知道
     * @param speed    转速；没有为 null
     * @param progress 当前这一份做到几成；没有为 null
     * @param shown    台面、盆里、传送带上看得见的东西；没有为 null
     * @param readings 其余读数；没有为 null
     * @param note     读不到某些东西的原因；没有为 null
     */
    record StateView(MachineState.Activity activity, Double speed, Double progress, List<MachineState.Shown> shown,
                     Map<String, Object> readings, String note) {

        static StateView of(MachineState state, Map<String, Object> readings) {
            return new StateView(state.activity(), state.speed(), state.progress(),
                    state.shown().isEmpty() ? null : state.shown(),
                    readings.isEmpty() ? null : Map.copyOf(readings), state.note().isEmpty() ? null : state.note());
        }
    }

    /**
     * 读现场抄出来的那一台：照着建一台就把 blueprint 原样交给 machine_build，改这一台就让 machine_build 指档案名。
     *
     * @param devices       抄进来的是这一眼里的哪些设备（d#）；连接件随设备一起抄，不另列
     * @param archive       档案名：指档案读时是那份档案，不然是这次建的那份
     * @param archived      这次新建了档案；指档案读、名字被占、存不进时为 false，notes 写明
     * @param anchor        锚点：蓝图里 offset 的原点（包围盒最小角，指档案时是档案的锚点）
     * @param size          包围盒东西、上下、南北各几格
     * @param blueprint     machine_build 的 blueprint 正文，可以原样喂回去
     * @param cells         逐格几条（空气不写）
     * @param installations 安装段几段
     * @param parts         部件几个
     * @param unread        包围盒里没加载、读不到的格数：这些格没抄，不按空气算
     * @param complete      包围盒每一格都读到了、机器也没伸出这次看的范围
     * @param notes         抄不全、照着建会走样的地方，一句一条
     */
    record SiteRead(List<String> devices, String archive, boolean archived, BlockPos anchor, List<Integer> size,
                    JsonObject blueprint, int cells, int installations, int parts, int unread, boolean complete,
                    List<String> notes) {

        SiteRead {
            devices = List.copyOf(devices);
            size = List.copyOf(size);
            notes = List.copyOf(notes);
        }
    }

    /** 没有机器类型认领的格，按方块 ID 归并：共几格、前几个位置、缺哪个联动的一句说明。 */
    record UnrecognizedBlock(String blockId, int cells, List<BlockPos> samples, String note) {

        UnrecognizedBlock {
            samples = List.copyOf(samples);
        }
    }
}
