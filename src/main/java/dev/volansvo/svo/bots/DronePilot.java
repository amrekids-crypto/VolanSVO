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

    // ---- FPV как у человека с мышью
    /** Скорость дрона (блоков за тик): разгоняется и поворачивает не мгновенно, на виражах заносит. */
    private Vector vel = new Vector();
    /** Где пилот видел цель: камера дрона не видит сквозь блоки, картинка запаздывает. */
    private final java.util.ArrayDeque<Location> seen = new java.util.ArrayDeque<Location>();
    private int lag;
    /** Личный промах этого вылета: пилот целится чуть мимо и плавно водит прицел. */
    private Vector aimOff = new Vector();
    private double aimErr;
    /** Тик, когда пилот отпустит спринт (реакция на «пора»), и прошлое расстояние до точки. */
    private int releaseAt = -1;
    private double prevD = Double.MAX_VALUE;
    /** Этот заход пилот проспит и пролетит мимо (потом развернётся). */
    private boolean missPass;

    DronePilot(VolanHooks hooks, UUID self) {
        this.hooks = hooks;
        this.self = self;
    }

    boolean active() { return mode != Mode.NONE; }

    /** Предмет уже в руке: нажимаем спринт (FPV) или присед (Bombsender). */
    void launch(Player p, boolean fpv, Player tgt, int now, BotSkill skill) {
        target = tgt;
        prevTarget = null;
        since = now;
        drops = 0;
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        leadK = 0.3 + r.nextDouble() * 0.9;
        wobble = r.nextDouble() * 6.28;
        vel = new Vector();
        seen.clear();
        // Картинка с дрона и рука пилота: 0.4-0.9 сек от того, где цель на самом деле.
        lag = (int) Math.round(Math.max(6, Math.min(18, skill.reactionTicks * 1.2 * skill.tempo + r.nextInt(6))));
        // Промах по-человечески: обычно 1-3 блока, у метких меньше, у косых больше.
        aimErr = Math.max(0.5, Math.min(4.0, (0.8 + r.nextDouble() * 2.0) * skill.aimError / 2.0));
        double a = r.nextDouble() * Math.PI * 2;
        aimOff = new Vector(Math.cos(a) * aimErr, r.nextDouble() * 1.2 - 0.4, Math.sin(a) * aimErr);
        releaseAt = -1;
        prevD = Double.MAX_VALUE;
        missPass = r.nextDouble() < 0.3;
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
        if (!validTarget(target, pos)) { target = nearestSeen(p, 160); seen.clear(); prevTarget = null; }
        if (target == null || now - since > 20 * 38) { BotNms.sprint(p, false); return; }
        if (releaseAt >= 0 && now >= releaseAt) { BotNms.sprint(p, false); return; } // отпустил спринт - взрыв
        // Камера дрона: цель видна, только если между ними нет блоков (вблизи ещё и слышно).
        Location real = target.getLocation();
        double realD = real.distance(pos);
        if (realD < 5 || p.hasLineOfSight(target)) {
            seen.addLast(real.clone().add(lead(target).multiply(6 * leadK)));
            prevTarget = real;
        }
        while (seen.size() > lag + 1) seen.removeFirst();
        if (seen.isEmpty()) { BotNms.sprint(p, false); return; } // так и не увидел - дрон в никуда
        // Пилот реагирует на картинку lag тиков назад, целится со своим промахом.
        Location dest = seen.peekFirst().clone().add(0, 1.0, 0).add(aimOff);
        double t = (now - since) / 10.0 + wobble;
        aimOff.add(new Vector(Math.sin(t) * 0.05, 0, Math.cos(t * 0.7) * 0.05));
        if (aimOff.length() > aimErr * 1.3) aimOff.multiply(aimErr * 1.3 / aimOff.length());
        Vector to = dest.toVector().subtract(pos.toVector());
        double d = to.length();
        // Издалека дрон идёт не по струне: пилота водит из стороны в сторону.
        if (d > 8) to.rotateAroundY(Math.sin((now - since) / 9.0 + wobble) * 0.22);

        // Подрыв: долетел до точки или проскочил её (расстояние начало расти). Палец отпускает
        // спринт с опозданием, дрон в это время летит дальше по инерции.
        if (releaseAt < 0 && !teammateNear(pos, 5)) {
            boolean close = d <= 1.7;
            boolean passing = d < 4.0 && d > prevD + 0.05;
            if ((close || passing) && missPass) {
                missPass = false; // этот заход проспал, развернётся и зайдёт снова
            } else if (close || passing) {
                releaseAt = now + 2 + java.util.concurrent.ThreadLocalRandom.current().nextInt(5);
            }
        }
        prevD = d;

        // Инерция: желаемая скорость к точке, но разгон и поворот ограничены - на вираже заносит.
        double speed = Math.min(0.75, Math.max(0.25, d * 0.25));
        Vector want = d > 1e-3 ? to.clone().multiply(speed / d) : new Vector();
        Vector dv = want.subtract(vel);
        double acc = 0.085;
        if (dv.length() > acc) dv.multiply(acc / dv.length());
        vel.add(dv);
        float yaw = Motor.yawTo(vel.getX(), vel.getZ());
        float pitch = Motor.pitchTo(vel.getX(), vel.getY(), vel.getZ());
        BotNms.moveTo(p, pos.getX() + vel.getX(), pos.getY() + vel.getY(), pos.getZ() + vel.getZ(), yaw, pitch);
    }

    private void flyBomber(Player p, int now) {
        Location pos = p.getLocation();
        if (!validTarget(target, pos)) target = nearestSeen(p, 140);
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

    /** Можно ли пускать дрон в этого врага: рядом с ним нет своих (взрыв заденет и их). */
    boolean safeTarget(Player o) {
        return o != null && !teammateNear(o.getLocation(), 8);
    }

    /** Новая цель с камеры дрона: только тех, кого из дрона видно (или кто совсем рядом). */
    private Player nearestSeen(Player pilot, double radius) {
        Location from = pilot.getLocation();
        Player best = null;
        double bd = radius * radius;
        for (Player o : hooks.alivePlayers()) {
            if (o.getUniqueId().equals(self) || o.getGameMode() != GameMode.SURVIVAL) continue;
            if (!o.getWorld().equals(from.getWorld()) || hooks.sameTeam(self, o.getUniqueId())) continue;
            double d = o.getLocation().distanceSquared(from);
            if (d >= bd || teammateNear(o.getLocation(), 8)) continue;
            if (d > 24 * 24 && !pilot.hasLineOfSight(o)) continue;
            bd = d;
            best = o;
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
