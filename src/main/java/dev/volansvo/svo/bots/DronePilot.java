package dev.volansvo.svo.bots;

import dev.volansvo.svo.bots.nms.BotNms;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.UUID;

/**
 * Управление дронами ExecutableItems, где игрок сам становится дроном (режим наблюдателя):
 *
 *  FPV-камикадзе: спринт с предметом в руке - взлёт на 20 блоков, полёт, отпустил спринт -
 *  два TNT на месте дрона и возврат к телу (заряд ~40 сек).
 *  Bombsender: присед с предметом - взлёт на 100 блоков, держим присед и летаем,
 *  прыжок (с приседом) - сброс бомбы строго вниз, отпустил присед - возврат (~15 сек).
 *
 * Всю механику (взлёт, взрыв, возврат, тело-стойка) делает сам предмет, бот только жмёт
 * те же кнопки, что игрок, и ведёт себя к цели.
 */
final class DronePilot {

    enum Mode { NONE, ARMING_FPV, ARMING_BOMBER, FPV, BOMBER }

    private final VolanHooks hooks;
    private final UUID self;

    Mode mode = Mode.NONE;
    int nextUse;
    private int since;
    private Player target;
    private Location prevTarget;
    private int nextDrop;
    private int drops;
    /** Насколько точно пилот берёт упреждение в этом вылете и в какую сторону его водит. */
    private double leadK = 1.0;
    private double wobble;

    DronePilot(VolanHooks hooks, UUID self) {
        this.hooks = hooks;
        this.self = self;
    }

    boolean active() { return mode != Mode.NONE; }

    /** Предмет уже в руке: нажимаем спринт (FPV) или присед (Bombsender). */
    void launch(Player p, boolean fpv, Player tgt, int now) {
        target = tgt;
        prevTarget = null;
        since = now;
        drops = 0;
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        leadK = 0.3 + r.nextDouble() * 0.9;
        wobble = r.nextDouble() * 6.28;
        if (fpv) {
            BotNms.input(p, 0f, 0f, false);
            BotNms.sneak(p, false);
            BotNms.sprint(p, false);
            BotNms.sprint(p, true);
            mode = Mode.ARMING_FPV;
        } else {
            BotNms.input(p, 0f, 0f, false);
            BotNms.sprint(p, false);
            BotNms.sneak(p, false); // если уже сидел (прицеливался) - отпускаем, иначе нажатия не будет
            BotNms.sneak(p, true);
            mode = Mode.ARMING_BOMBER;
        }
    }

    void abort(Player p, int now) {
        if (p != null) {
            if (mode == Mode.FPV || mode == Mode.ARMING_FPV) BotNms.sprint(p, false);
            if (mode == Mode.BOMBER || mode == Mode.ARMING_BOMBER) BotNms.sneak(p, false);
        }
        mode = Mode.NONE;
        nextUse = now + 20 * 30;
    }

    /** Вызывается каждый тик, пока active(). */
    void tick(Player p, int now) {
        boolean spectator = p.getGameMode() == GameMode.SPECTATOR;
        switch (mode) {
            case ARMING_FPV: case ARMING_BOMBER:
                if (spectator) {
                    mode = (mode == Mode.ARMING_FPV) ? Mode.FPV : Mode.BOMBER;
                    since = now;
                    nextDrop = now + 30;
                } else if (now - since > 12) {
                    abort(p, now); // кулдаун предмета или не сработал
                }
                return;
            case FPV: case BOMBER:
                if (!spectator) { // предмет вернул нас к телу
                    BotNms.sneak(p, false);
                    BotNms.sprint(p, false);
                    mode = Mode.NONE;
                    nextUse = now + 20 * 65;
                    return;
                }
                if (mode == Mode.FPV) flyFpv(p, now); else flyBomber(p, now);
                return;
            default:
        }
    }

    private void flyFpv(Player p, int now) {
        Location pos = p.getLocation();
        if (!validTarget(target, pos)) target = nearestEnemy(pos, 160);
        if (target == null || now - since > 20 * 38) { BotNms.sprint(p, false); return; }
        Location dest = target.getLocation().add(lead(target).multiply(6 * leadK)).add(0, 1.0, 0);
        Vector to = dest.toVector().subtract(pos.toVector());
        double d = to.length();
        // Издалека дрон идёт не по струне: пилота водит из стороны в сторону.
        if (d > 8) to.rotateAroundY(Math.sin((now - since) / 9.0 + wobble) * 0.22);
        if (d <= 1.7) {
            // Не взрываемся рядом со своими.
            if (!teammateNear(dest, 5)) { BotNms.sprint(p, false); return; }
        }
        double step = Math.min(d, 0.75);
        Vector mv = d > 1e-3 ? to.clone().multiply(step / d) : new Vector();
        float yaw = Motor.yawTo(to.getX(), to.getZ());
        float pitch = Motor.pitchTo(to.getX(), to.getY(), to.getZ());
        BotNms.moveTo(p, pos.getX() + mv.getX(), pos.getY() + mv.getY(), pos.getZ() + mv.getZ(), yaw, pitch);
        prevTarget = target.getLocation();
    }

    private void flyBomber(Player p, int now) {
        Location pos = p.getLocation();
        if (!validTarget(target, pos)) target = nearestEnemy(pos, 140);
        if (target == null || drops >= 3 || now - since > 20 * 13) { BotNms.sneak(p, false); return; }
        Location t = target.getLocation().add(lead(target).multiply(4 * leadK));
        World w = pos.getWorld();
        double ground = w.isChunkLoaded(t.getBlockX() >> 4, t.getBlockZ() >> 4) ? w.getHighestBlockYAt(t) : t.getY();
        // Над холмом по пути тоже держим высоту: влетевший в блок дрон разбивается.
        double here = w.getHighestBlockYAt(pos);
        double hoverY = Math.max(Math.max(t.getY() + 14, ground + 6), here + 4);
        double dx = t.getX() - pos.getX(), dz = t.getZ() - pos.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        double step = Math.min(flat, 0.85);
        double nx = flat > 1e-3 ? pos.getX() + dx / flat * step : pos.getX();
        double nz = flat > 1e-3 ? pos.getZ() + dz / flat * step : pos.getZ();
        double ny = pos.getY() + Math.max(-1.0, Math.min(1.0, hoverY - pos.getY()));
        if (blocked(w, nx, ny, nz)) { nx = pos.getX(); nz = pos.getZ(); ny = pos.getY() + 1.0; } // стена впереди - вверх
        BotNms.moveTo(p, nx, ny, nz, Motor.yawTo(dx, dz), 89f);
        if (flat < 0.9 && now >= nextDrop && Math.abs(pos.getY() - hoverY) < 3 && !teammateNear(t, 6)) {
            BotNms.pressJump(p, true); // прыжок с зажатым приседом = сброс бомбы вниз
            drops++;
            nextDrop = now + 66; // кулдаун сброса 3 сек
        }
        prevTarget = target.getLocation();
    }

    /** В точке (x,y,z) тело дрона упрётся в твёрдый блок (ноги или голова). */
    private static boolean blocked(World w, double x, double y, double z) {
        for (double dy : new double[]{0.1, 1.0, 1.7}) {
            org.bukkit.block.Block b = w.getBlockAt((int) Math.floor(x), (int) Math.floor(y + dy), (int) Math.floor(z));
            if (b.getType().isSolid() && !b.isPassable()) return true;
        }
        return false;
    }

    /** Скорость цели (блоков за тик) для упреждения. */
    private Vector lead(Player t) {
        if (prevTarget == null || !prevTarget.getWorld().equals(t.getWorld())) return new Vector();
        Vector v = t.getLocation().toVector().subtract(prevTarget.toVector()).setY(0);
        return v.lengthSquared() > 1 ? new Vector() : v;
    }

    private boolean validTarget(Player t, Location from) {
        return t != null && t.isOnline() && !t.isDead() && t.getGameMode() == GameMode.SURVIVAL
            && t.getWorld().equals(from.getWorld()) && hooks.inGame(t.getUniqueId());
    }

    Player nearestEnemy(Location from, double radius) {
        Player best = null;
        double bd = radius * radius;
        for (Player o : hooks.alivePlayers()) {
            if (o.getUniqueId().equals(self) || o.getGameMode() != GameMode.SURVIVAL) continue;
            if (!o.getWorld().equals(from.getWorld()) || hooks.sameTeam(self, o.getUniqueId())) continue;
            double dx = o.getLocation().getX() - from.getX(), dz = o.getLocation().getZ() - from.getZ();
            double d = dx * dx + dz * dz;
            if (d < bd && !teammateNear(o.getLocation(), 8)) { bd = d; best = o; }
        }
        return best;
    }

    private boolean teammateNear(Location l, double r) {
        for (Player o : hooks.alivePlayers()) {
            if (o.getUniqueId().equals(self) || !hooks.sameTeam(self, o.getUniqueId())) continue;
            if (o.getWorld().equals(l.getWorld()) && o.getLocation().distanceSquared(l) < r * r) return true;
        }
        return false;
    }
}
