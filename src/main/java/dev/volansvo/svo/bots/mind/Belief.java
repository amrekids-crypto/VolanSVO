package dev.volansvo.svo.bots.mind;

/**
 * Насколько бот уверен в том, где враг. У каждого сведения есть источник: увиденному верят
 * долго, шагам за стеной - пару секунд. Уверенность падает со временем, а место расплывается:
 * враг за это время мог уйти.
 */
public final class Belief {

    /** Откуда бот знает о враге. */
    public enum Source {
        SEEN(1.0, 8, 0.5),
        DAMAGE(0.7, 5, 1.5),
        SHOT(0.6, 5, 1.0),
        STEPS(0.4, 3, 1.0),
        CALLOUT(0.5, 6, 3.0),
        MAP(0.3, 15, 3.0);

        /** Уверенность в момент получения. */
        public final double conf0;
        /** За сколько секунд она падает в e раз. */
        public final double tau;
        /** Погрешность места в блоках в момент получения (для звуков к ней добавляется дальность). */
        public final double r0;

        Source(double conf0, double tau, double r0) {
            this.conf0 = conf0;
            this.tau = tau;
            this.r0 = r0;
        }
    }

    /** Стрелять и кидать гранату по памяти можно только при такой уверенности и таком радиусе. */
    public static final double FIRE_CONF = 0.75, FIRE_RADIUS = 4.0;
    /** Выше - идём прямо к месту, ниже - обыскиваем район. */
    public static final double CHASE_CONF = 0.35;
    /** Ниже - сведение ничего не стоит. */
    public static final double FORGET_CONF = 0.05;

    /** Скорость врага в блоках за секунду: стоял, шёл, бежал. */
    public static final double STILL = 1.5, WALK = 4.3, RUN = 5.6;
    private static final double MAX_RADIUS = 40;

    private Belief() {}

    public static double conf(Source s, int ageTicks) {
        if (s == null) return 0;
        return ageTicks <= 0 ? s.conf0 : s.conf0 * Math.exp(-ageTicks / (s.tau * 20.0));
    }

    /** В каком радиусе от запомненного места враг может быть сейчас (speed - его скорость, блоков в секунду). */
    public static double radius(double r0, int ageTicks, double speed) {
        return Math.min(MAX_RADIUS, r0 + speed * Math.max(0, ageTicks) / 20.0);
    }

    /** Погрешность места по звуку: чем дальше источник, тем грубее. */
    public static double heardRadius(double distance) {
        return 0.6 + 0.1 * distance;
    }

    /**
     * Сила «среднего» игрока на этой доле матча (0 - старт, 1 - конец): на неё бот опирается,
     * пока не разглядел, чем враг вооружён. В начале все почти голые, к середине одеты.
     */
    public static double expectedPower(double matchFraction) {
        double f = Math.max(0, Math.min(1, matchFraction));
        return 0.7 + 2.3 * Math.min(1, f / 0.45);
    }
}
