package dev.volansvo.svo.bots.mind;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Доска отряда: кто что взял на себя. Бот бронирует сундук, аирдроп или цель охоты, и
 * остальные из его команды туда не идут. Бронь надо подтверждать: бот погиб или занялся
 * другим - через несколько секунд она снимается сама.
 */
public final class Board {

    /** Роль бота в общем бою. */
    public enum Role { COVER, FLANK, SCOUT, SUPPORT }

    private static final int STALE = 20 * 8;

    private static final class Claim {
        UUID owner;
        int tick;
    }

    private final Map<String, Claim> claims = new HashMap<String, Claim>();
    private final Map<UUID, Role> roles = new HashMap<UUID, Role>();

    /** Забронировать (или подтвердить свою бронь). false - занято другим. */
    public boolean claim(String key, UUID bot, int now) {
        Claim c = claims.get(key);
        if (c != null && !c.owner.equals(bot) && now - c.tick <= STALE) return false;
        if (c == null) { c = new Claim(); claims.put(key, c); }
        c.owner = bot;
        c.tick = now;
        if (claims.size() > 200) prune(now);
        return true;
    }

    /** Занято ли кем-то другим. */
    public boolean taken(String key, UUID bot, int now) {
        Claim c = claims.get(key);
        return c != null && !c.owner.equals(bot) && now - c.tick <= STALE;
    }

    public void release(String key, UUID bot) {
        Claim c = claims.get(key);
        if (c != null && c.owner.equals(bot)) claims.remove(key);
    }

    public void releaseAll(UUID bot) {
        Iterator<Map.Entry<String, Claim>> it = claims.entrySet().iterator();
        while (it.hasNext()) if (it.next().getValue().owner.equals(bot)) it.remove();
        roles.remove(bot);
    }

    public void role(UUID bot, Role r) {
        if (r == null) roles.remove(bot); else roles.put(bot, r);
    }

    public Role role(UUID bot) {
        return roles.get(bot);
    }

    /** Сколько ботов отряда сейчас в этой роли (кроме спрашивающего). */
    public int count(Role r, UUID except) {
        int n = 0;
        for (Map.Entry<UUID, Role> e : roles.entrySet()) if (e.getValue() == r && !e.getKey().equals(except)) n++;
        return n;
    }

    public void clear() {
        claims.clear();
        roles.clear();
    }

    private void prune(int now) {
        Iterator<Map.Entry<String, Claim>> it = claims.entrySet().iterator();
        while (it.hasNext()) if (now - it.next().getValue().tick > STALE) it.remove();
    }
}
