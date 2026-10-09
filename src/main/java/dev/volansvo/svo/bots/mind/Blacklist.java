package dev.volansvo.svo.bots.mind;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Что у бота не получилось и к чему пока не возвращаться. Ключ - «тип дела и место»
 * (сундук 10,64,-3; лестница; план). Срок запрета растёт с каждым повтором: не вышло раз -
 * попробует скоро, не вышло трижды - надолго оставит.
 */
public final class Blacklist {

    private static final int FORGET = 20 * 300;

    private static final class Entry {
        int until, count, last;
        String why;
    }

    private final Map<String, Entry> entries = new HashMap<String, Entry>();

    /** Запретить на base тиков (вдвое дольше за каждый недавний повтор, но не больше чем в 8 раз). */
    public int ban(String key, int base, int now, String why) {
        Entry e = entries.get(key);
        if (e == null) { e = new Entry(); entries.put(key, e); }
        if (now - e.last > FORGET) e.count = 0;
        int ticks = base * Math.min(8, 1 << Math.min(3, e.count));
        e.count++;
        e.last = now;
        e.until = Math.max(e.until, now + ticks);
        e.why = why;
        if (entries.size() > 400) prune(now);
        return ticks;
    }

    /** Просто пауза на ticks тиков: не провал, срок с повторами не растёт. */
    public void hold(String key, int ticks, int now) {
        Entry e = entries.get(key);
        if (e == null) { e = new Entry(); entries.put(key, e); }
        e.until = Math.max(e.until, now + ticks);
    }

    public boolean banned(String key, int now) {
        Entry e = entries.get(key);
        return e != null && now < e.until;
    }

    /** Сколько раз подряд это не получалось. */
    public int fails(String key, int now) {
        Entry e = entries.get(key);
        return e == null || now - e.last > FORGET ? 0 : e.count;
    }

    public String why(String key) {
        Entry e = entries.get(key);
        return e == null ? null : e.why;
    }

    public void lift(String key) {
        entries.remove(key);
    }

    public void clear() {
        entries.clear();
    }

    private void prune(int now) {
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            if (now >= e.until && now - e.last > FORGET) it.remove();
        }
    }

    public static String place(String type, int x, int y, int z) {
        return type + "@" + x + "," + y + "," + z;
    }
}
