package dev.volansvo.svo.bots.human;

import java.util.Locale;

/** Что игрок хочет от ботов, когда пишет в чат. Разбор по частым словам, без догадок. */
public enum ChatIntent {
    FOLLOW, HOLD, LOOT, FREE, ASK_HEAL, ASK_AMMO, ASK_FOOD, ARE_YOU_BOT, GG, NONE;

    private static final String[] FOLLOW_W = {"за мной", "ко мне", "сюда", "го за", "идите за", "иди за", "follow"};
    private static final String[] HOLD_W = {"стой", "стоять", "держи", "держать", "жди тут", "ждите", "hold"};
    private static final String[] LOOT_W = {"лутай", "лутать", "собери лут", "собирай", "ищи лут"};
    private static final String[] FREE_W = {"вольно", "свободн", "сами", "как хотите", "делай что хоч"};
    private static final String[] HEAL_W = {"хил", "аптеч", "лечил", "хилк", "бинт"};
    private static final String[] AMMO_W = {"патрон", "пули", "аммо", "стрел"};
    private static final String[] FOOD_W = {"еда", "еды", "еду", "хавк", "жрать", "поесть"};
    private static final String[] ASK_W = {"дай", "дайте", "есть ", "нужн", "кинь", "скинь", "поделис", "?"};
    private static final String[] BOT_W = {"ты бот", "вы бот", "это бот", "он бот", "бот?", "боты?", "are you bot"};

    public static ChatIntent parse(String message) {
        if (message == null) return NONE;
        String m = " " + message.toLowerCase(Locale.ROOT).trim() + " ";
        if (has(m, BOT_W)) return ARE_YOU_BOT;
        String bare = m.trim();
        if (bare.equals("gg") || bare.equals("гг") || bare.startsWith("гг ") || bare.startsWith("gg ")) return GG;
        boolean ask = has(m, ASK_W);
        if (ask && has(m, HEAL_W)) return ASK_HEAL;
        if (ask && has(m, AMMO_W)) return ASK_AMMO;
        if (ask && has(m, FOOD_W)) return ASK_FOOD;
        if (has(m, FOLLOW_W)) return FOLLOW;
        if (has(m, LOOT_W)) return LOOT;
        if (has(m, FREE_W)) return FREE;
        if (has(m, HOLD_W)) return HOLD;
        return NONE;
    }

    /** В сообщении назван этот бот: ник целиком или его часть до цифр и подчёркивания. */
    public static boolean mentions(String message, String botName) {
        if (message == null || botName == null) return false;
        String m = message.toLowerCase(Locale.ROOT), n = botName.toLowerCase(Locale.ROOT);
        if (m.contains(n)) return true;
        int cut = 0;
        while (cut < n.length() && Character.isLetter(n.charAt(cut))) cut++;
        return cut >= 4 && m.contains(n.substring(0, cut));
    }

    private static boolean has(String m, String[] words) {
        for (String w : words) if (m.contains(w)) return true;
        return false;
    }
}
