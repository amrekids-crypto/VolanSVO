package dev.volansvo.svo.bots.mind;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Поиск потерянного врага. Вместо одной точки «где видел в последний раз» бот держит
 * десяток-другой догадок о том, куда враг мог деться. Догадки расползаются со скоростью
 * пешехода, а те, что бот увидел пустыми, отбрасываются. Бот идёт к самой весомой и держит
 * на ней прицел: отсюда «заглянуть за угол» и «проверить дом».
 */
public final class Search {

    /** Что поиску нужно знать о мире. */
    public interface Probe {
        /** Можно ли стоять в клетке (ноги в y). */
        boolean standable(int x, int y, int z);

        /** Видна ли точка из глаз. */
        boolean sees(double ex, double ey, double ez, double x, double y, double z);
    }

    public static final class Guess {
        public double x, y, z;
        public double weight;
        double hx, hz;
    }

    private final List<Guess> guesses = new ArrayList<Guess>();
    private Guess pick;
    private int startTick, lastSpread;

    public boolean active() { return !guesses.isEmpty(); }

    public int size() { return guesses.size(); }

    public int startTick() { return startTick; }

    public List<Guess> guesses() { return guesses; }

    public void clear() {
        guesses.clear();
        pick = null;
    }

    /**
     * Враг пропал в точке (x,y,z), двигаясь со скоростью (vx,vz) блоков за тик. Раскидываем n
     * догадок в радиусе radius: больше в сторону его движения, тяжелее - у стен и в проходах.
     */
    public void seed(double x, double y, double z, double vx, double vz, double radius, int n, int now, Random rnd, Probe probe) {
        clear();
        startTick = now;
        lastSpread = now;
        double speed = Math.hypot(vx, vz);
        double base = speed > 0.03 ? Math.atan2(vz, vx) : 0;
        // Место известно примерно и может оказаться в стене или в воздухе: берём ближайший пол.
        int bx = (int) Math.floor(x), by = (int) Math.floor(y + 0.01), bz = (int) Math.floor(z);
        double sy = by;
        for (int dy : new int[]{0, 1, -1, 2, -2, 3, -3}) {
            if (probe.standable(bx, by + dy, bz)) { sy = by + dy; break; }
        }
        for (int i = 0; i < n; i++) {
            double a = speed > 0.03 && rnd.nextDouble() < 0.6
                ? base + (rnd.nextDouble() - 0.5) * Math.toRadians(80)
                : rnd.nextDouble() * Math.PI * 2;
            Guess g = new Guess();
            g.x = x; g.y = sy; g.z = z;
            g.hx = Math.cos(a); g.hz = Math.sin(a);
            g.weight = 1.0;
            walk(g, Math.max(1.5, radius) * (0.35 + 0.65 * rnd.nextDouble()), probe);
            g.weight += shelter(g, probe);
            guesses.add(g);
        }
    }

    /** Догадки расползаются: за dt тиков враг мог пройти столько-то блоков. */
    public void spread(int now, Random rnd, Probe probe) {
        int dt = now - lastSpread;
        if (dt < 10) return;
        lastSpread = now;
        for (Guess g : guesses) {
            // Половина догадок - «он затаился», остальные идут дальше.
            if (rnd.nextDouble() < 0.5) continue;
            if (rnd.nextDouble() < 0.25) {
                double a = rnd.nextDouble() * Math.PI * 2;
                g.hx = Math.cos(a); g.hz = Math.sin(a);
            }
            if (walk(g, 4.3 * dt / 20.0 * rnd.nextDouble(), probe) < 0.3) {
                g.hx = -g.hx; g.hz = -g.hz;
            }
        }
    }

    /**
     * Бот посмотрел: догадки в конусе взгляда (half - половина угла в радианах, range - дальность),
     * которые видно, оказались пустыми. Возвращает, сколько отброшено.
     */
    public int observe(double ex, double ey, double ez, double lx, double ly, double lz, double half, double range, Probe probe) {
        int removed = 0;
        double cos = Math.cos(half);
        for (int i = guesses.size() - 1; i >= 0; i--) {
            Guess g = guesses.get(i);
            double dx = g.x - ex, dy = g.y + 1.0 - ey, dz = g.z - ez;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d > range) continue;
            if (d > 1.5 && (dx * lx + dy * ly + dz * lz) / d < cos) continue;
            if (!probe.sees(ex, ey, ez, g.x, g.y + 1.0, g.z)) continue;
            if (guesses.remove(i) == pick) pick = null;
            removed++;
        }
        return removed;
    }

    /** Куда идти сейчас: самая весомая и близкая догадка. Выбранной держимся, пока её не проверим. */
    public Guess best(double mx, double my, double mz) {
        if (pick != null) return pick;
        double bestScore = -1;
        for (Guess g : guesses) {
            int near = 0;
            for (Guess o : guesses) {
                double dx = o.x - g.x, dz = o.z - g.z;
                if (dx * dx + dz * dz < 16) near++;
            }
            double d = Math.sqrt((g.x - mx) * (g.x - mx) + (g.z - mz) * (g.z - mz)) + Math.abs(g.y - my) * 2;
            double s = (g.weight + near * 0.35) / (1 + d * 0.06);
            if (s > bestScore) { bestScore = s; pick = g; }
        }
        return pick;
    }

    /** Догадка проверена и пуста. */
    public void drop(Guess g) {
        guesses.remove(g);
        if (pick == g) pick = null;
    }

    /** До выбранной догадки не дойти: берём другую. */
    public void dropPick() {
        if (pick != null) drop(pick);
    }

    /** Идём по клеткам вдоль курса догадки, пока можно стоять. Возвращает пройденное. */
    private static double walk(Guess g, double dist, Probe probe) {
        double done = 0;
        int y = (int) Math.floor(g.y + 0.01);
        while (done + 1.0 <= dist) {
            int nx = (int) Math.floor(g.x + g.hx), nz = (int) Math.floor(g.z + g.hz);
            int ny;
            if (probe.standable(nx, y, nz)) ny = y;
            else if (probe.standable(nx, y + 1, nz)) ny = y + 1;
            else if (probe.standable(nx, y - 1, nz)) ny = y - 1;
            else break;
            g.x += g.hx; g.z += g.hz;
            y = ny;
            g.y = ny;
            done += 1.0;
        }
        return done;
    }

    /** Прибавка к весу: рядом стена, за которой удобно стоять, или узкий проход. */
    private static double shelter(Guess g, Probe probe) {
        int x = (int) Math.floor(g.x), y = (int) Math.floor(g.y + 0.01), z = (int) Math.floor(g.z);
        int walls = 0;
        if (!probe.standable(x + 1, y, z) && !probe.standable(x + 1, y + 1, z) && !probe.standable(x + 1, y - 1, z)) walls++;
        if (!probe.standable(x - 1, y, z) && !probe.standable(x - 1, y + 1, z) && !probe.standable(x - 1, y - 1, z)) walls++;
        if (!probe.standable(x, y, z + 1) && !probe.standable(x, y + 1, z + 1) && !probe.standable(x, y - 1, z + 1)) walls++;
        if (!probe.standable(x, y, z - 1) && !probe.standable(x, y + 1, z - 1) && !probe.standable(x, y - 1, z - 1)) walls++;
        return walls == 0 ? 0 : walls >= 2 ? 0.6 : 0.3;
    }
}
