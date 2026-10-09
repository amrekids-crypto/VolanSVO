package dev.volansvo.svo.bots;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Память ботов о «неизвестных» плагинных предметах (ExecutableItems и т.п.).
 *
 * Бот не знает, что делает предмет, поэтому пробует его в бою: ПКМ в сторону врага.
 * Если в следующие 3 секунды враг получил урон, а сам бот нет - проба удачная.
 * Пробы считаются отдельно для ближней, средней и дальней дистанции: огнемёт хорош в упор и
 * бесполезен на тридцати блоках. По числу удач и неудач бот оценивает, с какой вероятностью
 * предмет полезен, и списывает его только когда уверен в этом, а не после пары промахов.
 * Результаты общие для всех ботов и сохраняются в bot_knowledge.yml.
 */
public final class ItemLearning {

    /** Корзины дистанции: до 6 блоков, 6-20, дальше 20. */
    static final int NEAR = 0, MID = 1, FAR = 2;
    private static final String[] BUCKET = {"near", "mid", "far"};

    /** Проба удачная, если предмет снял врагу хотя бы столько (за вычетом удвоенного урона себе). */
    private static final double WIN = 1.5;
    /** Предмет бесполезен, если даже по оптимистичной оценке он срабатывает реже. */
    private static final double USELESS = 0.3;
    /** Предмет - оружие, если даже по осторожной оценке он срабатывает чаще. */
    private static final double USEFUL = 0.35;

    public static final class Stat {
        public final int[] uses = new int[3], wins = new int[3];
        public final double[] enemyDamage = new double[3], selfDamage = new double[3];

        int uses() { return uses[0] + uses[1] + uses[2]; }

        int wins() { return wins[0] + wins[1] + wins[2]; }

        double score() {
            int n = uses();
            if (n == 0) return 0;
            return (enemyDamage[0] + enemyDamage[1] + enemyDamage[2] - (selfDamage[0] + selfDamage[1] + selfDamage[2]) * 2.0) / n;
        }
    }

    private final File file;
    private final Map<String, Stat> stats = new HashMap<String, Stat>();
    private boolean dirty;

    public ItemLearning(File dataFolder) {
        this.file = new File(dataFolder, "bot_knowledge.yml");
        load();
    }

    static int bucket(double dist) {
        return dist < 6 ? NEAR : dist <= 20 ? MID : FAR;
    }

    /** Оценка доли удач: среднее и разброс при wins удачах из uses проб (до первых проб - «не знаю»). */
    static double[] estimate(int wins, int uses) {
        double a = wins + 1, b = uses - wins + 1;
        double mean = a / (a + b);
        double sd = Math.sqrt(a * b / ((a + b) * (a + b) * (a + b + 1)));
        return new double[]{mean, sd};
    }

    public Stat get(String key) {
        Stat s = stats.get(key);
        if (s == null) { s = new Stat(); stats.put(key, s); }
        return s;
    }

    /** Ещё изучаем: стоит пробовать в бою. */
    public boolean worthTrying(String key) {
        Stat s = stats.get(key);
        if (s == null) return true;
        if (isWeapon(key)) return false;
        double[] e = estimate(s.wins(), s.uses());
        return s.uses() < 12 && e[0] + e[1] > USELESS;
    }

    /** Изученный боевой предмет. */
    public boolean isWeapon(String key) {
        Stat s = stats.get(key);
        if (s == null || s.uses() < 2) return false;
        double[] e = estimate(s.wins(), s.uses());
        return e[0] - e[1] > USEFUL && s.score() >= 1.5;
    }

    /** Годится ли предмет на этой дистанции: нет, если там он уже уверенно не срабатывал. */
    public boolean worksAt(String key, double dist) {
        Stat s = stats.get(key);
        if (s == null) return true;
        int b = bucket(dist);
        if (s.uses[b] < 3) return true;
        double[] e = estimate(s.wins[b], s.uses[b]);
        return e[0] + e[1] > USELESS;
    }

    /** Ожидаемый урон за применение. */
    public double score(String key) {
        Stat s = stats.get(key);
        return s == null ? 0 : s.score();
    }

    /** Ценность для подбора: берём только то, чем бот уже умеет пользоваться (изученное оружие). */
    public double valueOf(String key) {
        if (!isWeapon(key)) return 0;
        double sc = stats.get(key).score();
        return sc <= 0.5 ? 0 : Math.min(70, 10 + sc * 6);
    }

    public void record(String key, double dist, double enemyDamage, double selfDamage) {
        Stat s = get(key);
        int b = bucket(dist);
        s.uses[b]++;
        if (enemyDamage - selfDamage * 2.0 >= WIN) s.wins[b]++;
        s.enemyDamage[b] += enemyDamage;
        s.selfDamage[b] += selfDamage;
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
            boolean buckets = false;
            for (int b = 0; b < 3; b++) {
                List<Double> v = e.getDoubleList(BUCKET[b]);
                if (v.size() < 4) continue;
                buckets = true;
                s.uses[b] = (int) Math.round(v.get(0));
                s.wins[b] = (int) Math.round(v.get(1));
                s.enemyDamage[b] = v.get(2);
                s.selfDamage[b] = v.get(3);
            }
            if (!buckets && e.getInt("uses") > 0) {
                // Запись без дистанций: кладём в среднюю, удачи восстанавливаем по среднему урону.
                int uses = e.getInt("uses");
                s.uses[MID] = uses;
                s.enemyDamage[MID] = e.getDouble("enemy-damage");
                s.selfDamage[MID] = e.getDouble("self-damage");
                double avg = (s.enemyDamage[MID] - s.selfDamage[MID] * 2.0) / uses;
                s.wins[MID] = avg >= WIN ? uses : avg <= 0 ? 0 : (int) Math.round(uses * avg / WIN / 2.0);
            }
            stats.put(e.getString("key", k), s);
        }
    }

    public void save() {
        if (!dirty) return;
        YamlConfiguration y = new YamlConfiguration();
        int i = 0;
        for (Map.Entry<String, Stat> e : stats.entrySet()) {
            String path = "items.i" + (i++);
            Stat s = e.getValue();
            y.set(path + ".key", e.getKey());
            for (int b = 0; b < 3; b++) {
                if (s.uses[b] == 0) continue;
                y.set(path + "." + BUCKET[b], java.util.Arrays.asList((double) s.uses[b], (double) s.wins[b],
                    Math.round(s.enemyDamage[b] * 10) / 10.0, Math.round(s.selfDamage[b] * 10) / 10.0));
            }
        }
        try { y.save(file); dirty = false; } catch (Exception ignored) {}
    }
}
