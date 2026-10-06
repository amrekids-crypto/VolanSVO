package dev.volansvo.svo.maps;

import dev.volansvo.svo.VolanSVO;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Хранит координаты сундуков лута каждой карты в отдельном текстовом файле
 * (plugins/VolanSVO/chests/<mapId>.txt), который админ редактирует вручную -
 * без пересборки плагина и без перезапуска сервера. Формат: "X Y Z" по одному
 * сундуку на строку, пустые строки и строки с '#' игнорируются.
 */
public final class ChestFileStore {
    private ChestFileStore() {}

    private static File dir(VolanSVO plugin) {
        File d = new File(plugin.getDataFolder(), "chests");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static File fileFor(VolanSVO plugin, String mapId) {
        return new File(dir(plugin), mapId + ".txt");
    }

    /** Создаёт файл карты (заполнив текущими координатами), если его ещё нет. */
    public static void ensureExists(VolanSVO plugin, String mapId, List<int[]> seed) {
        if (fileFor(plugin, mapId).exists()) return;
        write(plugin, mapId, seed);
    }

    /** Полностью перезаписывает файл карты заданными координатами. */
    public static void write(VolanSVO plugin, String mapId, List<int[]> coords) {
        File f = fileFor(plugin, mapId);
        try {
            PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8));
            try {
                pw.println("# Координаты сундуков лута карты '" + mapId + "'.");
                pw.println("# Формат: X Y Z (по одному сундуку на строку, через пробел).");
                pw.println("# Строки с # и пустые строки игнорируются.");
                pw.println("# Правь и сохраняй прямо здесь - подхватится на следующем старте игры,");
                pw.println("# перезапуск сервера не нужен.");
                for (int[] c : coords) pw.println(c[0] + " " + c[1] + " " + c[2]);
            } finally {
                pw.close();
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось создать chests/" + mapId + ".txt: " + e.getMessage());
        }
    }

    /** Читает координаты из файла карты. Если файла нет - создаёт его из fallback и возвращает fallback. */
    public static int[][] load(VolanSVO plugin, String mapId, int[][] fallback) {
        File f = fileFor(plugin, mapId);
        if (!f.exists()) {
            List<int[]> seed = new ArrayList<int[]>();
            for (int[] c : fallback) seed.add(c);
            write(plugin, mapId, seed);
            return fallback;
        }

        List<int[]> result = new ArrayList<int[]>();
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
            try {
                String line;
                int lineNo = 0;
                while ((line = br.readLine()) != null) {
                    lineNo++;
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("#")) continue;
                    String[] parts = t.split("\\s+");
                    if (parts.length < 3) {
                        plugin.getLogger().warning("chests/" + mapId + ".txt:" + lineNo
                            + " - пропущена строка (нужно 'X Y Z'): " + line);
                        continue;
                    }
                    try {
                        int x = Integer.parseInt(parts[0]);
                        int y = Integer.parseInt(parts[1]);
                        int z = Integer.parseInt(parts[2]);
                        result.add(new int[]{x, y, z});
                    } catch (NumberFormatException nfe) {
                        plugin.getLogger().warning("chests/" + mapId + ".txt:" + lineNo + " - не число: " + line);
                    }
                }
            } finally {
                br.close();
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось прочитать chests/" + mapId + ".txt: " + e.getMessage());
            return fallback;
        }
        return result.toArray(new int[0][]);
    }
}
