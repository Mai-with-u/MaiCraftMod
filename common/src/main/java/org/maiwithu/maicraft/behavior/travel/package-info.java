// SPDX-License-Identifier: GPL-3.0-only
/**
 * 出行：把"去一个地方"整段管起来——解析目的地（坐标、记得的地点、看得见的东西、方向走多远）、
 * 规划行程并逐刻推进、到达与中途停下都如实结算（实测位置、剩余距离与八向方位、垫的临时方块、泅渡）。
 *
 * <p>各出行方式（船、矿车、传送门、珍珠折跃、跨维度）在 {@code modes}，目的地解析与行程规划在
 * {@code planning}，出行任务与结果结算在 {@code settle}。出行方式通过 {@code behavior.travel.spi} 报价接入。
 */
package org.maiwithu.maicraft.behavior.travel;
