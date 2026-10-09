package dev.volansvo.svo.bots.mind;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Темп матча для живых игроков. Следит, насколько жарко каждому человеку, и влияет только на
 * то, кого боты выбирают целью и куда идут: после тяжёлого боя человеку дают передышку,
 * заскучавшему - повод для встречи, и на одного человека одновременно стреляют не больше двух
 * ботов. Точность и реакцию ботов это не трогает.
 */
public final class Pace {

    public enum State {
        /** Обычная игра. */
        BUILD,
        /** Жарко: новые боты на этого человека не охотятся. */
        PEAK,
        /** Передышка после жаркого боя. */
        RELAX,
        /** Давно ничего не происходит. */
        BORED
    }

    private static final class Human {
        double intensity;
        State state = State.BUILD;
        int relaxUntil, lastContact, nudgeAt;
        /** Боты, которым сейчас можно стрелять по этому человеку: id -> до какого тика. */
        final Map<UUID, Integer> tokens = new HashMap<UUID, Integer>();
    }

    public static final int MAX_SHOOTERS = 2;
    private static final int TOKEN_LEASE = 50, BORED_AFTER = 20 * 90;
    private static final double PEAK = 0.75, CALM = 0.35;

    private final Map<UUID, Human> humans = new HashMap<UUID, Human>();

    private Human of(UUID id, int now) {
        Human h = humans.get(id);
        if (h == null) {
            h = new Human();
            h.lastContact = now;
            humans.put(id, h);
        }
        return h;
    }

    public void clear() { humans.clear(); }

    public void forget(UUID id) { humans.remove(id); }

    /** Человека ранили. */
    public void hurt(UUID id, double damage, int now) {
        Human h = of(id, now);
        h.intensity = Math.min(1.5, h.intensity + 0.06 + damage * 0.035);
        h.lastContact = now;
    }

    /** По человеку стреляли и не попали, или он видит врага рядом. */
    public void pressure(UUID id, double amount, int now) {
        Human h = of(id, now);
        h.intensity = Math.min(1.5, h.intensity + amount);
        h.lastContact = now;
    }

    /** Погиб союзник человека. */
    public void allyDied(UUID id, int now) {
        pressure(id, 0.3, now);
    }

    /** Раз в секунду для каждого живого человека. relaxTicks - длина передышки для этого раза. */
    public void second(UUID id, int now, int relaxTicks) {
        Human h = of(id, now);
        h.intensity *= 0.977; // вдвое за полминуты
        Iterator<Map.Entry<UUID, Integer>> it = h.tokens.entrySet().iterator();
        while (it.hasNext()) if (now >= it.next().getValue()) it.remove();
        switch (h.state) {
            case PEAK:
                if (h.intensity < CALM) { h.state = State.RELAX; h.relaxUntil = now + relaxTicks; }
                break;
            case RELAX:
                if (h.intensity >= PEAK) h.state = State.PEAK;
                else if (now >= h.relaxUntil) { h.state = State.BUILD; h.lastContact = now; }
                break;
            default:
                if (h.intensity >= PEAK) h.state = State.PEAK;
                else h.state = now - h.lastContact > BORED_AFTER ? State.BORED : State.BUILD;
        }
    }

    public State state(UUID id) {
        Human h = humans.get(id);
        return h == null ? State.BUILD : h.state;
    }

    public double intensity(UUID id) {
        Human h = humans.get(id);
        return h == null ? 0 : h.intensity;
    }

    /** Можно ли сейчас идти охотиться на этого человека издалека. */
    public boolean huntable(UUID id) {
        State s = state(id);
        return s != State.PEAK && s != State.RELAX;
    }

    /** Сколько ботов сейчас стреляют по человеку (кроме самого спрашивающего). */
    public int shooters(UUID human, UUID except, int now) {
        Human h = humans.get(human);
        if (h == null) return 0;
        int n = 0;
        for (Map.Entry<UUID, Integer> e : h.tokens.entrySet()) if (now < e.getValue() && !e.getKey().equals(except)) n++;
        return n;
    }

    /**
     * Бот хочет выстрелить по человеку. true - можно (и право закреплено за ним на пару секунд).
     * selfDefense - человек сам бьёт бота в упор: тогда можно всегда.
     */
    public boolean mayFire(UUID human, UUID bot, int now, boolean selfDefense) {
        Human h = of(human, now);
        Integer mine = h.tokens.get(bot);
        if (mine != null && now < mine) { h.tokens.put(bot, now + TOKEN_LEASE); return true; }
        if (selfDefense || shooters(human, bot, now) < MAX_SHOOTERS) {
            h.tokens.put(bot, now + TOKEN_LEASE);
            return true;
        }
        return false;
    }

    /** Заскучавшему человеку пора подвести бота. true не чаще раза в минуту. */
    public boolean wantsVisitor(UUID id, int now) {
        Human h = humans.get(id);
        if (h == null || h.state != State.BORED || now < h.nudgeAt) return false;
        h.nudgeAt = now + 20 * 60;
        return true;
    }

    public Set<UUID> known() {
        return new HashSet<UUID>(humans.keySet());
    }
}
