package dev.volansvo.svo.bots.human;

/**
 * Состояние бота в матче: три числа, которые меняют события и которые сами затухают.
 * От них зависят точность, скорость реакции и готовность лезть в драку.
 */
public final class Affect {

    /** 0..1: растёт от урона и близкой опасности. Руки трясутся, поле зрения сужается. */
    public double arousal;
    /** -1..1: растёт от убийств, падает от смертей. */
    public double confidence;
    /** 0..1: растёт, пока ничего не происходит. Бот невнимателен и ищет, чем заняться. */
    public double boredom;

    /** Раз в секунду. calm - рядом нет врагов и бот не ранен. */
    public void second(boolean calm) {
        arousal *= 0.90;
        confidence *= 0.992;
        if (calm) boredom = Math.min(1.0, boredom + 0.012);
        else boredom *= 0.7;
    }

    public void hurt(double damage, boolean surprised) {
        arousal = Math.min(1.0, arousal + 0.12 + damage * 0.03 + (surprised ? 0.25 : 0));
        boredom = 0;
    }

    public void threat() {
        arousal = Math.min(1.0, arousal + 0.08);
        boredom *= 0.5;
    }

    public void kill() {
        confidence = Math.min(1.0, confidence + 0.35);
        boredom = 0;
    }

    public void death() {
        confidence = Math.max(-1.0, confidence - 0.45);
        arousal = 0.3;
        boredom = 0;
    }

    /** Множитель разброса прицела. */
    public double aim() { return 1.0 + 0.6 * arousal; }

    /** Множитель времени реакции: на взводе чуть быстрее, заскучав - медленнее. */
    public double react() { return 1.0 - 0.15 * arousal + 0.35 * boredom; }

    /** Множитель агрессивности. */
    public double aggression() { return 1.0 + 0.25 * confidence + 0.15 * boredom; }

    /** Множитель осторожности по числу оставшихся жизней: последняя - бережём. */
    public static double livesCaution(int lives) {
        if (lives <= 1) return 1.25;
        if (lives == 2) return 1.05;
        return 0.92;
    }
}
