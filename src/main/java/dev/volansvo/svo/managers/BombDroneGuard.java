package dev.volansvo.svo.managers;

import dev.volansvo.svo.VolanSVO;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Objective;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Дрон-бомбила (предмет ExecutableItems "bombsender"). Игрок летает наблюдателем с тегом
 * bombfly, его тело (стойка с головой и тегом-ником) ждёт на месте взлёта, рядом с игроком
 * появляется стойка «ДРОН». Сам предмет эту стойку не двигает и не убирает, а влетевший в
 * блок игрок висел в текстурах до конца таймера. Здесь:
 *  - стойка «ДРОН» летит за игроком и пропадает после полёта;
 *  - влетел в твёрдый блок или летает дольше 16 сек: полёт заканчивается так же, как при
 *    отпускании приседа (игрок у тела, в выживании, стойки убраны, заряд списан).
 */
public final class BombDroneGuard extends BukkitRunnable {

    private static final int MAX_TICKS = 20 * 16;
    private static final int CRASH_GRACE = 20; // после взлёта секунда, чтобы выйти из потолка

    private final VolanSVO plugin;
    private final Map<UUID, Flight> flights = new HashMap<UUID, Flight>();
    private int tick;

    private static final class Flight {
        final int start;
        UUID drone;
        Flight(int start) { this.start = start; }
    }

    public BombDroneGuard(VolanSVO plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        tick++;
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean fly = p.getGameMode() == GameMode.SPECTATOR && p.getScoreboardTags().contains("bombfly");
            Flight f = flights.get(p.getUniqueId());
            if (!fly) {
                if (f != null) {
                    flights.remove(p.getUniqueId());
                    removeDrone(f);
                    cleanBodyLater(p);
                }
                continue;
            }
            if (f == null) {
                f = new Flight(tick);
                flights.put(p.getUniqueId(), f);
            }
            ArmorStand drone = drone(p, f);
            if (drone != null) {
                Location l = p.getLocation();
                drone.teleport(new Location(l.getWorld(), l.getX(), l.getY() + 0.6, l.getZ(), l.getYaw(), 0f));
            }
            int age = tick - f.start;
            if (age > MAX_TICKS) end(p, f, false);
            else if (age > CRASH_GRACE && inBlock(p)) end(p, f, true);
        }
        // Вышел посреди полёта - стойку «ДРОН» всё равно убираем.
        for (Iterator<Map.Entry<UUID, Flight>> it = flights.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Flight> e = it.next();
            if (Bukkit.getPlayer(e.getKey()) == null) { removeDrone(e.getValue()); it.remove(); }
        }
    }

    /** Стойка «ДРОН»: появляется у игрока сразу после взлёта, дальше держим её ссылку. */
    private ArmorStand drone(Player p, Flight f) {
        if (f.drone != null) {
            Entity e = Bukkit.getEntity(f.drone);
            if (e instanceof ArmorStand && e.isValid()) return (ArmorStand) e;
            f.drone = null;
        }
        if (tick - f.start > 40) return null;
        for (Entity e : p.getNearbyEntities(3, 3, 3)) {
            if (e instanceof ArmorStand && e.getScoreboardTags().contains("dronestand")) {
                f.drone = e.getUniqueId();
                return (ArmorStand) e;
            }
        }
        return null;
    }

    private static void removeDrone(Flight f) {
        if (f.drone == null) return;
        Entity e = Bukkit.getEntity(f.drone);
        if (e != null) e.remove();
        f.drone = null;
    }

    /** Голова или ноги в твёрдом блоке (трава, вода и прочее проходимое не в счёт). */
    private static boolean inBlock(Player p) {
        return solid(p.getEyeLocation().getBlock()) || solid(p.getLocation().getBlock());
    }

    private static boolean solid(Block b) {
        return b.getType().isSolid() && !b.isPassable();
    }

    /** Конец полёта: всё, что делает предмет при отпускании приседа, плюс уборка стоек. */
    private void end(Player p, Flight f, boolean crash) {
        flights.remove(p.getUniqueId());
        String n = p.getName();
        World w = p.getWorld();
        Location at = p.getLocation();
        removeDrone(f);
        ArmorStand body = null;
        double best = Double.MAX_VALUE;
        for (ArmorStand a : w.getEntitiesByClass(ArmorStand.class)) {
            Set<String> tags = a.getScoreboardTags();
            if (tags.contains("bombsender" + n)) { a.remove(); continue; } // бомба в полёте
            if (!tags.contains(n) || tags.contains("dronestand")) continue;
            double d = a.getLocation().distanceSquared(at);
            if (d < best) { best = d; body = a; }
        }
        Location back;
        if (body != null) {
            back = body.getLocation();
            body.remove();
        } else {
            back = at.clone();
            back.setY(w.getHighestBlockYAt(at) + 1);
        }
        p.teleport(back);
        p.setGameMode(GameMode.SURVIVAL);
        p.removeScoreboardTag("bombfly");
        p.removeScoreboardTag("bombmann");
        setScore("bombershift", n, 1);
        setScore("iskamikadze", n, 0);
        console("execute as @e[tag=WT,limit=1] at @s run setblock 105 86 -164 minecraft:air");
        int slot = bombSlot(p);
        if (slot >= 0 && Bukkit.getPluginManager().isPluginEnabled("ExecutableItems")) {
            console("ei console-modification modification usage " + n + " " + slot + " -1");
        }
        if (crash) {
            w.spawnParticle(Particle.EXPLOSION, at, 1);
            w.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.5f);
        }
    }

    /** Предмет сам убирает тело по имени стойки; если не убрал - через полсекунды убираем мы. */
    private void cleanBodyLater(final Player p) {
        new BukkitRunnable() {
            @Override public void run() {
                if (!p.isOnline()) return;
                Set<String> tags = p.getScoreboardTags();
                if (tags.contains("bombfly") || tags.contains("fpvfly")) return;
                for (Entity e : p.getNearbyEntities(4, 4, 4)) {
                    if (e instanceof ArmorStand && e.getScoreboardTags().contains(p.getName())) e.remove();
                }
            }
        }.runTaskLater(plugin, 10L);
    }

    private static int bombSlot(Player p) {
        int held = p.getInventory().getHeldItemSlot();
        if (isBomber(p.getInventory().getItem(held))) return held;
        for (int i = 0; i < 36; i++) if (isBomber(p.getInventory().getItem(i))) return i;
        return -1;
    }

    private static boolean isBomber(ItemStack it) {
        String id = dev.volansvo.svo.bots.Items.eiId(it);
        return id != null && id.equalsIgnoreCase("bombsender");
    }

    private static void setScore(String objective, String entry, int value) {
        Objective o = Bukkit.getScoreboardManager().getMainScoreboard().getObjective(objective);
        if (o != null) o.getScore(entry).setScore(value);
    }

    private static void console(String cmd) {
        try { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd); } catch (Throwable ignored) {}
    }
}
