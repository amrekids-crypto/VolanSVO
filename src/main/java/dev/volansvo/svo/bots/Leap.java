package dev.volansvo.svo.bots;

import dev.volansvo.svo.bots.nav.Cell;
import dev.volansvo.svo.bots.nms.NmsBlockView;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Крюк-кошка и реактивный ранец MilitaryCraft: перелететь обрыв, забраться на крышу или
 * скалу. Перед выстрелом полёт просчитывается по ванильной физике игрока (гравитация,
 * сопротивление воздуха, столкновения с блоками), и бот берёт тот выстрел, после которого
 * встанет ближе всего к цели.
 *
 * Как работают предметы (MilitaryCraft, warkit.weapons): крюк по ПКМ цепляется за то, во что
 * упирается взгляд (до 30 блоков), и бросает игрока к этой точке; ранец по ПКМ толкает вверх
 * и чуть вперёд по взгляду, в воздухе можно толкнуться снова. После обоих несколько секунд
 * нет урона от падения.
 */
final class Leap {

    private Leap() {}

    static boolean isHook(ItemStack it) { return "grappling_hook".equals(Items.warkitId(it)); }

    static boolean isJet(ItemStack it) { return "jump_jet".equals(Items.warkitId(it)); }

    static boolean isGear(ItemStack it) { return isHook(it) || isJet(it); }

    // ---- настройки предметов (читаются из конфига MilitaryCraft)
    static double hookRange = 30, hookPull = 1.35, hookUp = 0.45;
    static int hookCharges = 12, hookNoFall = 6;
    static double jetUp = 0.85, jetForward = 0.35;
    static int jetFuel = 30, jetCost = 3, jetNoFall = 4;
    private static long readAt;

    /** Перечитать настройки MilitaryCraft (раз в минуту, дёшево). */
    static void refresh() {
        long t = System.currentTimeMillis();
        if (t - readAt < 60_000L) return;
        readAt = t;
        try {
            Plugin mc = Bukkit.getPluginManager().getPlugin("MilitaryCraft");
            if (mc == null) return;
            ConfigurationSection h = mc.getConfig().getConfigurationSection("warkit.weapons.grappling-hook");
            if (h != null) {
                hookRange = h.getDouble("range", hookRange);
                hookPull = h.getDouble("pull-strength", hookPull);
                hookUp = h.getDouble("up-boost", hookUp);
                hookCharges = h.getInt("charges", hookCharges);
                hookNoFall = h.getInt("no-fall-seconds", hookNoFall);
            }
            ConfigurationSection j = mc.getConfig().getConfigurationSection("warkit.weapons.jump-jet");
            if (j != null) {
                jetUp = j.getDouble("up", jetUp);
                jetForward = j.getDouble("forward", jetForward);
                jetFuel = j.getInt("fuel", jetFuel);
                jetCost = Math.max(1, j.getInt("cost-per-burst", jetCost));
                jetNoFall = j.getInt("no-fall-seconds", jetNoFall);
            }
        } catch (Throwable ignored) {}
    }

    /** Сколько зарядов у крюка или топлива у ранца (метка не выставлена - полный). */
    static int charge(ItemStack it) {
        int a = Items.ammo(it);
        if (a >= 0) return a;
        return isHook(it) ? hookCharges : jetFuel;
    }

    /** Предмет ещё работает (заряды есть, крюк не на перезарядке). */
    static boolean ready(Player p, ItemStack it) {
        if (isHook(it)) return charge(it) > 0 && !p.hasCooldown(it);
        if (isJet(it)) return charge(it) >= jetCost;
        return false;
    }

    // ================================================================== план

    /** Выбранный прыжок. */
    static final class Plan {
        boolean hook;
        float yaw, pitch;
        /** Ранец: сколько рывков всего (первый с земли, остальные в верхней точке). */
        int bursts;
        /** Куда жать W в полёте (единичный вектор по горизонтали). */
        double sx, sz;
        Location from, land;
        int ticks;
        double gain;
    }

    /** Итог просчёта полёта. */
    private static final class Flight {
        double x, y, z;
        int ticks;
        boolean ok;
    }

    /**
     * Лучший прыжок к точке dest (где хотим стоять). Годится только тот, после которого бот
     * встанет на твёрдое, не за зоной (не глубже, чем сейчас) и заметно ближе к цели.
     * Просчёт полётов не бесплатный: на всё не больше 3 мс, лучшие варианты считаются первыми.
     */
    static Plan plan(Player p, Location dest, ItemStack hook, ItemStack jet) {
        refresh();
        long deadline = System.nanoTime() + 3_000_000L;
        World w = p.getWorld();
        NmsBlockView view = new NmsBlockView(w, true, null, null);
        Location feet = p.getLocation();
        double startCost = cost(feet.getX(), feet.getY(), feet.getZ(), dest);
        Plan best = null;
        if (hook != null) best = better(best, planHook(p, view, dest, startCost, deadline));
        if (jet != null) best = better(best, planJet(p, view, dest, startCost, charge(jet) / jetCost, deadline));
        return best;
    }

    private static Plan better(Plan a, Plan b) {
        if (b == null) return a;
        if (a == null) return b;
        return b.gain > a.gain ? b : a;
    }

    /** Насколько точка далека от цели: по горизонтали плюс недобор высоты (снизу на крышу не попасть). */
    private static double cost(double x, double y, double z, Location d) {
        return Math.hypot(x - d.getX(), z - d.getZ()) + Math.max(0, d.getY() - y - 0.5) * 2.0 + Math.max(0, y - d.getY() - 4) * 0.4;
    }

    /** Сначала сама клетка цели, потом соседние в 2 блоках. */
    private static final int[][] AROUND = {{0, 0}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}, {2, 2}, {-2, -2}, {2, -2}, {-2, 2}};

    private static Plan planHook(Player p, NmsBlockView view, Location dest, double startCost, long deadline) {
        World w = p.getWorld();
        Location eye = p.getEyeLocation(), feet = p.getLocation();
        int dx0 = dest.getBlockX(), dz0 = dest.getBlockZ(), dy0 = dest.getBlockY();
        Plan best = null;
        for (int[] o : AROUND) {
            if (System.nanoTime() > deadline) return best;
            int cx = dx0 + o[0], cz = dz0 + o[1];
            // Пол в этой клетке у высоты цели: в его верх и цепляемся.
            int sy = standNear(view, cx, dy0, cz);
            if (sy == Integer.MIN_VALUE) continue;
            for (double aimY : new double[]{sy - 0.1, sy + 0.6}) {
                Vector to = new Vector(cx + 0.5 - eye.getX(), aimY - eye.getY(), cz + 0.5 - eye.getZ());
                if (to.lengthSquared() < 4) continue;
                float yaw = Motor.yawTo(to.getX(), to.getZ());
                float pitch = Motor.pitchTo(to.getX(), to.getY(), to.getZ());
                Vector dir = direction(yaw, pitch);
                // Крюк цепляется и за живых (луч толщиной 0.3), как в MilitaryCraft: стоящий на крыше
                // враг перехватит крюк, и бросок выйдет другим.
                RayTraceResult hit = w.rayTrace(eye, dir, hookRange, org.bukkit.FluidCollisionMode.NEVER, true, 0.3,
                    e -> e != p && e instanceof org.bukkit.entity.LivingEntity && !e.isDead()
                        && !(e instanceof Player && ((Player) e).getGameMode() == org.bukkit.GameMode.SPECTATOR));
                if (hit == null || hit.getHitPosition() == null) continue;
                Vector h = hit.getHitPosition();
                Vector pull = h.clone().subtract(feet.toVector());
                double d = pull.length();
                if (d < 1.5) continue;
                pull.normalize().multiply(Math.min(2.4, 0.55 + d * 0.06) * hookPull);
                pull.setY(pull.getY() + hookUp);
                double sl = Math.hypot(dir.getX(), dir.getZ());
                if (sl < 1e-3) continue;
                double sx = dir.getX() / sl, sz = dir.getZ() / sl;
                Flight f = fly(view, feet.getX(), feet.getY(), feet.getZ(), pull.getX(), pull.getY(), pull.getZ(),
                    2, dev.volansvo.svo.bots.nms.BotNms.onGround(p), sx, sz, 0, 0f, Math.min(115, hookNoFall * 20 - 5));
                Plan pl = judge(p, view, f, dest, startCost, 2.0);
                if (pl == null) continue;
                pl.hook = true; pl.yaw = yaw; pl.pitch = pitch; pl.sx = sx; pl.sz = sz;
                if (best == null || pl.gain > best.gain) best = pl;
            }
        }
        return best;
    }

    private static Plan planJet(Player p, NmsBlockView view, Location dest, double startCost, int maxBursts, long deadline) {
        if (maxBursts <= 0) return null;
        Location feet = p.getLocation();
        float base = Motor.yawTo(dest.getX() - feet.getX(), dest.getZ() - feet.getZ());
        Vector v0 = p.getVelocity();
        // Рывок поднимает блока на 4: сколько их нужно до высоты цели (и на один больше про запас).
        int need = Math.max(1, (int) Math.ceil((dest.getY() - feet.getY() - 0.5) / 4.2));
        int bLo = Math.min(need, maxBursts), bHi = Math.min(Math.min(4, maxBursts), need + 1);
        Plan best = null;
        for (float dyaw : new float[]{0f, -25f, 25f}) {
            float yaw = Motor.wrap(base + dyaw);
            for (float pitch : new float[]{0f, -35f, -70f}) {
                if (System.nanoTime() > deadline) return best;
                Vector dir = direction(yaw, pitch);
                double sl = Math.hypot(dir.getX(), dir.getZ());
                double sx = sl > 1e-3 ? dir.getX() / sl : -Math.sin(Math.toRadians(yaw));
                double sz = sl > 1e-3 ? dir.getZ() / sl : Math.cos(Math.toRadians(yaw));
                for (int b = bLo; b <= bHi; b++) {
                    double vx = dir.getX() * jetForward + v0.getX() * 0.25, vy = jetUp + v0.getY() * 0.25,
                        vz = dir.getZ() * jetForward + v0.getZ() * 0.25;
                    Flight f = fly(view, feet.getX(), feet.getY(), feet.getZ(), vx, vy, vz,
                        1, dev.volansvo.svo.bots.nms.BotNms.onGround(p), sx, sz, b - 1, pitch, Math.min(110, jetNoFall * 20 + 10 * (b - 1)));
                    // Каждый лишний рывок - топливо: берём, только если он заметно помогает.
                    Plan pl = judge(p, view, f, dest, startCost, 1.0 + b * 1.2);
                    if (pl == null) continue;
                    pl.hook = false; pl.yaw = yaw; pl.pitch = pitch; pl.bursts = b; pl.sx = sx; pl.sz = sz;
                    if (best == null || pl.gain > best.gain) best = pl;
                }
            }
        }
        return best;
    }

    /** Оценка полёта: null - не годится. price - во что обходится выстрел (заряд, топливо). */
    private static Plan judge(Player p, NmsBlockView view, Flight f, Location dest, double startCost, double price) {
        if (!f.ok) return null;
        int lx = (int) Math.floor(f.x), ly = (int) Math.floor(f.y + 0.05), lz = (int) Math.floor(f.z);
        if (!Cell.stand(view, lx, ly, lz) && (view.type(lx, ly, lz) & Cell.KIND) != Cell.WATER) return null;
        // За зону не улетаем (глубже, чем стоим сейчас).
        if (!view.inZone(lx, lz)) {
            Location l = p.getLocation();
            if (view.inZone(l.getBlockX(), l.getBlockZ()) || edgeOut(p, lx, lz) > edgeOut(p, l.getBlockX(), l.getBlockZ())) return null;
        }
        double gain = startCost - cost(f.x, f.y, f.z, dest);
        // Цель выше: прыжок должен поднять, иначе он ничего не решает.
        boolean up = dest.getY() - p.getLocation().getY() >= 2.5;
        if (up && f.y < p.getLocation().getY() + 1.5) return null;
        if (gain < 4) return null;
        Plan pl = new Plan();
        pl.from = p.getLocation().clone();
        pl.land = new Location(p.getWorld(), f.x, f.y, f.z);
        pl.ticks = f.ticks;
        pl.gain = gain - price;
        return pl;
    }

    /** Насколько клетка за краем зоны (0 и меньше - в зоне). */
    private static double edgeOut(Player p, int x, int z) {
        org.bukkit.WorldBorder wb = p.getWorld().getWorldBorder();
        return Math.max(Math.abs(x + 0.5 - wb.getCenter().getX()), Math.abs(z + 0.5 - wb.getCenter().getZ())) - wb.getSize() / 2.0;
    }

    /** Высота, на которой можно встать в столбце (x,z) поближе к near (до 6 блоков). */
    private static int standNear(NmsBlockView v, int x, int near, int z) {
        for (int d = 0; d <= 6; d++) {
            if (Cell.stand(v, x, near + d, z)) return near + d;
            if (d > 0 && Cell.stand(v, x, near - d, z)) return near - d;
        }
        return Integer.MIN_VALUE;
    }

    /** Направление взгляда по yaw/pitch, как у Location.getDirection(). */
    static Vector direction(float yaw, float pitch) {
        double ry = Math.toRadians(yaw), rp = Math.toRadians(pitch);
        double xz = Math.cos(rp);
        return new Vector(-xz * Math.sin(ry), -Math.sin(rp), xz * Math.cos(ry));
    }

    // ================================================================== физика

    /**
     * Полёт игрока по ванильной физике. (vx,vy,vz) - скорость в первый тик; repeat - столько
     * первых тиков скорость выставляется заново той же (крюк повторяет рывок через тик).
     * ground - стартуем с земли (в первый тик трение земли). (sx,sz) - куда жмём W.
     * bursts - ранец: столько ещё рывков в верхней точке (вверх и вперёд по взгляду pitch).
     */
    private static Flight fly(NmsBlockView v, double x, double y, double z, double vx, double vy, double vz,
                              int repeat, boolean ground, double sx, double sz, int bursts, float pitch, int maxTicks) {
        Flight f = new Flight();
        double v0x = vx, v0y = vy, v0z = vz;
        double fwd = Math.cos(Math.toRadians(pitch)) * jetForward;
        boolean onGround = ground;
        for (int t = 0; t < maxTicks; t++) {
            if (t > 0 && t < repeat) { vx = v0x; vy = v0y; vz = v0z; onGround = false; }
            if (bursts > 0 && t > 1 && !onGround && vy <= 0.05) {
                vx = sx * fwd + vx * 0.25; vy = jetUp + vy * 0.25; vz = sz * fwd + vz * 0.25;
                bursts--;
            }
            // W в полёте: ускорение 0.02 (на земле 0.1), ввод игрока ещё умножается на 0.98.
            double acc = (onGround ? 0.1 : 0.02) * 0.98;
            vx += sx * acc; vz += sz * acc;
            boolean wasGround = onGround;
            // Сначала по высоте, потом по горизонтали.
            double ny = moveY(v, x, y, z, vy);
            boolean hitY = Math.abs(ny - (y + vy)) > 1e-6;
            onGround = hitY && vy < 0;
            if (hitY) vy = 0;
            y = ny;
            double nx = moveH(v, x, y, z, vx, true);
            if (Math.abs(nx - (x + vx)) > 1e-6) vx = 0;
            x = nx;
            double nz = moveH(v, x, y, z, vz, false);
            if (Math.abs(nz - (z + vz)) > 1e-6) vz = 0;
            z = nz;
            double fr = wasGround ? 0.546 : 0.91;
            vx *= fr; vz *= fr;
            vy = (vy - 0.08) * 0.98;
            if (y < v.minY() + 1) return f;
            int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
            int k = v.type(bx, by, bz) & Cell.KIND;
            if (k == Cell.DANGER || (v.type(bx, by - 1, bz) & Cell.KIND) == Cell.DANGER && onGround) return f;
            if (k == Cell.WATER && t > 2) { f.x = x; f.y = y; f.z = z; f.ticks = t; f.ok = true; return f; }
            if (onGround && t >= 2) { f.x = x; f.y = y; f.z = z; f.ticks = t; f.ok = true; return f; }
        }
        return f;
    }

    private static final double HW = 0.3, HEIGHT = 1.8, STEP = 0.4;

    /** Тело (0.6 x 1.8) в точке задевает блоки. */
    private static boolean blocked(NmsBlockView v, double x, double y, double z) {
        int x0 = (int) Math.floor(x - HW), x1 = (int) Math.floor(x + HW - 1e-7);
        int z0 = (int) Math.floor(z - HW), z1 = (int) Math.floor(z + HW - 1e-7);
        // Блок ниже ног задевает тело, только если торчит выше полного (забор, стена).
        int y0 = (int) Math.floor(y) - (y - Math.floor(y) < 0.5 ? 1 : 0), y1 = (int) Math.floor(y + HEIGHT - 1e-7);
        for (int bx = x0; bx <= x1; bx++)
            for (int bz = z0; bz <= z1; bz++)
                for (int by = y0; by <= y1; by++)
                    if (v.collides(bx, by, bz, y, y + HEIGHT)) return true;
        return false;
    }

    private static double moveY(NmsBlockView v, double x, double y, double z, double d) {
        int n = Math.max(1, (int) Math.ceil(Math.abs(d) / STEP));
        double s = d / n;
        for (int i = 0; i < n; i++) {
            if (blocked(v, x, y + s, z)) return y;
            y += s;
        }
        return y;
    }

    private static double moveH(NmsBlockView v, double x, double y, double z, double d, boolean alongX) {
        int n = Math.max(1, (int) Math.ceil(Math.abs(d) / STEP));
        double s = d / n;
        double c = alongX ? x : z;
        for (int i = 0; i < n; i++) {
            double nc = c + s;
            if (alongX ? blocked(v, nc, y, z) : blocked(v, x, y, nc)) return c;
            c = nc;
        }
        return c;
    }
}
