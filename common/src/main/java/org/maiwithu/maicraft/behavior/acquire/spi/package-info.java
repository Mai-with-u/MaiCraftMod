// SPDX-License-Identifier: GPL-3.0-only
/**
 * 拿东西的接缝：物品来源与引擎之间的约定。引擎（engine 包）只认这里的类型——物品来源、一次请求、
 * 一次报价、问询现场；来源的实现（玩家行为层自带的与联动模组提供的）也只认这里的类型。
 * 放进来的只有接缝本身：来源自己才懂的判断（配方怎么挑、箱子怎么点）写在实现一侧的包里。
 *
 * <p>{@code ItemSource} 是实现一个来源要做的事，六个方法各管一段：
 * <ul>
 * <li>{@code describe()}：来源的名字，写进结果与失败说明让 LLM 看到问过谁；同一来源分几路报价时
 *     报价上的名字要与它一致，不然做砸了按名字划掉会对不上。</li>
 * <li>{@code via()}：自报属于哪条途径，取值是 {@code AcquireVia}；via 参数按它过滤来源，
 *     结果细节 obtained_via 也用它回答东西从哪来的。一个实现只认一条途径。</li>
 * <li>{@code quote(request, context)}：问价。只回答"能不能、能给多少、代价多少"，不决定先后——
 *     引擎收集所有来源的回答后统一挑。回答是 {@code SourceQuote} 五种之一，给不了就如实说给不了。</li>
 * <li>{@code begin(request, offer, context)}：按一份被选中的报价动手，返回的动作逐刻推进，
 *     把物品弄进背包算做完；数量以动手后引擎的重新清点为准。问价与动手之间世界可能变了，
 *     动手前重新看现场，现场已经做不了（设施没了、记忆过时）就返回 empty，引擎划掉它换下一个。</li>
 * <li>{@code look(request, context)}：可选。报价要"打开看一眼才知道有没有"的来源（随身终端、
 *     没读过的背包）给一个看一眼的动作，看完记下存货；不用看或看不了给空。</li>
 * <li>{@code missingIngredients(request, offer, context)}：可选。现做的来源回答这次动手还缺哪几样
 *     原料（每样一条请求、件数是还缺的），估算按它逐样往下估；现成拿的来源没有原料要备，默认为空。</li>
 * </ul>
 *
 * <p>{@code SourceQuote} 的五种变体按现场如实选：
 * {@code Offer} 给得了（数量写不出来时用 {@code UNKNOWN_COUNT}，但要留意：0 件不该报价，应回答给不了）；
 * {@code Unavailable} 这次问询下没有货，reason 写清游戏里的事实；
 * {@code NeedsApproval} 货可能有但这次任务的许可不让动，problem 里写清要哪一项许可；
 * {@code Unsupported} 需要的模组没装或还没实现，引擎不会在它身上空转；
 * {@code NotYet} 还在看现场（附近的方块还没扫完）说不准有没有，引擎等它看完，不把"还没看完"当成"没有"。
 *
 * <p>{@code SourceContext} 是问价或动手那一刻告诉来源的一次现场，哪些字段哪些来源用得着：
 * {@code characterAt} 与 {@code permissions} 必有，来源按位置估距离、按许可决定哪些事根本不做；
 * {@code radiusBlocks} 与 {@code maxDistanceBlocks} 是这次任务的搜索范围与愿意走的距离，
 * 采掘、容器这类要找现场的来源用它，报价太远的路仍由引擎挡下；
 * {@code records} 只有动手且接了记账口的执行才有，来源动作在世界上确认做过的事记进那次任务的结果；
 * {@code alsoWanted} 只有"同一次要货里还缺几样"的场景才有，开一次箱能一起拿的来源（取货、交易）用它；
 * {@code digTool} 只有目标点名了挖方块工具才有；{@code unreachable} 由引擎记着这一次走不到跟前的地方，
 * 来源问价时跳过它们、再走到不了时记进来；{@code leavesLongCooking} 只有 obtain 这一场及其备料层能打开。
 * 可选字段按真实出现过的组合用静态工厂表达，新加字段只改全参构造与用到的工厂。
 *
 * <p>联动模组实现一个来源的边界：必做 {@code describe}、{@code via}、{@code quote}、{@code begin}；
 * {@code look} 与 {@code missingIngredients} 按来源性质决定要不要覆写。途径是联动自己的就自报一条
 * 带 {@code AcquireVia} 说明的新途径，装了才出现；来源内部再缺原料、缺工具时经引擎备料，
 * 不自己问别的来源。所有实现启动时明确登记，不用 ServiceLoader。
 */
package org.maiwithu.maicraft.behavior.acquire.spi;
