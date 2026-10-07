package dev.volansvo.svo.bots;

import org.bukkit.configuration.ConfigurationSection;

/** Параметры «мастерства» ботов из config.yml (раздел bots). */
public final class BotSkill {

    /** Среднее отклонение прицела в градусах. Меньше - точнее. */
    public double aimError = 2.0;
    /** Задержка реакции на нового врага, тики (1 тик = 0.05 сек). */
    public int reactionTicks = 7;
    /** Максимальная скорость поворота головы, градусов за тик. */
    public float turnSpeed = 34f;
    /** Дальность зрения в блоках. */
    public double viewDistance = 110;
    /** Боты охотятся на Жириновского ради ядерной кнопки, когда хорошо вооружены.
     *  По умолчанию выключено: вардена быстрее игрока, и бот почти всегда погибает. */
    public boolean huntWarden = false;
    /** Иногда пишут в чат (после убийства/смерти). */
    public boolean chat = true;
    /** Пробовать в бою неизвестные плагинные предметы и запоминать полезные. */
    public boolean learnItems = true;
    /** Писать в консоль причины смертей и решения ботов. */
    public boolean debug = false;

    /** Свой поиск пути (прыжки через ямы, подкоп, мостики). Выключен - ванильный поиск мобов. */
    public boolean navSmart = true;
    /** Путь может ломать мешающие блоки. */
    public boolean navDig = true;
    /** Путь может ставить блоки: мостик через яму, столб вверх. */
    public boolean navPlace = true;
    /** Путь может прыгать через ямы в 1-2 блока. */
    public boolean navParkour = true;
    /** Сколько миллисекунд тика все боты вместе тратят на поиск пути. */
    public double navBudgetMs = 1.5;
    /** Бежать по прямой прыжками, как игроки. */
    public boolean sprintJump = true;
    /** Насколько боты отличаются друг от друга по мастерству (0 - одинаковые). */
    public double skillSpread = 1.0;

    /** Прицел как у руки с мышью: видит цель с задержкой, поправляет порциями. */
    public boolean humanAim = true;
    /** Занятый бот хуже видит и слышит; темнота и присед цели мешают её заметить. */
    public boolean attention = true;
    /** Реплики приводятся к манере письма бота, время набора зависит от длины. */
    public boolean chatStyle = true;
    /** Боты понимают «за мной», «дай хил», «ты бот?» в чате. */
    public boolean chatCommands = true;
    /** Память между матчами: счёт с игроками, место высадки, сказанные реплики. */
    public boolean memory = true;
    /** Записывать, где ходят игроки, и водить ботов теми же тропами. */
    public boolean trails = true;
    /** Отвечать на прыжки и приседания тиммейта тем же. */
    public boolean mirror = true;
    /** Забирать вещи из сундука по одной, а не разом. */
    public boolean lootByOne = true;
    /** Задирать голову при перезарядке плагинного ствола (старое поведение). */
    public boolean reloadLookUp = false;

    // ---- личное, задаётся в personal()
    /** Общий темп: больше - медленнее реакции и решения. */
    public double tempo = 1.0;
    /** Как часто бот «зевает» и реагирует в разы позже. */
    public double lapse = 0.04;
    /** На сколько тиков назад бот видит движущуюся цель. */
    public int trackDelay = 3;
    /** Раз во сколько тиков рука поправляет прицел. */
    public int aimPeriod = 3;
    /** Манера письма в чате. */
    public dev.volansvo.svo.bots.human.ChatStyle.Style style = dev.volansvo.svo.bots.human.ChatStyle.Style.LAZY;
    /** На «ты бот?» отвечает, что он человек. */
    public boolean deniesBot = false;
    /** Насколько охотно дурачится с тиммейтами. */
    public double playful = 0.5;
    /** В какую сторону чаще стрейфит: от -0.25 до 0.25. */
    public double strafeBias = 0;
    /** Как часто этот бот бежит прыжками (доля отрезков пути). */
    public double hop = 0.6;
    /** Привычный наклон головы при ходьбе, градусы (плюс - вниз). */
    public float pitchBias = 0f;

    /**
     * Настройки одного бота: общие, сдвинутые под его «уровень игры». Уровень зависит только
     * от ника, поэтому один и тот же бот от матча к матчу играет одинаково.
     */
    public BotSkill personal(String name) {
        BotSkill s = new BotSkill();
        s.aimError = aimError; s.reactionTicks = reactionTicks; s.turnSpeed = turnSpeed; s.viewDistance = viewDistance;
        s.huntWarden = huntWarden; s.chat = chat; s.learnItems = learnItems; s.debug = debug;
        s.navSmart = navSmart; s.navDig = navDig; s.navPlace = navPlace; s.navParkour = navParkour;
        s.navBudgetMs = navBudgetMs; s.sprintJump = sprintJump; s.skillSpread = skillSpread;
        s.humanAim = humanAim; s.attention = attention; s.chatStyle = chatStyle; s.chatCommands = chatCommands;
        s.memory = memory; s.trails = trails; s.mirror = mirror; s.lootByOne = lootByOne; s.reloadLookUp = reloadLookUp;
        java.util.Random r = new java.util.Random(name.hashCode() * 31L + 7);
        double lvl = Math.max(-1.6, Math.min(1.6, r.nextGaussian()));
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.contains("nub") || n.contains("noob")) lvl -= 1.0;
        if (n.contains("pro") || n.contains("sniper") || n.contains("top") || n.contains("boss") || n.contains("best")) lvl += 0.6;
        lvl *= skillSpread;
        s.aimError = aimError * Math.pow(1.45, -lvl);
        s.reactionTicks = Math.max(2, (int) Math.round(reactionTicks * Math.pow(1.3, -lvl)));
        s.turnSpeed = (float) (turnSpeed * Math.pow(1.12, lvl));
        s.hop = Math.max(0.1, Math.min(0.95, 0.55 + lvl * 0.15 + (r.nextDouble() - 0.5) * 0.5));
        s.pitchBias = (float) ((r.nextDouble() - 0.5) * 8.0);
        s.tempo = Math.pow(1.18, -lvl) * (0.9 + r.nextDouble() * 0.2);
        s.lapse = Math.max(0.01, Math.min(0.10, 0.05 - lvl * 0.02));
        s.trackDelay = lvl > 0.6 ? 2 : (lvl < -0.6 ? 4 : 3);
        s.aimPeriod = lvl > 0.8 ? 2 : (lvl < -0.8 ? 4 : 3);
        int st = r.nextInt(100);
        dev.volansvo.svo.bots.human.ChatStyle.Style[] styles = dev.volansvo.svo.bots.human.ChatStyle.Style.values();
        // Грамотных и транслита мало, ленивых и со скобочками большинство.
        s.style = st < 10 ? styles[0] : st < 45 ? styles[1] : st < 70 ? styles[2] : st < 80 ? styles[3] : st < 95 ? styles[4] : styles[5];
        s.deniesBot = r.nextInt(4) == 0;
        s.playful = 0.2 + r.nextDouble() * 0.7;
        s.strafeBias = (r.nextDouble() - 0.5) * 0.5;
        return s;
    }

    public static BotSkill from(ConfigurationSection sec) {
        BotSkill s = new BotSkill();
        if (sec == null) return s;
        s.aimError = sec.getDouble("aim-error", s.aimError);
        s.reactionTicks = sec.getInt("reaction-ticks", s.reactionTicks);
        s.turnSpeed = (float) sec.getDouble("turn-speed", s.turnSpeed);
        s.viewDistance = sec.getDouble("view-distance", s.viewDistance);
        s.huntWarden = sec.getBoolean("hunt-warden", s.huntWarden);
        s.chat = sec.getBoolean("chat", s.chat);
        s.learnItems = sec.getBoolean("learn-items", s.learnItems);
        s.debug = sec.getBoolean("debug", s.debug);
        s.navSmart = sec.getBoolean("nav.smart", s.navSmart);
        s.navDig = sec.getBoolean("nav.dig", s.navDig);
        s.navPlace = sec.getBoolean("nav.place", s.navPlace);
        s.navParkour = sec.getBoolean("nav.parkour", s.navParkour);
        s.navBudgetMs = sec.getDouble("nav.budget-ms", s.navBudgetMs);
        s.sprintJump = sec.getBoolean("sprint-jump", s.sprintJump);
        s.skillSpread = sec.getDouble("skill-spread", s.skillSpread);
        s.humanAim = sec.getBoolean("human.aim", s.humanAim);
        s.attention = sec.getBoolean("human.attention", s.attention);
        s.chatStyle = sec.getBoolean("human.chat-style", s.chatStyle);
        s.chatCommands = sec.getBoolean("human.chat-commands", s.chatCommands);
        s.memory = sec.getBoolean("human.memory", s.memory);
        s.trails = sec.getBoolean("human.trails", s.trails);
        s.mirror = sec.getBoolean("human.mirror", s.mirror);
        s.lootByOne = sec.getBoolean("human.loot-by-one", s.lootByOne);
        s.reloadLookUp = sec.getBoolean("human.reload-look-up", s.reloadLookUp);
        return s;
    }
}
