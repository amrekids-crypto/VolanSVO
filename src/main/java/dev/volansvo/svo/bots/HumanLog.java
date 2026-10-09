package dev.volansvo.svo.bots;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Как играют живые игроки: по этим записям подгоняют ботов (реакцию, точность по дистанциям,
 * время лута). Файл human_log.csv, строки «тик;игрок;событие;a;b»:
 *   see   - враг появился в поле зрения: a = дистанция;
 *   shot  - выстрел: a = сколько тиков прошло с момента, как стало видно врага, в которого
 *           целится (-1 - никого на прицеле), b = дистанция до него;
 *   hit   - попадание: a = дистанция, b = урон;
 *   loot  - закрыл сундук: a = сколько тиков держал открытым.
 */
final class HumanLog {

    private final File file;
    private final List<String> buf = new ArrayList<String>();
    /** Игрок -> враг -> с какого тика тот непрерывно в поле зрения. */
    private final Map<UUID, Map<UUID, Integer>> seen = new HashMap<UUID, Map<UUID, Integer>>();
    private final Map<UUID, Integer> lootSince = new HashMap<UUID, Integer>();

    HumanLog(File dataFolder) {
        this.file = new File(dataFolder, "human_log.csv");
    }

    private void line(int tick, Player p, String event, double a, double b) {
        buf.add(tick + ";" + p.getName() + ";" + event + ";" + Math.round(a * 10) / 10.0 + ";" + Math.round(b * 10) / 10.0);
        if (buf.size() > 5000) buf.remove(0);
    }

    private static boolean inView(Player h, Player e, double halfDeg) {
        Location eye = h.getEyeLocation();
        Vector to = e.getEyeLocation().toVector().subtract(eye.toVector());
        if (to.lengthSquared() < 1e-6) return true;
        return eye.getDirection().angle(to.normalize()) < Math.toRadians(halfDeg);
    }

    /** Раз в несколько тиков: кого сейчас видит человек h. */
    void sight(Player h, List<Player> alive, VolanHooks hooks, int tick) {
        Map<UUID, Integer> mine = seen.get(h.getUniqueId());
        if (mine == null) { mine = new HashMap<UUID, Integer>(); seen.put(h.getUniqueId(), mine); }
        for (Player e : alive) {
            if (e.equals(h) || !e.getWorld().equals(h.getWorld()) || hooks.sameTeam(h.getUniqueId(), e.getUniqueId())) continue;
            double d = e.getLocation().distance(h.getLocation());
            boolean sees = d < 90 && inView(h, e, 55) && h.hasLineOfSight(e);
            if (!sees) { mine.remove(e.getUniqueId()); continue; }
            if (!mine.containsKey(e.getUniqueId())) {
                mine.put(e.getUniqueId(), tick);
                line(tick, h, "see", d, 0);
            }
        }
        Iterator<UUID> it = mine.keySet().iterator();
        while (it.hasNext()) {
            Player e = org.bukkit.Bukkit.getPlayer(it.next());
            if (e == null || e.isDead()) it.remove();
        }
    }

    void shot(Player h, int tick) {
        Map<UUID, Integer> mine = seen.get(h.getUniqueId());
        Player aimed = null;
        double best = 12;
        int since = -1;
        if (mine != null) {
            for (Map.Entry<UUID, Integer> en : mine.entrySet()) {
                Player e = org.bukkit.Bukkit.getPlayer(en.getKey());
                if (e == null || !e.getWorld().equals(h.getWorld())) continue;
                Vector to = e.getEyeLocation().toVector().subtract(h.getEyeLocation().toVector());
                double ang = to.lengthSquared() < 1e-6 ? 0 : Math.toDegrees(h.getEyeLocation().getDirection().angle(to.normalize()));
                if (ang < best) { best = ang; aimed = e; since = en.getValue(); }
            }
        }
        if (aimed == null) line(tick, h, "shot", -1, 0);
        else line(tick, h, "shot", tick - since, aimed.getLocation().distance(h.getLocation()));
    }

    void hit(Player attacker, Player victim, double damage, int tick) {
        if (!attacker.getWorld().equals(victim.getWorld())) return;
        line(tick, attacker, "hit", attacker.getLocation().distance(victim.getLocation()), damage);
    }

    void lootOpen(Player h, int tick) {
        lootSince.put(h.getUniqueId(), tick);
    }

    void lootClose(Player h, int tick) {
        Integer since = lootSince.remove(h.getUniqueId());
        if (since != null && tick - since < 20 * 120) line(tick, h, "loot", tick - since, 0);
    }

    void flush() {
        if (buf.isEmpty()) return;
        List<String> out = new ArrayList<String>(buf);
        buf.clear();
        try {
            java.nio.file.Files.write(file.toPath(), out, java.nio.charset.StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }
}
