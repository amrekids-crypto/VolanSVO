package dev.volansvo.svo.bots;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.Iterator;

/**
 * Где, по мнению бота (и его отряда), бывают враги: грубая сетка клеток 8x8 блоков, в каждой
 * «жар» - сколько недавно там слышали выстрелы, видели врага, видели метку на карте или
 * погибли свои. Жар сам остывает (за минуту вдвое).
 *
 * На это опирается всё, что игрок делает «по ощущению»: осторожный бот обходит горячие места,
 * охотник идёт к ним, на стоянке бот смотрит туда, откуда скорее всего придут, а в погоне
 * держит прицел на углу, где враг вероятнее всего.
 */
final class ThreatMap {

    private static final int CELL = 8;
    private static final double HALF_LIFE = 20 * 60;

    private static final class Cell {
        double heat;
        int tick;
    }

    private final Long2ObjectOpenHashMap<Cell> cells = new Long2ObjectOpenHashMap<Cell>();

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xffffffffL);
    }

    private static double decay(double heat, int dt) {
        return dt <= 0 ? heat : heat * Math.pow(0.5, dt / HALF_LIFE);
    }

    /** Отметить: там (x,z) враг, вес w (выстрел ~1, увидели ~2, гибель своего ~4). */
    void add(double x, double z, double w, int now) {
        int cx = (int) Math.floor(x / CELL), cz = (int) Math.floor(z / CELL);
        Cell c = cells.get(key(cx, cz));
        if (c == null) { c = new Cell(); c.tick = now; cells.put(key(cx, cz), c); }
        c.heat = Math.min(12, decay(c.heat, now - c.tick) + w);
        c.tick = now;
        if (cells.size() > 3000) prune(now);
    }

    /** Жар в точке (с соседними клетками, слабее). */
    double heat(double x, double z, int now) {
        int cx = (int) Math.floor(x / CELL), cz = (int) Math.floor(z / CELL);
        double h = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Cell c = cells.get(key(cx + dx, cz + dz));
                if (c == null) continue;
                h += decay(c.heat, now - c.tick) * (dx == 0 && dz == 0 ? 1.0 : 0.35);
            }
        }
        return h;
    }

    /**
     * Самое горячее место в радиусе r от (x,z): {x, z, жар}, ближнее при равном жаре важнее.
     * null - ничего жарче minHeat.
     */
    double[] hottest(double x, double z, double r, double minHeat, int now) {
        double[] best = null;
        double bestScore = 0;
        for (Long2ObjectMap.Entry<Cell> e : cells.long2ObjectEntrySet()) {
            Cell c = e.getValue();
            double h = decay(c.heat, now - c.tick);
            if (h < minHeat) continue;
            long k = e.getLongKey();
            double cx = (int) (k >> 32) * CELL + CELL / 2.0, cz = (int) k * CELL + CELL / 2.0;
            double d = Math.hypot(cx - x, cz - z);
            if (d > r) continue;
            double score = h / (1 + d * 0.012);
            if (score > bestScore) { bestScore = score; best = new double[]{cx, cz, h}; }
        }
        return best;
    }

    /** Суммарный жар по направлению (единичный вектор dx,dz) в секторе ±60° до дальности r. */
    double heatToward(double x, double z, double dx, double dz, double r, int now) {
        double sum = 0;
        for (Long2ObjectMap.Entry<Cell> e : cells.long2ObjectEntrySet()) {
            Cell c = e.getValue();
            long k = e.getLongKey();
            double cx = (int) (k >> 32) * CELL + CELL / 2.0 - x, cz = (int) k * CELL + CELL / 2.0 - z;
            double d = Math.hypot(cx, cz);
            if (d < 1 || d > r) continue;
            if ((cx * dx + cz * dz) / d < 0.5) continue;
            sum += decay(c.heat, now - c.tick) / (1 + d * 0.02);
        }
        return sum;
    }

    private void prune(int now) {
        Iterator<Long2ObjectMap.Entry<Cell>> it = cells.long2ObjectEntrySet().iterator();
        while (it.hasNext()) {
            Cell c = it.next().getValue();
            if (decay(c.heat, now - c.tick) < 0.05) it.remove();
        }
    }

    void clear() { cells.clear(); }
}
