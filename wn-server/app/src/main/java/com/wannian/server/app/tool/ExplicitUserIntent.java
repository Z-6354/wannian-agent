package com.wannian.server.app.tool;

import java.util.Locale;
import java.util.regex.Pattern;

/** Conservative gate based only on the current user's original message. */
public final class ExplicitUserIntent {
    private static final Pattern NEGATION = Pattern.compile("(?:不要|別|别|不想|不需要|无需|無需|先不|不要自动|不要自動|分析|解释|解釋|怎样|怎樣|如何)");
    private static final Pattern ACTION = Pattern.compile(
            "(?:请|請|帮我|幫我|替我|现在|現在|直接|开始|開始|立即|把|将|將|我决定|我決定|我同意|我想请|我想請).{0,24}(?:导入|導入|提取|激活|啟用|启用|切换|切換|创建|創建|归档|歸檔)|"
            + "^(?:导入|導入|提取|激活|啟用|启用|切换|切換|创建|創建|归档|歸檔)(?:到|为|為|角色|人物|会话|會話|TXT|文本|文件|$)|"
            + "(?:用|把|将|將).{0,50}(?:完善|補充|补充|应用|應用).{0,32}(?:默认角色|預設角色|默认杜小洛|預設杜小洛|杜小洛|yanhuo)|"
            + "(?:应用|應用|完善|補充|补充).{0,24}(?:到|给|給|至)?(?:默认角色|預設角色|默认杜小洛|預設杜小洛|yanhuo)|"
            + "(?:审核通过|批准合并|写入性格|合并到性格|确认写入).{0,48}(?:性格|SOUL|VOICE|临时稿|审核稿)?");
    private ExplicitUserIntent() {}
    public static boolean allowsWrite(String originalUserMessage) {
        if (originalUserMessage == null || originalUserMessage.isBlank()) return false;
        String text = stripQuotes(originalUserMessage).toLowerCase(Locale.ROOT);
        return !NEGATION.matcher(text).find() && ACTION.matcher(text).find();
    }
    private static String stripQuotes(String value) {
        return value.replaceAll("“[^”]*”|‘[^’]*’|『[^』]*』|「[^」]*」|\"[^\"]*\"|'[^']*'|`[^`]*`", " ");
    }
}
