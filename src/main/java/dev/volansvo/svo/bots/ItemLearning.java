package dev.volansvo.svo.bots;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Память ботов о «неизвестных» плагинных предметах (ExecutableItems и т.п.).
 *
 * Бот не знает, что делает предмет, поэтому пробует его в бою: ПКМ в сторону врага.
 * Если в следующие 3 секунды враги получили урон, а сам бот нет - предмет полезный.
 * Результаты общие для всех ботов и сохраняются в bot_knowledge.yml, так что боты
 * умнеют от игры к игре.
 */
public final class ItemLearning {

    public static final class Stat {
        public int uses;
        public double enemyDamage;
        public double selfDamage;

        double score() {
            if (uses == 0) return 0;
            return (enemyDamage - selfDamage * 2.0) / uses;
        }
    }

    private static final int TRIES_BEFORE_JUDGING = 3;

    private final File file;
    private final Map<String, Stat> stats = new HashMap<String, Stat>();
    private boolean dirty;

    public ItemLearning(File dataFolder) {
        this.file = new File(dataFolder, "bot_knowledge.yml");
        load();
    }

    public Stat get(String key) {
        Stat s = stats.get(key);
        if (s == null) { s = new Stat(); stats.put(key, s); }
        return s;
    }

    /** Ещё изучаем: стоит пробовать в бою. */
    public boolean worthTrying(String key) {
        Stat s = stats.get(key);
        return s == null || s.uses < TRIES_BEFORE_JUDGING;
    }

    /** Изученный боевой предмет. */
    public boolean isWeapon(String key) {
        Stat s = stats.get(key);
        return s != null && s.uses >= TRIES_BEFORE_JUDGING && s.score() >= 1.5;
    }

    /** Ожидаемый урон за применение. */
    public double score(String key) {
        Stat s = stats.get(key);
        return s == null ? 0 : s.score();
    }

    /** Ценность для подбора: берём только то, чем бот уже умеет пользоваться (изученное оружие). */
    public double valueOf(String key) {
        Stat s = stats.get(key);
        if (s == null || s.uses < TRIES_BEFORE_JUDGING) return 0;
        double sc = s.score();
        return sc <= 0.5 ? 0 : Math.min(70, 10 + sc * 6);
    }

    public void record(String key, double enemyDamage, double selfDamage) {
        Stat s = get(key);
        s.uses++;
        s.enemyDamage += enemyDamage;
        s.selfDamage += selfDamage;
        dirty = true;
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = y.getConfigurationSection("items");
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            ConfigurationSection e = sec.getConfigurationSection(k);
            if (e == null) continue;
            Stat s = new Stat();
            s.uses = e.getInt("uses");
            s.enemyDamage = e.getDouble("enemy-damage");
            s.selfDamage = e.getDouble("self-damage");
            stats.put(e.getString("key", k), s);
        }
    }

    public void save() {
        if (!dirty) return;
        YamlConfiguration y = new YamlConfiguration();
        int i = 0;
        for (Map.Entry<String, Stat> e : stats.entrySet()) {
            String path = "items.i" + (i++);
            y.set(path + ".key", e.getKey());
            y.set(path + ".uses", e.getValue().uses);
            y.set(path + ".enemy-damage", Math.round(e.getValue().enemyDamage * 10) / 10.0);
            y.set(path + ".self-damage", Math.round(e.getValue().selfDamage * 10) / 10.0);
        }
        try { y.save(file); dirty = false; } catch (Exception ignored) {}
    }
}
