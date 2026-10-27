// SPDX-License-Identifier: GPL-3.0-only
/**
 * 提问：只在三种情况下向 LLM 提问（超出许可要同意、几种做法后果不同要选、目标说不清），其余一律不问。
 * 再试、换办法还是带着事实结束，由各任务按自己排好的办法与次数上限决定，永远不问"要不要重试"。
 */
package org.maiwithu.maicraft.behavior.retry;
