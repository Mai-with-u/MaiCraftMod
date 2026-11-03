// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.touhoulittlemaid;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 认领女仆的回话：车万女仆在她头上换上文字气泡的同时，把回话以灰字 {@code <她的名字> 回话} 只发给主人。
 * 回车之后聊天栏新收到的服务端系统消息里，第一条以她的名字开头的就是回话；"角色设定文件已经自动生成完毕"
 * 单独认（她这次只生成了人设、没回答）；别的原话都算提示（对话被禁用、token 用完、服务器消息）。
 *
 * <p>名字对不上时的兜底：服务端与本机的模型名翻译可能不同，角色身边只有她一只在想时，
 * 名字不是角色自己的 {@code <某名字> 话} 也认作她的回话。两只同名的都在想时分不清是谁说的，
 * 两条都放进提示，不认领。对话框回车时车万女仆在本机回显 {@code <角色名> 原话}，那一行不经服务端，进不来这里。
 */
public final class ReplyClaim {

    // 回话的样子：尖括号里是名字，空一格接回话；回话可以有好几行。
    private static final Pattern SAID = Pattern.compile("^<([^>]+)> (.*)$", Pattern.DOTALL);

    private ReplyClaim() {}

    /**
     * 认领的结果。
     *
     * @param reply            她的回话；没认到为 null
     * @param personaGenerated 收到了"角色设定文件已经自动生成完毕"
     * @param notices          其他系统消息原话，按先后
     * @param ambiguous        有同名的另一只也在想，分不清是谁说的
     */
    public record Claimed(String reply, boolean personaGenerated, List<String> notices, boolean ambiguous) {

        public Claimed {
            notices = List.copyOf(notices);
        }

        /** 她有了回应：回话、只生成了人设、或同名分不清——都不用再等。 */
        public boolean answered() {
            return reply != null || personaGenerated || ambiguous;
        }
    }

    /**
     * @param messages         回车之后聊天栏新收到的服务端系统消息原话，按先后
     * @param names            她的名字候选：此刻在本机的显示名、本机模型表里她皮肤的显示名
     * @param selfName         角色自己的名字
     * @param onlyHerThinking  角色身边在想的只有她一只
     * @param sameNameThinking 有和她同名的另一只也在想
     * @param personaText      "角色设定文件已经自动生成完毕"在本机语言里的样子
     */
    public static Claimed claim(List<String> messages, Set<String> names, String selfName, boolean onlyHerThinking,
            boolean sameNameThinking, String personaText) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(names, "names");
        String reply = null;
        boolean persona = false;
        boolean ambiguous = false;
        List<String> notices = new ArrayList<>();
        for (String message : messages) {
            if (message.equals(personaText)) {
                persona = true;
                continue;
            }
            Matcher said = SAID.matcher(message);
            if (reply == null && !ambiguous && said.matches()) {
                String who = said.group(1);
                boolean hers = names.contains(who)
                        || onlyHerThinking && !who.equals(selfName);
                if (hers && sameNameThinking) {
                    // 同名的另一只也在想：这句可能是谁说的都有，原话放进提示，不认领。
                    ambiguous = true;
                    notices.add(message);
                    continue;
                }
                if (hers) {
                    reply = said.group(2);
                    continue;
                }
            }
            notices.add(message);
        }
        return new Claimed(reply, persona, notices, ambiguous);
    }
}
