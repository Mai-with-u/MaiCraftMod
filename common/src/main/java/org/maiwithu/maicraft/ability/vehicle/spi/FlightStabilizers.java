// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle.spi;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;

/**
 * 装在载具上的飞行稳定器（Create: Flight Control）：高度稳定器保持一个目标高度，智能姿态稳定器把船身配平，
 * 线速度耦合器按飞控红石代理收到的方向信号推着船沿机体轴走、没信号就刹住。联动模组实现它：读一个结构上装着哪些、
 * 开没开、有没有动力、怎么出力，飞控红石代理的每个面从哪个频率收信号、转给哪个稳定器的哪个动作；
 * 改设置和在它们的界面里改好保存一样。驾驶与看载具只认这个接口，不认具体是哪个模组。
 *
 * <p>位置都是结构地块里的格（和位姿无关）；高度稳定器认的高度是结构转动点的世界 y，不是船底。
 */
public interface FlightStabilizers {

    /** 高度稳定器、智能姿态稳定器、线速度耦合器。 */
    String ALTITUDE_HOLD = "altitude_hold";
    String ATTITUDE_HOLD = "attitude_hold";
    String VELOCITY_COUPLER = "velocity_coupler";

    /** 出力方式：接了飞控计算机（由它分给推进器）、直接施力、都没有（算了也不出力）。 */
    String OUTPUT_COMPUTER = "computer";
    String OUTPUT_DIRECT = "direct";
    String OUTPUT_NONE = "none";

    /** 读这个结构上的稳定器；结构不在附近、不是这种联动认得的为空，在但一个都没装给空的一份。 */
    Optional<Setup> read(String structureId);

    /**
     * 把高度稳定器设成"保持设定高度"、设定高度是 y，并且开着：和在它的界面里填好设定高度保存一样。
     * 发不出去（那一格不是高度稳定器、没加载）返回假；服务器收没收下要再 read 读回。
     *
     * @param y 高度稳定器认的高度：结构转动点的世界 y
     */
    boolean holdAltitude(String structureId, BlockPos local, double y);

    /** 打开一个没开的智能姿态稳定器或线速度耦合器：和在界面里把默认开关拨到开保存一样。发不出去返回假。 */
    boolean switchOn(String structureId, BlockPos local);

    /** 线速度耦合器沿机体 x、z 两根轴的最大目标速度设成 speed（格每秒），竖轴不动。发不出去返回假。 */
    boolean setHorizontalSpeed(String structureId, BlockPos local, double speed);

    /**
     * 一个结构上的稳定器。
     *
     * @param stabilizers 每个稳定器
     * @param inputs      飞控红石代理每个接了动作的面
     * @param complete    结构地块都读到了
     * @param note        没读全、读不了的部分一句话；没有为空串
     */
    record Setup(List<Stabilizer> stabilizers, List<ProxyInput> inputs, boolean complete, String note) {
        public Setup {
            stabilizers = List.copyOf(stabilizers);
            inputs = List.copyOf(inputs);
            note = note == null ? "" : note;
        }
    }

    /**
     * 一个稳定器。
     *
     * @param kind     altitude_hold / attitude_hold / velocity_coupler
     * @param local    在结构地块里的格
     * @param on       开着（默认开关与红石总开关合起来）
     * @param powered  底下的轴在转、没过载（没动力它什么都不做）
     * @param output   computer 接了飞控计算机 / direct 直接施力 / none 都没有
     * @param mode     高度稳定器：hold_current 保持当前高度 / hold_set 保持设定高度 / minimum 最低高度；
     *                 智能姿态稳定器：level 配平 / level_heading 配平并保持航向 / hold_attitude 保持当前姿态；耦合器为空串
     * @param target   高度稳定器的目标高度（转动点的世界 y）；别的为 NaN
     * @param altitude 高度稳定器此刻读到的高度；别的为 NaN
     * @param maxX     线速度耦合器沿机体 x 轴的最大目标速度（格每秒）；别的为 0
     * @param maxY     沿机体 y 轴的
     * @param maxZ     沿机体 z 轴的
     */
    record Stabilizer(String kind, BlockPos local, boolean on, boolean powered, String output, String mode,
                      double target, double altitude, double maxX, double maxY, double maxZ) {
        public Stabilizer {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(local, "local");
            Objects.requireNonNull(output, "output");
            mode = mode == null ? "" : mode;
        }

        /** 能干活：有动力，也有地方出力。开没开另说（驾驶前会打开）。 */
        public boolean working() {
            return powered && !OUTPUT_NONE.equals(output);
        }
    }

    /**
     * 飞控红石代理的一个接了动作的面。
     *
     * @param frequency 面旁边那个无线收端听的频率（两格频率物品 ID，加号连，和无线红石打字机的写法一样）；
     *                  面旁边不是无线收端为空串
     * @param device    转给谁：altitude_hold / attitude_hold / velocity_coupler，别的写方块 ID
     * @param target    转给的那个稳定器在地块里的格
     * @param action    动作原名：master_switch、body_vx_pos、body_vz_neg……
     */
    record ProxyInput(String frequency, String device, BlockPos target, String action) {
        public ProxyInput {
            frequency = frequency == null ? "" : frequency;
            Objects.requireNonNull(device, "device");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(action, "action");
        }
    }
}
