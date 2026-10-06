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
        return s;
    }
}
