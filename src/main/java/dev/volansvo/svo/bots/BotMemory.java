package dev.volansvo.svo.bots;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Что боты помнят между матчами (bot_memory.yml, ключ - ник бота): счёт с каждым
 * игроком, где высаживались и чем это кончилось, какие реплики уже говорили.
 * На этом держатся обида на конкретного игрока и привычки.
 */
public final class BotMemory {

    /** Память одного бота. */
    public static final class Record {
        /** Ник игрока -> {сколько раз я его убил, сколько раз он меня}. */
        final Map<String, int[]> versus = new HashMap<String, int[]>();
        final List<Integer> said = new ArrayList<Integer>();
        int matches;
        double landX, landZ;
        /** Оценка любимого места высадки: меньше нуля - разонравилось. */
        int landScore;
        boolean hasLanding;

        private int[] vs(String player) {
            int[] v = versus.get(player);
            if (v == null) { v = new int[2]; versus.put(player, v); }
            return v;
        }

        void killed(String victim) { vs(victim)[0]++; }
        void killedBy(String killer) { vs(killer)[1]++; }

        /** Насколько бот зол на игрока: его победы надо мной минус половина моих над ним. */
        public int grudge(String player) {
            int[] v = versus.get(player);
            return v == null ? 0 : Math.max(0, v[1] - v[0] / 2);
        }

        /** Сколько раз мы уже встречались насмерть. */
        public int met(String player) {
            int[] v = versus.get(player);
            return v == null ? 0 : v[0] + v[1];
        }

        /** Говорил ли недавно эту реплику; если нет - запоминает её. */
        boolean repeats(String line) {
            Integer h = line.hashCode();
            if (said.contains(h)) return true;
            said.add(h);
            while (said.size() > 60) said.remove(0);
            return false;
        }

        /** Запомнить место высадки (вызывается при приземлении). */
        void landed(double x, double z) {
            if (hasLanding && Math.hypot(x - landX, z - landZ) < 30) return;
            landX = x; landZ = z; landScore = 0; hasLanding = true;
        }

        /** Итог высадки: хорошо, если после неё бот долго прожил. */
        void landingResult(boolean good) {
            if (!hasLanding) return;
            landScore += good ? 1 : -1;
            if (landScore <= -2) hasLanding = false;
        }
    }

    private final File file;
    private final Map<String, Record> records = new HashMap<String, Record>();
    private boolean dirty;

    public BotMemory(File dataFolder) {
        this.file = new File(dataFolder, "bot_memory.yml");
        load();
    }

    public Record of(String bot) {
        Record r = records.get(bot);
        if (r == null) { r = new Record(); records.put(bot, r); }
        dirty = true;
        return r;
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection bots = y.getConfigurationSection("bots");
        if (bots == null) return;
        for (String name : bots.getKeys(false)) {
            ConfigurationSection s = bots.getConfigurationSection(name);
            if (s == null) continue;
            Record r = new Record();
            r.matches = s.getInt("matches");
            r.said.addAll(s.getIntegerList("said"));
            r.hasLanding = s.contains("land.x");
            r.landX = s.getDouble("land.x");
            r.landZ = s.getDouble("land.z");
            r.landScore = s.getInt("land.score");
            ConfigurationSection vs = s.getConfigurationSection("vs");
            if (vs != null) {
                for (String player : vs.getKeys(false)) {
                    r.versus.put(player, new int[]{vs.getInt(player + ".k"), vs.getInt(player + ".d")});
                }
            }
            records.put(name, r);
        }
    }

    public void save() {
        if (!dirty) return;
        dirty = false;
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, Record> e : records.entrySet()) {
            String base = "bots." + e.getKey();
            Record r = e.getValue();
            y.set(base + ".matches", r.matches);
            y.set(base + ".said", r.said);
            if (r.hasLanding) {
                y.set(base + ".land.x", r.landX);
                y.set(base + ".land.z", r.landZ);
                y.set(base + ".land.score", r.landScore);
            }
            for (Map.Entry<String, int[]> v : r.versus.entrySet()) {
                // Точка в нике сломала бы путь в YAML: такие не пишем.
                if (v.getKey().indexOf('.') >= 0) continue;
                y.set(base + ".vs." + v.getKey() + ".k", v.getValue()[0]);
                y.set(base + ".vs." + v.getKey() + ".d", v.getValue()[1]);
            }
        }
        try { y.save(file); } catch (Exception ignored) {}
    }
}
