package dev.volansvo.svo.bots.nav;

import it.unimi.dsi.fastutil.longs.Long2ShortMap;
import it.unimi.dsi.fastutil.longs.Long2ShortOpenHashMap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * Тропы: по каким клеткам карты ходят живые игроки. Поиск пути делает натоптанные клетки
 * дешевле, и боты ходят там же, где люди: по улицам, через привычные проходы.
 * Файл - на карту, со временем старые следы выветриваются.
 */
public final class Trails {

    private static final short MAX = 2000;

    private final Long2ShortOpenHashMap steps = new Long2ShortOpenHashMap();
    private boolean dirty;

    /** Игрок прошёл по клетке. */
    public void record(int x, int y, int z) {
        long k = Pos.pack(x, y, z);
        short n = steps.get(k);
        if (n < MAX) { steps.put(k, (short) (n + 1)); dirty = true; }
    }

    /** Скидка к цене шага в клетку: натоптано - дешевле. */
    public double bonus(int x, int y, int z) {
        if (steps.isEmpty()) return 0;
        short n = steps.get(Pos.pack(x, y, z));
        if (n >= 12) return 0.4;
        if (n >= 3) return 0.2;
        return 0;
    }

    public int size() { return steps.size(); }

    public static Trails load(File file) {
        Trails t = new Trails();
        if (file == null || !file.exists()) return t;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                long k = in.readLong();
                // Каждая загрузка немного стирает след: заброшенные тропы зарастают.
                short v = (short) (in.readShort() * 0.97);
                if (v > 0) t.steps.put(k, v);
            }
        } catch (Exception ignored) {
        }
        return t;
    }

    public void save(File file) {
        if (!dirty || file == null) return;
        dirty = false;
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            out.writeInt(steps.size());
            for (Long2ShortMap.Entry e : steps.long2ShortEntrySet()) {
                out.writeLong(e.getLongKey());
                out.writeShort(e.getShortValue());
            }
        } catch (Exception ignored) {
        }
    }
}
