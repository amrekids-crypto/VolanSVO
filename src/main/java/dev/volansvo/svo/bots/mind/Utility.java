package dev.volansvo.svo.bots.mind;

/**
 * Взвешивание намерений. Каждое намерение - произведение «соображений» от 0 до 1 (уверен ли
 * в цели, хватит ли сил, подходит ли дистанция...). Каждое соображение проходит через кривую
 * отклика, а итог поправляется на их число: иначе намерение с пятью соображениями проигрывало
 * бы намерению с двумя просто из-за умножения.
 */
public final class Utility {

    private Utility() {}

    public static double clamp(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    /** Прямая: 0 при x <= lo, 1 при x >= hi. */
    public static double linear(double x, double lo, double hi) {
        if (hi == lo) return x >= hi ? 1 : 0;
        return clamp((x - lo) / (hi - lo));
    }

    /** Убывающая прямая: 1 при x <= lo, 0 при x >= hi. */
    public static double inverse(double x, double lo, double hi) {
        return 1 - linear(x, lo, hi);
    }

    /** S-образная: 0.5 в точке mid, крутизна k. */
    public static double logistic(double x, double mid, double k) {
        return 1.0 / (1.0 + Math.exp(-k * (x - mid)));
    }

    /** Степенная от x в 0..1: exp < 1 - выпуклая (быстро растёт в начале), exp > 1 - вогнутая. */
    public static double power(double x, double exp) {
        return Math.pow(clamp(x), exp);
    }

    /** Колокол: 1 в центре, спадает к краям; width - где остаётся около 0.6. */
    public static double bell(double x, double center, double width) {
        double d = (x - center) / Math.max(1e-6, width);
        return Math.exp(-0.5 * d * d);
    }

    /** Итог по соображениям (каждое 0..1) с поправкой на их число. */
    public static double score(double... considerations) {
        int n = considerations.length;
        if (n == 0) return 0;
        double mod = 1.0 - 1.0 / n;
        double s = 1;
        for (double c : considerations) {
            double v = clamp(c);
            s *= v + (1 - v) * mod * v;
        }
        return s;
    }
}
