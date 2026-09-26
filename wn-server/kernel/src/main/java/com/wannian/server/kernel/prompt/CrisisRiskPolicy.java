package com.wannian.server.kernel.prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 可审计的中文危机线索分层；这是线索筛查，不是诊断或完整风险评估。 */
public final class CrisisRiskPolicy {

    private static final Pattern STANDALONE_DENIAL = Pattern.compile(
            "^\\s*(?:我)?(?:没有|没|并没有|并不|不|不想|没想|没有想|并不想|不会)(?:想|要)?(?:自杀|自残|自伤|轻生|结束生命|伤害自己|死)[。！？.!?\\s]*$");
    private static final Pattern HARMFUL_MEDICAL_ACT = Pattern.compile(
            "(?:吞|吃|服)(?:下(?:了|过)?|了|过)?(?:\\d{1,3}|很多|大量|一把|几把|整瓶|一整瓶|超量|过量)(?:片|颗|粒|瓶)?\\s*(?:[\\p{IsHan}]{0,6}药(?:片|物)?)");
    private static final Pattern URGENT_MEDICAL_ACT = Pattern.compile(
            "(?:吞|吃|服)(?:下(?:了|过)?|了|过)?(?:[\\p{IsHan}]{0,6}药(?:片|物)?)(?:.{0,12}(?:救命|快来|帮帮我|叫救护车))");
    private static final Pattern NEGATED_MEDICAL_ACT = Pattern.compile(
            "(?:我)?(?:没有|没|未|并未)(?:吞|吃|服)(?:下(?:了|过)?|了|过)?(?:[\\p{IsHan}]{0,8}药(?:片|物)?)");
    private static final Pattern QUOTED_SPAN = Pattern.compile("[“‘「『\\\"][^”’」』\\\"]*[”’」』\\\"]");

    public enum Level { NONE, WATCH, ELEVATED, IMMEDIATE }

    public record Decision(Level level, List<String> reasons) {
        public Decision {
            reasons = List.copyOf(reasons);
        }

        public boolean requiresSafetyPath() {
            return level != Level.NONE;
        }
    }

    private CrisisRiskPolicy() {}

    public static Decision classify(String userMessage) {
        String text = userMessage == null ? "" : userMessage.toLowerCase(Locale.ROOT);
        String signalText = text;
        boolean nonIntentQuoteContext = containsAny(text, "只是引用", "引用示例", "只是电影台词", "只是小说台词", "台词引用");
        boolean attributedQuote = containsAny(text, "朋友说", "家人说", "同事说", "他告诉我", "她告诉我", "对方说");
        if (nonIntentQuoteContext && !attributedQuote) {
            signalText = QUOTED_SPAN.matcher(signalText).replaceAll(" ");
        }
        String activeText = signalText.replace("自杀预防", "").replace("预防自杀", "")
                .replace("自杀防治", "").replace("防止自杀", "")
                .replace("自伤预防", "").replace("自残预防", "");
        String medicalSignalText = NEGATED_MEDICAL_ACT.matcher(activeText).replaceAll(" ");
        List<String> reasons = new ArrayList<>();
        boolean deniedStandalone = STANDALONE_DENIAL.matcher(activeText).matches();
        boolean selfHarm = !deniedStandalone
                && containsAny(activeText, "自杀", "自残", "自伤", "轻生", "结束生命", "想死", "伤害自己");
        boolean harmOthers = containsAny(activeText, "伤害别人", "伤害他人", "伤害他", "杀人", "杀了他", "杀了她", "打死他", "捅他", "砍他");
        boolean weaponHeld = containsAny(activeText, "手里有武器", "拿着武器", "手里有刀", "拿着刀", "手里有枪", "拿着枪", "正拿着武器");
        boolean weaponPresent = weaponHeld || containsAny(activeText, "旁边有武器", "附近有武器", "身边有刀", "放着一把刀", "武器在旁边", "带着武器");
        boolean medicationIngestion = !deniedStandalone && (HARMFUL_MEDICAL_ACT.matcher(medicalSignalText).find()
                || URGENT_MEDICAL_ACT.matcher(medicalSignalText).find()
                || containsAny(activeText, "药物过量", "药片过量", "故意吞药", "故意服药"));
        boolean directAct = !deniedStandalone && (medicationIngestion
                || containsAny(activeText, "割腕", "跳楼", "开车撞", "正在伤害自己", "已经伤害自己"));
        boolean thirdParty = !deniedStandalone && containsAny(activeText, "朋友", "家人", "同事", "他", "她", "对方")
                && (containsAny(activeText, "自杀", "自残", "自伤", "轻生", "想死", "伤害自己") || directAct);
        boolean plan = containsAny(activeText, "我要伤害自己", "准备伤害自己", "打算伤害自己",
                "准备自杀", "打算自杀", "我要自残", "准备自残");
        boolean imminent = containsAny(activeText, "现在就", "马上", "马上要去找", "立即去找", "正在", "已经吞", "已经割", "已经跳", "手里拿着", "今晚就")
                || directAct || (harmOthers && weaponPresent);
        boolean vague = containsAny(activeText, "不想活", "活着没意思", "活不下去", "撑不下去了", "想消失", "不如死了");

        if (selfHarm) reasons.add("SELF_HARM_EXPLICIT");
        if (thirdParty || harmOthers) reasons.add("THIRD_PARTY_HARM_EXPLICIT");
        if (harmOthers) reasons.add("HARM_TO_OTHERS_EXPLICIT");
        if (weaponPresent) reasons.add("WEAPON_ACCESS_EXPLICIT");
        if (weaponHeld) reasons.add("WEAPON_HELD_EXPLICIT");
        if (directAct) reasons.add("HARMFUL_ACT_EXPLICIT");
        if (medicationIngestion) reasons.add("MEDICATION_INGESTION_EXPLICIT");
        if (plan) reasons.add("SELF_HARM_PLAN_EXPLICIT");
        if (imminent && (selfHarm || thirdParty || harmOthers || vague || directAct)) reasons.add("IMMEDIACY_LANGUAGE");
        if (vague && !selfHarm) reasons.add("DISTRESS_AMBIGUOUS");

        Level level = Level.NONE;
        if (reasons.contains("IMMEDIACY_LANGUAGE")) level = Level.IMMEDIATE;
        else if (thirdParty || harmOthers || selfHarm || plan) level = Level.ELEVATED;
        else if (vague) level = Level.WATCH;
        return new Decision(level, reasons);
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) if (text.contains(needle)) return true;
        return false;
    }
}
