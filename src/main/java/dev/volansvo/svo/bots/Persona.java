package dev.volansvo.svo.bots;

import java.util.Locale;
import java.util.Random;

/**
 * Кто этот бот: класс поведения (как он вообще играет), характер (числа, с которыми он
 * принимает решения) и план на этот матч (как он собирается победить).
 *
 * Класс и характер зависят только от ника: один и тот же бот из матча в матч узнаваем
 * (Vanya_2011 всегда лезет в драку, Kostya_nub всегда сидит в кустах). План каждый матч
 * свой, но выбирается по классу и характеру.
 */
final class Persona {

    /** Класс поведения: что бот любит и чем пользуется охотнее всего. */
    enum Archetype {
        ASSAULT("Штурмовик", "бегает и стреляет, лезет в бой"),
        TACTICIAN("Тактик", "мины, турели, гранаты, крюк, засады"),
        MARKSMAN("Стрелок", "держит дистанцию, высоту и углы"),
        SURVIVOR("Выживальщик", "лутает, прячется, доживает до финала"),
        MECHANIZED("Техник", "техника, дроны, вертолёт"),
        HUNTER("Охотник", "ищет людей по карте и звукам"),
        SUPPORT("Поддержка", "держится своих, делится и прикрывает");

        final String title, hint;

        Archetype(String title, String hint) { this.title = title; this.hint = hint; }
    }

    /** План на матч: как бот собирается победить. */
    enum Plan {
        HOT_DROP("Горячая высадка", "сесть в гуще и драться с первых минут"),
        LOOT_PUSH("Лут, потом вперёд", "спокойно одеться и идти за убийствами"),
        HOLD_CENTER("Занять центр", "рано встать в центре зоны и держать его"),
        THIRD_PARTY("Добивать", "идти на звуки боя и добивать ослабленных"),
        EDGE("Край зоны", "держаться края, пропуская бои вперёд"),
        HUNT("Охота", "искать людей по карте и звукам"),
        AMBUSH("Засада", "занять проход у лута, заминировать и ждать");

        final String title, hint;

        Plan(String title, String hint) { this.title = title; this.hint = hint; }
    }

    final Archetype type;
    final Plan plan;

    // ---- характер, 0..1
    /** Охота лезть в драку. */
    final double aggression;
    /** Бережёт себя: раньше отходит и лечится. */
    final double caution;
    /** Решительность: выбрав занятие, держится его, а не мечется. */
    final double decisiveness;
    /** Терпение: сколько готов сидеть на позиции. */
    final double patience;
    /** Жадность до лута. */
    final double greed;
    /** Любопытство: бегает на звуки, смотрит по сторонам. */
    final double curiosity;
    /** Командность: держится своих, помогает. */
    final double teamSpirit;

    // ---- что из этого следует
    /** Какой уровень снаряжения (см. Bot.gearScore) хватает, чтобы перестать лутать. */
    final double lootSatiation;
    /** Как охотно ставит мины, турели и кидает гранаты (0..1). */
    final double gadgets;
    /** Как охотно садится в технику и ставит свою (0..1). */
    final double vehicles;
    /** Как часто смотрит на карту (0..1). */
    final double mapUse;
    /** Любимая дистанция боя, блоков. */
    final double range;

    private Persona(Archetype type, Plan plan, double aggression, double caution, double decisiveness, double patience,
                    double greed, double curiosity, double teamSpirit) {
        this.type = type;
        this.plan = plan;
        this.aggression = aggression;
        this.caution = caution;
        this.decisiveness = decisiveness;
        this.patience = patience;
        this.greed = greed;
        this.curiosity = curiosity;
        this.teamSpirit = teamSpirit;
        double sat;
        double g = 0.15, v = 0.25, m = 0.35, r = 14;
        switch (type) {
            case ASSAULT: sat = 30; r = 9; break;
            case TACTICIAN: sat = 36; g = 0.95; m = 0.5; r = 14; break;
            case MARKSMAN: sat = 34; r = 30; m = 0.45; break;
            case SURVIVOR: sat = 44; g = 0.35; r = 18; break;
            case MECHANIZED: sat = 32; v = 0.95; r = 16; break;
            case HUNTER: sat = 30; m = 0.95; r = 14; break;
            default: sat = 36; r = 14; break; // SUPPORT
        }
        switch (plan) {
            case HOT_DROP: sat -= 8; break;
            case LOOT_PUSH: case EDGE: sat += 5; break;
            case AMBUSH: g = Math.max(g, 0.6); break;
            case HUNT: m = Math.max(m, 0.8); break;
            default:
        }
        this.lootSatiation = sat + greed * 8 - 4;
        this.gadgets = g;
        this.vehicles = v;
        this.mapUse = m;
        this.range = r;
    }

    /**
     * Личность бота. Класс и характер - по нику (стабильно между матчами), план - каждый
     * матч заново. Слова в нике («sniper», «tank», «noob»...) подсказывают класс.
     */
    static Persona of(String name, Random matchRnd) {
        Random r = new Random(name.hashCode() * 131L + 17);
        Archetype type = pickType(name, r);
        double aggression = clamp(0.5 + r.nextGaussian() * 0.22 + typeAggro(type));
        double caution = clamp(0.5 + r.nextGaussian() * 0.2 - typeAggro(type) * 0.6);
        double decisiveness = clamp(0.55 + r.nextGaussian() * 0.2);
        double patience = clamp(0.5 + r.nextGaussian() * 0.22
            + (type == Archetype.MARKSMAN || type == Archetype.TACTICIAN || type == Archetype.SURVIVOR ? 0.2 : 0)
            - (type == Archetype.ASSAULT ? 0.2 : 0));
        double greed = clamp(0.5 + r.nextGaussian() * 0.2 + (type == Archetype.SURVIVOR ? 0.2 : 0));
        double curiosity = clamp(0.5 + r.nextGaussian() * 0.2 + (type == Archetype.HUNTER ? 0.2 : 0));
        double teamSpirit = clamp(0.5 + r.nextGaussian() * 0.2 + (type == Archetype.SUPPORT ? 0.35 : 0));
        Plan plan = pickPlan(type, aggression, caution, matchRnd);
        return new Persona(type, plan, aggression, caution, decisiveness, patience, greed, curiosity, teamSpirit);
    }

    private static Archetype pickType(String name, Random r) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("sniper") || n.contains("snaip")) return Archetype.MARKSMAN;
        if (n.contains("tank") || n.contains("tankist")) return Archetype.MECHANIZED;
        if (n.contains("kill") || n.contains("shturm") || n.contains("strike") || n.contains("pvp")) return Archetype.ASSAULT;
        if (n.contains("desant") || n.contains("spetsnaz")) return Archetype.TACTICIAN;
        if (n.contains("nub") || n.contains("noob")) return Archetype.SURVIVOR;
        // Остальные: чаще всего штурмовики, реже экзотика.
        double[] w = {0.27, 0.15, 0.13, 0.13, 0.10, 0.12, 0.10};
        double x = r.nextDouble(), acc = 0;
        Archetype[] all = Archetype.values();
        for (int i = 0; i < all.length; i++) { acc += w[i]; if (x < acc) return all[i]; }
        return Archetype.ASSAULT;
    }

    private static double typeAggro(Archetype t) {
        switch (t) {
            case ASSAULT: return 0.22;
            case HUNTER: return 0.15;
            case MECHANIZED: return 0.08;
            case SURVIVOR: return -0.22;
            case MARKSMAN: return -0.05;
            default: return 0;
        }
    }

    private static Plan pickPlan(Archetype t, double aggression, double caution, Random rnd) {
        // Веса планов по классу: {HOT_DROP, LOOT_PUSH, HOLD_CENTER, THIRD_PARTY, EDGE, HUNT, AMBUSH}
        double[] w;
        switch (t) {
            case ASSAULT:    w = new double[]{4, 3, 1.5, 2, 0.3, 2, 0.3}; break;
            case TACTICIAN:  w = new double[]{0.5, 2, 2.5, 1.5, 1, 1, 4}; break;
            case MARKSMAN:   w = new double[]{0.5, 2, 3, 2, 1.5, 1, 2}; break;
            case SURVIVOR:   w = new double[]{0.2, 2.5, 1, 2, 4, 0.3, 1.5}; break;
            case MECHANIZED: w = new double[]{1.5, 3, 1.5, 2, 0.5, 3, 0.5}; break;
            case HUNTER:     w = new double[]{2, 2, 1, 2.5, 0.3, 4, 0.5}; break;
            default:         w = new double[]{1, 3, 2.5, 1.5, 1, 1, 1}; break; // SUPPORT
        }
        // Характер двигает выбор: смелые - к бою, осторожные - к краю и засадам.
        w[0] *= 0.5 + aggression; w[5] *= 0.6 + aggression * 0.8; w[3] *= 0.7 + aggression * 0.6;
        w[4] *= 0.5 + caution; w[6] *= 0.6 + caution * 0.8;
        double sum = 0;
        for (double v : w) sum += v;
        double x = rnd.nextDouble() * sum, acc = 0;
        Plan[] all = Plan.values();
        for (int i = 0; i < all.length; i++) { acc += w[i]; if (x < acc) return all[i]; }
        return Plan.LOOT_PUSH;
    }

    private static double clamp(double v) {
        return Math.max(0.02, Math.min(0.98, v));
    }

    /** Множитель агрессии для старой формулы (0.75..1.35, как раньше). */
    double aggressionMul() { return 0.75 + aggression * 0.6; }

    /** Множитель осторожности для старой формулы (0.7..1.3, как раньше). */
    double cautionMul() { return 0.7 + caution * 0.6; }

    String describe() {
        return type.title + "/" + plan.title + String.format(Locale.ROOT, " агр%.1f ост%.1f реш%.1f", aggression, caution, decisiveness);
    }
}
