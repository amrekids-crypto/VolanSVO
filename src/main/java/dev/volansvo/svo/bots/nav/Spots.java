package dev.volansvo.svo.bots.nav;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * Привычные места игроков на карте: где они подолгу стоят, где лутают и где дерутся. Сетка
 * клеток 8x8 блоков, файл - на карту, старое со временем выветривается. Боты по этим местам
 * выбирают засады и куда идти искать бой.
 */
public final class Spots {

    public static final int STOP = 0, LOOT = 1, FIGHT = 2;
    private static final int CELL = 8;
    private static final short MAX = 20000;

    private final Long2ObjectOpenHashMap<short[]> cells = new Long2ObjectOpenHashMap<short[]>();
    private boolean dirty;

    private static long key(double x, double z) {
        return ((long) (int) Math.floor(x / CELL) << 32) ^ ((int) Math.floor(z / CELL) & 0xffffffffL);
    }

    public void record(int kind, double x, double z) {
        long k = key(x, z);
        short[] c = cells.get(k);
        if (c == null) { c = new short[3]; cells.put(k, c); }
        if (c[kind] < MAX) { c[kind]++; dirty = true; }
    }

    public int count(int kind, double x, double z) {
        short[] c = cells.get(key(x, z));
        return c == null ? 0 : c[kind];
    }

    /** Самое обжитое место этого вида в радиусе r: {x, z, сколько раз}. null - ничего заметного. */
    public double[] busiest(int kind, double x, double z, double r, int min) {
        double[] best = null;
        double bestScore = 0;
        for (Long2ObjectMap.Entry<short[]> e : cells.long2ObjectEntrySet()) {
            int n = e.getValue()[kind];
            if (n < min) continue;
            long k = e.getLongKey();
            double cx = (int) (k >> 32) * CELL + CELL / 2.0, cz = (int) k * CELL + CELL / 2.0;
            double d = Math.hypot(cx - x, cz - z);
            if (d > r) continue;
            double score = n / (1 + d * 0.02);
            if (score > bestScore) { bestScore = score; best = new double[]{cx, cz, n}; }
        }
        return best;
    }

    public int size() { return cells.size(); }

    public static Spots load(File file) {
        Spots s = new Spots();
        if (file == null || !file.exists()) return s;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                long k = in.readLong();
                short[] c = new short[3];
                int sum = 0;
                for (int j = 0; j < 3; j++) { c[j] = (short) (in.readShort() * 0.97); sum += c[j]; }
                if (sum > 0) s.cells.put(k, c);
            }
        } catch (Exception ignored) {
        }
        return s;
    }

    public void save(File file) {
        if (!dirty || file == null) return;
        dirty = false;
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            out.writeInt(cells.size());
            for (Long2ObjectMap.Entry<short[]> e : cells.long2ObjectEntrySet()) {
                out.writeLong(e.getLongKey());
                for (int j = 0; j < 3; j++) out.writeShort(e.getValue()[j]);
            }
        } catch (Exception ignored) {
        }
    }
}
